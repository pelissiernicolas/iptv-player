//! Chromecast : découverte sur le réseau local (mDNS) et pilotage du récepteur multimédia
//! par défaut de Google (protocole Cast v2 : messages protobuf sur TLS, port 8009).

use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

const DEFAULT_RECEIVER: &str = "CC1AD845";
const NS_CONNECTION: &str = "urn:x-cast:com.google.cast.tp.connection";
const NS_HEARTBEAT: &str = "urn:x-cast:com.google.cast.tp.heartbeat";
const NS_RECEIVER: &str = "urn:x-cast:com.google.cast.receiver";
const NS_MEDIA: &str = "urn:x-cast:com.google.cast.media";
const SENDER: &str = "sender-0";
const RECEIVER: &str = "receiver-0";

#[derive(Clone, Debug, PartialEq)]
pub struct CastDevice {
    pub name: String,
    pub addr: SocketAddr,
}

/// Recherche des Chromecast pendant que la liste est affichée.
pub struct Discovery {
    daemon: Option<mdns_sd::ServiceDaemon>,
    pub devices: Arc<Mutex<Vec<CastDevice>>>,
    pub error: Option<String>,
}

impl Discovery {
    pub fn start(repaint: impl Fn() + Send + 'static) -> Discovery {
        let devices = Arc::new(Mutex::new(Vec::new()));
        let daemon = match mdns_sd::ServiceDaemon::new() {
            Ok(d) => d,
            Err(e) => return Discovery { daemon: None, devices, error: Some(format!("recherche impossible : {e}")) },
        };
        let receiver = match daemon.browse("_googlecast._tcp.local.") {
            Ok(r) => r,
            Err(e) => return Discovery { daemon: Some(daemon), devices, error: Some(format!("recherche impossible : {e}")) },
        };
        let list = devices.clone();
        std::thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                if let mdns_sd::ServiceEvent::ServiceResolved(info) = event {
                    let name = info
                        .get_property_val_str("fn")
                        .map(str::to_string)
                        .unwrap_or_else(|| info.get_fullname().split('.').next().unwrap_or("Chromecast").to_string());
                    let ip: Option<IpAddr> = info
                        .get_addresses()
                        .iter()
                        .map(|a| a.to_ip_addr())
                        .find(|a| a.is_ipv4())
                        .or_else(|| info.get_addresses().iter().next().map(|a| a.to_ip_addr()));
                    if let Some(ip) = ip {
                        let device = CastDevice { name, addr: SocketAddr::new(ip, info.get_port()) };
                        let mut l = list.lock().unwrap();
                        l.retain(|d| d.name != device.name);
                        l.push(device);
                        l.sort_by_key(|a| a.name.to_lowercase());
                        repaint();
                    }
                }
            }
        });
        Discovery { daemon: Some(daemon), devices, error: None }
    }
}

impl Drop for Discovery {
    fn drop(&mut self) {
        if let Some(d) = self.daemon.take() {
            let _ = d.shutdown();
        }
    }
}

/// Média envoyé au Chromecast.
#[derive(Clone, Debug)]
pub struct CastMedia {
    pub url: String,
    pub title: String,
    pub image: Option<String>,
    pub live: bool,
    pub start_secs: f64,
}

#[derive(Clone, Debug, Default)]
pub struct CastStatus {
    pub connected: bool,
    /// "PLAYING", "PAUSED", "BUFFERING", "IDLE"…
    pub player_state: String,
    /// Raison de l'état IDLE ("FINISHED", "ERROR"…).
    pub idle_reason: Option<String>,
    pub position: f64,
    pub position_at: Option<Instant>,
    pub duration: Option<f64>,
    pub error: Option<String>,
    pub closed: bool,
}

impl CastStatus {
    /// Position estimée maintenant (le Chromecast n'envoie son état que de temps en temps).
    pub fn current_position(&self) -> f64 {
        match (self.player_state.as_str(), self.position_at) {
            ("PLAYING", Some(at)) => self.position + at.elapsed().as_secs_f64(),
            _ => self.position,
        }
    }
}

enum Command {
    Load(CastMedia),
    Play,
    Pause,
    Seek(f64),
    Stop,
}

/// Session de diffusion vers un Chromecast (fil dédié).
pub struct CastSession {
    pub device: CastDevice,
    pub status: Arc<Mutex<CastStatus>>,
    tx: Sender<Command>,
}

