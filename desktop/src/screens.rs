//! Écrans de l'application, adaptés à la souris, au clavier et au doigt : grandes cibles,
//! défilement au glisser, appui long ou clic droit pour les favoris, mise en page qui
//! s'adapte à la largeur de la fenêtre.

use std::sync::Arc;

use egui::{Align, Align2, Color32, FontId, Layout, Rect, RichText, ScrollArea, Sense, Ui, Vec2, pos2, vec2};

use crate::app::{App, CATEGORY_ALL, CATEGORY_FAV, CATEGORY_RECENT, Screen, Section};
use crate::epg::{now_ms, now_next};
use crate::store::Profile;
use crate::ui::{self, PRIMARY, STAR, SURFACE, SURFACE_HIGH, SURFACE_VARIANT, TEXT_DIM, bold, dim, format_duration, format_time};
use crate::xtream::{self, ContentType, MediaEntry};

/// Actions décidées pendant le dessin, appliquées ensuite (évite les emprunts croisés).
enum Action {
    OpenProfile(Profile),
    EditProfile(Option<Profile>),
    AskDelete(Profile),
    SelectHome,
    SelectTab(ContentType),
    SelectCategory(ContentType, String),
    ToggleFavorite(MediaEntry),
    PlayLive(Arc<Vec<MediaEntry>>, usize),
    OpenItem(MediaEntry),
    Resume(crate::store::WatchProgress),
    RemoveProgress(String),
    Retry(ContentType),
    OpenGuide(Arc<Vec<MediaEntry>>, String),
    QueryChanged,
}

