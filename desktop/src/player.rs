//! Lecteur intégré : lecture (mpv), zapping, reprise, pistes audio / sous-titres,
//! plein écran, mini-lecteur toujours au premier plan, et diffusion vers un Chromecast.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use egui::{Align2, Color32, FontId, Key, Pos2, Rect, RichText, Sense, Ui, Vec2, pos2, vec2};

use crate::cast::{self, CastDevice, CastMedia, CastSession, Discovery};
use crate::epg::{EpgStore, now_ms, now_next};
use crate::mpv::{self, Mpv, VideoRenderer};
use crate::store::{RecentChannel, WatchProgress};
use crate::ui::{self, PRIMARY, TEXT_DIM, format_duration, format_time};
use crate::xtream::XtreamClient;

const OVERLAY_TIMEOUT: Duration = Duration::from_millis(3500);
const SAVE_EVERY: Duration = Duration::from_secs(10);

#[derive(Clone, Debug)]
pub struct PlayItem {
    pub title: String,
    pub url: String,
    /// URL essayée si la première échoue (ex. .m3u8 au lieu de .ts).
    pub fallback_url: Option<String>,
    pub is_live: bool,
    pub content_id: Option<String>,
    pub image: Option<String>,
    pub epg_id: Option<String>,
    /// Films / épisodes : clé de progression ("vod:123", "ep:456").
    pub progress_key: Option<String>,
    /// "vod" ou "episode".
    pub kind: Option<String>,
    pub extension: Option<String>,
    pub library_title: Option<String>,
    pub library_subtitle: Option<String>,
    pub series_id: Option<String>,
    pub season: Option<i32>,
}

impl PlayItem {
    pub fn simple(title: String, url: String) -> Self {
        Self {
            title,
            url,
            fallback_url: None,
            is_live: false,
            content_id: None,
            image: None,
            epg_id: None,
            progress_key: None,
            kind: None,
            extension: None,
            library_title: None,
            library_subtitle: None,
            series_id: None,
            season: None,
        }
    }
}

/// Ce que le lecteur demande à l'application.
pub enum PlayerOutput {
    SaveProgress(WatchProgress),
    RemoveProgress(String),
    Recent(RecentChannel),
    Toast(String),
    Closed,
}

/// Environnement fourni par l'application à chaque image.
pub struct PlayerEnv<'a> {
    pub rt: &'a tokio::runtime::Handle,
    pub client: Option<&'a XtreamClient>,
    pub epg: &'a EpgStore,
}

struct Track {
    id: String,
    label: String,
    selected: bool,
}

struct MiniState {
    outer: Option<Rect>,
    maximized: bool,
}

/// Résolution des redirections (serveur HTTPS) en cours.
type Resolved = Arc<Mutex<Option<(u64, Result<String, String>)>>>;

pub struct Session {
    items: Vec<PlayItem>,
    index: usize,
    previous: Option<usize>,
    fallback_used: HashSet<usize>,
    error: Option<String>,
    secure_server: bool,
    load_gen: u64,
    resolved: Resolved,
    start_secs: Option<f64>,
    loaded: bool,
    live_retries: Vec<Instant>,
    last_save: Instant,
    overlay_until: Instant,
    zap_until: Option<Instant>,
    show_channels: bool,
    channel_filter: String,
    scroll_to_current: bool,
    last_pointer: Option<Pos2>,
    paused: bool,
    // Chromecast
    cast: Option<CastSession>,
    discovery: Option<Discovery>,
    cast_picker: bool,
    cast_warning: Option<String>,
    cast_loaded_index: Option<usize>,
    cast_last_save: Instant,
    mini: Option<MiniState>,
    seek_drag: Option<f64>,
}

pub struct Player {
    rt: tokio::runtime::Handle,
    mpv: Option<Arc<Mpv>>,
    renderer: Option<Arc<Mutex<VideoRenderer>>>,
    get_proc: Option<Arc<dyn Fn(&std::ffi::CStr) -> *const std::ffi::c_void + Send + Sync>>,
    pub engine_error: Option<String>,
    pub session: Option<Session>,
    pub volume: f64,
    outputs: Vec<PlayerOutput>,
}

impl Player {
    pub fn new(
        rt: tokio::runtime::Handle,
        ctx: &egui::Context,
        get_proc: Option<Arc<dyn Fn(&std::ffi::CStr) -> *const std::ffi::c_void + Send + Sync>>,
        volume: f64,
    ) -> Player {
        let wake_ctx = ctx.clone();
        let (mpv, engine_error) = match Mpv::new(Box::new(move || wake_ctx.request_repaint())) {
            Ok(m) => (Some(m), None),
            Err(e) => (None, Some(e)),
        };
        let renderer = mpv.as_ref().map(|m| {
            let frame_ctx = ctx.clone();
            Arc::new(Mutex::new(VideoRenderer::new(m.clone(), Box::new(move || frame_ctx.request_repaint()))))
        });
        if let Some(m) = &mpv {
            m.set("volume", &format!("{volume:.0}"));
        }
        let mut engine_error = engine_error.or_else(|| get_proc.is_none().then(|| "OpenGL indisponible".to_string()));
        // Le contexte OpenGL d'eframe est actif pendant la création de l'application.
        if let (Some(r), Some(gp)) = (&renderer, &get_proc)
            && !r.lock().unwrap().ensure_context(gp) && engine_error.is_none() {
                engine_error = Some("rendu OpenGL de mpv impossible".into());
            }
        Player { rt, mpv, renderer, get_proc, engine_error, session: None, volume, outputs: Vec::new() }
    }

    pub fn is_open(&self) -> bool {
        self.session.is_some()
    }

