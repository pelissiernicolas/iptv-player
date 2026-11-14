//! Stockage local : profils (chiffrés), réglages, et données de chaque profil (favoris,
//! progression, chaînes récentes). Tout est dans `%APPDATA%\IPTV Player`.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::backup::{self, Backup, Favorites};
use crate::secret;
use crate::xtream::{ContentType, Credentials};

pub const MAX_PROGRESS: usize = 60;
pub const MAX_RECENT: usize = 30;
pub const MIN_POSITION_MS: i64 = 15_000;
pub const FINISHED_RATIO: f64 = 0.95;

/// Un profil = un compte Xtream Codes enregistré, avec ses propres favoris.
#[derive(Serialize, Deserialize, Clone, PartialEq)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub server: String,
    pub username: String,
    pub password: String,
}

impl Profile {
    pub fn credentials(&self) -> Credentials {
        Credentials { server: self.server.clone(), username: self.username.clone(), password: self.password.clone() }
    }
}

/// Jamais d'identifiants dans un log par accident.
impl std::fmt::Debug for Profile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Profile(id={}, name={}, server={})", self.id, self.name, self.server)
    }
}

/// Film ou épisode commencé (« Continuer à regarder »). Noms de champs identiques à Android.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WatchProgress {
    pub key: String,
    pub kind: String,
    pub content_id: String,
    #[serde(default)]
    pub extension: Option<String>,
    pub title: String,
    #[serde(default)]
    pub subtitle: Option<String>,
    #[serde(default)]
    pub image: Option<String>,
    pub position: i64,
    pub duration: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub series_id: Option<String>,
    #[serde(default)]
    pub season: Option<i32>,
}

/// Chaîne regardée récemment.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RecentChannel {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub image: Option<String>,
    #[serde(default)]
    pub epg_id: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default)]
pub struct FavoriteSets {
    pub live: BTreeSet<String>,
    pub vod: BTreeSet<String>,
    pub series: BTreeSet<String>,
}

impl FavoriteSets {
    pub fn get(&self, t: ContentType) -> &BTreeSet<String> {
        match t {
            ContentType::Live => &self.live,
            ContentType::Vod => &self.vod,
            ContentType::Series => &self.series,
        }
    }

    pub fn get_mut(&mut self, t: ContentType) -> &mut BTreeSet<String> {
        match t {
            ContentType::Live => &mut self.live,
            ContentType::Vod => &mut self.vod,
            ContentType::Series => &mut self.series,
        }
    }
}

/// Ce qu'il faut faire d'une position de lecture.
#[derive(Debug, PartialEq, Eq)]
pub enum ProgressAction {
    Save,
    Remove,
    Ignore,
}

/// Vidéo quasiment terminée -> retirée ; moins de 15 s regardées -> ignorée.
pub fn progress_action(position: i64, duration: i64) -> ProgressAction {
    if duration <= 0 {
        ProgressAction::Ignore
    } else if position as f64 >= duration as f64 * FINISHED_RATIO {
        ProgressAction::Remove
    } else if position < MIN_POSITION_MS {
        ProgressAction::Ignore
    } else {
        ProgressAction::Save
    }
}

/// Données personnelles d'un profil.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
#[serde(default)]
pub struct ProfileData {
    pub favorites: FavoriteSets,
    /// Du plus récent au plus ancien.
    pub progress: Vec<WatchProgress>,
    pub recent: Vec<RecentChannel>,
}

impl ProfileData {
    pub fn progress_for(&self, key: &str) -> Option<&WatchProgress> {
        self.progress.iter().find(|p| p.key == key)
    }

    /// Enregistre une position ; supprime l'entrée si la vidéo est quasiment terminée.
    /// Retourne `true` si quelque chose a changé.
    pub fn save_progress(&mut self, entry: WatchProgress) -> bool {
        match progress_action(entry.position, entry.duration) {
            ProgressAction::Ignore => false,
            ProgressAction::Remove => self.remove_progress(&entry.key),
            ProgressAction::Save => {
                self.progress.retain(|p| p.key != entry.key);
                self.progress.insert(0, entry);
                self.progress.truncate(MAX_PROGRESS);
                true
            }
        }
    }

    pub fn remove_progress(&mut self, key: &str) -> bool {
        let before = self.progress.len();
        self.progress.retain(|p| p.key != key);
        before != self.progress.len()
    }