impl CastSession {
    pub fn connect(device: CastDevice, media: CastMedia, repaint: impl Fn() + Send + 'static) -> CastSession {
        let status = Arc::new(Mutex::new(CastStatus::default()));
        let (tx, rx) = channel();
        let st = status.clone();
        let addr = device.addr;
        let _ = tx.send(Command::Load(media));
        std::thread::spawn(move || {
            let result = run_session(addr, rx, &st, &repaint);
            let mut s = st.lock().unwrap();
            s.connected = false;
            s.closed = true;
            if let Err(e) = result {
                s.error = Some(e);
            }
            drop(s);
            repaint();
        });
        CastSession { device, status, tx }
    }

    pub fn load(&self, media: CastMedia) {
        let _ = self.tx.send(Command::Load(media));
    }
    pub fn play(&self) {
        let _ = self.tx.send(Command::Play);
    }
    pub fn pause(&self) {
        let _ = self.tx.send(Command::Pause);
    }
    pub fn seek(&self, secs: f64) {
        let _ = self.tx.send(Command::Seek(secs));
    }
    /// Arrête la lecture sur la TV et ferme la session.
    pub fn stop(&self) {
        let _ = self.tx.send(Command::Stop);
    }
}

struct Conn {
    stream: native_tls::TlsStream<TcpStream>,
    buf: Vec<u8>,
}

impl Conn {
    fn send(&mut self, dest: &str, ns: &str, payload: &Value) -> Result<(), String> {
        let msg = encode_message(SENDER, dest, ns, &payload.to_string());
        let mut frame = (msg.len() as u32).to_be_bytes().to_vec();
        frame.extend_from_slice(&msg);
        self.stream.write_all(&frame).map_err(|_| "connexion au Chromecast perdue".to_string())
    }

    /// Message complet reçu, s'il y en a un (lecture avec délai court).
    fn recv(&mut self) -> Result<Option<(String, String, Value)>, String> {
        loop {
            if self.buf.len() >= 4 {
                let len = u32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]) as usize;
                if len > 1024 * 1024 {
                    return Err("réponse du Chromecast invalide".into());
                }
                if self.buf.len() >= 4 + len {
                    let msg: Vec<u8> = self.buf.drain(..4 + len).skip(4).collect();
                    let Some((source, ns, payload)) = decode_message(&msg) else { continue };
                    let value = serde_json::from_str(&payload).unwrap_or(Value::Null);
                    return Ok(Some((source, ns, value)));
                }
            }
            let mut tmp = [0u8; 16 * 1024];
            match self.stream.read(&mut tmp) {
                Ok(0) => return Err("le Chromecast a fermé la connexion".into()),
                Ok(n) => self.buf.extend_from_slice(&tmp[..n]),
                Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => return Ok(None),
                Err(_) => return Err("connexion au Chromecast perdue".into()),
            }
        }
    }
}