    /// Lance la lecture de `items` à partir de `start` (position en ms, ou -1 = début / direct).
    pub fn play(&mut self, ctx: &egui::Context, items: Vec<PlayItem>, start: usize, start_ms: i64, secure_server: bool) {
        if items.is_empty() {
            return;
        }
        // Une lecture en cours est d'abord enregistrée (progression).
        if self.session.is_some() {
            self.save_progress();
        }
        let start = start.min(items.len() - 1);
        let previous_cast = self.session.as_mut().and_then(|s| s.cast.take());
        let previous_mini = self.session.as_mut().and_then(|s| s.mini.take());
        let now = Instant::now();
        self.session = Some(Session {
            items,
            index: start,
            previous: None,
            fallback_used: HashSet::new(),
            error: None,
            secure_server,
            load_gen: 0,
            resolved: Arc::new(Mutex::new(None)),
            start_secs: (start_ms > 0).then(|| start_ms as f64 / 1000.0),
            loaded: false,
            live_retries: Vec::new(),
            last_save: now,
            overlay_until: now + OVERLAY_TIMEOUT,
            zap_until: None,
            show_channels: false,
            channel_filter: String::new(),
            scroll_to_current: true,
            last_pointer: None,
            paused: false,
            cast: previous_cast,
            discovery: None,
            cast_picker: false,
            cast_warning: None,
            cast_loaded_index: None,
            cast_last_save: now,
            mini: previous_mini,
            seek_drag: None,
        });
        self.load_current(ctx);
    }

    /// Ferme le lecteur (la diffusion Chromecast éventuelle est arrêtée).
    pub fn close(&mut self, ctx: &egui::Context) {
        self.save_progress();
        if let Some(mpv) = &self.mpv {
            mpv.command(&["stop"]);
        }
        if let Some(s) = self.session.take() {
            if let Some(c) = &s.cast {
                c.stop();
            }
            if let Some(mini) = s.mini {
                restore_window(ctx, mini);
            }
        }
        self.outputs.push(PlayerOutput::Closed);
    }

    pub fn take_outputs(&mut self) -> Vec<PlayerOutput> {
        std::mem::take(&mut self.outputs)
    }

    pub fn shutdown(&mut self, gl: Option<&egui_glow::glow::Context>) {
        self.save_progress();
        if let Some(s) = &self.session
            && let Some(c) = &s.cast {
                c.stop();
            }
        if let Some(r) = &self.renderer {
            r.lock().unwrap().destroy(gl);
        }
        self.renderer = None;
        self.mpv = None;
    }

    // ---------- Chargement ----------

    fn load_current(&mut self, ctx: &egui::Context) {
        let Some(s) = self.session.as_mut() else { return };
        s.error = None;
        s.loaded = false;
        s.paused = false;
        s.load_gen += 1;
        let item = s.items[s.index].clone();
        if item.is_live
            && let Some(id) = &item.content_id {
                self.outputs.push(PlayerOutput::Recent(RecentChannel {
                    id: id.clone(),
                    name: item.title.clone(),
                    image: item.image.clone(),
                    epg_id: item.epg_id.clone(),
                }));
            }
        if s.cast.is_some() {
            Self::cast_current(s);
            return;
        }
        let url = if s.fallback_used.contains(&s.index) { item.fallback_url.clone().unwrap_or(item.url.clone()) } else { item.url.clone() };
        if s.secure_server && url.to_ascii_lowercase().starts_with("https://") {
            // Serveur HTTPS : on suit nous-mêmes les redirections (une redirection vers HTTP
            // exposerait les identifiants contenus dans l'URL) avant de confier l'URL finale à mpv.
            let (slot, generation, ctx) = (s.resolved.clone(), s.load_gen, ctx.clone());
            let client = crate::xtream::http_client();
            self.rt.spawn(async move {
                let res = match client.get(&url).header("Range", "bytes=0-").send().await {
                    Ok(resp) => Ok(resp.url().to_string()),
                    // Redirection vers HTTP refusée ; autre erreur : mpv essaiera et l'affichera.
                    Err(e) if crate::xtream::is_downgrade_error(&e) => Err(crate::xtream::HTTPS_DOWNGRADE_MESSAGE.to_string()),
                    Err(_) => Ok(url),
                };
                *slot.lock().unwrap() = Some((generation, res));
                ctx.request_repaint();
            });
        } else {
            let start = s.start_secs.take();
            Self::mpv_load(self.mpv.as_deref(), &url, start, item.is_live);
        }
    }

    fn mpv_load(mpv: Option<&Mpv>, url: &str, start: Option<f64>, live: bool) {
        let Some(mpv) = mpv else { return };
        mpv.set("pause", "no");
        match start {
            Some(t) if !live && t > 0.0 => {
                mpv.command_async(&["loadfile", url, "replace", "-1", &format!("start={t:.1}")]);
            }
            _ => {
                mpv.command_async(&["loadfile", url, "replace"]);
            }
        }
    }

    fn go_to(&mut self, ctx: &egui::Context, index: usize) {
        let Some(s) = self.session.as_ref() else { return };
        if index >= s.items.len() || index == s.index {
            return;
        }
        self.save_progress();
        let s = self.session.as_mut().unwrap();
        s.previous = Some(s.index);
        s.index = index;
        s.start_secs = None;
        s.zap_until = Some(Instant::now() + Duration::from_secs(3));
        s.scroll_to_current = true;
        self.load_current(ctx);
    }

    fn zap(&mut self, ctx: &egui::Context, delta: i64) {
        let Some(s) = self.session.as_ref() else { return };
        let target = s.index as i64 + delta;
        if target >= 0 && (target as usize) < s.items.len() {
            self.go_to(ctx, target as usize);
        }
    }

    fn previous_channel(&mut self, ctx: &egui::Context) {
        if let Some(p) = self.session.as_ref().and_then(|s| s.previous) {
            self.go_to(ctx, p);
        }
    }

    fn retry(&mut self, ctx: &egui::Context) {
        if let Some(s) = self.session.as_mut() {
            s.fallback_used.remove(&s.index);
        }
        self.load_current(ctx);
    }

    // ---------- Progression ----------

    fn position_duration(&self) -> Option<(f64, f64)> {
        let s = self.session.as_ref()?;
        if let Some(c) = &s.cast {
            let st = c.status.lock().unwrap();
            return st.duration.map(|d| (st.current_position(), d));
        }
        let mpv = self.mpv.as_ref()?;
        if !s.loaded {
            return None;
        }
        Some((mpv.get_f64("time-pos")?, mpv.get_f64("duration")?))
    }

