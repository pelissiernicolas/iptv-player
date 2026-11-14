//! Thème, mise en forme et petits composants partagés par les écrans.

use egui::{Color32, CornerRadius, FontFamily, FontId, Rect, Response, RichText, Sense, Stroke, TextStyle, Ui, Vec2, vec2};

pub const PRIMARY: Color32 = Color32::from_rgb(0x29, 0xB6, 0xF6);
pub const ON_PRIMARY: Color32 = Color32::from_rgb(0x00, 0x22, 0x2F);
pub const BACKGROUND: Color32 = Color32::from_rgb(0x0A, 0x12, 0x20);
pub const SURFACE: Color32 = Color32::from_rgb(0x0F, 0x1A, 0x2B);
pub const SURFACE_VARIANT: Color32 = Color32::from_rgb(0x1B, 0x29, 0x40);
pub const SURFACE_HIGH: Color32 = Color32::from_rgb(0x24, 0x36, 0x52);
pub const TEXT: Color32 = Color32::from_rgb(0xE6, 0xEC, 0xF5);
pub const TEXT_DIM: Color32 = Color32::from_rgb(0x9A, 0xA8, 0xBC);
pub const STAR: Color32 = Color32::from_rgb(0xFF, 0xC1, 0x07);
pub const WARNING: Color32 = Color32::from_rgb(0xFF, 0xB7, 0x4D);
pub const ERROR: Color32 = Color32::from_rgb(0xEF, 0x53, 0x50);

/// Polices : Segoe UI (Windows) si présente, sinon celles d'egui ; symboles en secours.
pub fn install_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let windir = std::env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".into());
    let mut add = |name: &str, file: &str, first: bool| {
        if let Ok(bytes) = std::fs::read(format!("{windir}\\Fonts\\{file}")) {
            fonts.font_data.insert(name.into(), std::sync::Arc::new(egui::FontData::from_owned(bytes)));
            let list = fonts.families.entry(FontFamily::Proportional).or_default();
            if first { list.insert(0, name.into()) } else { list.push(name.into()) }
        }
    };
    add("segoe", "segoeui.ttf", true);
    add("segoe-symbol", "seguisym.ttf", false);
    if let Ok(bytes) = std::fs::read(format!("{windir}\\Fonts\\segoeuib.ttf")) {
        fonts.font_data.insert("segoe-bold".into(), std::sync::Arc::new(egui::FontData::from_owned(bytes)));
        let mut bold = vec!["segoe-bold".to_string()];
        bold.extend(fonts.families.get(&FontFamily::Proportional).cloned().unwrap_or_default());
        fonts.families.insert(FontFamily::Name("bold".into()), bold);
    } else {
        let prop = fonts.families.get(&FontFamily::Proportional).cloned().unwrap_or_default();
        fonts.families.insert(FontFamily::Name("bold".into()), prop);
    }
    ctx.set_fonts(fonts);
}

/// Style sombre, cibles assez grandes pour le doigt (plus grandes en mode tactile).
pub fn apply_style(ctx: &egui::Context, touch: bool) {
    ctx.all_styles_mut(|style| {
        let mut v = egui::Visuals::dark();
        v.panel_fill = BACKGROUND;
        v.window_fill = SURFACE;
        v.extreme_bg_color = Color32::from_rgb(0x07, 0x0D, 0x18);
        v.faint_bg_color = SURFACE;
        v.override_text_color = Some(TEXT);
        v.hyperlink_color = PRIMARY;
        v.selection.bg_fill = PRIMARY.gamma_multiply(0.45);
        v.selection.stroke = Stroke::new(1.0, PRIMARY);
        v.window_corner_radius = CornerRadius::same(14);
        v.window_stroke = Stroke::new(1.0, SURFACE_HIGH);
        v.widgets.noninteractive.bg_fill = SURFACE;
        v.widgets.inactive.bg_fill = SURFACE_VARIANT;
        v.widgets.inactive.weak_bg_fill = SURFACE_VARIANT;
        v.widgets.hovered.bg_fill = SURFACE_HIGH;
        v.widgets.hovered.weak_bg_fill = SURFACE_HIGH;
        v.widgets.active.bg_fill = PRIMARY.gamma_multiply(0.6);
        v.widgets.active.weak_bg_fill = PRIMARY.gamma_multiply(0.6);
        for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.open] {
            w.corner_radius = CornerRadius::same(10);
        }
        v.widgets.hovered.bg_stroke = Stroke::new(1.0, PRIMARY.gamma_multiply(0.7));
        style.visuals = v;

        let base = if touch { 17.0 } else { 15.0 };
        style.text_styles = [
            (TextStyle::Small, FontId::new(base - 3.0, FontFamily::Proportional)),
            (TextStyle::Body, FontId::new(base, FontFamily::Proportional)),
            (TextStyle::Button, FontId::new(base, FontFamily::Proportional)),
            (TextStyle::Heading, FontId::new(base + 9.0, FontFamily::Name("bold".into()))),
            (TextStyle::Monospace, FontId::new(base - 1.0, FontFamily::Monospace)),
        ]
        .into();
        let s = &mut style.spacing;
        s.item_spacing = if touch { vec2(12.0, 12.0) } else { vec2(10.0, 8.0) };
        s.button_padding = if touch { vec2(16.0, 12.0) } else { vec2(12.0, 7.0) };
        s.interact_size = if touch { vec2(48.0, 48.0) } else { vec2(36.0, 32.0) };
        s.icon_width = if touch { 24.0 } else { 18.0 };
        s.combo_height = 400.0;
        s.scroll = if touch { egui::style::ScrollStyle::solid() } else { egui::style::ScrollStyle::floating() };
        s.scroll.bar_width = if touch { 14.0 } else { 10.0 };
        style.interaction.tooltip_delay = 0.4;
    });
}