fn run_session(addr: SocketAddr, rx: Receiver<Command>, status: &Arc<Mutex<CastStatus>>, repaint: &dyn Fn()) -> Result<(), String> {
    let tcp = TcpStream::connect_timeout(&addr, Duration::from_secs(8)).map_err(|_| "Chromecast injoignable".to_string())?;
    tcp.set_read_timeout(Some(Duration::from_millis(150))).ok();
    tcp.set_write_timeout(Some(Duration::from_secs(5))).ok();
    // Les Chromecast utilisent un certificat auto-signé.
    let tls = native_tls::TlsConnector::builder()
        .danger_accept_invalid_certs(true)
        .danger_accept_invalid_hostnames(true)
        .build()
        .map_err(|e| e.to_string())?;
    let stream = tls.connect(&addr.ip().to_string(), tcp).map_err(|_| "connexion sécurisée au Chromecast impossible".to_string())?;
    let mut conn = Conn { stream, buf: Vec::new() };

    conn.send(RECEIVER, NS_CONNECTION, &json!({"type": "CONNECT"}))?;
    let mut request_id = 1;
    conn.send(RECEIVER, NS_RECEIVER, &json!({"type": "LAUNCH", "appId": DEFAULT_RECEIVER, "requestId": request_id}))?;

    let mut transport: Option<String> = None;
    let mut session_id: Option<String> = None;
    let mut media_session: Option<i64> = None;
    let mut pending: Vec<Command> = Vec::new();
    let mut last_ping = Instant::now();
    let mut last_status_request = Instant::now();
    let started = Instant::now();

    loop {
        // Commandes de l'application.
        loop {
            match rx.try_recv() {
                Ok(cmd) => pending.push(cmd),
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    pending.push(Command::Stop);
                    break;
                }
            }
        }
        if transport.is_some() {
            for cmd in pending.drain(..) {
                let dest = transport.clone().unwrap();
                request_id += 1;
                match cmd {
                    Command::Load(m) => {
                        let content_type = content_type_of(&m.url);
                        let mut metadata = json!({"metadataType": 0, "title": m.title});
                        if let Some(img) = &m.image {
                            metadata["images"] = json!([{ "url": img }]);
                        }
                        let mut msg = json!({
                            "type": "LOAD",
                            "requestId": request_id,
                            "sessionId": session_id,
                            "autoplay": true,
                            "currentTime": if m.live { 0.0 } else { m.start_secs.max(0.0) },
                            "media": {
                                "contentId": m.url,
                                "streamType": if m.live { "LIVE" } else { "BUFFERED" },
                                "contentType": content_type,
                                "metadata": metadata,
                            }
                        });
                        if m.live {
                            msg.as_object_mut().unwrap().remove("currentTime");
                        }
                        conn.send(&dest, NS_MEDIA, &msg)?;
                        let mut s = status.lock().unwrap();
                        s.player_state = "BUFFERING".into();
                        s.idle_reason = None;
                        s.position = m.start_secs.max(0.0);
                        s.position_at = None;
                        s.duration = None;
                    }
                    Command::Play | Command::Pause | Command::Seek(_) => {
                        let Some(ms) = media_session else { continue };
                        let msg = match cmd {
                            Command::Play => json!({"type": "PLAY", "requestId": request_id, "mediaSessionId": ms}),
                            Command::Pause => json!({"type": "PAUSE", "requestId": request_id, "mediaSessionId": ms}),
                            Command::Seek(t) => json!({"type": "SEEK", "requestId": request_id, "mediaSessionId": ms, "currentTime": t.max(0.0)}),
                            _ => unreachable!(),
                        };
                        conn.send(&dest, NS_MEDIA, &msg)?;
                    }
                    Command::Stop => {
                        if let Some(sid) = &session_id {
                            let _ = conn.send(RECEIVER, NS_RECEIVER, &json!({"type": "STOP", "sessionId": sid, "requestId": request_id}));
                        }
                        let _ = conn.send(&dest, NS_CONNECTION, &json!({"type": "CLOSE"}));
                        return Ok(());
                    }
                }
            }
        } else {
            if pending.iter().any(|c| matches!(c, Command::Stop)) {
                return Ok(());
            }
            if started.elapsed() > Duration::from_secs(25) {
                return Err("le Chromecast n'a pas lancé le lecteur".into());
            }
        }

        if last_ping.elapsed() > Duration::from_secs(5) {
            conn.send(RECEIVER, NS_HEARTBEAT, &json!({"type": "PING"}))?;
            last_ping = Instant::now();
        }
        if let (Some(dest), Some(_)) = (&transport, media_session)
            && last_status_request.elapsed() > Duration::from_secs(2) {
                request_id += 1;
                conn.send(dest, NS_MEDIA, &json!({"type": "GET_STATUS", "requestId": request_id}))?;
                last_status_request = Instant::now();
            }

        let Some((source, ns, payload)) = conn.recv()? else { continue };
        let kind = payload.get("type").and_then(Value::as_str).unwrap_or_default().to_string();
        match (ns.as_str(), kind.as_str()) {
            (NS_HEARTBEAT, "PING") => conn.send(&source, NS_HEARTBEAT, &json!({"type": "PONG"}))?,
            (NS_CONNECTION, "CLOSE") if Some(&source) == transport.as_ref() => {
                return Err("le Chromecast a arrêté la diffusion".into());
            }
            (NS_RECEIVER, "RECEIVER_STATUS") => {
                let app = payload
                    .pointer("/status/applications")
                    .and_then(Value::as_array)
                    .and_then(|a| a.iter().find(|x| x.get("appId").and_then(Value::as_str) == Some(DEFAULT_RECEIVER)));
                match app {
                    Some(app) if transport.is_none() => {
                        let t = app.get("transportId").and_then(Value::as_str).unwrap_or_default().to_string();
                        session_id = app.get("sessionId").and_then(Value::as_str).map(str::to_string);
                        conn.send(&t, NS_CONNECTION, &json!({"type": "CONNECT"}))?;
                        transport = Some(t);
                        status.lock().unwrap().connected = true;
                        repaint();
                    }
                    None if transport.is_some() => return Err("le lecteur du Chromecast a été fermé".into()),
                    _ => {}
                }
            }
            (NS_RECEIVER, "LAUNCH_ERROR") => return Err("le Chromecast refuse de lancer le lecteur".into()),
            (NS_MEDIA, "MEDIA_STATUS") => {
                if let Some(st) = payload.get("status").and_then(Value::as_array).and_then(|a| a.first()) {
                    if let Some(id) = st.get("mediaSessionId").and_then(Value::as_i64) {
                        media_session = Some(id);
                    }
                    let mut s = status.lock().unwrap();
                    if let Some(state) = st.get("playerState").and_then(Value::as_str) {
                        s.player_state = state.to_string();
                    }
                    s.idle_reason = st.get("idleReason").and_then(Value::as_str).map(str::to_string);
                    if let Some(t) = st.get("currentTime").and_then(Value::as_f64) {
                        s.position = t;
                        s.position_at = Some(Instant::now());
                    }
                    if let Some(d) = st.pointer("/media/duration").and_then(Value::as_f64) {
                        s.duration = Some(d);
                    }
                    drop(s);
                    repaint();
                }
            }
            (NS_MEDIA, "LOAD_FAILED") | (NS_MEDIA, "LOAD_CANCELLED") | (NS_MEDIA, "INVALID_REQUEST") => {
                status.lock().unwrap().error = Some("Le Chromecast ne peut pas lire ce flux (format ou serveur incompatible)".into());
                repaint();
            }
            _ => {}
        }
    }
}