impl App {
    pub fn screens(&mut self, ui: &mut Ui) {
        // Échap : retour à l'écran précédent.
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) && !self.show_account && self.update.is_none() {
            match self.screen {
                Screen::Movie(_) | Screen::Series(_) | Screen::Guide => self.back_home(),
                Screen::Home if !self.query.is_empty() => {
                    self.query.clear();
                    self.on_query_changed();
                }
                _ => {}
            }
        }
        let screen = self.screen.clone();
        match screen {
            Screen::Profiles => self.profiles_screen(ui),
            Screen::EditProfile(p) => self.edit_profile_screen(ui, p),
            Screen::Home => self.home_screen(ui),
            Screen::Movie(e) => self.movie_screen(ui, &e),
            Screen::Series(e) => self.series_screen(ui, &e),
            Screen::Guide => self.guide_screen(ui),
        }
    }

    fn apply(&mut self, actions: Vec<Action>) {
        for a in actions {
            match a {
                Action::OpenProfile(p) => self.open_profile(p),
                Action::EditProfile(p) => self.edit_profile(p),
                Action::AskDelete(p) => self.confirm_delete = Some(p),
                Action::SelectHome => self.select_home(),
                Action::SelectTab(t) => self.select_tab(t),
                Action::SelectCategory(t, c) => self.select_category(t, &c),
                Action::ToggleFavorite(e) => self.toggle_favorite(&e),
                Action::PlayLive(list, i) => self.play_live(&list, i),
                Action::OpenItem(e) => match e.kind {
                    ContentType::Vod => self.open_movie(e),
                    ContentType::Series => self.open_series(e),
                    ContentType::Live => self.play_live(&[e], 0),
                },
                Action::Resume(p) => self.resume(p),
                Action::RemoveProgress(k) => self.remove_progress(&k),
                Action::Retry(t) => self.retry(t),
                Action::OpenGuide(list, title) => self.open_guide(list, title),
                Action::QueryChanged => self.on_query_changed(),
            }
        }
    }

    // ---------- Profils ----------

    fn profiles_screen(&mut self, ui: &mut Ui) {
        let mut actions = Vec::new();
        egui::CentralPanel::default().show(ui, |ui| {
            ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
                ui.add_space(40.0);
                ui.vertical_centered(|ui| {
                    ui.label(bold("Qui regarde ?", 30.0));
                    ui.add_space(8.0);
                    ui.label(dim("Choisissez un profil (compte Xtream Codes)"));
                });
                ui.add_space(28.0);
                let card_w = if self.touch { 230.0 } else { 210.0 };
                let gap = 20.0;
                let cols = (((ui.available_width() + gap) / (card_w + gap)).floor() as usize).clamp(1, 5);
                let total = cols as f32 * card_w + (cols - 1) as f32 * gap;
                let profiles = self.profiles.clone();
                let mut index = 0;
                let count = profiles.len() + 1;
                while index < count {
                    ui.horizontal(|row| {
                        row.add_space(((row.available_width() - total) / 2.0).max(0.0));
                        row.spacing_mut().item_spacing.x = gap;
                        for _ in 0..cols {
                            if index >= count {
                                break;
                            }
                            if index < profiles.len() {
                                profile_card(row, &profiles[index], card_w, &mut actions);
                            } else {
                                let (rect, resp) = ui::card(row, vec2(card_w, 230.0));
                                let p = row.painter();
                                p.rect_stroke(rect, 16.0, egui::Stroke::new(2.0, SURFACE_HIGH), egui::StrokeKind::Inside);
                                p.text(rect.center() - vec2(0.0, 18.0), Align2::CENTER_CENTER, "+", FontId::proportional(56.0), PRIMARY);
                                p.text(rect.center() + vec2(0.0, 40.0), Align2::CENTER_CENTER, "Ajouter un profil", FontId::proportional(16.0), ui::TEXT);
                                if resp.clicked() {
                                    actions.push(Action::EditProfile(None));
                                }
                            }
                            index += 1;
                        }
                    });
                    ui.add_space(gap);
                }
            });
        });
        self.apply(actions);
    }

    fn edit_profile_screen(&mut self, ui: &mut Ui, editing: Option<Profile>) {
        let mut submit = false;
        let mut cancel = false;
        egui::CentralPanel::default().show(ui, |ui| {
            ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
                let width = ui.available_width().min(560.0);
                ui.add_space(30.0);
                ui.vertical_centered(|ui| {
                    ui.set_max_width(width);
                    let title = if editing.is_some() {
                        "Modifier le profil"
                    } else if self.profiles.is_empty() {
                        "IPTV Player"
                    } else {
                        "Nouveau profil"
                    };
                    ui.label(bold(title, 28.0));
                    ui.add_space(4.0);
                    ui.label(dim("Identifiants Xtream Codes"));
                    ui.add_space(14.0);
                    ui.with_layout(Layout::top_down(Align::Min), |ui| {
                        ui.set_max_width(width);
                        ui.label(dim("Astuce : collez votre lien M3U complet (…/get.php?username=…&password=…) dans le champ serveur pour tout remplir automatiquement."));
                        ui.add_space(10.0);
                        let field = |ui: &mut Ui, label: &str, value: &mut String, hint: &str, password: bool| -> egui::Response {
                            ui.label(label);
                            ui.add(egui::TextEdit::singleline(value).hint_text(hint).password(password).desired_width(f32::INFINITY).margin(vec2(10.0, 10.0)))
                        };
                        field(ui, "Nom du profil (facultatif)", &mut self.form.name, "Ex. : Salon, Abonnement 2…", false);
                        let before = self.form.server.chars().count();
                        let server = field(ui, "URL du serveur", &mut self.form.server, "http://exemple.com:8080", false);
                        // Seulement sur un collage (plusieurs caractères d'un coup) ou en quittant
                        // le champ : pendant la frappe, le lien serait coupé au premier caractère du mot de passe.
                        let pasted = server.changed() && self.form.server.chars().count() > before + 3;
                        if pasted || server.lost_focus() {
                            // Lien M3U ou adresse de lecture collé : on remplit le compte et on ne
                            // garde jamais le mot de passe dans l'adresse.
                            if let Some(c) = xtream::parse_m3u_link(&self.form.server) {
                                self.form.server = c.server;
                                self.form.username = c.username;
                                self.form.password = c.password;
                            }
                        }
                        if xtream::is_cleartext(&self.form.server) {
                            ui.colored_label(
                                ui::WARNING,
                                "Connexion HTTP non chiffrée : identifiants et vidéos circulent en clair sur le réseau. Utilisez une adresse https:// si votre fournisseur la propose.",
                            );
                        }
                        field(ui, "Nom d'utilisateur", &mut self.form.username, "", false);
                        ui.label("Mot de passe");
                        ui.horizontal(|h| {
                            let w = h.available_width() - h.spacing().interact_size.x - h.spacing().item_spacing.x;
                            let resp = h.add(egui::TextEdit::singleline(&mut self.form.password).password(!self.form.show_password).desired_width(w).margin(vec2(10.0, 10.0)));
                            if resp.lost_focus() && h.input(|i| i.key_pressed(egui::Key::Enter)) {
                                submit = true;
                            }
                            let eye = if self.form.show_password { "🙈" } else { "👁" };
                            if ui::icon_button(h, eye, if self.form.show_password { "Masquer" } else { "Afficher" }).clicked() {
                                self.form.show_password = !self.form.show_password;
                            }
                        });
                        if let Some(e) = &self.login_error {
                            ui.add_space(4.0);
                            ui.colored_label(ui::ERROR, e);
                        }
                        ui.add_space(14.0);
                        ui.horizontal(|h| {
                            let label = if editing.is_some() { "Enregistrer et se connecter" } else { "Se connecter" };
                            if self.login_busy {
                                h.add_enabled(false, egui::Button::new(label));
                                h.spinner();
                            } else if ui::primary_button(h, label).clicked() {
                                submit = true;
                            }
                            if !self.profiles.is_empty() && h.button("Annuler").clicked() {
                                cancel = true;
                            }
                        });
                    });
                });
            });
        });
        if submit {
            self.save_profile();
        }
        if cancel {
            self.cancel_edit();
        }
    }

    // ---------- Accueil / catalogue ----------

    fn home_screen(&mut self, ui: &mut Ui) {
        let mut actions = Vec::new();
        let width = ui.available_width();
        let wide = width >= 860.0;
        let very_wide = width >= 1180.0;

        // Barre du haut : titre, recherche, guide, compte.
        egui::Panel::top("topbar").frame(egui::Frame::NONE.fill(SURFACE).inner_margin(egui::Margin::symmetric(14, 8))).show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(bold("IPTV Player", 20.0).color(PRIMARY));
                if let Some(p) = &self.active {
                    ui.label(dim(format!("· {}", p.name)));
                }
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    let initial = self.active.as_ref().and_then(|p| p.name.chars().next()).unwrap_or('?').to_uppercase().to_string();
                    if avatar_button(ui, &initial, 36.0).on_hover_text("Mon abonnement").clicked() {
                        self.show_account = true;
                    }
                    if self.section == Section::Tab(ContentType::Live) {
                        let (items, title) = self.guide_source();
                        if ui.add_enabled(!items.is_empty(), egui::Button::new("📅 Guide TV")).on_hover_text("Grille des programmes de la catégorie").clicked() {
                            actions.push(Action::OpenGuide(items, title));
                        }
                    }
                    if let Section::Tab(kind) = self.section {
                        let w = if wide { 320.0 } else { (ui.available_width() - 20.0).clamp(120.0, 260.0) };
                        let resp = ui.add(
                            egui::TextEdit::singleline(&mut self.query)
                                .hint_text(format!("🔍 Rechercher : {}", kind.label().to_lowercase()))
                                .desired_width(w)
                                .margin(vec2(10.0, 8.0)),
                        );
                        if ui.input(|i| i.modifiers.command && i.key_pressed(egui::Key::F)) {
                            resp.request_focus();
                        }
                        if resp.changed() {
                            actions.push(Action::QueryChanged);
                        }
                        if !self.query.is_empty() && ui::icon_button(ui, "✕", "Effacer la recherche").clicked() {
                            self.query.clear();
                            actions.push(Action::QueryChanged);
                        }
                    }
                });
            });
        });

        // Navigation : rail à gauche (large) ou onglets (étroit).
        let sections = [
            (Section::Home, "🏠", "Accueil"),
            (Section::Tab(ContentType::Live), "📺", "Direct"),
            (Section::Tab(ContentType::Vod), "🎬", "Films"),
            (Section::Tab(ContentType::Series), "🎞", "Séries"),
        ];
        if wide {
            egui::Panel::left("nav").exact_size(if self.touch { 108.0 } else { 96.0 }).resizable(false).frame(egui::Frame::NONE.fill(SURFACE).inner_margin(egui::Margin::symmetric(8, 12))).show(ui, |ui| {
                ui.vertical_centered(|ui| {
                    for (section, icon, label) in sections {
                        let selected = self.section == section;
                        let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), if self.touch { 80.0 } else { 70.0 }), Sense::click());
                        if selected || resp.hovered() {
                            ui.painter().rect_filled(rect, 12.0, if selected { PRIMARY.gamma_multiply(0.25) } else { SURFACE_HIGH });
                        }
                        let color = if selected { PRIMARY } else { ui::TEXT };
                        ui.painter().text(rect.center() - vec2(0.0, 11.0), Align2::CENTER_CENTER, icon, FontId::proportional(24.0), color);
                        ui.painter().text(rect.center() + vec2(0.0, 18.0), Align2::CENTER_CENTER, label, FontId::proportional(13.5), color);
                        if resp.clicked() {
                            actions.push(match section {
                                Section::Home => Action::SelectHome,
                                Section::Tab(t) => Action::SelectTab(t),
                            });
                        }
                    }
                });
            });
        } else {
            egui::Panel::top("tabs").frame(egui::Frame::NONE.fill(SURFACE).inner_margin(egui::Margin::symmetric(8, 6))).show(ui, |ui| {
                ui.columns(4, |cols| {
                    for (i, (section, icon, label)) in sections.into_iter().enumerate() {
                        let selected = self.section == section;
                        let text = RichText::new(format!("{icon} {label}")).color(if selected { PRIMARY } else { ui::TEXT });
                        let btn = egui::Button::new(text).fill(if selected { PRIMARY.gamma_multiply(0.2) } else { Color32::TRANSPARENT });
                        if cols[i].add_sized([cols[i].available_width(), 40.0], btn).clicked() {
                            actions.push(match section {
                                Section::Home => Action::SelectHome,
                                Section::Tab(t) => Action::SelectTab(t),
                            });
                        }
                    }
                });
            });
        }

        match self.section {
            Section::Home => {
                egui::CentralPanel::default().show(ui, |ui| self.home_tab(ui, &mut actions));
            }
            Section::Tab(kind) => {
                let searching = !self.query.trim().is_empty();
                if !searching {
                    if very_wide {
                        egui::Panel::left("categories").default_size(250.0).resizable(true).frame(egui::Frame::NONE.fill(ui::BACKGROUND).inner_margin(egui::Margin::symmetric(8, 10))).show(ui, |ui| {
                            self.category_list(ui, kind, &mut actions);
                        });
                    } else {
                        egui::Panel::top("chips").frame(egui::Frame::NONE.inner_margin(egui::Margin::symmetric(10, 6))).show(ui, |ui| {
                            self.category_chips(ui, kind, &mut actions);
                        });
                    }
                }
                egui::CentralPanel::default().show(ui, |ui| self.tab_content(ui, kind, &mut actions));
            }
        }
        self.apply(actions);
    }

    /// Chaînes affichées (catégorie en cours) pour le guide.
    fn guide_source(&self) -> (Arc<Vec<MediaEntry>>, String) {
        let Some(tab) = self.tabs.get(&ContentType::Live) else { return (Arc::new(Vec::new()), String::new()) };
        let sel = tab.selected.clone().unwrap_or_default();
        let title = category_name(tab, &sel);
        let items = match sel.as_str() {
            CATEGORY_RECENT => Arc::new(self.recent_entries()),
            CATEGORY_FAV => Arc::new(tab.items.iter().filter(|e| self.is_favorite(e)).cloned().collect()),
            _ => tab.items.clone(),
        };
        (items, title)
    }

    fn special_categories(&self, kind: ContentType) -> Vec<(String, String)> {
        let tab = self.tabs.get(&kind);
        let selected = tab.and_then(|t| t.selected.clone()).unwrap_or_default();
        let mut out = Vec::new();
        if kind == ContentType::Live && (!self.data.recent.is_empty() || selected == CATEGORY_RECENT) {
            out.push((CATEGORY_RECENT.to_string(), "🕘 Récents".to_string()));
        }
        if !self.data.favorites.get(kind).is_empty() || selected == CATEGORY_FAV {
            out.push((CATEGORY_FAV.to_string(), "★ Favoris".to_string()));
        }
        out.push((CATEGORY_ALL.to_string(), "Tout".to_string()));
        if let Some(t) = tab {
            out.extend(t.categories.iter().map(|c| (c.id.clone(), c.name.clone())));
        }
        out
    }

    fn category_list(&mut self, ui: &mut Ui, kind: ContentType, actions: &mut Vec<Action>) {
        let selected = self.tabs.get(&kind).and_then(|t| t.selected.clone());
        let cats = self.special_categories(kind);
        ui.label(bold("Catégories", 16.0));
        ui.add_space(4.0);
        let row_h = if self.touch { 48.0 } else { 36.0 };
        ScrollArea::vertical().auto_shrink(false).id_salt(("cats", kind)).show_rows(ui, row_h, cats.len(), |ui, range| {
            for (id, name) in &cats[range] {
                let is_sel = selected.as_deref() == Some(id.as_str());
                let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), row_h - 4.0), Sense::click());
                if is_sel || resp.hovered() {
                    ui.painter().rect_filled(rect, 8.0, if is_sel { PRIMARY.gamma_multiply(0.3) } else { SURFACE_VARIANT });
                }
                let galley = ui.painter().layout(name.clone(), FontId::proportional(14.5), if is_sel { Color32::WHITE } else { ui::TEXT }, rect.width() - 20.0);
                let text_pos = pos2(rect.left() + 10.0, rect.center().y - galley.size().y.min(row_h) / 2.0);
                ui.painter().with_clip_rect(rect).galley(text_pos, galley, ui::TEXT);
                if resp.on_hover_text(name).clicked() {
                    actions.push(Action::SelectCategory(kind, id.clone()));
                }
            }
        });
    }

    fn category_chips(&mut self, ui: &mut Ui, kind: ContentType, actions: &mut Vec<Action>) {
        let selected = self.tabs.get(&kind).and_then(|t| t.selected.clone());
        let cats = self.special_categories(kind);
        ScrollArea::horizontal().id_salt(("chips", kind)).scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden).show(ui, |ui| {
            ui.horizontal(|ui| {
                for (id, name) in &cats {
                    let is_sel = selected.as_deref() == Some(id.as_str());
                    let btn = egui::Button::new(RichText::new(name).color(if is_sel { ui::ON_PRIMARY } else { ui::TEXT }))
                        .fill(if is_sel { PRIMARY } else { SURFACE_VARIANT })
                        .corner_radius(18.0);
                    if ui.add(btn).clicked() {
                        actions.push(Action::SelectCategory(kind, id.clone()));
                    }
                }
            });
        });
    }

    fn tab_content(&mut self, ui: &mut Ui, kind: ContentType, actions: &mut Vec<Action>) {
        let searching = !self.query.trim().is_empty();
        let tab_error = self.tabs.get(&kind).and_then(|t| t.error.clone());
        let (items, loading, title): (Arc<Vec<MediaEntry>>, bool, String) = if searching {
            match &self.search_results {
                Some((k, _, r)) if *k == kind => (r.clone(), self.searching || self.search_due.is_some(), "Recherche".into()),
                _ => (Arc::new(Vec::new()), true, "Recherche".into()),
            }
        } else {
            let Some(tab) = self.tabs.get(&kind) else {
                ui.centered_and_justified(|ui| ui.spinner());
                return;
            };
            let sel = tab.selected.clone().unwrap_or_default();
            let items = match sel.as_str() {
                CATEGORY_RECENT => Arc::new(self.recent_entries()),
                CATEGORY_FAV => Arc::new(tab.items.iter().filter(|e| self.is_favorite(e)).cloned().collect()),
                _ => tab.items.clone(),
            };
            (items, tab.loading, category_name(tab, &sel))
        };

        ui.horizontal(|h| {
            h.label(bold(&title, 20.0));
            if !loading {
                h.label(dim(format!("{} éléments", items.len())));
            }
        });
        ui.add_space(4.0);

        if let Some(e) = tab_error.filter(|_| items.is_empty()) {
            error_box(ui, &e, || actions.push(Action::Retry(kind)));
            return;
        }
        if loading && items.is_empty() {
            ui.centered_and_justified(|ui| ui.spinner());
            return;
        }
        if items.is_empty() {
            let sel = self.tabs.get(&kind).and_then(|t| t.selected.clone()).unwrap_or_default();
            let msg = if searching {
                "Aucun résultat".to_string()
            } else if sel == CATEGORY_FAV {
                "Aucun favori.\nClic droit ou appui long sur un élément (ou son étoile) pour l'ajouter.".into()
            } else {
                "Aucun contenu dans cette catégorie".into()
            };
            ui.centered_and_justified(|ui| ui.label(dim(msg)));
            return;
        }
        match kind {
            ContentType::Live => self.channel_list(ui, items, actions),
            _ => self.poster_grid(ui, items, actions),
        }
    }

    fn channel_list(&mut self, ui: &mut Ui, items: Arc<Vec<MediaEntry>>, actions: &mut Vec<Action>) {
        let row_h = if self.touch { 78.0 } else { 68.0 };
        let now = now_ms();
        let rt = self.rt.handle().clone();
        let client = self.client.clone();
        let ctx = ui.ctx().clone();
        ScrollArea::vertical().auto_shrink(false).id_salt("channels").show_rows(ui, row_h, items.len(), |ui, range| {
            for i in range {
                let e = &items[i];
                let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), row_h - 6.0), Sense::click());
                if resp.hovered() {
                    ui.painter().rect_filled(rect, 10.0, SURFACE_VARIANT);
                }
                let logo = Rect::from_min_size(rect.min + vec2(8.0, 6.0), vec2(96.0, rect.height() - 12.0));
                ui::paint_image(ui, e.image.as_deref(), logo, 8.0, true, "📺");
                let text_left = logo.right() + 14.0;
                let star_w = 48.0;
                let text_w = rect.right() - star_w - text_left - 8.0;
                let p = ui.painter().with_clip_rect(Rect::from_min_max(pos2(text_left, rect.top()), pos2(text_left + text_w, rect.bottom())));
                p.text(pos2(text_left, rect.top() + 8.0), Align2::LEFT_TOP, &e.name, FontId::proportional(16.0), ui::TEXT);
                if let Some(c) = &client {
                    let st = self.epg.get(&rt, c, &ctx, &e.id, e.epg_id.is_some());
                    epg_lines(&p, st.programs.as_deref().map(|v| v.as_slice()), now, pos2(text_left, rect.top() + 30.0), text_w);
                }
                // Étoile : bouton à part, toujours visible.
                let fav = self.is_favorite(e);
                let star_rect = Rect::from_center_size(pos2(rect.right() - star_w / 2.0, rect.center().y), Vec2::splat(44.0));
                let star = ui.interact(star_rect, ui.scope_id().with(("star", i)), Sense::click());
                ui.painter().text(star_rect.center(), Align2::CENTER_CENTER, if fav { "★" } else { "☆" }, FontId::proportional(24.0), if fav { STAR } else { TEXT_DIM });
                if star.on_hover_text(if fav { "Retirer des favoris" } else { "Ajouter aux favoris" }).clicked() {
                    actions.push(Action::ToggleFavorite(e.clone()));
                } else if resp.clicked() {
                    actions.push(Action::PlayLive(items.clone(), i));
                } else if resp.long_touched() {
                    actions.push(Action::ToggleFavorite(e.clone()));
                }
                resp.context_menu(|m| {
                    if m.button("▶ Regarder").clicked() {
                        actions.push(Action::PlayLive(items.clone(), i));
                    }
                    if m.button(if fav { "Retirer des favoris" } else { "Ajouter aux favoris" }).clicked() {
                        actions.push(Action::ToggleFavorite(e.clone()));
                    }
                });
            }
        });
    }

    fn poster_grid(&mut self, ui: &mut Ui, items: Arc<Vec<MediaEntry>>, actions: &mut Vec<Action>) {
        let card_w: f32 = if self.touch { 170.0 } else { 150.0 };
        let gap = 16.0;
        let avail = ui.available_width() - 14.0;
        let cols = (((avail + gap) / (card_w + gap)).floor() as usize).max(2);
        let card_w = (avail - gap * (cols - 1) as f32) / cols as f32;
        let poster_h = card_w * 1.5;
        let row_h = poster_h + 54.0;
        let rows = items.len().div_ceil(cols);
        ScrollArea::vertical().auto_shrink(false).id_salt(("grid", items.len())).show_rows(ui, row_h, rows, |ui, range| {
            for r in range {
                ui.horizontal(|row| {
                    row.spacing_mut().item_spacing.x = gap;
                    for c in 0..cols {
                        let i = r * cols + c;
                        let Some(e) = items.get(i) else { break };
                        let (rect, resp) = ui::card(row, vec2(card_w, row_h - 10.0));
                        let poster = Rect::from_min_size(rect.min, vec2(card_w, poster_h));
                        ui::paint_image(row, e.image.as_deref(), poster, 10.0, false, if e.kind == ContentType::Vod { "🎬" } else { "🎞" });
                        let p = row.painter();
                        let fav = self.is_favorite(e);
                        if fav {
                            p.circle_filled(poster.right_top() + vec2(-18.0, 18.0), 14.0, Color32::from_black_alpha(150));
                            p.text(poster.right_top() + vec2(-18.0, 18.0), Align2::CENTER_CENTER, "★", FontId::proportional(18.0), STAR);
                        }
                        if let Some(rating) = e.rating.as_deref().filter(|r| !r.is_empty() && *r != "0") {
                            let t = format!("★ {}", rating.chars().take(3).collect::<String>());
                            let g = p.layout_no_wrap(t, FontId::proportional(12.0), Color32::WHITE);
                            let br = Rect::from_min_size(poster.left_top() + vec2(6.0, 6.0), g.size() + vec2(10.0, 4.0));
                            p.rect_filled(br, 6.0, Color32::from_black_alpha(160));
                            p.galley(br.min + vec2(5.0, 2.0), g, Color32::WHITE);
                        }
                        if e.kind == ContentType::Vod
                            && let Some(pr) = self.data.progress_for(&format!("vod:{}", e.id)) {
                                ui::thin_progress(row, Rect::from_min_size(pos2(poster.left() + 6.0, poster.bottom() - 9.0), vec2(card_w - 12.0, 4.0)), pr.position as f32 / pr.duration as f32);
                            }
                        let galley = p.layout(e.name.clone(), FontId::proportional(14.0), ui::TEXT, card_w - 4.0);
                        p.with_clip_rect(Rect::from_min_size(pos2(rect.left(), poster.bottom() + 4.0), vec2(card_w, 40.0))).galley(pos2(rect.left() + 2.0, poster.bottom() + 6.0), galley, ui::TEXT);
                        if resp.clicked() {
                            actions.push(Action::OpenItem(e.clone()));
                        } else if resp.long_touched() {
                            actions.push(Action::ToggleFavorite(e.clone()));
                        }
                        resp.on_hover_text(&e.name).context_menu(|m| {
                            if m.button("Ouvrir").clicked() {
                                actions.push(Action::OpenItem(e.clone()));
                            }
                            if m.button(if fav { "Retirer des favoris" } else { "Ajouter aux favoris" }).clicked() {
                                actions.push(Action::ToggleFavorite(e.clone()));
                            }
                        });
                    }
                });
            }
        });
    }

    /// Onglet Accueil : reprise de lecture et chaînes récentes.
    fn home_tab(&mut self, ui: &mut Ui, actions: &mut Vec<Action>) {
        let progress = self.data.progress.clone();
        let recents = Arc::new(self.recent_entries());
        if progress.is_empty() && recents.is_empty() {
            ui.centered_and_justified(|ui| {
                ui.label(dim("Bienvenue !\n\nLes films et épisodes commencés apparaîtront ici,\nainsi que vos dernières chaînes regardées."));
            });
            return;
        }
        let now = now_ms();
        ScrollArea::vertical().auto_shrink(false).id_salt("home").show(ui, |ui| {
            if !progress.is_empty() {
                ui.horizontal(|h| {
                    h.label(bold("Continuer à regarder", 20.0));
                    if self.resuming {
                        h.spinner();
                    }
                });
                ui.label(dim("Clic droit, appui long ou ✕ pour retirer de la liste"));
                ui.add_space(6.0);
                let card_w = if self.touch { 160.0 } else { 140.0 };
                ScrollArea::horizontal().id_salt("continue").show(ui, |ui| {
                    ui.horizontal(|row| {
                        for p in &progress {
                            let (rect, resp) = ui::card(row, vec2(card_w, card_w * 1.5 + 50.0));
                            let poster = Rect::from_min_size(rect.min, vec2(card_w, card_w * 1.5));
                            ui::paint_image(row, p.image.as_deref(), poster, 10.0, false, "");
                            let painter = row.painter();
                            painter.circle_filled(poster.center(), 26.0, Color32::from_black_alpha(130));
                            painter.text(poster.center() + vec2(2.0, 0.0), Align2::CENTER_CENTER, "▶", FontId::proportional(26.0), Color32::WHITE);
                            ui::thin_progress(row, Rect::from_min_size(pos2(poster.left() + 6.0, poster.bottom() - 10.0), vec2(card_w - 12.0, 4.0)), p.position as f32 / p.duration as f32);
                            let painter = row.painter().with_clip_rect(rect);
                            painter.text(pos2(rect.left() + 2.0, poster.bottom() + 6.0), Align2::LEFT_TOP, &p.title, FontId::proportional(14.0), ui::TEXT);
                            let sub = p.subtitle.clone().unwrap_or_else(|| format!("Reste {}", format_duration(p.duration - p.position)));
                            painter.text(pos2(rect.left() + 2.0, poster.bottom() + 26.0), Align2::LEFT_TOP, sub, FontId::proportional(12.5), TEXT_DIM);
                            // Bouton de retrait (utile au doigt comme à la souris).
                            let x_rect = Rect::from_center_size(poster.right_top() + vec2(-18.0, 18.0), Vec2::splat(32.0));
                            let x = row.interact(x_rect, row.scope_id().with(("rm", &p.key)), Sense::click());
                            if resp.hovered() || x.hovered() || self.touch {
                                row.painter().circle_filled(x_rect.center(), 14.0, Color32::from_black_alpha(170));
                                row.painter().text(x_rect.center(), Align2::CENTER_CENTER, "✕", FontId::proportional(14.0), Color32::WHITE);
                            }
                            if x.on_hover_text("Retirer de la liste").clicked() {
                                actions.push(Action::RemoveProgress(p.key.clone()));
                            } else if resp.clicked() {
                                actions.push(Action::Resume(p.clone()));
                            } else if resp.long_touched() {
                                actions.push(Action::RemoveProgress(p.key.clone()));
                            }
                            resp.context_menu(|m| {
                                if m.button("▶ Reprendre").clicked() {
                                    actions.push(Action::Resume(p.clone()));
                                }
                                if m.button("Retirer de la liste").clicked() {
                                    actions.push(Action::RemoveProgress(p.key.clone()));
                                }
                            });
                        }
                    });
                });
                ui.add_space(16.0);
            }
            if !recents.is_empty() {
                ui.label(bold("Chaînes récentes", 20.0));
                ui.add_space(6.0);
                let rt = self.rt.handle().clone();
                let client = self.client.clone();
                let ctx = ui.ctx().clone();
                for (i, e) in recents.iter().take(12).enumerate() {
                    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), if self.touch { 72.0 } else { 62.0 }), Sense::click());
                    if resp.hovered() {
                        ui.painter().rect_filled(rect, 10.0, SURFACE_VARIANT);
                    }
                    let logo = Rect::from_min_size(rect.min + vec2(8.0, 6.0), vec2(84.0, rect.height() - 12.0));
                    ui::paint_image(ui, e.image.as_deref(), logo, 8.0, true, "📺");
                    let left = logo.right() + 14.0;
                    let w = rect.right() - left - 10.0;
                    let p = ui.painter().with_clip_rect(rect);
                    p.text(pos2(left, rect.top() + 7.0), Align2::LEFT_TOP, &e.name, FontId::proportional(16.0), ui::TEXT);
                    if let Some(c) = &client {
                        let st = self.epg.get(&rt, c, &ctx, &e.id, e.epg_id.is_some());
                        epg_lines(&p, st.programs.as_deref().map(|v| v.as_slice()), now, pos2(left, rect.top() + 29.0), w);
                    }
                    if resp.clicked() {
                        actions.push(Action::PlayLive(recents.clone(), i));
                    }
                }
            }
        });
    }

    // ---------- Fiche film ----------

    fn movie_screen(&mut self, ui: &mut Ui, entry: &MediaEntry) {
        let mut actions = Vec::new();
        let mut play: Option<i64> = None;
        let detail = self.movie_detail.clone();
        let fav = self.is_favorite(entry);
        back_bar(ui, &detail.as_ref().map(|d| d.name.clone()).filter(|n| !n.is_empty()).unwrap_or(entry.name.clone()), || self.back_home(), |ui| {
            if ui::icon_button(ui, if fav { "★" } else { "☆" }, if fav { "Retirer des favoris" } else { "Ajouter aux favoris" }).clicked() {
                actions.push(Action::ToggleFavorite(entry.clone()));
            }
        });
        egui::CentralPanel::default().show(ui, |ui| {
            ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
                let wide = ui.available_width() > 760.0;
                let backdrop = detail.as_ref().and_then(|d| d.backdrop.clone());
                if let Some(b) = backdrop.as_deref() {
                    let h = (ui.available_width() * 0.32).clamp(140.0, 340.0);
                    let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), h), Sense::hover());
                    ui::paint_image(ui, Some(b), rect, 12.0, false, "");
                }
                let poster = detail.as_ref().and_then(|d| d.poster.clone()).or(entry.image.clone());
                let mut body = |ui: &mut Ui| {
                    let meta: Vec<String> = [
                        detail.as_ref().and_then(|d| d.release_date.clone()).map(|d| d.chars().take(4).collect()),
                        detail.as_ref().and_then(|d| d.duration.clone()),
                        detail.as_ref().and_then(|d| d.genre.clone()),
                        detail.as_ref().and_then(|d| d.rating.clone()).or(entry.rating.clone()).filter(|r| r != "0" && !r.is_empty()).map(|r| format!("★ {r}")),
                    ]
                    .into_iter()
                    .flatten()
                    .collect();
                    ui.label(bold(detail.as_ref().map(|d| d.name.clone()).filter(|n| !n.is_empty()).unwrap_or(entry.name.clone()), 26.0));
                    if !meta.is_empty() {
                        ui.label(dim(meta.join(" · ")));
                    }
                    ui.add_space(10.0);
                    ui.horizontal_wrapped(|h| {
                        let saved = self.data.progress_for(&format!("vod:{}", entry.id)).cloned();
                        if let Some(p) = &saved {
                            if ui::primary_button(h, &format!("▶  Reprendre à {}", format_duration(p.position))).clicked() {
                                play = Some(p.position);
                            }
                            if h.button("⟲  Depuis le début").clicked() {
                                play = Some(-1);
                            }
                        } else if ui::primary_button(h, "▶  Lecture").clicked() {
                            play = Some(-1);
                        }
                        if let Some(id) = detail.as_ref().and_then(|d| d.trailer_youtube_id.clone()).filter(|s| !s.is_empty())
                            && h.button("🎞  Bande-annonce").clicked() {
                                h.ctx().open_url(egui::OpenUrl::new_tab(format!("https://www.youtube.com/watch?v={id}")));
                            }
                    });
                    ui.add_space(12.0);
                    if self.tasks.is_running("movie") && detail.is_none() {
                        ui.spinner();
                    }
                    if let Some(d) = &detail {
                        if let Some(plot) = &d.plot {
                            ui.label(RichText::new(plot).size(15.5));
                            ui.add_space(10.0);
                        }
                        if let Some(v) = &d.director {
                            info_block(ui, "Réalisation", v);
                        }
                        if let Some(v) = &d.cast {
                            info_block(ui, "Distribution", v);
                        }
                    }
                    if let Some(e) = &self.movie_error {
                        ui.colored_label(ui::WARNING, format!("Détails indisponibles ({e}). La lecture reste possible."));
                    }
                };
                ui.add_space(12.0);
                if wide {
                    ui.horizontal_top(|h| {
                        let (rect, _) = h.allocate_exact_size(vec2(220.0, 330.0), Sense::hover());
                        ui::paint_image(h, poster.as_deref(), rect, 12.0, false, "🎬");
                        h.add_space(20.0);
                        h.vertical(|v| body(v));
                    });
                } else {
                    body(ui);
                }
            });
        });
        if let Some(start) = play {
            let ext = detail.and_then(|d| d.extension);
            self.play_vod(entry, ext, start);
        }
        self.apply(actions);
    }

    // ---------- Fiche série ----------

    fn series_screen(&mut self, ui: &mut Ui, entry: &MediaEntry) {
        let mut actions = Vec::new();
        let detail = self.series_detail.clone();
        let fav = self.is_favorite(entry);
        let mut play: Option<(Vec<crate::xtream::Episode>, usize, i64)> = None;
        back_bar(ui, &entry.name, || self.back_home(), |ui| {
            if ui::icon_button(ui, if fav { "★" } else { "☆" }, if fav { "Retirer des favoris" } else { "Ajouter aux favoris" }).clicked() {
                actions.push(Action::ToggleFavorite(entry.clone()));
            }
        });
        egui::CentralPanel::default().show(ui, |ui| {
            let Some(d) = detail.clone() else {
                if let Some(e) = self.series_error.clone() {
                    error_box(ui, &e, || actions.push(Action::OpenItem(entry.clone())));
                } else {
                    ui.centered_and_justified(|ui| ui.spinner());
                }
                return;
            };
            ScrollArea::vertical().auto_shrink(false).show(ui, |ui| {
                ui.horizontal_top(|h| {
                    let w = if h.available_width() > 700.0 { 180.0 } else { 110.0 };
                    let (rect, _) = h.allocate_exact_size(vec2(w, w * 1.5), Sense::hover());
                    ui::paint_image(h, d.cover.as_deref().or(entry.image.as_deref()), rect, 12.0, false, "🎞");
                    h.add_space(16.0);
                    h.vertical(|v| {
                        v.label(bold(if d.name.is_empty() { &entry.name } else { &d.name }, 26.0));
                        let meta: Vec<String> = [d.release_date.clone().map(|r| r.chars().take(4).collect()), d.genre.clone(), d.rating.clone().filter(|r| r != "0").map(|r| format!("★ {r}"))]
                            .into_iter()
                            .flatten()
                            .collect();
                        if !meta.is_empty() {
                            v.label(dim(meta.join(" · ")));
                        }
                        if let Some(plot) = d.plot.as_ref().or(entry.plot.as_ref()) {
                            v.add_space(8.0);
                            v.label(RichText::new(plot).size(15.0));
                        }
                    });
                });
                ui.add_space(14.0);
                if d.seasons.is_empty() {
                    ui.label(dim("Aucun épisode disponible"));
                    return;
                }
                // Onglets des saisons.
                ScrollArea::horizontal().id_salt("seasons").show(ui, |ui| {
                    ui.horizontal(|h| {
                        for (s, _) in &d.seasons {
                            let sel = self.series_season == Some(*s);
                            let btn = egui::Button::new(RichText::new(format!("Saison {s}")).color(if sel { ui::ON_PRIMARY } else { ui::TEXT }))
                                .fill(if sel { PRIMARY } else { SURFACE_VARIANT })
                                .corner_radius(18.0);
                            if h.add(btn).clicked() {
                                self.series_season = Some(*s);
                            }
                        }
                    });
                });
                ui.add_space(8.0);
                let season = self.series_season.unwrap_or(d.seasons[0].0);
                let Some(episodes) = d.season(season).cloned() else { return };
                for (i, ep) in episodes.iter().enumerate() {
                    let saved = self.data.progress_for(&format!("ep:{}", ep.id)).cloned();
                    let h = if self.touch { 110.0 } else { 96.0 };
                    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), h), Sense::click());
                    if resp.hovered() {
                        ui.painter().rect_filled(rect, 10.0, SURFACE_VARIANT);
                    }
                    let thumb = Rect::from_min_size(rect.min + vec2(6.0, 6.0), vec2((h - 12.0) * 16.0 / 9.0, h - 12.0));
                    ui::paint_image(ui, ep.image.as_deref().or(d.cover.as_deref()), thumb, 8.0, false, "▶");
                    if let Some(p) = &saved {
                        ui::thin_progress(ui, Rect::from_min_size(pos2(thumb.left() + 4.0, thumb.bottom() - 8.0), vec2(thumb.width() - 8.0, 4.0)), p.position as f32 / p.duration as f32);
                    }
                    let left = thumb.right() + 14.0;
                    let w = rect.right() - left - 10.0;
                    let p = ui.painter().with_clip_rect(rect);
                    p.text(pos2(left, rect.top() + 8.0), Align2::LEFT_TOP, format!("{}. {}", ep.number, ep.title), FontId::proportional(16.0), ui::TEXT);
                    let mut y = rect.top() + 30.0;
                    let info = match (&saved, &ep.duration) {
                        (Some(s), _) => Some(format!("Reprendre à {}", format_duration(s.position))),
                        (None, Some(d)) => Some(d.clone()),
                        _ => None,
                    };
                    if let Some(info) = info {
                        p.text(pos2(left, y), Align2::LEFT_TOP, info, FontId::proportional(13.0), if saved.is_some() { PRIMARY } else { TEXT_DIM });
                        y += 20.0;
                    }
                    if let Some(plot) = &ep.plot {
                        let g = p.layout(plot.clone(), FontId::proportional(13.0), TEXT_DIM, w);
                        p.with_clip_rect(Rect::from_min_max(pos2(left, y), pos2(rect.right(), rect.bottom() - 4.0))).galley(pos2(left, y), g, TEXT_DIM);
                    }
                    if resp.clicked() {
                        play = Some((episodes.clone(), i, saved.map(|s| s.position).unwrap_or(-1)));
                    }
                }
            });
        });
        if let Some((eps, i, pos)) = play {
            let cover = detail.and_then(|d| d.cover).or(entry.image.clone());
            self.play_episodes(Some(entry.id.clone()), &entry.name, cover, &eps, i, pos);
        }
        self.apply(actions);
    }

    // ---------- Guide TV ----------

    fn guide_screen(&mut self, ui: &mut Ui) {
        const PX_PER_MIN: f32 = 5.0;
        const HOURS: i64 = 8;
        let now = now_ms();
        // Fenêtre : de la demi-heure précédente à +8 h, décalée toutes les 30 min.
        let half = 30 * 60_000;
        let window_start = (now / half) * half - half;
        if self.guide_window_start != window_start {
            if self.guide_window_start != 0 && !self.guide_offset.is_nan() {
                self.guide_offset -= ((window_start - self.guide_window_start) / 60_000) as f32 * PX_PER_MIN;
            }
            self.guide_window_start = window_start;
        }
        let window_end = window_start + (HOURS * 60 + 30) * 60_000;
        let timeline_w = ((window_end - window_start) / 60_000) as f32 * PX_PER_MIN;
        let x_of = |t: i64| ((t - window_start) as f32 / 60_000.0) * PX_PER_MIN;

        let mut go_now = false;
        let title = format!("Guide TV · {}", self.guide_title);
        let mut shift = 0.0;
        back_bar(ui, &title, || self.back_home(), |ui| {
            if ui.button("▶|").on_hover_text("1 heure plus tard").clicked() {
                shift += 60.0 * PX_PER_MIN;
            }
            if ui.button("Maintenant").clicked() {
                go_now = true;
            }
            if ui.button("|◀").on_hover_text("1 heure plus tôt").clicked() {
                shift -= 60.0 * PX_PER_MIN;
            }
        });

        let channels = self.guide_channels.clone();
        let rt = self.rt.handle().clone();
        let client = self.client.clone();
        let mut play: Option<usize> = None;
        egui::CentralPanel::default().show(ui, |ui| {
            let channel_w = if ui.available_width() > 700.0 { 220.0 } else { 140.0 };
            let view_w = (ui.available_width() - channel_w).max(100.0);
            let max_offset = (timeline_w - view_w).max(0.0);
            if self.guide_offset.is_nan() || go_now {
                self.guide_offset = (x_of(now) - 60.0).max(0.0);
            }
            self.guide_offset = (self.guide_offset + shift).clamp(0.0, max_offset);
            let offset = self.guide_offset;
            let ctx = ui.ctx().clone();

            // En-tête des heures.
            let (header, _) = ui.allocate_exact_size(vec2(ui.available_width(), 30.0), Sense::hover());
            let p = ui.painter_at(Rect::from_min_max(pos2(header.left() + channel_w, header.top()), header.max));
            let mut t = window_start;
            while t <= window_end {
                let x = header.left() + channel_w + x_of(t) - offset;
                p.text(pos2(x + 4.0, header.center().y), Align2::LEFT_CENTER, format_time(t), FontId::proportional(13.0), TEXT_DIM);
                p.line_segment([pos2(x, header.bottom() - 8.0), pos2(x, header.bottom())], egui::Stroke::new(1.0, TEXT_DIM));
                t += half;
            }

            let row_h = if self.touch { 72.0 } else { 62.0 };
            // Glisser horizontalement (doigt, souris) ou molette avec Maj pour parcourir les heures.
            let grid_rect = Rect::from_min_max(pos2(ui.max_rect().left() + channel_w, ui.cursor().top()), ui.max_rect().max);
            let drag = ui.interact(grid_rect, ui.scope_id().with("guide-drag"), Sense::drag());
            let scroll_x = ui.input(|i| if i.modifiers.shift { i.smooth_scroll_delta.y + i.smooth_scroll_delta.x } else { i.smooth_scroll_delta.x });
            if drag.dragged() {
                self.guide_offset = (self.guide_offset - drag.drag_delta().x).clamp(0.0, max_offset);
            }
            if scroll_x != 0.0 && ui.rect_contains_pointer(grid_rect) {
                self.guide_offset = (self.guide_offset - scroll_x).clamp(0.0, max_offset);
            }

            let selected = &mut self.guide_selected;
            let epg = &self.epg;
            ScrollArea::vertical()
                .auto_shrink(false)
                .id_salt("guide")
                .scroll_source(egui::scroll_area::ScrollSource { drag: egui::scroll_area::DragScroll::Never, ..Default::default() })
                .show_rows(ui, row_h, channels.len(), |ui, range| {
                    for i in range {
                        let ch = &channels[i];
                        let (row, _) = ui.allocate_exact_size(vec2(ui.available_width(), row_h - 4.0), Sense::hover());
                        // Cellule chaîne (clic = regarder).
                        let cell = Rect::from_min_size(row.min, vec2(channel_w - 6.0, row.height()));
                        let cell_resp = ui.interact(cell, ui.scope_id().with(("ch", i)), Sense::click());
                        ui.painter().rect_filled(cell, 8.0, if cell_resp.hovered() { SURFACE_HIGH } else { SURFACE });
                        let logo = Rect::from_min_size(cell.min + vec2(6.0, 6.0), vec2(56.0, cell.height() - 12.0));
                        ui::paint_image(ui, ch.image.as_deref(), logo, 6.0, true, "");
                        let tp = ui.painter().with_clip_rect(cell.shrink(2.0));
                        let g = tp.layout(ch.name.clone(), FontId::proportional(13.5), ui::TEXT, cell.right() - logo.right() - 14.0);
                        tp.galley(pos2(logo.right() + 8.0, cell.center().y - g.size().y.min(cell.height()) / 2.0), g, ui::TEXT);
                        if cell_resp.on_hover_text(format!("Regarder {}", ch.name)).clicked() {
                            play = Some(i);
                        }

                        // Programmes.
                        let line = Rect::from_min_max(pos2(row.left() + channel_w, row.top()), row.max);
                        let painter = ui.painter_at(line);
                        let state = client.as_ref().map(|c| epg.get(&rt, c, &ctx, &ch.id, ch.epg_id.is_some()));
                        let programs = state.as_ref().and_then(|s| s.programs.clone());
                        let failed = state.as_ref().is_some_and(|s| s.failed);
                        let hint = |text: &str| {
                            painter.text(pos2(line.left() + 12.0, line.center().y), Align2::LEFT_CENTER, text, FontId::proportional(13.0), TEXT_DIM);
                        };
                        match programs.as_deref() {
                            None => hint("Chargement…"),
                            Some(list) if list.is_empty() && failed => hint("Guide momentanément indisponible (nouvel essai automatique)"),
                            Some(list) if list.is_empty() => hint("Programmes non fournis par le serveur"),
                            Some(list) => {
                                let visible: Vec<_> = list.iter().filter(|p| p.end > window_start && p.start < window_end).collect();
                                if visible.is_empty() {
                                    hint("Pas de programme sur cette période");
                                }
                                for prog in visible {
                                    let x0 = line.left() + x_of(prog.start.max(window_start)) - offset;
                                    let x1 = line.left() + x_of(prog.end.min(window_end)) - offset;
                                    if x1 < line.left() || x0 > line.right() {
                                        continue;
                                    }
                                    let r = Rect::from_min_max(pos2(x0 + 1.5, line.top()), pos2(x1 - 1.5, line.bottom()));
                                    let on_air = prog.start <= now && now < prog.end;
                                    let resp = ui.interact(r.intersect(line), ui.scope_id().with(("p", i, prog.start)), Sense::click());
                                    let fill = if on_air { PRIMARY.gamma_multiply(0.28) } else if resp.hovered() { SURFACE_HIGH } else { SURFACE_VARIANT };
                                    painter.rect_filled(r, 6.0, fill);
                                    let text_x = r.left().max(line.left()) + 8.0;
                                    let tp = painter.with_clip_rect(r.shrink(2.0).intersect(line));
                                    tp.text(pos2(text_x, r.top() + 8.0), Align2::LEFT_TOP, &prog.title, FontId::proportional(14.0), ui::TEXT);
                                    tp.text(pos2(text_x, r.top() + 30.0), Align2::LEFT_TOP, format!("{} – {}", format_time(prog.start), format_time(prog.end)), FontId::proportional(12.0), TEXT_DIM);
                                    if resp.clicked() {
                                        *selected = Some((i, prog.clone()));
                                    }
                                }
                            }
                        }
                        // Ligne « maintenant ».
                        let nx = line.left() + x_of(now) - offset;
                        if nx > line.left() && nx < line.right() {
                            painter.line_segment([pos2(nx, line.top()), pos2(nx, line.bottom())], egui::Stroke::new(2.0, Color32::from_rgb(0xFF, 0x52, 0x52)));
                        }
                    }
                });
        });

        if let Some((i, prog)) = self.guide_selected.clone() {
            let name = channels.get(i).map(|c| c.name.clone()).unwrap_or_default();
            let mut close = false;
            egui::Modal::new(egui::Id::unique("program")).show(ui.ctx(), |ui| {
                ui.set_width(460.0_f32.min(ui.ctx().content_rect().width() - 60.0));
                ui.label(bold(&prog.title, 20.0));
                ui.label(dim(format!("{name} · {} – {}", format_time(prog.start), format_time(prog.end))));
                ui.add_space(8.0);
                ScrollArea::vertical().max_height(300.0).show(ui, |ui| {
                    ui.label(prog.description.clone().unwrap_or_else(|| "Pas de description.".into()));
                });
                ui.add_space(10.0);
                ui.horizontal(|h| {
                    if ui::primary_button(h, "▶  Regarder la chaîne").clicked() {
                        play = Some(i);
                        close = true;
                    }
                    if h.button("Fermer").clicked() {
                        close = true;
                    }
                });
            });
            if close {
                self.guide_selected = None;
            }
        }
        if let Some(i) = play {
            self.guide_selected = None;
            self.play_live(&channels, i);
        }
    }

    // ---------- Fenêtres par-dessus ----------

    pub fn overlays(&mut self, ui: &mut Ui) {
        let ctx = ui.ctx().clone();
        if self.show_account && !self.player.is_open() {
            self.account_dialog(&ctx);
        }
        if let Some(p) = self.confirm_delete.clone() {
            let mut close = false;
            egui::Modal::new(egui::Id::unique("delete")).show(&ctx, |ui| {
                ui.set_width(420.0);
                ui.label(bold(format!("Supprimer « {} » ?", p.name), 20.0));
                ui.add_space(6.0);
                ui.label("Le profil, ses favoris, sa progression de lecture et son historique de chaînes seront supprimés de ce PC.");
                ui.add_space(12.0);
                ui.horizontal(|h| {
                    if h.add(egui::Button::new(RichText::new("Supprimer").color(Color32::WHITE)).fill(ui::ERROR)).clicked() {
                        self.delete_profile(&p);
                        close = true;
                    }
                    if h.button("Annuler").clicked() {
                        close = true;
                    }
                });
            });
            if close {
                self.confirm_delete = None;
            }
        }
        if self.update.is_some() && !self.player.is_open() {
            self.update_dialog(&ctx);
        }

        // Messages temporaires.
        let now = std::time::Instant::now();
        self.toasts.retain(|t| t.until > now);
        if !self.toasts.is_empty() {
            let screen = ctx.content_rect();
            let mut y = screen.bottom() - 24.0;
            for t in self.toasts.iter().rev() {
                let galley = ctx.fonts_mut(|f| f.layout(t.text.clone(), FontId::proportional(15.0), Color32::WHITE, (screen.width() - 80.0).min(560.0)));
                let size = galley.size() + vec2(32.0, 20.0);
                let rect = Rect::from_min_size(pos2(screen.center().x - size.x / 2.0, y - size.y), size);
                let painter = ctx.layer_painter(egui::LayerId::new(egui::Order::Tooltip, egui::Id::unique("toasts")));
                painter.rect_filled(rect, 10.0, Color32::from_rgba_unmultiplied(40, 52, 72, 245));
                painter.galley(rect.min + vec2(16.0, 10.0), galley, Color32::WHITE);
                y -= size.y + 8.0;
            }
            if let Some(next) = self.toasts.iter().map(|t| t.until).min() {
                ctx.request_repaint_after(next - now);
            }
        }
    }

    fn account_dialog(&mut self, ctx: &egui::Context) {
        let mut close = false;
        let mut switch = false;
        let mut refresh = false;
        let mut export = false;
        let mut import = false;
        let mut check = false;
        let mut touch_choice: Option<Option<bool>> = None;
        egui::Modal::new(egui::Id::unique("account")).show(ctx, |ui| {
            ui.set_width(460.0_f32.min(ctx.content_rect().width() - 60.0));
            ui.label(bold("Mon abonnement", 22.0));
            ui.add_space(8.0);
            ScrollArea::vertical().max_height(ctx.content_rect().height() - 200.0).show(ui, |ui| {
                match &self.account {
                    None => {
                        ui.label(dim("Informations du compte indisponibles."));
                    }
                    Some(a) => {
                        info_row(ui, "Utilisateur", &a.username);
                        info_row(ui, "Statut", &format!("{}{}", a.status, if a.is_trial { " (essai)" } else { "" }));
                        info_row(ui, "Expiration", &a.exp_date.map(ui::format_date).unwrap_or_else(|| "Illimité".into()));
                        info_row(ui, "Connexions", &format!("{} / {}", a.active_connections.as_deref().unwrap_or("?"), a.max_connections.as_deref().unwrap_or("?")));
                    }
                }
                if let Some(c) = &self.client {
                    info_row(ui, "Serveur", &c.credentials.server);
                }
                ui.add_space(6.0);
                let link = |ui: &mut Ui, text: &str| ui.add_sized([ui.available_width(), 40.0], egui::Button::new(RichText::new(text).color(PRIMARY)).frame(false));
                if link(ui, "⟳  Actualiser le catalogue").clicked() {
                    refresh = true;
                }
                if link(ui, "⤓  Exporter favoris et historique").clicked() {
                    export = true;
                }
                if link(ui, "⤒  Importer une sauvegarde (PC ou Android)").clicked() {
                    import = true;
                }
                ui.separator();
                ui.label(bold("Affichage", 16.0));
                ui.horizontal(|h| {
                    h.label("Taille de l'interface");
                    let mut z = ctx.zoom_factor();
                    if h.add(egui::Slider::new(&mut z, 0.75..=2.0).step_by(0.05).custom_formatter(|v, _| format!("{:.0} %", v * 100.0))).changed() {
                        ctx.set_zoom_factor(z);
                    }
                });
                ui.horizontal(|h| {
                    h.label("Mode tactile");
                    let current = self.settings.touch_mode;
                    if h.selectable_label(current.is_none(), "Auto").clicked() {
                        touch_choice = Some(None);
                    }
                    if h.selectable_label(current == Some(true), "Activé").clicked() {
                        touch_choice = Some(Some(true));
                    }
                    if h.selectable_label(current == Some(false), "Désactivé").clicked() {
                        touch_choice = Some(Some(false));
                    }
                });
                ui.label(dim("Raccourcis du lecteur : Espace pause · ←/→ reculer/avancer · ↑/↓ volume · Page préc./suiv. chaîne · Retour arrière chaîne précédente · F plein écran · L liste des chaînes · Échap retour"));
                ui.separator();
                info_row(ui, "Application", &format!("IPTV Player {} (Windows {})", crate::updater::CURRENT_VERSION, crate::updater::arch()));
                let label = if self.checking_update { "Recherche en cours…" } else { "Rechercher une mise à jour" };
                if ui.add_enabled(!self.checking_update, egui::Button::new(RichText::new(label).color(PRIMARY)).frame(false)).clicked() {
                    check = true;
                }
            });
            ui.add_space(8.0);
            ui.horizontal(|h| {
                if h.button("Changer de profil").clicked() {
                    switch = true;
                }
                h.with_layout(Layout::right_to_left(Align::Center), |h| {
                    if ui::primary_button(h, "Fermer").clicked() {
                        close = true;
                    }
                });
            });
        });
        if close || ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.show_account = false;
        }
        if switch {
            self.switch_profile();
        }
        if refresh {
            self.show_account = false;
            self.refresh_catalog();
        }
        if export {
            self.export_backup();
        }
        if import {
            self.import_backup();
        }
        if check {
            self.check_for_update(true);
        }
        if let Some(t) = touch_choice {
            self.set_touch_mode(t);
        }
    }

    fn update_dialog(&mut self, ctx: &egui::Context) {
        let Some(u) = self.update.as_ref() else { return };
        let (info, progress, error, installing) = (u.info.clone(), u.progress, u.error.clone(), u.installing);
        let mut start = false;
        let mut skip = false;
        let mut later = false;
        egui::Modal::new(egui::Id::unique("update")).show(ctx, |ui| {
            ui.set_width(480.0_f32.min(ctx.content_rect().width() - 60.0));
            ui.label(bold(format!("Nouvelle version {}", info.version), 22.0));
            ui.label(dim(format!("Version installée : {}{}", crate::updater::CURRENT_VERSION, if info.size > 0 { format!(" · {:.1} Mo", info.size as f64 / 1_048_576.0) } else { String::new() })));
            ui.add_space(8.0);
            ScrollArea::vertical().max_height(260.0).show(ui, |ui| {
                ui.label(if info.notes.is_empty() { "Corrections et améliorations." } else { &info.notes });
            });
            ui.add_space(8.0);
            if let Some(p) = progress {
                ui.add(egui::ProgressBar::new(p).show_percentage());
                if installing {
                    ui.label(dim("Installation… l'application va redémarrer."));
                }
            }
            if let Some(e) = &error {
                ui.colored_label(ui::ERROR, e);
            }
            ui.add_space(8.0);
            ui.horizontal_wrapped(|h| {
                if ui::primary_button(h, "Mettre à jour").clicked() && progress.is_none() {
                    start = true;
                }
                if h.button("Ignorer cette version").clicked() {
                    skip = true;
                }
                if h.button("Plus tard").clicked() {
                    later = true;
                }
            });
        });
        if start {
            self.start_update();
        }
        if skip {
            self.dismiss_update(true);
        }
        if later {
            self.dismiss_update(false);
        }
    }
}

