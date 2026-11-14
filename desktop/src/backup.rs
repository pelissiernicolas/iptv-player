//! Sauvegarde des données personnelles d'un profil : favoris, progression et chaînes récentes.
//! Même format que la version Android (fichiers échangeables entre les deux). Jamais
//! d'identifiants ni d'adresse d'image (celles d'un fournisseur peuvent contenir un jeton).

use serde::{Deserialize, Serialize};

use crate::store::{RecentChannel, WatchProgress};
use crate::xtream::ContentType;

pub const FORMAT: &str = "iptv-player-backup";
pub const VERSION: i32 = 1;
/// Au-delà, le fichier n'est pas une sauvegarde de l'application.
pub const MAX_BYTES: usize = 4 * 1024 * 1024;

const MAX_FAVORITES: usize = 5_000;
const MAX_ID: usize = 64;
const MAX_TEXT: usize = 300;

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
#[serde(default)]
pub struct Favorites {
    pub live: Vec<String>,
    pub vod: Vec<String>,
    pub series: Vec<String>,
}

impl Favorites {
    pub fn get(&self, t: ContentType) -> &Vec<String> {
        match t {
            ContentType::Live => &self.live,
            ContentType::Vod => &self.vod,
            ContentType::Series => &self.series,
        }
    }

    pub fn count(&self) -> usize {
        self.live.len() + self.vod.len() + self.series.len()
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Backup {
    /// Sans valeur par défaut : un fichier sans ce champ n'est pas une sauvegarde.
    #[serde(default)]
    pub format: String,
    pub version: i32,
    pub exported_at: i64,
    pub favorites: Favorites,
    pub progress: Vec<WatchProgress>,
    pub recent_channels: Vec<RecentChannel>,
}

impl Default for Backup {
    fn default() -> Self {
        Self {
            format: FORMAT.into(),
            version: VERSION,
            exported_at: 0,
            favorites: Favorites::default(),
            progress: Vec::new(),
            recent_channels: Vec::new(),
        }
    }
}

impl Backup {
    /// Les adresses d'images ne sortent jamais de l'appareil.
    pub fn encode(&self) -> String {
        let mut b = self.clone();
        b.progress.iter_mut().for_each(|p| p.image = None);
        b.recent_channels.iter_mut().for_each(|c| c.image = None);
        serde_json::to_string_pretty(&b).unwrap_or_default()
    }

    /// Lit et assainit une sauvegarde ; erreur si ce n'en est pas une.
    pub fn decode(text: &str) -> Result<Backup, String> {
        if text.len() > MAX_BYTES {
            return Err("fichier trop volumineux".into());
        }
        let raw: Backup = serde_json::from_str(text).map_err(|_| "fichier illisible".to_string())?;
        if raw.format != FORMAT {
            return Err("ce n'est pas une sauvegarde IPTV Player".into());
        }
        if raw.version > VERSION {
            return Err("sauvegarde créée par une version plus récente".into());
        }
        Ok(raw.sanitized())
    }

    /// Écarte les entrées invalides et borne les textes : le fichier vient de l'extérieur.
    fn sanitized(self) -> Backup {
        fn id(s: &str) -> Option<String> {
            let t = s.trim();
            (!t.is_empty() && t.chars().count() <= MAX_ID).then(|| t.to_string())
        }
        fn text(s: &str) -> String {
            s.chars().take(MAX_TEXT).collect()
        }
        fn ids(list: &[String]) -> Vec<String> {
            let mut out: Vec<String> = Vec::new();
            for s in list {
                if let Some(i) = id(s)
                    && !out.contains(&i) {
                        out.push(i);
                    }
                if out.len() >= MAX_FAVORITES {
                    break;
                }
            }
            out
        }

        let mut progress: Vec<WatchProgress> = Vec::new();
        for p in self.progress {
            let Some(content_id) = id(&p.content_id) else { continue };
            let expected = match p.kind.as_str() {
                "vod" => format!("vod:{content_id}"),
                "episode" => format!("ep:{content_id}"),
                _ => continue,
            };
            if p.key != expected || p.duration <= 0 || p.position < 0 || p.position > p.duration {
                continue;
            }
            if progress.iter().any(|x| x.key == p.key) {
                continue;
            }
            progress.push(WatchProgress {
                extension: p.extension.filter(|e| e.len() <= 8 && e.chars().all(|c| c.is_ascii_alphanumeric())),
                title: text(&p.title),
                subtitle: p.subtitle.as_deref().map(text),
                image: None,
                series_id: p.series_id.as_deref().and_then(id),
                content_id,
                ..p
            });
        }

        let mut recent: Vec<RecentChannel> = Vec::new();
        for c in self.recent_channels {
            let Some(cid) = id(&c.id) else { continue };
            if recent.iter().any(|x| x.id == cid) {
                continue;
            }
            recent.push(RecentChannel {
                id: cid,
                name: text(&c.name),
                image: None,
                epg_id: c.epg_id.as_deref().map(text),
            });
        }

        Backup {
            favorites: Favorites {
                live: ids(&self.favorites.live),
                vod: ids(&self.favorites.vod),
                series: ids(&self.favorites.series),
            },
            progress,
            recent_channels: recent,
            ..self
        }
    }
}

/// Par contenu, la position mise à jour le plus récemment ; du plus récent au plus ancien.
pub fn merge_progress(existing: &[WatchProgress], imported: &[WatchProgress], max: usize) -> Vec<WatchProgress> {
    let mut out: Vec<WatchProgress> = Vec::new();
    for p in existing.iter().chain(imported) {
        match out.iter_mut().find(|x| x.key == p.key) {
            Some(x) if p.updated_at > x.updated_at => *x = p.clone(),
            Some(_) => {}
            None => out.push(p.clone()),
        }
    }
    out.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
    out.truncate(max);
    out
}

/// Les chaînes de l'appareil d'abord (ordre conservé), puis celles importées absentes.
pub fn merge_recent(existing: &[RecentChannel], imported: &[RecentChannel], max: usize) -> Vec<RecentChannel> {
    let mut out = existing.to_vec();
    for c in imported {
        if !out.iter().any(|x| x.id == c.id) {
            out.push(c.clone());
        }
    }
    out.truncate(max);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wp(key: &str, kind: &str, id: &str, updated: i64) -> WatchProgress {
        WatchProgress {
            key: key.into(),
            kind: kind.into(),
            content_id: id.into(),
            extension: Some("mkv".into()),
            title: "T".into(),
            subtitle: None,
            image: Some("http://img?token=x".into()),
            position: 100,
            duration: 1000,
            updated_at: updated,
            series_id: None,
            season: None,
        }
    }

    #[test]
    fn android_format_roundtrip() {
        let android = r#"{"format":"iptv-player-backup","version":1,"exportedAt":5,
            "favorites":{"live":["1","1"," "],"vod":["9"],"series":[]},
            "progress":[{"key":"vod:9","kind":"vod","contentId":"9","extension":"mp4","title":"Film",
              "position":20000,"duration":90000,"updatedAt":7}],
            "recentChannels":[{"id":"1","name":"TF1","epgId":"tf1.fr"}]}"#;
        let b = Backup::decode(android).unwrap();
        assert_eq!(b.favorites.live, vec!["1"]);
        assert_eq!(b.progress[0].content_id, "9");
        assert_eq!(b.recent_channels[0].epg_id.as_deref(), Some("tf1.fr"));
        let text = b.encode();
        assert!(text.contains("\"recentChannels\""));
        assert!(text.contains("\"exportedAt\""));
        assert_eq!(Backup::decode(&text).unwrap(), b);
    }

    #[test]
    fn rejects_foreign_and_invalid() {
        assert!(Backup::decode("{}").is_err());
        assert!(Backup::decode("pas du json").is_err());
        assert!(Backup::decode(r#"{"format":"iptv-player-backup","version":99}"#).is_err());
        let mut b = Backup::default();
        b.progress = vec![wp("vod:1", "vod", "1", 1), wp("ep:2", "vod", "2", 1), wp("x", "film", "3", 1)];
        let back = Backup::decode(&b.encode()).unwrap();
        assert_eq!(back.progress.len(), 1);
        assert!(back.progress[0].image.is_none());
    }

    #[test]
    fn merges_keep_latest() {
        let a = vec![wp("vod:1", "vod", "1", 10)];
        let mut newer = wp("vod:1", "vod", "1", 20);
        newer.position = 500;
        let merged = merge_progress(&a, &[newer, wp("vod:2", "vod", "2", 5)], 60);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].position, 500);
    }
}
