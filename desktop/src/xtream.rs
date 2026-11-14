//! Client de l'API Xtream Codes (`player_api.php`) et modèles de données.

use std::sync::Arc;
use std::time::Duration;

use base64::Engine;
use futures_util::StreamExt;
use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use serde_json::Value;
use url::Url;

pub const USER_AGENT: &str = "IPTVPlayer/1.0 (Windows)";

pub const HTTPS_DOWNGRADE_MESSAGE: &str = "Le serveur redirige vers une adresse non chiffrée (http). \
Par sécurité, la connexion est refusée. Si votre fournisseur ne propose que HTTP, saisissez son adresse en http://.";

/// Taille maximale d'une réponse de l'API (l'arbre JSON occupe plusieurs fois la taille du texte).
const MAX_RESPONSE_BYTES: usize = 96 * 1024 * 1024;

// ---------- Modèles ----------

#[derive(Clone, PartialEq, Eq)]
pub struct Credentials {
    pub server: String,
    pub username: String,
    pub password: String,
}

/// Jamais d'identifiants dans un log ou un message d'erreur par accident.
impl std::fmt::Debug for Credentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Credentials(server={}, username=***, password=***)", self.server)
    }
}

#[derive(Clone, Debug)]
pub struct AccountInfo {
    pub username: String,
    pub status: String,
    /// Date d'expiration en secondes epoch, `None` = illimité.
    pub exp_date: Option<i64>,
    pub max_connections: Option<String>,
    pub active_connections: Option<String>,
    pub is_trial: bool,
}

#[derive(Clone, Debug)]
pub struct Category {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum ContentType {
    Live,
    Vod,
    Series,
}

impl ContentType {
    pub const ALL: [ContentType; 3] = [ContentType::Live, ContentType::Vod, ContentType::Series];

    pub fn label(self) -> &'static str {
        match self {
            ContentType::Live => "Direct",
            ContentType::Vod => "Films",
            ContentType::Series => "Séries",
        }
    }
}

/// Élément affiché dans les listes : chaîne, film ou série.
#[derive(Clone, Debug)]
pub struct MediaEntry {
    pub id: String,
    pub name: String,
    pub image: Option<String>,
    pub extension: Option<String>,
    pub rating: Option<String>,
    pub plot: Option<String>,
    pub kind: ContentType,
    /// Identifiant EPG (chaînes uniquement).
    pub epg_id: Option<String>,
}

/// Un programme du guide TV. Horaires en millisecondes epoch.
#[derive(Clone, Debug)]
pub struct EpgProgram {
    pub title: String,
    pub description: Option<String>,
    pub start: i64,
    pub end: i64,
}

#[derive(Clone, Debug, Default)]
pub struct MovieDetail {
    pub name: String,
    pub poster: Option<String>,
    pub backdrop: Option<String>,
    pub plot: Option<String>,
    pub genre: Option<String>,
    pub director: Option<String>,
    pub cast: Option<String>,
    pub release_date: Option<String>,
    pub duration: Option<String>,
    pub rating: Option<String>,
    pub trailer_youtube_id: Option<String>,
    pub extension: Option<String>,
}

#[derive(Clone, Debug)]
pub struct Episode {
    pub id: String,
    pub title: String,
    pub season: i32,
    pub number: i32,
    pub extension: String,
    pub plot: Option<String>,
    pub image: Option<String>,
    pub duration: Option<String>,
}

#[derive(Clone, Debug, Default)]
pub struct SeriesDetail {
    pub name: String,
    pub cover: Option<String>,
    pub plot: Option<String>,
    pub genre: Option<String>,
    pub release_date: Option<String>,
    pub rating: Option<String>,
    /// Saisons triées, épisodes triés par numéro.
    pub seasons: Vec<(i32, Vec<Episode>)>,
}