fn content_type_of(url: &str) -> &'static str {
    let path = url.split(['?', '#']).next().unwrap_or(url).to_ascii_lowercase();
    if path.ends_with(".m3u8") {
        "application/x-mpegURL"
    } else if path.ends_with(".ts") {
        "video/mp2t"
    } else if path.ends_with(".mkv") {
        "video/x-matroska"
    } else if path.ends_with(".webm") {
        "video/webm"
    } else {
        "video/mp4"
    }
}

/// Formats audio que le récepteur par défaut ne décode pas dans un fichier (MKV, MP4…).
pub fn unsupported_audio(codec: &str) -> bool {
    let c = codec.to_ascii_lowercase();
    ["ac3", "eac3", "dts", "truehd", "mlp"].iter().any(|x| c.contains(x))
}

// ---------- Protobuf CastMessage (encodage minimal) ----------

fn varint(mut v: u64, out: &mut Vec<u8>) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            break;
        }
        out.push(byte | 0x80);
    }
}

fn field_str(num: u64, s: &str, out: &mut Vec<u8>) {
    varint((num << 3) | 2, out);
    varint(s.len() as u64, out);
    out.extend_from_slice(s.as_bytes());
}

fn encode_message(source: &str, dest: &str, ns: &str, payload: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 128);
    varint(1 << 3, &mut out); // protocol_version = CASTV2_1_0
    varint(0, &mut out);
    field_str(2, source, &mut out);
    field_str(3, dest, &mut out);
    field_str(4, ns, &mut out);
    varint(5 << 3, &mut out); // payload_type = STRING
    varint(0, &mut out);
    field_str(6, payload, &mut out);
    out
}

fn read_varint(data: &[u8], pos: &mut usize) -> Option<u64> {
    let mut v = 0u64;
    for shift in (0..64).step_by(7) {
        let b = *data.get(*pos)?;
        *pos += 1;
        v |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 {
            return Some(v);
        }
    }
    None
}

/// (source, namespace, payload texte)
fn decode_message(data: &[u8]) -> Option<(String, String, String)> {
    let (mut source, mut ns, mut payload) = (String::new(), String::new(), String::new());
    let mut pos = 0;
    while pos < data.len() {
        let key = read_varint(data, &mut pos)?;
        match key & 7 {
            0 => {
                read_varint(data, &mut pos)?;
            }
            2 => {
                let len = read_varint(data, &mut pos)? as usize;
                let bytes = data.get(pos..pos.checked_add(len)?)?;
                pos += len;
                let text = String::from_utf8_lossy(bytes).into_owned();
                match key >> 3 {
                    2 => source = text,
                    4 => ns = text,
                    6 => payload = text,
                    _ => {}
                }
            }
            5 => pos += 4,
            1 => pos += 8,
            _ => return None,
        }
    }
    Some((source, ns, payload))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protobuf_roundtrip() {
        let msg = encode_message("sender-0", "receiver-0", NS_HEARTBEAT, r#"{"type":"PING"}"#);
        let (src, ns, payload) = decode_message(&msg).unwrap();
        assert_eq!(src, "sender-0");
        assert_eq!(ns, NS_HEARTBEAT);
        assert_eq!(payload, r#"{"type":"PING"}"#);
    }

    #[test]
    fn long_payload_varint() {
        let big = "x".repeat(300);
        let msg = encode_message("a", "b", "c", &big);
        assert_eq!(decode_message(&msg).unwrap().2.len(), 300);
    }

    #[test]
    fn content_types() {
        assert_eq!(content_type_of("http://h/live/u/p/1.m3u8"), "application/x-mpegURL");
        assert_eq!(content_type_of("http://h/movie/u/p/1.mkv"), "video/x-matroska");
        assert!(unsupported_audio("eac3"));
        assert!(!unsupported_audio("aac"));
    }
}
