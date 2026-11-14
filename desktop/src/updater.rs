//! Mise à jour automatique depuis les releases GitHub : une archive
//! `IPTV-Player-<version>-windows-<arch>.zip` est téléchargée, ses fichiers remplacent ceux
//! de l'application (les anciens sont renommés `.old` puis supprimés au lancement suivant),
//! et l'application redémarre.

use std::path::{Path, PathBuf};

use futures_util::StreamExt;
use serde_json::Value;

use crate::xtream::s;

pub const REPO: &str = "pelissiernicolas/iptv-player";
pub const CURRENT_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Debug)]
pub struct UpdateInfo {
    pub version: String,
    pub notes: String,
    pub url: String,
    pub size: i64,
}

pub fn arch() -> &'static str {
    if cfg!(target_arch = "aarch64") { "arm64" } else { "x64" }
}

pub fn asset_name(version: &str) -> String {
    format!("IPTV-Player-{version}-windows-{}.zip", arch())
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent("IPTVPlayer-Updater")
        .connect_timeout(std::time::Duration::from_secs(15))
        .build()
        .expect("client HTTP")
}

/// Dernière version publiée si elle est plus récente et contient une archive Windows pour
/// cette architecture ; `None` sinon.
pub async fn check_latest() -> Result<Option<UpdateInfo>, String> {
    let resp = client()
        .get(format!("https://api.github.com/repos/{REPO}/releases/latest"))
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|_| "GitHub injoignable".to_string())?;
    if !resp.status().is_success() {
        return Err(format!("GitHub a répondu HTTP {}", resp.status().as_u16()));
    }
    let root: Value = resp.json_value().await?;
    let Some(version) = s(root.get("tag_name")).map(|t| t.trim_start_matches(['v', 'V']).to_string()) else {
        return Ok(None);
    };
    if !is_newer(&version, CURRENT_VERSION) {
        return Ok(None);
    }
    let wanted = asset_name(&version);
    let asset = root
        .get("assets")
        .and_then(Value::as_array)
        .and_then(|a| a.iter().find(|x| s(x.get("name")).is_some_and(|n| n.eq_ignore_ascii_case(&wanted))));
    let Some(asset) = asset else { return Ok(None) };
    let Some(url) = s(asset.get("browser_download_url")) else { return Ok(None) };
    // Uniquement depuis GitHub : l'adresse vient d'une réponse réseau.
    if !url.starts_with(&format!("https://github.com/{REPO}/releases/download/")) {
        return Ok(None);
    }
    Ok(Some(UpdateInfo {
        notes: clean_notes(&s(root.get("body")).unwrap_or_default()),
        size: s(asset.get("size")).and_then(|v| v.parse().ok()).unwrap_or(-1),
        version,
        url,
    }))
}

trait JsonValue {
    async fn json_value(self) -> Result<Value, String>;
}

impl JsonValue for reqwest::Response {
    async fn json_value(self) -> Result<Value, String> {
        let bytes = self.bytes().await.map_err(|_| "réponse GitHub incomplète".to_string())?;
        serde_json::from_slice(&bytes).map_err(|_| "réponse GitHub illisible".to_string())
    }
}

/// "1.10.0" > "1.9.2". Une préversion ("1.4.0-beta") est plus ancienne que la version finale.
pub fn is_newer(remote: &str, current: &str) -> bool {
    fn numbers(v: &str) -> Vec<u64> {
        v.split('-')
            .next()
            .unwrap_or("")
            .split('.')
            .map(|p| p.chars().take_while(char::is_ascii_digit).collect::<String>().parse().unwrap_or(0))
            .collect()
    }
    let (r, c) = (numbers(remote), numbers(current));
    for i in 0..r.len().max(c.len()) {
        let (a, b) = (r.get(i).copied().unwrap_or(0), c.get(i).copied().unwrap_or(0));
        if a != b {
            return a > b;
        }
    }
    // Même numéro : la version finale l'emporte sur une préversion.
    !remote.contains('-') && current.contains('-')
}

/// Notes de version en texte simple (sans Markdown ni consignes de la page de téléchargement).
pub fn clean_notes(markdown: &str) -> String {
    let link = |line: &str| -> String {
        // [texte](url) -> texte
        let mut out = String::new();
        let mut rest = line;
        while let Some(start) = rest.find('[') {
            let Some(mid) = rest[start..].find("](") else { break };
            let Some(end) = rest[start + mid..].find(')') else { break };
            out.push_str(&rest[..start]);
            out.push_str(&rest[start + 1..start + mid]);
            rest = &rest[start + mid + end + 1..];
        }
        out.push_str(rest);
        out
    };
    let lines: Vec<String> = markdown
        .lines()
        .filter(|l| !l.trim_start().starts_with("Téléchargez"))
        .map(|l| {
            let t = l.trim_end();
            let t = t.trim_start_matches('#').trim_start_matches('>').trim_start();
            let t = if let Some(r) = t.strip_prefix("- ").or_else(|| t.strip_prefix("* ")) { format!("• {r}") } else { t.to_string() };
            link(&t.replace("**", "").replace('`', ""))
        })
        .collect();
    let mut out = lines.join("\n");
    while out.contains("\n\n\n") {
        out = out.replace("\n\n\n", "\n\n");
    }
    out.trim().to_string()
}

