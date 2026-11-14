//! État de l'application et logique (profils, catalogue, recherche, lecture, sauvegarde,
//! mise à jour). Le dessin des écrans est dans `screens.rs`.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::time::{Duration, Instant};

use crate::backup::Backup;
use crate::epg::EpgStore;
use crate::images::HttpImageLoader;
use crate::player::{Player, PlayerEnv, PlayerOutput, PlayItem};
use crate::store::{Profile, ProfileData, Settings, Store, WatchProgress};
use crate::updater::{self, UpdateInfo};
use crate::xtream::{self, AccountInfo, Category, ContentType, Episode, MediaEntry, MovieDetail, SeriesDetail, XtreamClient};

pub const CATEGORY_ALL: &str = "__all__";
pub const CATEGORY_FAV: &str = "__fav__";
pub const CATEGORY_RECENT: &str = "__recent__";

const CATALOG_TTL: Duration = Duration::from_secs(30 * 60);
const MAX_CACHED_CATEGORIES: usize = 40;

#[derive(Clone)]
pub enum Screen {
    Profiles,
    EditProfile(Option<Profile>),
    Home,
    Movie(MediaEntry),
    Series(MediaEntry),
    Guide,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Section {
    Home,
    Tab(ContentType),
}

#[derive(Default)]
pub struct TabState {
    pub categories: Vec<Category>,
    pub categories_loaded: bool,
    pub selected: Option<String>,
    pub items: Arc<Vec<MediaEntry>>,
    pub loading: bool,
    pub error: Option<String>,
}

pub enum Msg {
    Login { profile: Profile, editing: bool, result: Result<AccountInfo, String> },
    Account(Result<AccountInfo, String>),
    Categories(ContentType, Result<Vec<Category>, String>),
    Items { kind: ContentType, category: String, result: Result<Arc<Vec<MediaEntry>>, String> },
    SearchSource(ContentType, Result<Arc<Vec<MediaEntry>>, String>),
    Series(Result<SeriesDetail, String>),
    Movie(Result<MovieDetail, String>),
    Resume(WatchProgress, Result<SeriesDetail, String>),
    UpdateChecked { manual: bool, result: Result<Option<UpdateInfo>, String> },
    UpdateProgress(f32),
    UpdateDownloaded(Result<std::path::PathBuf, String>),
}

/// Tâches asynchrones : une seule par clé, la précédente est annulée et ses résultats ignorés.
#[derive(Default)]
pub struct Tasks {
    gens: HashMap<String, u64>,
    handles: HashMap<String, tokio::task::JoinHandle<()>>,
}

impl Tasks {
    pub fn cancel(&mut self, key: &str) {
        *self.gens.entry(key.to_string()).or_default() += 1;
        if let Some(h) = self.handles.remove(key) {
            h.abort();
        }
    }

    pub fn cancel_all(&mut self) {
        let keys: Vec<String> = self.handles.keys().cloned().collect();
        keys.iter().for_each(|k| self.cancel(k));
        self.gens.values_mut().for_each(|g| *g += 1);
    }

    pub fn is_running(&self, key: &str) -> bool {
        self.handles.get(key).is_some_and(|h| !h.is_finished())
    }
}

pub struct Toast {
    pub text: String,
    pub until: Instant,
}

pub struct UpdateState {
    pub info: UpdateInfo,
    pub progress: Option<f32>,
    pub error: Option<String>,
    pub installing: bool,
}

pub struct App {
    pub rt: tokio::runtime::Runtime,
    pub ctx: egui::Context,
    pub tx: Sender<(String, u64, Msg)>,
    rx: Receiver<(String, u64, Msg)>,
    pub tasks: Tasks,
    pub store: Store,
    pub settings: Settings,
    pub screen: Screen,

    // Profils
    pub profiles: Vec<Profile>,
    pub active: Option<Profile>,
    pub client: Option<XtreamClient>,
    pub data: ProfileData,
    pub account: Option<AccountInfo>,
    pub login_busy: bool,
    pub login_error: Option<String>,
    pub form: ProfileForm,
    pub confirm_delete: Option<Profile>,

    // Catalogue
    pub section: Section,
    pub current_tab: ContentType,
    pub tabs: HashMap<ContentType, TabState>,
    category_cache: HashMap<(ContentType, String), (Instant, Arc<Vec<MediaEntry>>)>,
    all_cache: HashMap<ContentType, (Instant, Arc<Vec<MediaEntry>>)>,
    pub query: String,
    pub search_due: Option<Instant>,
    pub search_results: Option<(ContentType, String, Arc<Vec<MediaEntry>>)>,
    pub searching: bool,
    pub epg: EpgStore,

    // Fiches
    pub series_detail: Option<SeriesDetail>,
    pub series_error: Option<String>,
    pub series_season: Option<i32>,
    pub movie_detail: Option<MovieDetail>,
    pub movie_error: Option<String>,
    pub resuming: bool,