    /// Mémorise la position du film / épisode en cours pour « Continuer à regarder ».
    fn save_progress(&mut self) {
        let Some((pos, dur)) = self.position_duration() else { return };
        let Some(s) = self.session.as_mut() else { return };
        let item = &s.items[s.index];
        let (Some(key), Some(content_id)) = (&item.progress_key, &item.content_id) else { return };
        if item.is_live || dur <= 0.0 {
            return;
        }
        s.last_save = Instant::now();
        self.outputs.push(PlayerOutput::SaveProgress(WatchProgress {
            key: key.clone(),
            kind: item.kind.clone().unwrap_or_else(|| "vod".into()),
            content_id: content_id.clone(),
            extension: item.extension.clone(),
            title: item.library_title.clone().unwrap_or_else(|| item.title.clone()),
            subtitle: item.library_subtitle.clone(),
            image: item.image.clone(),
            position: (pos * 1000.0) as i64,
            duration: (dur * 1000.0) as i64,
            updated_at: chrono::Utc::now().timestamp_millis(),
            series_id: item.series_id.clone(),
            season: item.season,
        }));
    }

    fn mark_finished(&mut self) {
        if let Some(s) = &self.session
            && let Some(key) = &s.items[s.index].progress_key {
                self.outputs.push(PlayerOutput::RemoveProgress(key.clone()));
            }
    }

    // ---------- Évènements ----------

    fn process_events(&mut self, ctx: &egui::Context) {
        // Redirections résolues (serveur HTTPS).
        if let Some(s) = self.session.as_mut() {
            let ready = s.resolved.lock().unwrap().take();
            if let Some((generation, res)) = ready
                && generation == s.load_gen && s.cast.is_none() {
                    match res {
                        Ok(url) => {
                            let start = s.start_secs.take();
                            let live = s.items[s.index].is_live;
                            Self::mpv_load(self.mpv.as_deref(), &url, start, live);
                        }
                        Err(msg) => s.error = Some(msg),
                    }
                }
        }

        let Some(mpv) = self.mpv.clone() else { return };
        for ev in mpv.poll_events() {
            let Some(s) = self.session.as_mut() else { continue };
            if s.cast.is_some() {
                continue;
            }
            match ev {
                mpv::Event::FileLoaded | mpv::Event::PlaybackRestart => {
                    s.loaded = true;
                    s.error = None;
                }
                mpv::Event::EndFile { reason, error } if reason == mpv::END_REASON_ERROR => {
                    let index = s.index;
                    let item = &s.items[index];
                    // Le flux .ts ne passe pas ? On retente automatiquement en HLS (.m3u8).
                    if item.fallback_url.is_some() && s.fallback_used.insert(index) {
                        self.load_current(ctx);
                        continue;
                    }
                    s.loaded = false;
                    s.error = Some(error_message(error));
                }
                mpv::Event::EndFile { reason, .. } if reason == mpv::END_REASON_EOF => {
                    if s.items[s.index].is_live {
                        // Flux direct coupé par le serveur : quelques reconnexions automatiques.
                        let now = Instant::now();
                        s.live_retries.retain(|t| now.duration_since(*t) < Duration::from_secs(60));
                        if s.live_retries.len() < 3 {
                            s.live_retries.push(now);
                            self.load_current(ctx);
                        } else {
                            s.error = Some("Le flux s'est interrompu (serveur ou réseau).".into());
                        }
                    } else {
                        // Épisode / film terminé : retiré de « Continuer à regarder », puis suite.
                        self.mark_finished();
                        let s = self.session.as_mut().unwrap();
                        if s.index + 1 < s.items.len() {
                            s.previous = Some(s.index);
                            s.index += 1;
                            s.start_secs = None;
                            self.load_current(ctx);
                        } else {
                            self.close(ctx);
                        }
                    }
                }
                _ => {}
            }
        }
    }

    // ---------- Chromecast ----------

    fn cast_media(item: &PlayItem, start_secs: f64) -> CastMedia {
        CastMedia {
            url: if item.is_live { item.fallback_url.clone().unwrap_or(item.url.clone()) } else { item.url.clone() },
            title: item.title.clone(),
            image: item.image.clone(),
            live: item.is_live,
            start_secs,
        }
    }

    fn cast_current(s: &mut Session) {
        let start = s.start_secs.take().unwrap_or(0.0);
        if let Some(c) = &s.cast {
            c.load(Self::cast_media(&s.items[s.index], start));
            s.cast_loaded_index = Some(s.index);
        }
    }

    fn start_cast(&mut self, ctx: &egui::Context, device: CastDevice) {
        let pos = self.position_duration().map(|(p, _)| p).unwrap_or(0.0);
        // Son Dolby / DTS : le récepteur par défaut ne sait généralement pas le décoder.
        let codec = self.mpv.as_ref().and_then(|m| m.get("current-tracks/audio/codec"));
        if let Some(mpv) = &self.mpv {
            mpv.command(&["stop"]);
        }
        let Some(s) = self.session.as_mut() else { return };
        s.cast_warning = codec
            .filter(|c| cast::unsupported_audio(c) && !s.items[s.index].is_live)
            .map(|c| format!("Son {} : le Chromecast risque de ne pas le lire (image sans son).", c.to_uppercase()));
        let item = &s.items[s.index];
        let repaint_ctx = ctx.clone();
        let media = Self::cast_media(item, if item.is_live { 0.0 } else { pos });
        s.cast = Some(CastSession::connect(device, media, move || repaint_ctx.request_repaint()));
        s.cast_loaded_index = Some(s.index);
        s.cast_picker = false;
        s.discovery = None;
        s.loaded = false;
        s.error = None;
    }

    /// Retour sur le PC à la position atteinte sur la TV.
    fn stop_cast(&mut self, ctx: &egui::Context, message: Option<String>) {
        let Some(s) = self.session.as_mut() else { return };
        let Some(c) = s.cast.take() else { return };
        let pos = c.status.lock().unwrap().current_position();
        c.stop();
        if !s.items[s.index].is_live && pos > 1.0 {
            s.start_secs = Some(pos);
        }
        s.cast_warning = None;
        if let Some(m) = message {
            self.outputs.push(PlayerOutput::Toast(m));
        }
        self.load_current(ctx);
    }