/// Télécharge l'archive dans le dossier temporaire ; `progress` reçoit 0..1.
pub async fn download(info: &UpdateInfo, progress: impl Fn(f32)) -> Result<PathBuf, String> {
    let dir = std::env::temp_dir().join("iptv-player-update");
    let _ = std::fs::create_dir_all(&dir);
    let target = dir.join(asset_name(&info.version));
    let resp = client().get(&info.url).send().await.map_err(|_| "téléchargement impossible".to_string())?;
    if !resp.status().is_success() {
        return Err(format!("téléchargement refusé (HTTP {})", resp.status().as_u16()));
    }
    let total = resp.content_length().map(|v| v as i64).filter(|v| *v > 0).unwrap_or(info.size);
    let mut data: Vec<u8> = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| "connexion interrompue".to_string())?;
        data.extend_from_slice(&chunk);
        if data.len() > 512 * 1024 * 1024 {
            return Err("fichier trop volumineux".into());
        }
        if total > 0 {
            progress((data.len() as f32 / total as f32).min(1.0));
        }
    }
    if info.size > 0 && data.len() as i64 != info.size {
        return Err("fichier incomplet".into());
    }
    std::fs::write(&target, &data).map_err(|e| format!("écriture impossible : {e}"))?;
    Ok(target)
}

/// Remplace les fichiers de l'application par ceux de l'archive puis relance l'application.
pub fn install_and_restart(zip_path: &Path) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let app_dir = exe.parent().ok_or("dossier de l'application introuvable")?.to_path_buf();
    let file = std::fs::File::open(zip_path).map_err(|e| e.to_string())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| "archive invalide ou abîmée".to_string())?;

    // Extraction complète dans un dossier temporaire avant de toucher à quoi que ce soit.
    let staging = zip_path.with_extension("staging");
    let _ = std::fs::remove_dir_all(&staging);
    std::fs::create_dir_all(&staging).map_err(|e| e.to_string())?;
    let mut names = Vec::new();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|_| "archive abîmée".to_string())?;
        if entry.is_dir() {
            continue;
        }
        // Fichiers à plat uniquement : aucun chemin ne doit sortir du dossier de l'application.
        let Some(name) = entry.enclosed_name().and_then(|p| p.file_name().map(|n| n.to_owned())) else { continue };
        let mut out = std::fs::File::create(staging.join(&name)).map_err(|e| e.to_string())?;
        std::io::copy(&mut entry, &mut out).map_err(|_| "archive abîmée".to_string())?;
        names.push(name);
    }
    let exe_name = exe.file_name().map(|n| n.to_owned());
    if !names.iter().any(|n| Some(n) == exe_name.as_ref()) {
        return Err("l'archive ne contient pas l'application".into());
    }

    // Windows permet de renommer un exécutable ou une DLL en cours d'utilisation.
    for name in &names {
        let dest = app_dir.join(name);
        if dest.exists() {
            let old = app_dir.join(format!("{}.old", name.to_string_lossy()));
            let _ = std::fs::remove_file(&old);
            std::fs::rename(&dest, &old).map_err(|_| {
                "impossible de remplacer les fichiers : installez l'application dans un dossier où vous pouvez écrire".to_string()
            })?;
        }
        std::fs::rename(staging.join(name), &dest)
            .or_else(|_| std::fs::copy(staging.join(name), &dest).map(|_| ()))
            .map_err(|e| format!("copie impossible : {e}"))?;
    }
    let _ = std::fs::remove_dir_all(&staging);
    let _ = std::fs::remove_file(zip_path);
    std::process::Command::new(&exe).spawn().map_err(|e| format!("redémarrage impossible : {e}"))?;
    std::process::exit(0);
}

/// Supprime les fichiers `.old` laissés par une mise à jour précédente.
pub fn cleanup_old_files() {
    let Some(dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)) else { return };
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            if e.file_name().to_string_lossy().ends_with(".old") {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions() {
        assert!(is_newer("1.10.0", "1.9.2"));
        assert!(!is_newer("1.4.0", "1.4.0"));
        assert!(!is_newer("1.4.0-beta", "1.4.0"));
        assert!(is_newer("1.4.0", "1.4.0-beta"));
        assert!(is_newer("2.0", "1.99.99"));
        assert!(!is_newer("1.5.0", "1.5.1"));
    }

    #[test]
    fn notes() {
        let md = "## Lecteur\n- **Plus** rapide\n\n\n\nTéléchargez le zip\n[lien](http://x)";
        assert_eq!(clean_notes(md), "Lecteur\n• Plus rapide\n\nlien");
    }
}