impl SeriesDetail {
    pub fn season(&self, number: i32) -> Option<&Vec<Episode>> {
        self.seasons.iter().find(|(s, _)| *s == number).map(|(_, e)| e)
    }
}

// ---------- Client ----------

#[derive(Clone)]
pub struct XtreamClient {
    pub credentials: Arc<Credentials>,
    http: reqwest::Client,
}

/// Erreur affichable telle quelle.
pub type XResult<T> = Result<T, String>;

impl XtreamClient {
    pub fn new(credentials: Credentials) -> Self {
        Self { credentials: Arc::new(credentials), http: http_client() }
    }

    pub async fn login(&self) -> XResult<AccountInfo> {
        let root = self.call(None, &[]).await?;
        let Some(root) = root.as_object() else {
            return Err("Réponse inattendue : vérifiez l'adresse du serveur".into());
        };
        let user = root
            .get("user_info")
            .and_then(Value::as_object)
            .ok_or_else(|| "Identifiants invalides".to_string())?;
        if s(user.get("auth")).as_deref() != Some("1") {
            return Err("Identifiants invalides".into());
        }
        let status = s(user.get("status")).unwrap_or_else(|| "Inconnu".into());
        if !status.eq_ignore_ascii_case("active") {
            return Err(format!("Abonnement non actif (statut : {status})"));
        }
        Ok(AccountInfo {
            username: s(user.get("username")).unwrap_or_else(|| self.credentials.username.clone()),
            status,
            exp_date: s(user.get("exp_date")).and_then(|v| v.parse().ok()),
            max_connections: s(user.get("max_connections")),
            active_connections: s(user.get("active_cons")),
            is_trial: s(user.get("is_trial")).as_deref() == Some("1"),
        })
    }

    pub async fn categories(&self, kind: ContentType) -> XResult<Vec<Category>> {
        let action = match kind {
            ContentType::Live => "get_live_categories",
            ContentType::Vod => "get_vod_categories",
            ContentType::Series => "get_series_categories",
        };
        let arr = as_list(self.call(Some(action), &[]).await?)?;
        Ok(arr
            .iter()
            .filter_map(|o| {
                let id = s(o.get("category_id"))?;
                let name = s(o.get("category_name")).map(|n| n.trim().to_string()).unwrap_or_else(|| "Sans nom".into());
                Some(Category { id, name })
            })
            .collect())
    }

    /// Flux d'une catégorie, ou de tout le catalogue si `category_id` est `None`.
    pub async fn streams(&self, kind: ContentType, category_id: Option<&str>) -> XResult<Vec<MediaEntry>> {
        let action = match kind {
            ContentType::Live => "get_live_streams",
            ContentType::Vod => "get_vod_streams",
            ContentType::Series => "get_series",
        };
        let el = match category_id {
            Some(c) => self.call(Some(action), &[("category_id", c)]).await?,
            None => self.call(Some(action), &[]).await?,
        };
        let arr = as_list(el)?;
        Ok(arr.iter().filter_map(|o| to_entry(o, kind)).collect())
    }