// ---------- Composants ----------

fn category_name(tab: &crate::app::TabState, sel: &str) -> String {
    match sel {
        CATEGORY_ALL => "Tout".into(),
        CATEGORY_FAV => "Favoris".into(),
        CATEGORY_RECENT => "Récents".into(),
        other => tab.categories.iter().find(|c| c.id == other).map(|c| c.name.clone()).unwrap_or_else(|| "Catalogue".into()),
    }
}

fn profile_card(ui: &mut Ui, p: &Profile, w: f32, actions: &mut Vec<Action>) {
    let (rect, resp) = ui::card(ui, vec2(w, 230.0));
    let painter = ui.painter();
    painter.rect_filled(rect, 16.0, SURFACE);
    let color = avatar_color(&p.name);
    let c = pos2(rect.center().x, rect.top() + 62.0);
    painter.circle_filled(c, 42.0, color);
    painter.text(c, Align2::CENTER_CENTER, p.name.chars().next().unwrap_or('?').to_uppercase().to_string(), FontId::proportional(36.0), Color32::WHITE);
    let tp = painter.with_clip_rect(rect.shrink(8.0));
    tp.text(pos2(rect.center().x, rect.top() + 122.0), Align2::CENTER_CENTER, &p.name, FontId::proportional(18.0), ui::TEXT);
    let server = p.server.split("://").nth(1).unwrap_or(&p.server);
    tp.text(pos2(rect.center().x, rect.top() + 146.0), Align2::CENTER_CENTER, format!("{} · {server}", p.username), FontId::proportional(12.5), TEXT_DIM);
    // Boutons Modifier / Supprimer (à part de la zone « ouvrir »).
    let bw = (w - 36.0) / 2.0;
    let edit_rect = Rect::from_min_size(pos2(rect.left() + 12.0, rect.bottom() - 52.0), vec2(bw, 40.0));
    let del_rect = Rect::from_min_size(pos2(edit_rect.right() + 12.0, edit_rect.top()), vec2(bw, 40.0));
    let edit = ui.put(edit_rect, egui::Button::new("✏ Modifier"));
    let del = ui.put(del_rect, egui::Button::new("🗑 Supprimer"));
    if edit.clicked() {
        actions.push(Action::EditProfile(Some(p.clone())));
    } else if del.clicked() {
        actions.push(Action::AskDelete(p.clone()));
    } else if resp.clicked() {
        actions.push(Action::OpenProfile(p.clone()));
    }
}