    fn check_cast(&mut self, ctx: &egui::Context) {
        let Some(s) = self.session.as_mut() else { return };
        let Some(c) = &s.cast else { return };
        let st = c.status.lock().unwrap().clone();
        if st.closed {
            let msg = st.error.unwrap_or_else(|| "Diffusion terminée".into());
            self.stop_cast(ctx, Some(format!("Chromecast : {msg}. Lecture reprise sur le PC.")));
            return;
        }
        if let Some(err) = st.error.clone() {
            c.status.lock().unwrap().error = None;
            self.outputs.push(PlayerOutput::Toast(err));
        }
        if st.player_state == "IDLE" && st.idle_reason.as_deref() == Some("FINISHED") && s.cast_loaded_index == Some(s.index) {
            c.status.lock().unwrap().idle_reason = None;
            if !s.items[s.index].is_live {
                self.mark_finished();
                let s = self.session.as_mut().unwrap();
                if s.index + 1 < s.items.len() {
                    s.previous = Some(s.index);
                    s.index += 1;
                    self.load_current(ctx);
                }
            }
            return;
        }
        if s.cast_last_save.elapsed() > SAVE_EVERY && st.player_state == "PLAYING" {
            s.cast_last_save = Instant::now();
            self.save_progress();
        }
    }

    // ---------- Fenêtre ----------