    // Guide
    pub guide_channels: Arc<Vec<MediaEntry>>,
    pub guide_title: String,
    pub guide_offset: f32,
    pub guide_window_start: i64,
    pub guide_selected: Option<(usize, xtream::EpgProgram)>,

    // Lecteur
    pub player: Player,

    // Divers
    pub show_account: bool,
    pub update: Option<UpdateState>,
    pub checking_update: bool,
    pub toasts: Vec<Toast>,
    pub images: Arc<HttpImageLoader>,
    last_image_sweep: Instant,
    pub touch: bool,
    applied_scale: f32,
    pub startup_message: Option<String>,
}

#[derive(Default, Clone)]
pub struct ProfileForm {
    pub name: String,
    pub server: String,
    pub username: String,
    pub password: String,
    pub show_password: bool,
    pub editing_id: Option<String>,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>) -> App {
        let ctx = cc.egui_ctx.clone();
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build().expect("runtime tokio");
        let images = Arc::new(HttpImageLoader::new(rt.handle().clone()));
        ctx.add_image_loader(images.clone());
        crate::ui::install_fonts(&ctx);

        let mut store = Store::open();
        let settings = store.load_settings();
        let profiles = store.load_profiles();
        let touch = settings.touch_mode.unwrap_or_else(touch_screen_present);
        crate::ui::apply_style(&ctx, touch);
        ctx.set_zoom_factor(settings.ui_scale.clamp(0.6, 3.0));

        let player = Player::new(rt.handle().clone(), &ctx, cc.get_proc_address.clone(), settings.volume);
        let (tx, rx) = channel();
        let startup_message = store.profiles_unreadable.then(|| {
            "Des profils enregistrés n'ont pas pu être relus (autre compte Windows ou fichier abîmé). \
             Ils sont conservés et seront récupérés s'ils redeviennent lisibles ; sinon, ajoutez à nouveau votre compte."
                .to_string()
        });

        let mut app = App {
            rt,
            ctx: ctx.clone(),
            tx,
            rx,
            tasks: Tasks::default(),
            store,
            settings,
            screen: Screen::Profiles,
            profiles,
            active: None,
            client: None,
            data: ProfileData::default(),
            account: None,
            login_busy: false,
            login_error: None,
            form: ProfileForm::default(),
            confirm_delete: None,
            section: Section::Home,
            current_tab: ContentType::Live,
            tabs: HashMap::new(),
            category_cache: HashMap::new(),
            all_cache: HashMap::new(),
            query: String::new(),
            search_due: None,
            search_results: None,
            searching: false,
            epg: EpgStore::new(),
            series_detail: None,
            series_error: None,
            series_season: None,
            movie_detail: None,
            movie_error: None,
            resuming: false,
            guide_channels: Arc::new(Vec::new()),
            guide_title: String::new(),
            guide_offset: 0.0,
            guide_window_start: 0,
            guide_selected: None,
            player,
            show_account: false,
            update: None,
            checking_update: false,
            toasts: Vec::new(),
            images,
            last_image_sweep: Instant::now(),
            touch,
            applied_scale: 0.0,
            startup_message,
        };
        if let Some(m) = app.startup_message.take() {
            app.toast(m);
        }
        app.start_flow();
        app.check_for_update(false);
        app
    }

    // ---------- Tâches ----------

    pub fn spawn<F>(&mut self, key: &str, fut: F)
    where
        F: std::future::Future<Output = Msg> + Send + 'static,
    {
        self.tasks.cancel(key);
        let generation = *self.tasks.gens.get(key).unwrap_or(&0);
        let (tx, ctx, k) = (self.tx.clone(), self.ctx.clone(), key.to_string());
        let handle = self.rt.spawn(async move {
            let msg = fut.await;
            let _ = tx.send((k, generation, msg));
            ctx.request_repaint();
        });
        self.tasks.handles.insert(key.to_string(), handle);
    }

    fn drain_messages(&mut self) {
        while let Ok((key, generation, msg)) = self.rx.try_recv() {
            if self.tasks.gens.get(&key).copied().unwrap_or(0) != generation {
                continue;
            }
            self.tasks.handles.remove(&key);
            self.handle(msg);
        }
    }

    pub fn toast(&mut self, text: impl Into<String>) {
        self.toasts.push(Toast { text: text.into(), until: Instant::now() + Duration::from_secs(5) });
        self.ctx.request_repaint();
    }