fn avatar_color(name: &str) -> Color32 {
    const COLORS: [Color32; 6] = [
        Color32::from_rgb(0x29, 0x79, 0xFF),
        Color32::from_rgb(0x00, 0x96, 0x88),
        Color32::from_rgb(0xAB, 0x47, 0xBC),
        Color32::from_rgb(0xEF, 0x6C, 0x00),
        Color32::from_rgb(0xD8, 0x1B, 0x60),
        Color32::from_rgb(0x43, 0xA0, 0x47),
    ];
    let h = name.bytes().fold(0u32, |a, b| a.wrapping_mul(31).wrapping_add(b as u32));
    COLORS[(h as usize) % COLORS.len()]
}

fn avatar_button(ui: &mut Ui, initial: &str, size: f32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size.max(ui.spacing().interact_size.y)), Sense::click());
    let c = rect.center();
    ui.painter().circle_filled(c, size / 2.0, if resp.hovered() { PRIMARY } else { avatar_color(initial) });
    ui.painter().text(c, Align2::CENTER_CENTER, initial, FontId::proportional(size * 0.5), Color32::WHITE);
    resp
}

/// Barre de titre avec retour et actions à droite.
fn back_bar(ui: &mut Ui, title: &str, mut on_back: impl FnMut(), right: impl FnOnce(&mut Ui)) {
    egui::Panel::top("backbar").frame(egui::Frame::NONE.fill(SURFACE).inner_margin(egui::Margin::symmetric(10, 6))).show(ui, |ui| {
        ui.horizontal(|h| {
            if ui::icon_button(h, "←", "Retour (Échap)").clicked() {
                on_back();
            }
            h.add(egui::Label::new(bold(title, 19.0)).truncate());
            h.with_layout(Layout::right_to_left(Align::Center), right);
        });
    });
}