    fn toggle_fullscreen(&mut self, ctx: &egui::Context) {
        let full = ctx.input(|i| i.viewport().fullscreen.unwrap_or(false));
        if let Some(s) = self.session.as_mut()
            && let Some(mini) = s.mini.take() {
                restore_window(ctx, mini);
            }
        ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(!full));
    }

    /// Mini-lecteur : petite fenêtre sans bordure, toujours au premier plan, en bas à droite.
    fn toggle_mini(&mut self, ctx: &egui::Context) {
        let Some(s) = self.session.as_mut() else { return };
        if let Some(mini) = s.mini.take() {
            restore_window(ctx, mini);
            return;
        }
        let info = ctx.input(|i| i.viewport().clone());
        s.mini = Some(MiniState { outer: info.outer_rect, maximized: info.maximized.unwrap_or(false) });
        s.show_channels = false;
        let size = vec2(480.0, 270.0);
        ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
        ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(false));
        ctx.send_viewport_cmd(egui::ViewportCommand::Decorations(false));
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(size));
        ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(egui::WindowLevel::AlwaysOnTop));
        if let Some(monitor) = info.monitor_size {
            ctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(pos2(monitor.x - size.x - 24.0, monitor.y - size.y - 72.0)));
        }
    }

    fn toggle_pause(&mut self) {
        let Some(s) = self.session.as_mut() else { return };
        if let Some(c) = &s.cast {
            let playing = c.status.lock().unwrap().player_state == "PLAYING";
            if playing { c.pause() } else { c.play() }
            return;
        }
        if let Some(m) = &self.mpv {
            m.command(&["cycle", "pause"]);
            s.paused = m.get_bool("pause").unwrap_or(false);
        }
        if s.paused {
            self.save_progress();
        }
    }

    fn seek_relative(&mut self, secs: f64) {
        let Some(s) = self.session.as_ref() else { return };
        if s.items[s.index].is_live {
            return;
        }
        if let Some(c) = &s.cast {
            let p = c.status.lock().unwrap().current_position();
            c.seek(p + secs);
        } else if let Some(m) = &self.mpv {
            m.command(&["seek", &format!("{secs}"), "relative"]);
        }
    }

    fn seek_absolute(&mut self, secs: f64) {
        let Some(s) = self.session.as_ref() else { return };
        if let Some(c) = &s.cast {
            c.seek(secs);
        } else if let Some(m) = &self.mpv {
            m.command(&["seek", &format!("{secs:.1}"), "absolute"]);
        }
    }

    fn set_volume(&mut self, v: f64) {
        self.volume = v.clamp(0.0, 130.0);
        if let Some(m) = &self.mpv {
            m.set("volume", &format!("{:.0}", self.volume));
            if self.volume > 0.0 {
                m.set("mute", "no");
            }
        }
    }

    fn tracks(&self, kind: &str) -> Vec<Track> {
        let Some(m) = &self.mpv else { return Vec::new() };
        let count: usize = m.get("track-list/count").and_then(|c| c.parse().ok()).unwrap_or(0);
        let mut out = Vec::new();
        for i in 0..count {
            if m.get(&format!("track-list/{i}/type")).as_deref() != Some(kind) {
                continue;
            }
            let id = m.get(&format!("track-list/{i}/id")).unwrap_or_default();
            let lang = m.get(&format!("track-list/{i}/lang"));
            let title = m.get(&format!("track-list/{i}/title"));
            let codec = m.get(&format!("track-list/{i}/codec"));
            let channels = m.get(&format!("track-list/{i}/demux-channel-count"));
            let mut label = match (lang, title) {
                (Some(l), Some(t)) => format!("{} · {t}", l.to_uppercase()),
                (Some(l), None) => l.to_uppercase(),
                (None, Some(t)) => t,
                (None, None) => format!("Piste {id}"),
            };
            if let Some(c) = codec {
                label.push_str(&format!("  ({}{})", c.to_uppercase(), channels.map(|n| format!(" {n} can.")).unwrap_or_default()));
            }
            let selected = m.get(&format!("track-list/{i}/selected")).as_deref() == Some("yes");
            out.push(Track { id, label, selected });
        }
        out
    }

    // ---------- Interface ----------

    pub fn ui(&mut self, ui: &mut Ui, env: &PlayerEnv) {
        let ctx = ui.ctx().clone();
        self.process_events(&ctx);
        self.check_cast(&ctx);
        let Some(s) = self.session.as_mut() else { return };

        // Sauvegarde régulière de la progression.
        if s.cast.is_none() && s.last_save.elapsed() > SAVE_EVERY && !s.paused {
            self.save_progress();
        }

        let rect = ui.max_rect();
        let painter = ui.painter_at(rect);
        painter.rect_filled(rect, 0.0, Color32::BLACK);

        let casting = self.session.as_ref().unwrap().cast.is_some();
        if !casting
            && let (Some(renderer), Some(get_proc)) = (self.renderer.clone(), self.get_proc.clone()) {
                let cb = egui_glow::CallbackFn::new(move |info, painter| {
                    let vp = info.viewport_in_pixels();
                    renderer.lock().unwrap().paint(painter.gl(), &get_proc, (vp.left_px, vp.from_bottom_px, vp.width_px, vp.height_px));
                });
                ui.painter().add(egui::PaintCallback { rect, callback: Arc::new(cb) });
            }

        // Interactions sur la zone vidéo.
        let video = ui.interact(rect, ui.scope_id().with("video"), Sense::click_and_drag());
        let now = Instant::now();
        let pointer_moved = ctx.input(|i| {
            let pos = i.pointer.latest_pos();
            let moved = pos.is_some() && pos != self.session.as_ref().unwrap().last_pointer;
            (moved, pos)
        });
        let s = self.session.as_mut().unwrap();
        if pointer_moved.0 {
            s.last_pointer = pointer_moved.1;
            if video.hovered() || video.contains_pointer() {
                s.overlay_until = now + OVERLAY_TIMEOUT;
            }
        }
        let any_key = ctx.input(|i| i.events.iter().any(|e| matches!(e, egui::Event::Key { pressed: true, .. })));
        if any_key {
            s.overlay_until = now + OVERLAY_TIMEOUT;
        }
        let is_mini = s.mini.is_some();
        if is_mini && video.drag_started() {
            ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
        }
        let overlay_was_visible = now < s.overlay_until;
        if video.double_clicked() {
            self.toggle_fullscreen(&ctx);
        } else if video.clicked() {
            let s = self.session.as_mut().unwrap();
            s.overlay_until = if overlay_was_visible && !s.show_channels { now } else { now + OVERLAY_TIMEOUT };
            if s.show_channels {
                s.show_channels = false;
            }
        }

        self.handle_keys(&ctx);
        let Some(s) = self.session.as_mut() else { return };

        let paused = if s.cast.is_some() {
            s.cast.as_ref().unwrap().status.lock().unwrap().player_state == "PAUSED"
        } else {
            self.mpv.as_ref().and_then(|m| m.get_bool("pause")).unwrap_or(false)
        };
        s.paused = paused;
        let overlay = now < s.overlay_until || paused || s.error.is_some() || s.show_channels || s.cast_picker;
        // Le curseur n'est pas masqué : sur certains pilotes graphiques (PC ARM notamment),
        // un curseur caché s'affiche comme un carré noir au-dessus de la vidéo.
        if now < s.overlay_until {
            ctx.request_repaint_after(s.overlay_until - now);
        }

        // Bandeau Chromecast.
        if let Some(c) = &s.cast {
            let st = c.status.lock().unwrap().clone();
            let item = &s.items[s.index];
            ui::paint_image(ui, item.image.as_deref(), Rect::from_center_size(rect.center() - vec2(0.0, 60.0), vec2(120.0, 120.0)), 12.0, true, "📺");
            let state = if !st.connected {
                "Connexion…".to_string()
            } else {
                match st.player_state.as_str() {
                    "PLAYING" => "Lecture".into(),
                    "PAUSED" => "Pause".into(),
                    "BUFFERING" => "Chargement…".into(),
                    _ => "Prêt".into(),
                }
            };
            painter.text(rect.center() + vec2(0.0, 30.0), Align2::CENTER_CENTER, format!("Lecture sur « {} »", c.device.name), FontId::proportional(22.0), Color32::WHITE);
            painter.text(rect.center() + vec2(0.0, 60.0), Align2::CENTER_CENTER, state, FontId::proportional(16.0), TEXT_DIM);
            if let Some(w) = &s.cast_warning {
                painter.text(rect.center() + vec2(0.0, 90.0), Align2::CENTER_CENTER, w, FontId::proportional(15.0), ui::WARNING);
            }
        } else if self.engine_error.is_some() && s.error.is_none() {
            s.error = Some(format!(
                "Moteur vidéo indisponible : {}.\nPlacez libmpv-2.dll à côté de iptv-player.exe.",
                self.engine_error.clone().unwrap_or_default()
            ));
        } else if (!s.loaded && s.error.is_none()) || self.mpv.as_ref().and_then(|m| m.get_bool("paused-for-cache")).unwrap_or(false) {
            spinner(ui, rect.center());
        }

        // Bandeau de zapping (numéro + nom).
        if let Some(until) = s.zap_until
            && now < until && !overlay {
                let item = &s.items[s.index];
                let text = format!("{}  {}", s.index + 1, item.title);
                let galley = painter.layout_no_wrap(text, FontId::proportional(24.0), Color32::WHITE);
                let r = Rect::from_min_size(pos2(rect.left() + 24.0, rect.bottom() - 24.0 - galley.size().y - 16.0), galley.size() + vec2(24.0, 16.0));
                painter.rect_filled(r, 8.0, Color32::from_black_alpha(176));
                painter.galley(r.min + vec2(12.0, 8.0), galley, Color32::WHITE);
                ctx.request_repaint_after(until - now);
            }

        if overlay {
            self.overlay_ui(ui, rect, env, is_mini);
        }

        if self.session.as_ref().is_some_and(|s| s.error.is_some()) {
            self.error_ui(ui, rect);
        }
        if self.session.as_ref().is_some_and(|s| s.cast_picker) {
            self.cast_picker_ui(&ctx);
        }
    }

    fn handle_keys(&mut self, ctx: &egui::Context) {
        // Pas de raccourci pendant la saisie (filtre de la liste des chaînes).
        if ctx.text_edit_focused() {
            return;
        }
        let pressed = |k: Key| ctx.input_mut(|i| i.consume_key(egui::Modifiers::NONE, k));
        let ctrl = |k: Key| ctx.input_mut(|i| i.consume_key(egui::Modifiers::CTRL, k));
        if pressed(Key::Space) || pressed(Key::K) {
            self.toggle_pause();
        }
        if pressed(Key::F) || pressed(Key::F11) {
            self.toggle_fullscreen(ctx);
        }
        if pressed(Key::Escape) {
            let full = ctx.input(|i| i.viewport().fullscreen.unwrap_or(false));
            let s = self.session.as_mut().unwrap();
            if s.cast_picker {
                s.cast_picker = false;
                s.discovery = None;
            } else if s.show_channels {
                s.show_channels = false;
            } else if full {
                ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
            } else if let Some(mini) = s.mini.take() {
                restore_window(ctx, mini);
            } else {
                self.close(ctx);
                return;
            }
        }
        let live = self.session.as_ref().is_some_and(|s| s.items[s.index].is_live);
        if pressed(Key::ArrowLeft) {
            self.seek_relative(-10.0);
        }
        if pressed(Key::ArrowRight) {
            self.seek_relative(30.0);
        }
        if pressed(Key::PageDown) || ctrl(Key::ArrowDown) {
            self.zap(ctx, 1);
        }
        if pressed(Key::PageUp) || ctrl(Key::ArrowUp) {
            self.zap(ctx, -1);
        }
        if pressed(Key::ArrowUp) {
            self.set_volume(self.volume + 5.0);
        }
        if pressed(Key::ArrowDown) {
            self.set_volume(self.volume - 5.0);
        }
        if pressed(Key::Backspace) {
            self.previous_channel(ctx);
        }
        if pressed(Key::M)
            && let Some(m) = &self.mpv {
                m.command(&["cycle", "mute"]);
            }
        if live && (pressed(Key::L) || pressed(Key::C)) {
            let s = self.session.as_mut().unwrap();
            s.show_channels = !s.show_channels;
            s.scroll_to_current = true;
        }
    }

    fn overlay_ui(&mut self, ui: &mut Ui, rect: Rect, env: &PlayerEnv, mini: bool) {
        let ctx = ui.ctx().clone();
        let s = self.session.as_ref().unwrap();
        let item = s.items[s.index].clone();
        let live = item.is_live;
        let multi = s.items.len() > 1;
        let casting = s.cast.is_some();
        let has_previous = s.previous.is_some_and(|p| p != s.index && p < s.items.len());
        let full = ctx.input(|i| i.viewport().fullscreen.unwrap_or(false));
        let painter = ui.painter_at(rect);

        // Dégradés haut et bas pour la lisibilité.
        let top_h = if mini { 44.0 } else { 72.0 };
        let bottom_h = if mini { 56.0 } else { if live { 96.0 } else { 120.0 } };
        gradient(&painter, Rect::from_min_size(rect.min, vec2(rect.width(), top_h + 30.0)), true);
        gradient(&painter, Rect::from_min_max(pos2(rect.left(), rect.bottom() - bottom_h - 40.0), rect.max), false);

        // ----- Barre du haut -----
        let top = Rect::from_min_size(rect.min + vec2(12.0, 8.0), vec2(rect.width() - 24.0, top_h - 8.0));
        let mut top_ui = ui.new_child(egui::UiBuilder::new().max_rect(top).layout(egui::Layout::left_to_right(egui::Align::Center)));
        if ui::icon_button(&mut top_ui, "←", "Retour (Échap)").clicked() {
            self.close(&ctx);
            return;
        }
        let title = if live && multi { format!("{}. {}", s.index + 1, item.title) } else { item.title.clone() };
        top_ui.add(egui::Label::new(ui::bold(title, if mini { 15.0 } else { 20.0 }).color(Color32::WHITE)).truncate());
        top_ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |r| {
            if mini {
                if ui::icon_button(r, "⤢", "Agrandir").clicked() {
                    self.toggle_mini(&ctx);
                }
                return;
            }
            if ui::icon_button(r, if full { "🗗" } else { "⛶" }, "Plein écran (F)").clicked() {
                self.toggle_fullscreen(&ctx);
            }
            if ui::icon_button(r, "◱", "Mini-lecteur (toujours visible)").clicked() {
                self.toggle_mini(&ctx);
            }
            let cast_label = if casting { "📺✓" } else { "📺" };
            if ui::icon_button(r, cast_label, if casting { "Arrêter la diffusion" } else { "Diffuser sur un Chromecast" }).clicked() {
                if casting {
                    self.stop_cast(&ctx, None);
                } else {
                    let s = self.session.as_mut().unwrap();
                    s.cast_picker = true;
                    let rc = ctx.clone();
                    s.discovery = Some(Discovery::start(move || rc.request_repaint()));
                }
            }
            if !casting {
                self.tracks_menu(r);
            }
            if live && multi && ui::icon_button(r, "☰", "Liste des chaînes (L)").clicked() {
                let s = self.session.as_mut().unwrap();
                s.show_channels = !s.show_channels;
                s.scroll_to_current = true;
            }
            if live && has_previous && ui::icon_button(r, "↶", "Chaîne précédente (Retour arrière)").clicked() {
                self.previous_channel(&ctx);
            }
        });
        if self.session.is_none() {
            return;
        }

        // ----- Commandes du bas -----
        let paused = self.session.as_ref().unwrap().paused;
        let bottom = Rect::from_min_max(pos2(rect.left() + 16.0, rect.bottom() - bottom_h), pos2(rect.right() - 16.0, rect.bottom() - 10.0));
        let mut b = ui.new_child(egui::UiBuilder::new().max_rect(bottom).layout(egui::Layout::top_down(egui::Align::Center)));
        if !live && !mini
            && let Some((pos, dur)) = self.position_duration() {
                self.seek_bar(&mut b, pos, dur);
            }
        b.horizontal_centered(|row| {
            let btn = if mini { 40.0 } else { 56.0 };
            let big = |row: &mut Ui, icon: &str, tip: &str| {
                row.add(egui::Button::new(RichText::new(icon).size(btn * 0.45).color(Color32::WHITE)).min_size(vec2(btn, btn)).corner_radius(btn / 2.0).fill(Color32::from_black_alpha(110)))
                    .on_hover_text(tip)
            };
            // Centrage approximatif des boutons principaux.
            let n = 3.0;
            let lead = ((row.available_width() - n * (btn + 12.0)) / 2.0 - if mini { 0.0 } else { 140.0 }).max(0.0);
            row.add_space(lead);
            if live {
                if big(row, "⏮", "Chaîne précédente (Page préc.)").clicked() {
                    self.zap(&ctx, -1);
                }
            } else if big(row, "⟲", "Reculer de 10 s (←)").clicked() {
                self.seek_relative(-10.0);
            }
            if big(row, if paused { "▶" } else { "⏸" }, "Lecture / pause (Espace)").clicked() {
                self.toggle_pause();
            }
            if live {
                if big(row, "⏭", "Chaîne suivante (Page suiv.)").clicked() {
                    self.zap(&ctx, 1);
                }
            } else if big(row, "⟳", "Avancer de 30 s (→)").clicked() {
                self.seek_relative(30.0);
            }
            if !mini && !casting {
                row.add_space(24.0);
                let muted = self.mpv.as_ref().and_then(|m| m.get_bool("mute")).unwrap_or(false);
                if ui::icon_button(row, if muted || self.volume == 0.0 { "🔇" } else { "🔊" }, "Couper le son (M)").clicked()
                    && let Some(m) = &self.mpv {
                        m.command(&["cycle", "mute"]);
                    }
                let mut v = self.volume;
                row.spacing_mut().slider_width = 130.0;
                if row.add(egui::Slider::new(&mut v, 0.0..=130.0).show_value(false)).changed() {
                    self.set_volume(v);
                }
            }
        });

        if self.session.as_ref().is_some_and(|s| s.show_channels) {
            self.channel_panel(ui, rect, env);
        }
    }

    fn seek_bar(&mut self, ui: &mut Ui, pos: f64, dur: f64) {
        let mut drag = self.session.as_ref().and_then(|s| s.seek_drag);
        let shown = drag.unwrap_or(pos);
        let mut seek_to: Option<f64> = None;
        ui.horizontal(|row| {
            row.label(RichText::new(format_duration((shown * 1000.0) as i64)).color(Color32::WHITE));
            let w = (row.available_width() - 90.0).max(50.0);
            let (r, resp) = row.allocate_exact_size(vec2(w, 28.0), Sense::click_and_drag());
            let track = Rect::from_center_size(r.center(), vec2(r.width(), 6.0));
            let painter = row.painter();
            painter.rect_filled(track, 3.0, Color32::from_white_alpha(60));
            let frac = if dur > 0.0 { (shown / dur).clamp(0.0, 1.0) as f32 } else { 0.0 };
            let mut done = track;
            done.set_width(track.width() * frac);
            painter.rect_filled(done, 3.0, PRIMARY);
            let knob = pos2(track.left() + track.width() * frac, track.center().y);
            painter.circle_filled(knob, if resp.hovered() || resp.dragged() { 9.0 } else { 7.0 }, Color32::WHITE);
            if let Some(p) = resp.interact_pointer_pos() {
                let f = ((p.x - track.left()) / track.width()).clamp(0.0, 1.0) as f64;
                if resp.dragged() || resp.is_pointer_button_down_on() || resp.clicked() {
                    drag = Some(f * dur);
                }
            }
            if resp.drag_stopped() || resp.clicked() {
                seek_to = drag.take();
            }
            row.label(RichText::new(format!("-{}", format_duration(((dur - shown).max(0.0) * 1000.0) as i64))).color(Color32::WHITE));
        });
        if let Some(s) = self.session.as_mut() {
            s.seek_drag = drag;
        }
        if let Some(t) = seek_to {
            self.seek_absolute(t);
        }
    }

    fn tracks_menu(&mut self, ui: &mut Ui) {
        let mpv = self.mpv.clone();
        let audio = self.tracks("audio");
        let subs = self.tracks("sub");
        if audio.len() <= 1 && subs.is_empty() {
            return;
        }
        let btn = egui::Button::new(RichText::new("💬").size(ui.spacing().interact_size.y * 0.5)).frame(false);
        egui::containers::menu::MenuButton::from_button(btn).ui(ui, |m| {
            m.set_min_width(260.0);
            if audio.len() > 1 {
                m.label(ui::bold("Audio", 15.0));
                for t in &audio {
                    if m.radio(t.selected, &t.label).clicked()
                        && let Some(mp) = &mpv {
                            mp.set("aid", &t.id);
                        }
                }
                m.separator();
            }
            m.label(ui::bold("Sous-titres", 15.0));
            let none = !subs.iter().any(|t| t.selected);
            if m.radio(none, "Désactivés").clicked()
                && let Some(mp) = &mpv {
                    mp.set("sid", "no");
                }
            for t in &subs {
                if m.radio(t.selected, &t.label).clicked()
                    && let Some(mp) = &mpv {
                        mp.set("sid", &t.id);
                    }
            }
        })
        .0
        .on_hover_text("Audio et sous-titres");
    }

    fn channel_panel(&mut self, ui: &mut Ui, rect: Rect, env: &PlayerEnv) {
        let ctx = ui.ctx().clone();
        let width = (rect.width() * 0.38).clamp(300.0, 440.0);
        let panel = Rect::from_min_max(pos2(rect.right() - width, rect.top() + 72.0), pos2(rect.right() - 8.0, rect.bottom() - 110.0));
        if panel.height() < 120.0 {
            return;
        }
        ui.painter().rect_filled(panel, 12.0, Color32::from_rgba_unmultiplied(10, 18, 32, 235));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(panel.shrink(10.0)).layout(egui::Layout::top_down(egui::Align::Min)));
        let mut target: Option<usize> = None;
        {
            let s = self.session.as_mut().unwrap();
            child.horizontal(|h| {
                h.label(ui::bold("Chaînes", 18.0));
                h.add(egui::TextEdit::singleline(&mut s.channel_filter).hint_text("Filtrer…").desired_width(f32::INFINITY));
            });
            let filter = ui::fold(s.channel_filter.trim());
            let indices: Vec<usize> = (0..s.items.len()).filter(|i| filter.is_empty() || ui::fold(&s.items[*i].title).contains(&filter)).collect();
            let row_h = 56.0;
            let mut area = egui::ScrollArea::vertical().auto_shrink(false).id_salt("player-channels");
            if s.scroll_to_current {
                if let Some(pos) = indices.iter().position(|i| *i == s.index) {
                    area = area.vertical_scroll_offset((pos as f32 * (row_h + child.spacing().item_spacing.y) - 120.0).max(0.0));
                }
                s.scroll_to_current = false;
            }
            let now = now_ms();
            area.show_rows(&mut child, row_h, indices.len(), |ui, range| {
                for &i in &indices[range] {
                    let item = &s.items[i];
                    let (r, resp) = ui.allocate_exact_size(vec2(ui.available_width(), row_h), Sense::click());
                    let current = i == s.index;
                    if current || resp.hovered() {
                        ui.painter().rect_filled(r, 8.0, if current { PRIMARY.gamma_multiply(0.35) } else { ui::SURFACE_HIGH });
                    }
                    ui::paint_image(ui, item.image.as_deref(), Rect::from_min_size(r.min + vec2(6.0, 6.0), vec2(60.0, row_h - 12.0)), 6.0, true, "");
                    let p = ui.painter();
                    p.text(r.min + vec2(76.0, 8.0), Align2::LEFT_TOP, format!("{}. {}", i + 1, item.title), FontId::proportional(15.0), Color32::WHITE);
                    if let (Some(client), Some(id)) = (env.client, &item.content_id) {
                        let st = env.epg.get(env.rt, client, &ctx, id, item.epg_id.is_some());
                        if let Some(programs) = st.programs
                            && let (Some(cur), _) = now_next(&programs, now) {
                                p.text(r.min + vec2(76.0, 30.0), Align2::LEFT_TOP, format!("{}  {}", format_time(cur.start), cur.title), FontId::proportional(12.5), TEXT_DIM);
                            }
                    }
                    if resp.clicked() {
                        target = Some(i);
                    }
                }
            });
        }
        if let Some(i) = target {
            self.go_to(&ctx, i);
        }
    }

    fn error_ui(&mut self, ui: &mut Ui, rect: Rect) {
        let ctx = ui.ctx().clone();
        let msg = self.session.as_ref().and_then(|s| s.error.clone()).unwrap_or_default();
        let w = (rect.width() - 40.0).min(520.0);
        let area = Rect::from_center_size(rect.center(), vec2(w, 150.0));
        ui.painter().rect_filled(area, 14.0, Color32::from_rgba_unmultiplied(32, 32, 32, 235));
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(area.shrink(18.0)).layout(egui::Layout::top_down(egui::Align::Center)));
        child.label(RichText::new(msg).size(17.0).color(Color32::WHITE));
        child.add_space(10.0);
        child.horizontal(|h| {
            h.add_space(((h.available_width() - 260.0) / 2.0).max(0.0));
            if ui::primary_button(h, "Réessayer").clicked() {
                self.retry(&ctx);
            }
            if h.button("Retour").clicked() {
                self.close(&ctx);
            }
        });
    }

    fn cast_picker_ui(&mut self, ctx: &egui::Context) {
        let mut chosen: Option<CastDevice> = None;
        let mut close = false;
        {
            let s = self.session.as_mut().unwrap();
            let devices = s.discovery.as_ref().map(|d| d.devices.lock().unwrap().clone()).unwrap_or_default();
            let error = s.discovery.as_ref().and_then(|d| d.error.clone());
            egui::Modal::new(egui::Id::unique("cast-picker")).show(ctx, |ui| {
                ui.set_width(380.0);
                ui.label(ui::bold("Diffuser sur un Chromecast", 20.0));
                ui.add_space(6.0);
                if let Some(e) = error {
                    ui.colored_label(ui::ERROR, e);
                } else if devices.is_empty() {
                    ui.horizontal(|h| {
                        h.spinner();
                        h.label("Recherche des appareils sur le réseau…");
                    });
                }
                for d in devices {
                    if ui.add_sized([ui.available_width(), 44.0], egui::Button::new(format!("📺  {}", d.name))).clicked() {
                        chosen = Some(d);
                    }
                }
                ui.add_space(6.0);
                ui.label(ui::dim("Le PC et le Chromecast doivent être sur le même réseau. L'adresse du flux, qui contient vos identifiants, est transmise au Chromecast."));
                ui.add_space(6.0);
                if ui.button("Annuler").clicked() {
                    close = true;
                }
            });
        }
        if let Some(d) = chosen {
            self.start_cast(ctx, d);
        } else if close {
            let s = self.session.as_mut().unwrap();
            s.cast_picker = false;
            s.discovery = None;
        }
    }
}