    pub fn add_recent(&mut self, channel: RecentChannel) -> bool {
        if self.recent.first().is_some_and(|c| c.id == channel.id) {
            return false;
        }
        self.recent.retain(|c| c.id != channel.id);
        self.recent.insert(0, channel);
        self.recent.truncate(MAX_RECENT);
        true
    }

    pub fn to_backup(&self) -> Backup {
        Backup {
            exported_at: chrono::Utc::now().timestamp_millis(),
            favorites: Favorites {
                live: self.favorites.live.iter().cloned().collect(),
                vod: self.favorites.vod.iter().cloned().collect(),
                series: self.favorites.series.iter().cloned().collect(),
            },
            progress: self.progress.clone(),
            recent_channels: self.recent.clone(),
            ..Backup::default()
        }
    }

    /// Fusionne une sauvegarde : rien n'est supprimé, la donnée la plus récente l'emporte.
    /// Retourne le nombre de favoris ajoutés.
    pub fn import(&mut self, b: &Backup) -> usize {
        let mut added = 0;
        for t in ContentType::ALL {
            for id in b.favorites.get(t) {
                if self.favorites.get_mut(t).insert(id.clone()) {
                    added += 1;
                }
            }
        }
        self.progress = backup::merge_progress(&self.progress, &b.progress, MAX_PROGRESS);
        self.recent = backup::merge_recent(&self.recent, &b.recent_channels, MAX_RECENT);
        added
    }
}

/// Réglages de l'application (non sensibles).
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Settings {
    pub active_profile: Option<String>,
    pub skipped_update: Option<String>,
    /// Taille de l'interface (zoom egui).
    pub ui_scale: f32,
    pub volume: f64,
    /// Mode tactile (cibles plus grandes) : `None` = automatique (écran tactile détecté).
    pub touch_mode: Option<bool>,
}

impl Default for Settings {
    fn default() -> Self {
        Self { active_profile: None, skipped_update: None, ui_scale: 1.0, volume: 100.0, touch_mode: None }
    }
}

pub struct Store {
    dir: PathBuf,
    /// Profils qui n'ont pas pu être relus (autre compte Windows, fichier abîmé) : mis de côté.
    pub profiles_unreadable: bool,
}

impl Store {
    pub fn open() -> Store {
        let dir = data_dir();
        let _ = std::fs::create_dir_all(&dir);
        Store { dir, profiles_unreadable: false }
    }

    #[cfg(test)]
    pub fn at(dir: PathBuf) -> Store {
        let _ = std::fs::create_dir_all(&dir);
        Store { dir, profiles_unreadable: false }
    }

    fn profiles_path(&self) -> PathBuf {
        self.dir.join("profiles.dat")
    }

    /// Profils déchiffrés. Un fichier illisible est mis de côté (jamais effacé) et signalé ;
    /// les copies mises de côté sont réessayées à chaque lancement.
    pub fn load_profiles(&mut self) -> Vec<Profile> {
        self.profiles_unreadable = false;
        let path = self.profiles_path();
        let mut list = match std::fs::read(&path) {
            Ok(bytes) => match decode_profiles(&bytes) {
                Some(l) => l,
                None => {
                    let aside = self.dir.join(format!("profiles.unreadable-{}.dat", chrono::Utc::now().timestamp_millis()));
                    let _ = std::fs::rename(&path, aside);
                    Vec::new()
                }
            },
            Err(_) => Vec::new(),
        };
        let mut recovered_files = Vec::new();
        if let Ok(entries) = std::fs::read_dir(&self.dir) {
            for e in entries.flatten() {
                let name = e.file_name().to_string_lossy().into_owned();
                if !name.starts_with("profiles.unreadable-") {
                    continue;
                }
                match std::fs::read(e.path()).ok().and_then(|b| decode_profiles(&b)) {
                    Some(recovered) => {
                        for p in recovered {
                            if !list.iter().any(|x: &Profile| x.id == p.id) {
                                list.push(p);
                            }
                        }
                        recovered_files.push(e.path());
                    }
                    None => self.profiles_unreadable = true,
                }
            }
        }
        if !recovered_files.is_empty() && self.save_profiles(&list).is_ok() {
            recovered_files.iter().for_each(|p| {
                let _ = std::fs::remove_file(p);
            });
        }
        list
    }

    /// Enregistre les profils chiffrés. Jamais d'identifiants en clair : en cas d'échec du
    /// chiffrement, rien n'est écrit.
    pub fn save_profiles(&self, list: &[Profile]) -> Result<(), String> {
        let json = serde_json::to_vec(list).map_err(|e| e.to_string())?;
        let enc = secret::protect(&json)?;
        write_atomic(&self.profiles_path(), &enc)
    }