    pub fn save_settings(&self) {
        self.store.save_settings(&self.settings);
    }

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::Login { profile, editing, result } => {
                self.login_busy = false;
                match result {
                    Ok(info) => {
                        if editing {
                            for p in self.profiles.iter_mut() {
                                if p.id == profile.id {
                                    *p = profile.clone();
                                }
                            }
                        } else {
                            self.profiles.push(profile.clone());
                        }
                        self.persist_profiles();
                        self.activate(profile, Some(info));
                    }
                    Err(e) => self.login_error = Some(e),
                }
            }
            Msg::Account(result) => {
                if let Ok(info) = result {
                    self.account = Some(info);
                }
            }
            Msg::Categories(kind, result) => {
                let has_favorites = !self.data.favorites.get(kind).is_empty();
                let tab = self.tabs.entry(kind).or_default();
                match result {
                    Ok(cats) => {
                        tab.categories_loaded = true;
                        let first = if has_favorites {
                            CATEGORY_FAV.to_string()
                        } else {
                            cats.first().map(|c| c.id.clone()).unwrap_or_else(|| CATEGORY_ALL.to_string())
                        };
                        tab.categories = cats;
                        if tab.selected.is_none() {
                            self.select_category(kind, &first);
                        } else {
                            tab.loading = false;
                        }
                    }
                    Err(e) => {
                        tab.loading = false;
                        tab.error = Some(e);
                    }
                }
            }
            Msg::Items { kind, category, result } => {
                if let Ok(items) = &result {
                    if category == CATEGORY_ALL || category == CATEGORY_FAV {
                        self.all_cache.insert(kind, (Instant::now(), items.clone()));
                    } else {
                        self.cache_category(kind, &category, items.clone());
                    }
                }
                let tab = self.tabs.entry(kind).or_default();
                if tab.selected.as_deref() == Some(category.as_str()) {
                    tab.loading = false;
                    match result {
                        Ok(items) => {
                            tab.items = items;
                            tab.error = None;
                        }
                        Err(e) => tab.error = Some(e),
                    }
                }
            }
            Msg::SearchSource(kind, result) => {
                self.searching = false;
                match result {
                    Ok(all) => {
                        self.all_cache.insert(kind, (Instant::now(), all));
                        self.run_search();
                    }
                    Err(e) => {
                        self.search_results = Some((kind, self.query.clone(), Arc::new(Vec::new())));
                        self.tabs.entry(kind).or_default().error = Some(e);
                    }
                }
            }
            Msg::Series(result) => match result {
                Ok(d) => {
                    // Saison par défaut : celle du dernier épisode commencé.
                    let started = d.seasons.iter().find(|(_, eps)| eps.iter().any(|e| self.data.progress_for(&format!("ep:{}", e.id)).is_some()));
                    self.series_season = started.or(d.seasons.first()).map(|(s, _)| *s);
                    self.series_detail = Some(d);
                }
                Err(e) => self.series_error = Some(e),
            },
            Msg::Movie(result) => match result {
                Ok(d) => self.movie_detail = Some(d),
                Err(e) => self.movie_error = Some(e),
            },
            Msg::Resume(progress, result) => {
                self.resuming = false;
                let played = result.ok().and_then(|detail| {
                    let eps = detail.season(progress.season.unwrap_or(-1))?.clone();
                    let index = eps.iter().position(|e| e.id == progress.content_id)?;
                    let cover = detail.cover.clone().or(progress.image.clone());
                    self.play_episodes(progress.series_id.clone(), &progress.title, cover, &eps, index, progress.position);
                    Some(())
                });
                if played.is_none() {
                    self.resume_single(&progress);
                }
            }
            Msg::UpdateChecked { manual, result } => {
                self.checking_update = false;
                match result {
                    Ok(None) => {
                        if manual {
                            self.toast(format!("Vous avez la dernière version ({})", updater::CURRENT_VERSION));
                        }
                    }
                    Ok(Some(info)) => {
                        if manual || self.settings.skipped_update.as_deref() != Some(info.version.as_str()) {
                            self.update = Some(UpdateState { info, progress: None, error: None, installing: false });
                        }
                    }
                    Err(e) => {
                        if manual {
                            self.toast(format!("Impossible de vérifier les mises à jour : {e}"));
                        }
                    }
                }
            }
            Msg::UpdateProgress(p) => {
                if let Some(u) = self.update.as_mut() {
                    u.progress = Some(p);
                }
            }
            Msg::UpdateDownloaded(result) => {
                let Some(u) = self.update.as_mut() else { return };
                match result {
                    Ok(path) => {
                        u.installing = true;
                        self.player.shutdown(None);
                        if let Err(e) = updater::install_and_restart(&path) {
                            let u = self.update.as_mut().unwrap();
                            u.installing = false;
                            u.progress = None;
                            u.error = Some(format!("Échec de l'installation : {e}"));
                        }
                    }
                    Err(e) => {
                        u.progress = None;
                        u.error = Some(format!("Échec du téléchargement : {e}"));
                    }
                }
            }
        }
    }

    // ---------- Mise à jour ----------

    pub fn check_for_update(&mut self, manual: bool) {
        if self.checking_update {
            return;
        }
        self.checking_update = true;
        self.spawn("update-check", async move { Msg::UpdateChecked { manual, result: updater::check_latest().await } });
    }

    pub fn start_update(&mut self) {
        let Some(u) = self.update.as_mut() else { return };
        if u.progress.is_some() {
            return;
        }
        u.error = None;
        u.progress = Some(0.0);
        let info = u.info.clone();
        let (tx, ctx) = (self.tx.clone(), self.ctx.clone());
        let generation = *self.tasks.gens.get("update-dl").unwrap_or(&0);
        self.spawn("update-dl", async move {
            let progress = |p: f32| {
                let _ = tx.send(("update-dl".into(), generation, Msg::UpdateProgress(p)));
                ctx.request_repaint();
            };
            Msg::UpdateDownloaded(updater::download(&info, progress).await)
        });
    }

    pub fn dismiss_update(&mut self, skip: bool) {
        self.tasks.cancel("update-dl");
        if skip {
            self.settings.skipped_update = self.update.as_ref().map(|u| u.info.version.clone());
            self.save_settings();
        }
        self.update = None;
    }

    // ---------- Profils ----------

    fn start_flow(&mut self) {
        let active = self.settings.active_profile.as_ref().and_then(|id| self.profiles.iter().find(|p| &p.id == id).cloned());
        match active {
            Some(p) => self.open_profile(p),
            None if self.profiles.is_empty() => self.edit_profile(None),
            None => self.screen = Screen::Profiles,
        }
    }

    pub fn open_profile(&mut self, profile: Profile) {
        self.activate(profile, None);
        if let Some(client) = self.client.clone() {
            self.spawn("account", async move { Msg::Account(client.login().await) });
        }
    }

    pub fn edit_profile(&mut self, profile: Option<Profile>) {
        self.login_error = None;
        self.form = match &profile {
            Some(p) => ProfileForm {
                name: p.name.clone(),
                server: p.server.clone(),
                username: p.username.clone(),
                password: p.password.clone(),
                show_password: false,
                editing_id: Some(p.id.clone()),
            },
            None => ProfileForm::default(),
        };
        self.screen = Screen::EditProfile(profile);
    }

    pub fn cancel_edit(&mut self) {
        self.tasks.cancel("login");
        self.login_busy = false;
        self.login_error = None;
        self.form.password.clear();
        self.screen = if self.profiles.is_empty() { Screen::EditProfile(None) } else { Screen::Profiles };
    }

    /// Vérifie les identifiants auprès du serveur, enregistre le profil puis l'ouvre.
    pub fn save_profile(&mut self) {
        if self.login_busy {
            return;
        }
        let f = self.form.clone();
        if f.server.trim().is_empty() || f.username.trim().is_empty() || f.password.is_empty() {
            self.login_error = Some("Remplissez tous les champs".into());
            return;
        }
        let url = xtream::normalize_server(&f.server);
        let default_name = url.split("://").nth(1).unwrap_or(&url).split([':', '/']).next().unwrap_or("Profil").to_string();
        let profile = Profile {
            id: f.editing_id.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            name: if f.name.trim().is_empty() { default_name } else { f.name.trim().chars().take(40).collect() },
            server: url,
            username: f.username.trim().to_string(),
            password: f.password.clone(),
        };
        self.login_busy = true;
        self.login_error = None;
        let editing = f.editing_id.is_some();
        let client = XtreamClient::new(profile.credentials());
        self.spawn("login", async move { Msg::Login { result: client.login().await, profile, editing } });
    }

    pub fn delete_profile(&mut self, profile: &Profile) {
        self.profiles.retain(|p| p.id != profile.id);
        if self.settings.active_profile.as_deref() == Some(profile.id.as_str()) {
            self.settings.active_profile = None;
            self.save_settings();
        }
        // Données effacées seulement une fois la liste bien enregistrée.
        if self.persist_profiles() {
            self.store.delete_profile_data(&profile.id);
        }
        if self.profiles.is_empty() {
            self.edit_profile(None);
        }
    }

    fn persist_profiles(&mut self) -> bool {
        match self.store.save_profiles(&self.profiles) {
            Ok(()) => true,
            Err(e) => {
                self.toast(format!(
                    "Stockage sécurisé indisponible ({e}) : les modifications de profils ne seront pas conservées après la fermeture."
                ));
                false
            }
        }
    }

    /// Ferme la session en cours et revient au choix du profil.
    pub fn switch_profile(&mut self) {
        if self.player.is_open() {
            self.player.close(&self.ctx);
        }
        self.settings.active_profile = None;
        self.save_settings();
        self.tasks.cancel_all();
        self.client = None;
        self.active = None;
        self.account = None;
        self.data = ProfileData::default();
        self.reset_catalog();
        self.ctx.forget_all_images();
        self.show_account = false;
        self.screen = Screen::Profiles;
    }

    fn activate(&mut self, profile: Profile, account: Option<AccountInfo>) {
        self.tasks.cancel_all();
        self.settings.active_profile = Some(profile.id.clone());
        self.save_settings();
        self.data = self.store.load_profile_data(&profile.id);
        self.client = Some(XtreamClient::new(profile.credentials()));
        self.active = Some(profile);
        self.account = account;
        self.reset_catalog();
        self.screen = Screen::Home;
        self.form.password.clear();
        self.select_tab(ContentType::Live);
        // Accueil s'il y a quelque chose à reprendre.
        if !self.data.progress.is_empty() || !self.data.recent.is_empty() {
            self.section = Section::Home;
        }
    }

    fn reset_catalog(&mut self) {
        for t in ContentType::ALL {
            self.tasks.cancel(&format!("tab-{t:?}"));
        }
        self.tasks.cancel("search");
        self.category_cache.clear();
        self.all_cache.clear();
        self.tabs.clear();
        self.epg.clear();
        self.query.clear();
        self.search_results = None;
        self.search_due = None;
    }

    pub fn save_data(&self) {
        if let Some(p) = &self.active {
            self.store.save_profile_data(&p.id, &self.data);
        }
    }

    // ---------- Catalogue ----------

    pub fn select_home(&mut self) {
        self.section = Section::Home;
        self.query.clear();
        self.search_results = None;
    }

    pub fn select_tab(&mut self, kind: ContentType) {
        self.section = Section::Tab(kind);
        self.current_tab = kind;
        let needs = self.tabs.get(&kind).is_none_or(|t| !t.categories_loaded && !t.loading);
        if needs {
            self.load_categories(kind);
        }
        if !self.query.trim().is_empty() {
            self.search_due = Some(Instant::now());
        }
    }

    pub fn retry(&mut self, kind: ContentType) {
        let selected = self.tabs.get(&kind).and_then(|t| t.selected.clone());
        match selected {
            Some(sel) if self.tabs.get(&kind).is_some_and(|t| t.categories_loaded) => self.select_category(kind, &sel),
            _ => {
                self.tabs.remove(&kind);
                self.load_categories(kind);
            }
        }
    }

    fn load_categories(&mut self, kind: ContentType) {
        let Some(client) = self.client.clone() else { return };
        let tab = self.tabs.entry(kind).or_default();
        tab.loading = true;
        tab.error = None;
        self.spawn(&format!("tab-{kind:?}"), async move { Msg::Categories(kind, client.categories(kind).await) });
    }

    fn cache_category(&mut self, kind: ContentType, id: &str, items: Arc<Vec<MediaEntry>>) {
        if self.category_cache.len() >= MAX_CACHED_CATEGORIES
            && let Some(oldest) = self.category_cache.iter().min_by_key(|(_, (t, _))| *t).map(|(k, _)| k.clone()) {
                self.category_cache.remove(&oldest);
            }
        self.category_cache.insert((kind, id.to_string()), (Instant::now(), items));
    }

    fn cached_all(&self, kind: ContentType) -> Option<Arc<Vec<MediaEntry>>> {
        self.all_cache.get(&kind).filter(|(t, _)| t.elapsed() < CATALOG_TTL).map(|(_, v)| v.clone())
    }

    pub fn select_category(&mut self, kind: ContentType, category: &str) {
        let Some(client) = self.client.clone() else { return };
        let key = format!("tab-{kind:?}");
        self.tasks.cancel(&key);
        let tab = self.tabs.entry(kind).or_default();
        tab.selected = Some(category.to_string());
        tab.error = None;
        if category == CATEGORY_RECENT {
            tab.items = Arc::new(Vec::new());
            tab.loading = false;
            return;
        }
        let cached = if category == CATEGORY_ALL || category == CATEGORY_FAV {
            self.cached_all(kind)
        } else {
            self.category_cache.get(&(kind, category.to_string())).filter(|(t, _)| t.elapsed() < CATALOG_TTL).map(|(_, v)| v.clone())
        };
        let tab = self.tabs.entry(kind).or_default();
        if let Some(items) = cached {
            tab.items = items;
            tab.loading = false;
            return;
        }
        tab.items = Arc::new(Vec::new());
        tab.loading = true;
        let cat = category.to_string();
        self.spawn(&key, async move {
            let filter = (cat != CATEGORY_ALL && cat != CATEGORY_FAV).then_some(cat.as_str());
            let result = client.streams(kind, filter).await.map(Arc::new);
            Msg::Items { kind, category: cat.clone(), result }
        });
    }

    pub fn toggle_favorite(&mut self, entry: &MediaEntry) {
        let set = self.data.favorites.get_mut(entry.kind);
        if !set.remove(&entry.id) {
            set.insert(entry.id.clone());
        }
        self.save_data();
    }

    pub fn is_favorite(&self, entry: &MediaEntry) -> bool {
        self.data.favorites.get(entry.kind).contains(&entry.id)
    }

    /// Recharge le catalogue depuis le serveur (nouvelles chaînes, films ajoutés…).
    pub fn refresh_catalog(&mut self) {
        let section = self.section;
        let tab = self.current_tab;
        self.reset_catalog();
        self.select_tab(tab);
        self.section = section;
        self.toast("Catalogue actualisé");
    }

    // ---------- Recherche ----------

    pub fn on_query_changed(&mut self) {
        if self.query.trim().is_empty() {
            self.search_results = None;
            self.search_due = None;
            self.searching = false;
            self.tasks.cancel("search");
        } else {
            self.search_due = Some(Instant::now() + Duration::from_millis(300));
        }
    }

    pub fn run_search(&mut self) {
        self.search_due = None;
        let q = crate::ui::fold(self.query.trim());
        let kind = self.current_tab;
        if q.is_empty() || self.client.is_none() {
            self.search_results = None;
            return;
        }
        match self.cached_all(kind) {
            Some(all) => {
                let results: Vec<MediaEntry> = all.iter().filter(|e| crate::ui::fold(&e.name).contains(&q)).cloned().collect();
                self.search_results = Some((kind, self.query.clone(), Arc::new(results)));
                self.searching = false;
            }
            None => {
                if self.tasks.is_running("search") {
                    return;
                }
                self.searching = true;
                let client = self.client.clone().unwrap();
                self.spawn("search", async move { Msg::SearchSource(kind, client.streams(kind, None).await.map(Arc::new)) });
            }
        }
    }

    // ---------- Fiches ----------

    pub fn open_series(&mut self, entry: MediaEntry) {
        let Some(client) = self.client.clone() else { return };
        self.series_detail = None;
        self.series_error = None;
        self.series_season = None;
        let id = entry.id.clone();
        self.screen = Screen::Series(entry);
        self.spawn("series", async move { Msg::Series(client.series_info(&id).await) });
    }

    pub fn open_movie(&mut self, entry: MediaEntry) {
        let Some(client) = self.client.clone() else { return };
        self.movie_detail = None;
        self.movie_error = None;
        let id = entry.id.clone();
        self.screen = Screen::Movie(entry);
        self.spawn("movie", async move { Msg::Movie(client.vod_info(&id).await) });
    }

    pub fn back_home(&mut self) {
        self.tasks.cancel("series");
        self.tasks.cancel("movie");
        self.screen = Screen::Home;
    }

    pub fn open_guide(&mut self, channels: Arc<Vec<MediaEntry>>, title: String) {
        self.guide_channels = channels;
        self.guide_title = title;
        self.guide_offset = f32::NAN;
        self.guide_selected = None;
        self.screen = Screen::Guide;
    }

    pub fn recent_entries(&self) -> Vec<MediaEntry> {
        self.data
            .recent
            .iter()
            .map(|c| MediaEntry {
                id: c.id.clone(),
                name: c.name.clone(),
                image: c.image.clone(),
                extension: None,
                rating: None,
                plot: None,
                kind: ContentType::Live,
                epg_id: c.epg_id.clone(),
            })
            .collect()
    }

    // ---------- Lecture ----------

    fn secure(&self) -> bool {
        self.client.as_ref().is_some_and(|c| c.credentials.server.to_ascii_lowercase().starts_with("https://"))
    }

    pub fn play_live(&mut self, channels: &[MediaEntry], index: usize) {
        let Some(c) = self.client.clone() else { return };
        let items = channels
            .iter()
            .map(|e| PlayItem {
                is_live: true,
                fallback_url: Some(c.live_url(&e.id, "m3u8")),
                content_id: Some(e.id.clone()),
                image: e.image.clone(),
                epg_id: e.epg_id.clone(),
                ..PlayItem::simple(e.name.clone(), c.live_url(&e.id, "ts"))
            })
            .collect();
        let secure = self.secure();
        self.player.play(&self.ctx, items, index, -1, secure);
    }

    pub fn play_vod(&mut self, entry: &MediaEntry, extension: Option<String>, start_ms: i64) {
        let Some(c) = self.client.clone() else { return };
        let ext = extension.or(entry.extension.clone());
        let item = PlayItem {
            content_id: Some(entry.id.clone()),
            image: entry.image.clone(),
            progress_key: Some(format!("vod:{}", entry.id)),
            kind: Some("vod".into()),
            extension: ext.clone(),
            ..PlayItem::simple(entry.name.clone(), c.vod_url(&entry.id, ext.as_deref()))
        };
        let secure = self.secure();
        self.player.play(&self.ctx, vec![item], 0, start_ms, secure);
    }

    pub fn play_episodes(&mut self, series_id: Option<String>, series_name: &str, series_image: Option<String>, episodes: &[Episode], index: usize, start_ms: i64) {
        let Some(c) = self.client.clone() else { return };
        let items = episodes
            .iter()
            .map(|e| {
                let label = format!("S{} E{} · {}", e.season, e.number, e.title);
                PlayItem {
                    content_id: Some(e.id.clone()),
                    image: e.image.clone().or(series_image.clone()),
                    progress_key: Some(format!("ep:{}", e.id)),
                    kind: Some("episode".into()),
                    extension: Some(e.extension.clone()),
                    library_title: Some(series_name.to_string()),
                    library_subtitle: Some(label.clone()),
                    series_id: series_id.clone(),
                    season: Some(e.season),
                    ..PlayItem::simple(label, c.episode_url(&e.id, &e.extension))
                }
            })
            .collect();
        let secure = self.secure();
        self.player.play(&self.ctx, items, index, start_ms, secure);
    }

    /// Reprend un film / épisode depuis « Continuer à regarder ». Pour un épisode, la saison
    /// est rechargée afin d'enchaîner sur les épisodes suivants.
    pub fn resume(&mut self, progress: WatchProgress) {
        let Some(client) = self.client.clone() else { return };
        match progress.series_id.clone() {
            Some(series_id) if progress.kind == "episode" => {
                if self.resuming {
                    return;
                }
                self.resuming = true;
                self.spawn("resume", async move {
                    let result = client.series_info(&series_id).await;
                    Msg::Resume(progress, result)
                });
            }
            _ => self.resume_single(&progress),
        }
    }

    fn resume_single(&mut self, p: &WatchProgress) {
        let Some(c) = self.client.clone() else { return };
        let url = if p.kind == "episode" {
            c.episode_url(&p.content_id, p.extension.as_deref().unwrap_or("mp4"))
        } else {
            c.vod_url(&p.content_id, p.extension.as_deref())
        };
        let item = PlayItem {
            content_id: Some(p.content_id.clone()),
            image: p.image.clone(),
            progress_key: Some(p.key.clone()),
            kind: Some(p.kind.clone()),
            extension: p.extension.clone(),
            library_title: Some(p.title.clone()),
            library_subtitle: p.subtitle.clone(),
            series_id: p.series_id.clone(),
            season: p.season,
            ..PlayItem::simple(p.subtitle.clone().unwrap_or(p.title.clone()), url)
        };
        let secure = self.secure();
        self.player.play(&self.ctx, vec![item], 0, p.position, secure);
    }

    pub fn remove_progress(&mut self, key: &str) {
        if self.data.remove_progress(key) {
            self.save_data();
        }
    }

    fn apply_player_outputs(&mut self) {
        for out in self.player.take_outputs() {
            match out {
                PlayerOutput::SaveProgress(p) => {
                    if self.data.save_progress(p) {
                        self.save_data();
                    }
                }
                PlayerOutput::RemoveProgress(k) => self.remove_progress(&k),
                PlayerOutput::Recent(c) => {
                    if self.data.add_recent(c) {
                        self.save_data();
                    }
                }
                PlayerOutput::Toast(t) => self.toast(t),
                PlayerOutput::Closed => {
                    if self.settings.volume != self.player.volume {
                        self.settings.volume = self.player.volume;
                        self.save_settings();
                    }
                }
            }
        }
    }

    // ---------- Sauvegarde ----------

    fn backup_summary(favorites: usize, b: &Backup) -> String {
        format!("{favorites} favoris, {} en cours, {} chaînes", b.progress.len(), b.recent_channels.len())
    }

    pub fn export_backup(&mut self) {
        let Some(profile) = self.active.clone() else { return };
        let date = chrono::Local::now().format("%Y-%m-%d");
        let name: String = profile.name.chars().filter(|c| c.is_alphanumeric()).take(20).collect();
        let file_name = ["IPTV-Player".to_string(), name, date.to_string()].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join("-") + ".json";
        let Some(path) = rfd::FileDialog::new().set_title("Exporter favoris et historique").set_file_name(&file_name).add_filter("Sauvegarde IPTV Player", &["json"]).save_file() else {
            return;
        };
        let backup = self.data.to_backup();
        match crate::store::write_atomic(&path, backup.encode().as_bytes()) {
            Ok(()) => self.toast(format!("Sauvegarde enregistrée ({})", Self::backup_summary(backup.favorites.count(), &backup))),
            Err(_) => self.toast("Échec de l'enregistrement de la sauvegarde"),
        }
    }

    /// Fusionne une sauvegarde (y compris celles de l'application Android) dans le profil ouvert.
    pub fn import_backup(&mut self) {
        if self.active.is_none() {
            return;
        }
        let Some(path) = rfd::FileDialog::new().set_title("Importer une sauvegarde").add_filter("Sauvegarde IPTV Player", &["json"]).pick_file() else {
            return;
        };
        let result = std::fs::metadata(&path)
            .map_err(|_| "fichier illisible".to_string())
            .and_then(|m| if m.len() as usize > crate::backup::MAX_BYTES { Err("fichier trop volumineux".into()) } else { Ok(()) })
            .and_then(|_| std::fs::read_to_string(&path).map_err(|_| "fichier illisible".to_string()))
            .and_then(|t| Backup::decode(&t));
        match result {
            Ok(mut backup) => {
                self.fill_backup_images(&mut backup);
                let added = self.data.import(&backup);
                self.save_data();
                self.toast(format!("Sauvegarde importée ({})", Self::backup_summary(added, &backup)));
            }
            Err(e) => self.toast(format!("Import impossible : {e}")),
        }
    }

    /// Images absentes des sauvegardes, retrouvées dans le catalogue en mémoire.
    fn fill_backup_images(&self, b: &mut Backup) {
        let images = |kind: ContentType| -> HashMap<String, String> {
            let mut map = HashMap::new();
            let lists = self.all_cache.get(&kind).map(|(_, v)| v.clone()).into_iter().chain(
                self.category_cache.iter().filter(|((k, _), _)| *k == kind).map(|(_, (_, v))| v.clone()),
            );
            for list in lists {
                for e in list.iter() {
                    if let Some(img) = &e.image {
                        map.entry(e.id.clone()).or_insert(img.clone());
                    }
                }
            }
            map
        };
        let (vod, series, live) = (images(ContentType::Vod), images(ContentType::Series), images(ContentType::Live));
        for p in b.progress.iter_mut() {
            p.image = if p.kind == "vod" { vod.get(&p.content_id).cloned() } else { p.series_id.as_ref().and_then(|s| series.get(s).cloned()) };
        }
        for c in b.recent_channels.iter_mut() {
            c.image = live.get(&c.id).cloned();
        }
    }

    pub fn set_touch_mode(&mut self, mode: Option<bool>) {
        self.settings.touch_mode = mode;
        self.touch = mode.unwrap_or_else(touch_screen_present);
        crate::ui::apply_style(&self.ctx, self.touch);
        self.save_settings();
    }

    // ---------- Boucle principale ----------

    pub fn frame(&mut self, ui: &mut egui::Ui) {
        self.drain_messages();
        self.apply_player_outputs();

        if let Some(due) = self.search_due {
            if Instant::now() >= due {
                self.run_search();
            } else {
                self.ctx.request_repaint_after(due - Instant::now());
            }
        }

        // Zoom (Ctrl + / Ctrl -) mémorisé.
        let zoom = self.ctx.zoom_factor();
        if (zoom - self.applied_scale).abs() > 0.001 {
            if self.applied_scale != 0.0 {
                self.settings.ui_scale = zoom;
                self.save_settings();
            }
            self.applied_scale = zoom;
        }

        // Images plus affichées depuis 2 minutes : mémoire libérée.
        if self.last_image_sweep.elapsed() > Duration::from_secs(30) {
            self.last_image_sweep = Instant::now();
            for uri in self.images.stale(Duration::from_secs(120)) {
                self.ctx.forget_image(&uri);
            }
        }

        // Rafraîchit l'heure (EPG, barres de progression) même sans interaction.
        self.ctx.request_repaint_after(Duration::from_secs(30));

        if self.player.is_open() {
            let rt = self.rt.handle().clone();
            let client = self.client.clone();
            let env = PlayerEnv { rt: &rt, client: client.as_ref(), epg: &self.epg };
            egui::CentralPanel::default().frame(egui::Frame::NONE.fill(egui::Color32::BLACK)).show(ui, |ui| {
                self.player.ui(ui, &env);
            });
            self.apply_player_outputs();
        } else {
            self.screens(ui);
        }
        self.overlays(ui);
    }

    pub fn on_exit(&mut self, gl: Option<&egui_glow::glow::Context>) {
        self.player.shutdown(gl);
        self.apply_player_outputs();
        self.settings.volume = self.player.volume;
        self.save_settings();
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.frame(ui);
    }

    fn on_exit(&mut self, gl: Option<&egui_glow::glow::Context>) {
        App::on_exit(self, gl);
    }
}

/// Écran tactile présent ?
pub fn touch_screen_present() -> bool {
    #[cfg(windows)]
    {
        // SM_MAXIMUMTOUCHES = 95
        #[link(name = "user32")]
        unsafe extern "system" {
            fn GetSystemMetrics(index: i32) -> i32;
        }
        // SAFETY: fonction Win32 sans effet de bord.
        unsafe { GetSystemMetrics(95) > 0 }
    }
    #[cfg(not(windows))]
    {
        false
    }
}