    pub async fn series_info(&self, series_id: &str) -> XResult<SeriesDetail> {
        let root = self.call(Some("get_series_info"), &[("series_id", series_id)]).await?;
        let Some(root) = root.as_object() else { return Err("Série introuvable".into()) };
        let info = root.get("info").and_then(Value::as_object);
        let mut episodes: Vec<Episode> = Vec::new();

        let mut parse = |el: &Value, season_key: Option<i32>| {
            let Some(o) = el.as_object() else { return };
            let Some(id) = s(o.get("id")) else { return };
            let ep_info = o.get("info").and_then(Value::as_object);
            episodes.push(Episode {
                id,
                title: s(o.get("title")).unwrap_or_else(|| "Épisode".into()),
                season: s(o.get("season")).and_then(|v| v.parse().ok()).or(season_key).unwrap_or(1),
                number: s(o.get("episode_num")).and_then(|v| v.parse().ok()).unwrap_or(0),
                extension: s(o.get("container_extension")).unwrap_or_else(|| "mp4".into()),
                plot: ep_info.and_then(|i| s(i.get("plot"))),
                image: ep_info.and_then(|i| s(i.get("movie_image"))),
                duration: ep_info.and_then(|i| s(i.get("duration"))),
            });
        };

        // Selon les serveurs, "episodes" est un objet {"1":[...]} ou un tableau de tableaux.
        match root.get("episodes") {
            Some(Value::Object(map)) => {
                for (key, value) in map {
                    if let Some(list) = value.as_array() {
                        for e in list {
                            parse(e, key.parse().ok());
                        }
                    }
                }
            }
            Some(Value::Array(list)) => {
                for e in list {
                    match e {
                        Value::Array(inner) => inner.iter().for_each(|x| parse(x, None)),
                        other => parse(other, None),
                    }
                }
            }
            _ => {}
        }

        let mut seasons: std::collections::BTreeMap<i32, Vec<Episode>> = Default::default();
        for e in episodes {
            seasons.entry(e.season).or_default().push(e);
        }
        let seasons = seasons
            .into_iter()
            .map(|(k, mut v)| {
                v.sort_by_key(|e| e.number);
                (k, v)
            })
            .collect();

        Ok(SeriesDetail {
            name: info.and_then(|i| s(i.get("name"))).unwrap_or_default(),
            cover: info.and_then(|i| s(i.get("cover"))),
            plot: info.and_then(|i| s(i.get("plot"))),
            genre: info.and_then(|i| s(i.get("genre"))),
            release_date: info.and_then(|i| s(i.get("releaseDate")).or_else(|| s(i.get("release_date")))),
            rating: info.and_then(|i| s(i.get("rating"))),
            seasons,
        })
    }

    /// Programmes à venir d'une chaîne (en cours inclus).
    pub async fn short_epg(&self, stream_id: &str) -> XResult<Vec<EpgProgram>> {
        let root = self.call(Some("get_short_epg"), &[("stream_id", stream_id), ("limit", "20")]).await?;
        let Some(list) = root.get("epg_listings").and_then(Value::as_array) else {
            return Ok(Vec::new());
        };
        let mut programs: Vec<EpgProgram> = list
            .iter()
            .filter_map(|o| {
                let start: i64 = s(o.get("start_timestamp"))?.parse().ok()?;
                let end: i64 = s(o.get("stop_timestamp"))?.parse().ok()?;
                Some(EpgProgram {
                    title: decode_text(s(o.get("title"))).unwrap_or_else(|| "Programme".into()),
                    description: decode_text(s(o.get("description"))),
                    start: start * 1000,
                    end: end * 1000,
                })
            })
            .collect();
        programs.sort_by_key(|p| p.start);
        Ok(programs)
    }

    pub async fn vod_info(&self, vod_id: &str) -> XResult<MovieDetail> {
        let root = self.call(Some("get_vod_info"), &[("vod_id", vod_id)]).await?;
        let Some(root) = root.as_object() else { return Err("Film introuvable".into()) };
        let info = root.get("info").and_then(Value::as_object);
        let data = root.get("movie_data").and_then(Value::as_object);
        let get = |k: &str| info.and_then(|i| s(i.get(k)));
        let backdrop = match info.and_then(|i| i.get("backdrop_path")) {
            Some(Value::Array(a)) => a.first().and_then(|v| s(Some(v))),
            other => s(other),
        };
        Ok(MovieDetail {
            name: get("name").or_else(|| data.and_then(|d| s(d.get("name")))).unwrap_or_default(),
            poster: get("movie_image").or_else(|| get("cover_big")),
            backdrop,
            plot: get("plot").or_else(|| get("description")),
            genre: get("genre"),
            director: get("director"),
            cast: get("cast").or_else(|| get("actors")),
            release_date: get("releasedate").or_else(|| get("release_date")),
            duration: get("duration"),
            rating: get("rating"),
            trailer_youtube_id: get("youtube_trailer"),
            extension: data.and_then(|d| s(d.get("container_extension"))),
        })
    }