fn restore_window(ctx: &egui::Context, mini: MiniState) {
    ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(egui::WindowLevel::Normal));
    ctx.send_viewport_cmd(egui::ViewportCommand::Decorations(true));
    if let Some(r) = mini.outer {
        ctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(r.min));
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(r.size() - vec2(0.0, 32.0)));
    }
    if mini.maximized {
        ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(true));
    }
}

fn gradient(painter: &egui::Painter, rect: Rect, from_top: bool) {
    let mut mesh = egui::Mesh::default();
    let dark = Color32::from_black_alpha(170);
    let clear = Color32::TRANSPARENT;
    let (c_top, c_bottom) = if from_top { (dark, clear) } else { (clear, dark) };
    mesh.colored_vertex(rect.left_top(), c_top);
    mesh.colored_vertex(rect.right_top(), c_top);
    mesh.colored_vertex(rect.left_bottom(), c_bottom);
    mesh.colored_vertex(rect.right_bottom(), c_bottom);
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(1, 2, 3);
    painter.add(egui::Shape::mesh(mesh));
}

fn spinner(ui: &mut Ui, center: Pos2) {
    let r = Rect::from_center_size(center, Vec2::splat(56.0));
    ui.put(r, egui::Spinner::new().size(56.0).color(Color32::WHITE));
}

/// Message clair selon l'erreur de mpv.
fn error_message(code: i32) -> String {
    match code {
        mpv::MPV_ERROR_LOADING_FAILED => "Le serveur refuse ce flux (abonnement, nombre de connexions ou flux hors ligne), ou la connexion a échoué.".into(),
        mpv::MPV_ERROR_UNKNOWN_FORMAT | mpv::MPV_ERROR_NOTHING_TO_PLAY => "Le fichier envoyé par le serveur est vide ou illisible.".into(),
        mpv::MPV_ERROR_AO_INIT_FAILED => "Aucune sortie audio disponible sur ce PC.".into(),
        mpv::MPV_ERROR_VO_INIT_FAILED => "Affichage vidéo impossible (pilote graphique).".into(),
        _ => "Lecture impossible.".into(),
    }
}