pub fn bold(text: impl Into<String>, size: f32) -> RichText {
    RichText::new(text).family(FontFamily::Name("bold".into())).size(size)
}

pub fn dim(text: impl Into<String>) -> RichText {
    RichText::new(text).color(TEXT_DIM)
}

/// Bouton principal (fond bleu).
pub fn primary_button(ui: &mut Ui, text: &str) -> Response {
    ui.add(egui::Button::new(RichText::new(text).color(ON_PRIMARY).strong()).fill(PRIMARY))
}

/// Bouton icône rond, avec info-bulle.
pub fn icon_button(ui: &mut Ui, icon: &str, tooltip: &str) -> Response {
    let size = ui.spacing().interact_size.y;
    ui.add(egui::Button::new(RichText::new(icon).size(size * 0.5)).min_size(vec2(size, size)).frame(false))
        .on_hover_text(tooltip)
}

/// Image distante (affiche, logo) dans `rect`, ou fond neutre en attendant / à défaut.
pub fn paint_image(ui: &Ui, url: Option<&str>, rect: Rect, rounding: f32, fit: bool, placeholder: &str) {
    let painter = ui.painter_at(rect.expand(1.0));
    painter.rect_filled(rect, rounding, SURFACE_VARIANT);
    let mut drawn = false;
    if let Some(url) = url.filter(|u| u.starts_with("http")) {
        let poll = ui.ctx().try_load_texture(url, egui::TextureOptions::LINEAR, egui::SizeHint::default());
        if let Ok(egui::load::TexturePoll::Ready { texture }) = poll {
            let tex_size = texture.size;
            let target = if fit {
                let scale = (rect.width() / tex_size.x).min(rect.height() / tex_size.y);
                Rect::from_center_size(rect.center(), tex_size * scale)
            } else {
                rect
            };
            // « Recadrer » : on prend la partie centrale de l'image qui a les proportions de la zone.
            let uv = if fit {
                Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0))
            } else {
                let (ra, ta) = (rect.width() / rect.height(), tex_size.x / tex_size.y);
                if ta > ra {
                    let w = ra / ta;
                    Rect::from_min_max(egui::pos2((1.0 - w) / 2.0, 0.0), egui::pos2((1.0 + w) / 2.0, 1.0))
                } else {
                    let h = ta / ra;
                    Rect::from_min_max(egui::pos2(0.0, (1.0 - h) / 2.0), egui::pos2(1.0, (1.0 + h) / 2.0))
                }
            };
            egui::Image::from_texture(egui::load::SizedTexture::new(texture.id, tex_size))
                .uv(uv)
                .corner_radius(rounding)
                .paint_at(ui, target);
            drawn = true;
        }
    }
    if !drawn && !placeholder.is_empty() {
        painter.text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            placeholder,
            FontId::proportional((rect.height() * 0.3).clamp(12.0, 40.0)),
            TEXT_DIM,
        );
    }
}

/// Barre de progression fine (lecture, programme en cours).
pub fn thin_progress(ui: &Ui, rect: Rect, fraction: f32) {
    let painter = ui.painter_at(rect);
    painter.rect_filled(rect, 2.0, Color32::from_white_alpha(40));
    let mut fill = rect;
    fill.set_width(rect.width() * fraction.clamp(0.0, 1.0));
    painter.rect_filled(fill, 2.0, PRIMARY);
}

/// Zone cliquable de taille fixe (cartes, lignes) avec survol visible.
pub fn card(ui: &mut Ui, size: Vec2) -> (Rect, Response) {
    let (rect, resp) = ui.allocate_exact_size(size, Sense::click());
    if resp.hovered() {
        ui.painter().rect_filled(rect.expand(3.0), 12.0, SURFACE_HIGH.gamma_multiply(0.8));
    }
    (rect, resp)
}

// ---------- Mise en forme ----------

/// 75 000 ms -> "1:15", 3 900 000 ms -> "1:05:00".
pub fn format_duration(ms: i64) -> String {
    let total = (ms.max(0) / 1000);
    let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

/// Heure locale "HH:MM" d'un instant en ms epoch.
pub fn format_time(ms: i64) -> String {
    use chrono::{Local, TimeZone};
    Local.timestamp_millis_opt(ms).single().map(|d| d.format("%H:%M").to_string()).unwrap_or_default()
}

pub fn format_date(secs: i64) -> String {
    use chrono::{Local, TimeZone};
    Local.timestamp_opt(secs, 0).single().map(|d| d.format("%d/%m/%Y").to_string()).unwrap_or_default()
}

/// Minuscules sans accents, pour une recherche tolérante.
pub fn fold(s: &str) -> String {
    s.chars()
        .flat_map(char::to_lowercase)
        .map(|c| match c {
            'à' | 'â' | 'ä' | 'á' | 'ã' | 'å' => 'a',
            'ç' => 'c',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' | 'í' | 'ì' => 'i',
            'ô' | 'ö' | 'ó' | 'ò' | 'õ' => 'o',
            'û' | 'ü' | 'ú' | 'ù' => 'u',
            'ÿ' => 'y',
            'ñ' => 'n',
            other => other,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durations() {
        assert_eq!(format_duration(75_000), "1:15");
        assert_eq!(format_duration(3_900_000), "1:05:00");
        assert_eq!(format_duration(-5), "0:00");
    }

    #[test]
    fn folding() {
        assert_eq!(fold("Série Été"), "serie ete");
    }
}