    // ---------- URLs de lecture ----------

    fn user_path(&self) -> String {
        format!(
            "{}/{}",
            utf8_percent_encode(&self.credentials.username, PATH_SEGMENT),
            utf8_percent_encode(&self.credentials.password, PATH_SEGMENT)
        )
    }

    pub fn live_url(&self, stream_id: &str, ext: &str) -> String {
        format!("{}/live/{}/{stream_id}.{ext}", self.credentials.server, self.user_path())
    }

    pub fn vod_url(&self, stream_id: &str, ext: Option<&str>) -> String {
        format!("{}/movie/{}/{stream_id}.{}", self.credentials.server, self.user_path(), ext.unwrap_or("mp4"))
    }

    pub fn episode_url(&self, episode_id: &str, ext: &str) -> String {
        format!("{}/series/{}/{episode_id}.{ext}", self.credentials.server, self.user_path())
    }

    // ---------- Interne ----------

    async fn call(&self, action: Option<&str>, params: &[(&str, &str)]) -> XResult<Value> {
        let mut url = Url::parse(&format!("{}/player_api.php", self.credentials.server))
            .map_err(|_| "Adresse du serveur invalide".to_string())?;
        {
            let mut q = url.query_pairs_mut();
            q.append_pair("username", &self.credentials.username);
            q.append_pair("password", &self.credentials.password);
            if let Some(a) = action {
                q.append_pair("action", a);
            }
            for (k, v) in params {
                q.append_pair(k, v);
            }
        }
        let resp = self.http.get(url).send().await.map_err(describe_error)?;
        let code = resp.status().as_u16();
        if code == 401 || code == 403 {
            return Err(format!("Accès refusé par le serveur (HTTP {code})"));
        }
        if !resp.status().is_success() {
            return Err(format!("Erreur serveur (HTTP {code})"));
        }
        // Réponse lue au fil de l'eau et coupée au-delà de la limite.
        let mut body = Vec::new();
        let mut stream = resp.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(describe_error)?;
            if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(format!(
                    "Réponse du serveur trop volumineuse (plus de {} Mo).",
                    MAX_RESPONSE_BYTES / (1024 * 1024)
                ));
            }
            body.extend_from_slice(&chunk);
        }
        if body.iter().all(|b| b.is_ascii_whitespace()) {
            return Ok(Value::Array(Vec::new()));
        }
        tokio::task::spawn_blocking(move || serde_json::from_slice::<Value>(&body))
            .await
            .map_err(|_| "Réponse invalide du serveur".to_string())?
            .map_err(|_| "Réponse invalide du serveur".to_string())
    }
}

/// Client HTTP partagé : une adresse HTTPS ne doit jamais être redirigée vers HTTP (les
/// identifiants, dans l'URL, partiraient en clair). HTTP -> HTTPS reste permis.
pub fn http_client() -> reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT
        .get_or_init(|| {
            reqwest::Client::builder()
                .user_agent(USER_AGENT)
                .connect_timeout(Duration::from_secs(15))
                .read_timeout(Duration::from_secs(90))
                .redirect(secure_redirect_policy())
                .build()
                .expect("client HTTP")
        })
        .clone()
}

#[derive(Debug)]
pub struct HttpsDowngrade;

impl std::fmt::Display for HttpsDowngrade {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(HTTPS_DOWNGRADE_MESSAGE)
    }
}

impl std::error::Error for HttpsDowngrade {}

pub fn secure_redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|attempt| {
        let from_https = attempt.previous().last().is_some_and(|u| u.scheme() == "https");
        if is_downgrade(from_https, attempt.url().scheme()) {
            attempt.error(HttpsDowngrade)
        } else if attempt.previous().len() > 10 {
            attempt.error("trop de redirections")
        } else {
            attempt.follow()
        }
    })
}

