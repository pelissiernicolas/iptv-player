//! Chargement des affiches et logos (HTTP) pour egui : téléchargement limité en taille et en
//! nombre, image réduite avant d'être envoyée à la carte graphique, et oubli des images qui
//! ne sont plus affichées depuis un moment (catalogues de plusieurs milliers d'éléments).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use egui::load::{ImageLoadResult, ImageLoader, ImagePoll, LoadError, SizeHint};
use egui::{ColorImage, Context};
use tokio::sync::Semaphore;

const MAX_BYTES: usize = 6 * 1024 * 1024;
/// Côté le plus long d'une image une fois réduite.
const MAX_SIDE: u32 = 480;

enum Entry {
    Pending,
    Ready(Arc<ColorImage>),
    Failed,
}

struct Slot {
    entry: Entry,
    last_used: Instant,
}

pub struct HttpImageLoader {
    rt: tokio::runtime::Handle,
    slots: Arc<Mutex<HashMap<String, Slot>>>,
    permits: Arc<Semaphore>,
}

impl HttpImageLoader {
    pub fn new(rt: tokio::runtime::Handle) -> Self {
        Self { rt, slots: Default::default(), permits: Arc::new(Semaphore::new(8)) }
    }

    /// Adresses inutilisées depuis `idle` : à oublier (aussi dans le cache de textures egui).
    pub fn stale(&self, idle: Duration) -> Vec<String> {
        let now = Instant::now();
        self.slots
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, s)| !matches!(s.entry, Entry::Pending) && now.duration_since(s.last_used) > idle)
            .map(|(k, _)| k.clone())
            .collect()
    }
}

impl ImageLoader for HttpImageLoader {
    fn id(&self) -> &str {
        "iptv::HttpImageLoader"
    }

    fn load(&self, ctx: &Context, uri: &str, _size_hint: SizeHint) -> ImageLoadResult {
        if !(uri.starts_with("http://") || uri.starts_with("https://")) {
            return Err(LoadError::NotSupported);
        }
        let mut slots = self.slots.lock().unwrap();
        if let Some(slot) = slots.get_mut(uri) {
            slot.last_used = Instant::now();
            return match &slot.entry {
                Entry::Pending => Ok(ImagePoll::Pending { size: None }),
                Entry::Ready(img) => Ok(ImagePoll::Ready { image: img.clone() }),
                Entry::Failed => Err(LoadError::Loading("image indisponible".into())),
            };
        }
        slots.insert(uri.to_string(), Slot { entry: Entry::Pending, last_used: Instant::now() });
        drop(slots);

        let (uri_owned, slots, permits, ctx) = (uri.to_string(), self.slots.clone(), self.permits.clone(), ctx.clone());
        self.rt.spawn(async move {
            let _permit = permits.acquire().await;
            let result = fetch(&uri_owned).await;
            let entry = match result {
                Some(bytes) => match tokio::task::spawn_blocking(move || decode(&bytes)).await.ok().flatten() {
                    Some(img) => Entry::Ready(Arc::new(img)),
                    None => Entry::Failed,
                },
                None => Entry::Failed,
            };
            if let Some(slot) = slots.lock().unwrap().get_mut(&uri_owned) {
                slot.entry = entry;
            }
            ctx.request_repaint();
        });
        Ok(ImagePoll::Pending { size: None })
    }

    fn forget(&self, uri: &str) {
        self.slots.lock().unwrap().remove(uri);
    }

    fn forget_all(&self) {
        self.slots.lock().unwrap().clear();
    }

    fn byte_size(&self) -> usize {
        self.slots
            .lock()
            .unwrap()
            .values()
            .map(|s| match &s.entry {
                Entry::Ready(img) => img.pixels.len() * 4,
                _ => 0,
            })
            .sum()
    }

    fn has_pending(&self) -> bool {
        self.slots.lock().unwrap().values().any(|s| matches!(s.entry, Entry::Pending))
    }
}

async fn fetch(url: &str) -> Option<Vec<u8>> {
    use futures_util::StreamExt;
    let client = crate::xtream::http_client();
    let resp = tokio::time::timeout(Duration::from_secs(20), client.get(url).send()).await.ok()?.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    if resp.content_length().is_some_and(|l| l as usize > MAX_BYTES) {
        return None;
    }
    let mut data = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = tokio::time::timeout(Duration::from_secs(20), stream.next()).await.ok()? {
        let chunk = chunk.ok()?;
        if data.len() + chunk.len() > MAX_BYTES {
            return None;
        }
        data.extend_from_slice(&chunk);
    }
    Some(data)
}

fn decode(bytes: &[u8]) -> Option<ColorImage> {
    let img = image::load_from_memory(bytes).ok()?;
    let img = if img.width() > MAX_SIDE || img.height() > MAX_SIDE { img.thumbnail(MAX_SIDE, MAX_SIDE) } else { img };
    let rgba = img.to_rgba8();
    let size = [rgba.width() as usize, rgba.height() as usize];
    Some(ColorImage::from_rgba_unmultiplied(size, rgba.as_raw()))
}
