//! IPTV Player pour Windows : lecteur IPTV compatible Xtream Codes, pour PC et PC tactile.
//!
//! Cette application ne fournit aucun contenu : elle sert uniquement à lire un abonnement IPTV
//! auquel vous avez légitimement accès.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod backup;
mod cast;
mod epg;
mod images;
mod mpv;
mod player;
mod screens;
mod secret;
mod store;
mod ui;
mod updater;
mod xtream;

fn main() -> eframe::Result {
    updater::cleanup_old_files();
    let options = eframe::NativeOptions {
        renderer: eframe::Renderer::Glow,
        viewport: egui::ViewportBuilder::default()
            .with_title("IPTV Player")
            .with_app_id("fr.pelis.iptv")
            .with_inner_size([1280.0, 800.0])
            .with_maximized(true)
            .with_min_inner_size([520.0, 360.0])
            .with_icon(std::sync::Arc::new(icon())),
        ..Default::default()
    };
    eframe::run_native("IPTV Player", options, Box::new(|cc| Ok(Box::new(app::App::new(cc)))))
}

/// Icône : carré arrondi bleu avec un triangle « lecture ».
fn icon() -> egui::IconData {
    const N: usize = 64;
    let mut rgba = vec![0u8; N * N * 4];
    let r = 14.0;
    for y in 0..N {
        for x in 0..N {
            let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
            // Coins arrondis.
            let cx = fx.clamp(r, N as f32 - r);
            let cy = fy.clamp(r, N as f32 - r);
            let inside = (fx - cx).powi(2) + (fy - cy).powi(2) <= r * r;
            if !inside {
                continue;
            }
            // Triangle de lecture.
            let (tx, ty) = (fx - 24.0, fy - 32.0);
            let in_triangle = (0.0..=22.0).contains(&tx) && ty.abs() <= 14.0 * (1.0 - tx / 22.0);
            let i = (y * N + x) * 4;
            let c = if in_triangle { [255, 255, 255] } else { [0x29, 0xB6, 0xF6] };
            rgba[i..i + 4].copy_from_slice(&[c[0], c[1], c[2], 255]);
        }
    }
    egui::IconData { rgba, width: N as u32, height: N as u32 }
}