/// `true` si une redirection fait passer une adresse HTTPS en HTTP.
pub fn is_downgrade(from_https: bool, to_scheme: &str) -> bool {
    from_https && !to_scheme.eq_ignore_ascii_case("https")
}

/// `true` si la requête a échoué sur une redirection HTTPS -> HTTP refusée.
pub fn is_downgrade_error(e: &reqwest::Error) -> bool {
    let mut source: Option<&dyn std::error::Error> = Some(e);
    while let Some(err) = source {
        if err.is::<HttpsDowngrade>() {
            return true;
        }
        source = err.source();
    }
    false
}

fn describe_error(e: reqwest::Error) -> String {
    if is_downgrade_error(&e) {
        return HTTPS_DOWNGRADE_MESSAGE.to_string();
    }
    if e.is_timeout() {
        "Connexion impossible : le serveur ne répond pas".into()
    } else if e.is_connect() {
        "Connexion impossible : serveur injoignable".into()
    } else {
        // Le message de reqwest contient l'URL (et donc les identifiants) : on ne le montre pas.
        "Connexion impossible : erreur réseau".into()
    }
}

/// Liste attendue : une réponse d'un autre type (objet d'erreur…) est signalée, pas affichée comme vide.
fn as_list(v: Value) -> XResult<Vec<Value>> {
    match v {
        Value::Array(a) => Ok(a),
        Value::Object(o) if o.is_empty() => Ok(Vec::new()),
        // Certains panneaux renvoient {"1": {...}, "2": {...}} au lieu d'un tableau.
        Value::Object(o) if !o.contains_key("user_info") && o.values().all(Value::is_object) => {
            Ok(o.into_iter().map(|(_, v)| v).collect())
        }
        Value::Object(_) => Err("Réponse inattendue du serveur".into()),
        _ => Ok(Vec::new()),
    }
}

fn to_entry(o: &Value, kind: ContentType) -> Option<MediaEntry> {
    let name = s(o.get("name")).map(|n| n.trim().to_string()).unwrap_or_else(|| "Sans nom".into());
    Some(match kind {
        ContentType::Live => MediaEntry {
            id: s(o.get("stream_id"))?,
            name,
            image: s(o.get("stream_icon")),
            extension: None,
            rating: None,
            plot: None,
            kind,
            epg_id: s(o.get("epg_channel_id")),
        },
        ContentType::Vod => MediaEntry {
            id: s(o.get("stream_id"))?,
            name,
            image: s(o.get("stream_icon")),
            extension: s(o.get("container_extension")),
            rating: s(o.get("rating")),
            plot: None,
            kind,
            epg_id: None,
        },
        ContentType::Series => MediaEntry {
            id: s(o.get("series_id"))?,
            name,
            image: s(o.get("cover")),
            extension: None,
            rating: s(o.get("rating")),
            plot: s(o.get("plot")),
            kind,
            epg_id: None,
        },
    })
}

/// Valeur JSON (texte ou nombre) en `String`, `None` si absente/vide.
pub fn s(v: Option<&Value>) -> Option<String> {
    let out = match v? {
        Value::String(t) => t.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => if *b { "1".into() } else { "0".into() },
        _ => return None,
    };
    (!out.trim().is_empty() && out != "null").then_some(out)
}

/// Les textes EPG Xtream sont encodés en Base64 (pas toujours) : on décode si c'est valide.
pub fn decode_text(raw: Option<String>) -> Option<String> {
    let raw = raw?;
    if raw.trim().is_empty() {
        return None;
    }
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(raw.trim())
        .ok()
        .and_then(|b| String::from_utf8(b).ok());
    let text = match decoded {
        Some(d) if !d.chars().any(|c| c == '\u{FFFD}' || (c.is_control() && !matches!(c, '\n' | '\r' | '\t'))) => d,
        _ => raw,
    };
    let text = text.trim().to_string();
    (!text.is_empty()).then_some(text)
}

