//! Guide TV : programmes chargés à la demande pour les chaînes affichées, rafraîchis à la fin
//! du programme en cours, 4 requêtes simultanées au plus.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use tokio::sync::Semaphore;

use crate::xtream::{EpgProgram, XtreamClient};

const TTL_MS: i64 = 20 * 60_000;
/// Chaîne sans identifiant de guide dont le guide est vide : nouvel essai bien plus tard.
const ABSENT_TTL_MS: i64 = 2 * 60 * 60_000;
const RETRY_MS: i64 = 2 * 60_000;

#[derive(Default, Clone)]
pub struct EpgState {
    /// `None` = jamais chargé.
    pub programs: Option<Arc<Vec<EpgProgram>>>,
    pub failed: bool,
    next_fetch: i64,
    in_flight: bool,
}

pub struct EpgStore {
    map: Arc<Mutex<HashMap<String, EpgState>>>,
    permits: Arc<Semaphore>,
    /// Incrémenté à chaque changement de profil : les réponses d'un autre compte sont ignorées.
    generation: Arc<Mutex<u64>>,
}

pub fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

impl EpgStore {
    pub fn new() -> Self {
        Self { map: Default::default(), permits: Arc::new(Semaphore::new(4)), generation: Default::default() }
    }

    pub fn clear(&self) {
        *self.generation.lock().unwrap() += 1;
        self.map.lock().unwrap().clear();
    }

    /// État du guide d'une chaîne ; lance (ou relance) le chargement si nécessaire.
    pub fn get(&self, rt: &tokio::runtime::Handle, client: &XtreamClient, ctx: &egui::Context, stream_id: &str, has_epg_id: bool) -> EpgState {
        let now = now_ms();
        let mut map = self.map.lock().unwrap();
        let state = map.entry(stream_id.to_string()).or_default();
        if !state.in_flight && now >= state.next_fetch {
            state.in_flight = true;
            let (client, id, map_ref, permits, ctx) = (client.clone(), stream_id.to_string(), self.map.clone(), self.permits.clone(), ctx.clone());
            let gen_ref = self.generation.clone();
            let generation = *gen_ref.lock().unwrap();
            rt.spawn(async move {
                let result = {
                    let _p = permits.acquire().await;
                    client.short_epg(&id).await
                };
                if *gen_ref.lock().unwrap() != generation {
                    return;
                }
                let now = now_ms();
                let mut map = map_ref.lock().unwrap();
                let st = map.entry(id).or_default();
                st.in_flight = false;
                match result {
                    Ok(programs) => {
                        st.next_fetch = if programs.is_empty() && !has_epg_id {
                            now + ABSENT_TTL_MS
                        } else {
                            let end = programs.iter().find(|p| p.end > now).map(|p| p.end).unwrap_or(now + TTL_MS);
                            (end + 5_000).clamp(now + 60_000, now + TTL_MS)
                        };
                        st.programs = Some(Arc::new(programs));
                        st.failed = false;
                    }
                    Err(_) => {
                        // On garde l'éventuel guide déjà affiché et on retente plus tôt.
                        if st.programs.is_none() {
                            st.programs = Some(Arc::new(Vec::new()));
                        }
                        st.failed = true;
                        st.next_fetch = now + RETRY_MS;
                    }
                }
                drop(map);
                ctx.request_repaint();
            });
        }
        state.clone()
    }
}

/// Programme en cours et suivant.
pub fn now_next(programs: &[EpgProgram], now: i64) -> (Option<&EpgProgram>, Option<&EpgProgram>) {
    let current = programs.iter().find(|p| p.start <= now && now < p.end);
    let next = programs.iter().find(|p| p.start >= now.max(current.map(|c| c.end).unwrap_or(now)));
    (current, next)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_and_next() {
        let p = |s, e| EpgProgram { title: format!("{s}"), description: None, start: s, end: e };
        let list = vec![p(0, 10), p(10, 20), p(20, 30)];
        let (c, n) = now_next(&list, 12);
        assert_eq!(c.unwrap().start, 10);
        assert_eq!(n.unwrap().start, 20);
        let (c, n) = now_next(&list, 40);
        assert!(c.is_none() && n.is_none());
    }
}