fn info_row(ui: &mut Ui, label: &str, value: &str) {
    let mut job = egui::text::LayoutJob::default();
    let font = egui::TextStyle::Body.resolve(ui.style());
    job.append(&format!("{label} : "), 0.0, egui::TextFormat { font_id: font.clone(), color: TEXT_DIM, ..Default::default() });
    job.append(value, 0.0, egui::TextFormat { font_id: font, color: ui::TEXT, ..Default::default() });
    ui.label(job);
}

fn info_block(ui: &mut Ui, label: &str, value: &str) {
    ui.label(RichText::new(label).strong().color(PRIMARY));
    ui.label(value);
    ui.add_space(8.0);
}

fn error_box(ui: &mut Ui, message: &str, mut on_retry: impl FnMut()) {
    ui.vertical_centered(|ui| {
        ui.add_space(ui.available_height() * 0.3);
        ui.label(RichText::new(message).color(ui::TEXT).size(16.0));
        ui.add_space(12.0);
        if ui::primary_button(ui, "⟳  Réessayer").clicked() {
            on_retry();
        }
    });
}

/// Programme en cours (avec progression) et suivant, sous le nom d'une chaîne.
fn epg_lines(p: &egui::Painter, programs: Option<&[xtream::EpgProgram]>, now: i64, at: egui::Pos2, width: f32) {
    let Some(programs) = programs else { return };
    let (current, next) = now_next(programs, now);
    let mut y = at.y;
    if let Some(c) = current {
        p.text(pos2(at.x, y), Align2::LEFT_TOP, format!("{}  {}", format_time(c.start), c.title), FontId::proportional(13.0), ui::TEXT);
        y += 18.0;
        let frac = (now - c.start) as f32 / (c.end - c.start).max(1) as f32;
        let bar = Rect::from_min_size(pos2(at.x, y), vec2(width.min(220.0), 3.0));
        p.rect_filled(bar, 2.0, Color32::from_white_alpha(40));
        let mut done = bar;
        done.set_width(bar.width() * frac.clamp(0.0, 1.0));
        p.rect_filled(done, 2.0, PRIMARY);
        y += 6.0;
    }
    if let Some(n) = next {
        p.text(pos2(at.x, y), Align2::LEFT_TOP, format!("Ensuite {} · {}", format_time(n.start), n.title), FontId::proportional(12.0), TEXT_DIM);
    }
}