/// Caractères échappés dans un segment de chemin (identique à `Uri.encode` d'Android).
const PATH_SEGMENT: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.').remove(b'~');

// ---------- Analyse des adresses saisies ----------

/// `true` si l'adresse n'est pas chiffrée (identifiants et flux lisibles sur le réseau).
pub fn is_cleartext(server: &str) -> bool {
    let t = server.trim();
    t.len() >= 7 && t[..7].eq_ignore_ascii_case("http://") || (!t.is_empty() && !t.contains("://"))
}

fn has_scheme(s: &str) -> bool {
    let lower = s.to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

/// Retire d'une adresse les identifiants éventuels « utilisateur:motdepasse@ ».
pub fn strip_user_info(input: &str) -> String {
    let t = input.trim();
    let (scheme, rest) = match t.find("://") {
        Some(i) if t[..i].chars().all(|c| c.is_ascii_alphanumeric() || "+.-".contains(c)) => (&t[..i + 3], &t[i + 3..]),
        _ => ("", t),
    };
    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    match rest[..authority_end].rfind('@') {
        Some(at) => format!("{scheme}{}", &rest[at + 1..]),
        None => t.to_string(),
    }
}

/// "exemple.com:8080/" -> "http://exemple.com:8080". Sans schéma, HTTP est utilisé car la
/// plupart des serveurs Xtream n'acceptent que lui ; l'écran du profil le signale.
pub fn normalize_server(input: &str) -> String {
    let mut s = strip_user_info(input);
    if !has_scheme(&s) {
        s = format!("http://{s}");
    }
    if let Some(i) = s.find(['?', '#']) {
        s.truncate(i);
    }
    let mut s = s.trim_end_matches('/').to_string();
    for suffix in ["/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php"] {
        if s.to_ascii_lowercase().ends_with(suffix) {
            s.truncate(s.len() - suffix.len());
        }
    }
    s.trim_end_matches('/').to_string()
}

const STREAM_PATHS: [&str; 3] = ["live", "movie", "series"];

fn base_of(url: &Url, segments: &[&str]) -> String {
    let mut base = format!("{}://{}", url.scheme(), url.host_str().unwrap_or_default());
    if let Some(port) = url.port() {
        base.push_str(&format!(":{port}"));
    }
    for seg in segments.iter().filter(|s| !s.is_empty()) {
        base.push('/');
        base.push_str(seg);
    }
    base
}

fn decode_segment(s: &str) -> String {
    percent_encoding::percent_decode_str(s).decode_utf8_lossy().into_owned()
}

/// Extrait serveur / identifiants d'un lien M3U `get.php?username=..&password=..`, d'une
/// adresse `http://utilisateur:motdepasse@serveur/…` ou d'une adresse de lecture
/// `…/live/utilisateur/motdepasse/123.ts`. Le serveur retourné ne contient jamais d'identifiants.
pub fn parse_m3u_link(input: &str) -> Option<Credentials> {
    let url = Url::parse(input.trim()).ok()?;
    if !matches!(url.scheme(), "http" | "https") {
        return None;
    }
    let segments: Vec<&str> = url.path_segments().map(|s| s.collect()).unwrap_or_default();

    // Adresse de lecture : …/live|movie|series/utilisateur/motdepasse/123.ext
    if let Some(at) = segments.iter().position(|s| STREAM_PATHS.contains(&s.to_ascii_lowercase().as_str()))
        && segments.len() >= at + 4 {
            let user = decode_segment(segments[at + 1]);
            let pass = decode_segment(segments[at + 2]);
            if !user.trim().is_empty() && !pass.is_empty() {
                return Some(Credentials { server: base_of(&url, &segments[..at]), username: user, password: pass });
            }
        }

    let query = |k: &str| url.query_pairs().find(|(key, _)| key == k).map(|(_, v)| v.into_owned());
    let user = query("username").or_else(|| (!url.username().is_empty()).then(|| decode_segment(url.username())))?;
    let pass = query("password").or_else(|| url.password().map(decode_segment))?;
    if user.trim().is_empty() || pass.is_empty() {
        return None;
    }
    // On garde un éventuel sous-chemin (https://hote/iptv/get.php -> https://hote/iptv).
    let non_empty: Vec<&str> = segments.iter().copied().filter(|s| !s.is_empty()).collect();
    let base_segments = if non_empty.last().is_some_and(|s| s.contains('.')) {
        &non_empty[..non_empty.len() - 1]
    } else {
        &non_empty[..]
    };
    Some(Credentials { server: base_of(&url, base_segments), username: user, password: pass })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize() {
        assert_eq!(normalize_server("exemple.com:8080/"), "http://exemple.com:8080");
        assert_eq!(normalize_server("https://a.b/player_api.php?x=1"), "https://a.b");
        assert_eq!(normalize_server("http://bob:secret@a.b:80/get.php"), "http://a.b:80");
        assert_eq!(normalize_server("  HTTPS://Host/iptv/ "), "HTTPS://Host/iptv");
    }

    #[test]
    fn m3u() {
        let c = parse_m3u_link("http://srv.tv:8080/get.php?username=u1&password=p%40ss&type=m3u").unwrap();
        assert_eq!(c.server, "http://srv.tv:8080");
        assert_eq!(c.username, "u1");
        assert_eq!(c.password, "p@ss");

        let c = parse_m3u_link("https://h/iptv/get.php?username=a&password=b").unwrap();
        assert_eq!(c.server, "https://h/iptv");

        let c = parse_m3u_link("http://h:81/live/jean/mdp%2F1/123.ts").unwrap();
        assert_eq!((c.server.as_str(), c.username.as_str(), c.password.as_str()), ("http://h:81", "jean", "mdp/1"));

        let c = parse_m3u_link("http://bob:pw@h.com/").unwrap();
        assert_eq!((c.server.as_str(), c.username.as_str()), ("http://h.com", "bob"));

        assert!(parse_m3u_link("http://h.com/get.php").is_none());
        assert!(parse_m3u_link("exemple.com").is_none());
    }

    #[test]
    fn urls_escape_credentials() {
        let c = XtreamClient::new(Credentials { server: "http://h".into(), username: "a b".into(), password: "p/w?".into() });
        assert_eq!(c.live_url("12", "ts"), "http://h/live/a%20b/p%2Fw%3F/12.ts");
        assert_eq!(c.vod_url("5", None), "http://h/movie/a%20b/p%2Fw%3F/5.mp4");
    }

    #[test]
    fn cleartext() {
        assert!(is_cleartext("http://a"));
        assert!(is_cleartext("a.com"));
        assert!(!is_cleartext("https://a"));
        assert!(!is_cleartext(""));
    }

    #[test]
    fn downgrade() {
        assert!(is_downgrade(true, "http"));
        assert!(!is_downgrade(true, "https"));
        assert!(!is_downgrade(false, "http"));
    }

    #[test]
    fn epg_text() {
        assert_eq!(decode_text(Some("Sm91cm5hbA==".into())).as_deref(), Some("Journal"));
        assert_eq!(decode_text(Some("Météo".into())).as_deref(), Some("Météo"));
        assert_eq!(decode_text(Some("  ".into())), None);
    }

    #[test]
    fn list_shapes() {
        let v: Value = serde_json::from_str(r#"{"1":{"a":1},"2":{"a":2}}"#).unwrap();
        assert_eq!(as_list(v).unwrap().len(), 2);
        let v: Value = serde_json::from_str(r#"{"user_info":{"auth":0}}"#).unwrap();
        assert!(as_list(v).is_err());
    }
}