    pub fn load_settings(&self) -> Settings {
        read_json(&self.dir.join("settings.json")).unwrap_or_default()
    }

    pub fn save_settings(&self, s: &Settings) {
        let _ = write_json(&self.dir.join("settings.json"), s);
    }

    fn profile_path(&self, id: &str) -> PathBuf {
        let safe: String = id.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
        self.dir.join(format!("profile-{safe}.json"))
    }

    pub fn load_profile_data(&self, id: &str) -> ProfileData {
        read_json(&self.profile_path(id)).unwrap_or_default()
    }

    pub fn save_profile_data(&self, id: &str, data: &ProfileData) {
        let _ = write_json(&self.profile_path(id), data);
    }

    pub fn delete_profile_data(&self, id: &str) {
        let _ = std::fs::remove_file(self.profile_path(id));
    }
}

fn decode_profiles(bytes: &[u8]) -> Option<Vec<Profile>> {
    let plain = secret::unprotect(bytes).ok()?;
    serde_json::from_slice(&plain).ok()
}

pub fn data_dir() -> PathBuf {
    if let Some(appdata) = std::env::var_os("APPDATA") {
        return PathBuf::from(appdata).join("IPTV Player");
    }
    if let Some(home) = std::env::var_os("HOME") {
        return PathBuf::from(home).join(".config").join("iptv-player");
    }
    PathBuf::from(".iptv-player")
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Option<T> {
    let bytes = std::fs::read(path).ok()?;
    serde_json::from_slice(&bytes).ok()
}

fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    write_atomic(path, &bytes)
}

/// Écriture atomique : fichier temporaire puis renommage. Une interruption laisse
/// l'ancienne version intacte au lieu d'un fichier tronqué.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    let tmp = path.with_extension("tmp");
    let res = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        std::fs::rename(&tmp, path)
    })();
    if res.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    res.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wp(key: &str, position: i64, duration: i64) -> WatchProgress {
        WatchProgress {
            key: key.into(),
            kind: "vod".into(),
            content_id: key.trim_start_matches("vod:").into(),
            extension: None,
            title: "t".into(),
            subtitle: None,
            image: None,
            position,
            duration,
            updated_at: 0,
            series_id: None,
            season: None,
        }
    }

    #[test]
    fn progress_rules() {
        assert_eq!(progress_action(10_000, 100_000), ProgressAction::Ignore);
        assert_eq!(progress_action(50_000, 100_000), ProgressAction::Save);
        assert_eq!(progress_action(96_000, 100_000), ProgressAction::Remove);
        assert_eq!(progress_action(5, 0), ProgressAction::Ignore);

        let mut d = ProfileData::default();
        assert!(d.save_progress(wp("vod:1", 50_000, 100_000)));
        assert!(d.save_progress(wp("vod:2", 50_000, 100_000)));
        assert_eq!(d.progress[0].key, "vod:2");
        assert!(d.save_progress(wp("vod:1", 99_000, 100_000)));
        assert_eq!(d.progress.len(), 1);
    }

    #[test]
    fn recent_dedup() {
        let mut d = ProfileData::default();
        let c = |id: &str| RecentChannel { id: id.into(), name: id.into(), image: None, epg_id: None };
        assert!(d.add_recent(c("1")));
        assert!(!d.add_recent(c("1")));
        assert!(d.add_recent(c("2")));
        assert!(d.add_recent(c("1")));
        assert_eq!(d.recent.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), vec!["1", "2"]);
    }

    #[cfg(windows)]
    #[test]
    fn profiles_encrypted_on_disk() {
        let dir = std::env::temp_dir().join(format!("iptv-test-{}", uuid::Uuid::new_v4()));
        let mut store = Store::at(dir.clone());
        let p = Profile { id: "a".into(), name: "n".into(), server: "http://s".into(), username: "u".into(), password: "motdepasse".into() };
        store.save_profiles(&[p.clone()]).unwrap();
        let raw = std::fs::read(dir.join("profiles.dat")).unwrap();
        assert!(!raw.windows(10).any(|w| w == b"motdepasse"));
        assert_eq!(store.load_profiles(), vec![p]);

        std::fs::write(dir.join("profiles.dat"), b"abime").unwrap();
        assert!(store.load_profiles().is_empty());
        assert!(store.profiles_unreadable);
        let _ = std::fs::remove_dir_all(dir);
    }
}
