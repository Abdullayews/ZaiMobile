//! Veb-dən çıxarılmış gerçek SVG ikonların PNG teksturaları.
//! Bütün monoxrom ikonlar AĞ rəngdə rasterləşdirilib — çəkmə zamanı
//! `tint()` ilə istənilən rəngə boyanır (veb-də currentColor kimi).

use egui::{ColorImage, Context, TextureHandle};

macro_rules! icon_bytes {
    ($name:literal) => {
        include_bytes!(concat!("../assets/icons/", $name, ".png"))
    };
}

/// Monoxrom (ağ) ikonlar — tint ilə boyanır
pub const MONO: [&str; 13] = [
    "panel_left", "square_pen", "plus", "chevron_down", "arrow_up", "check", "copy", "x",
    "stop", "refresh", "arrow_left", "external", "mail",
];

pub struct Icons {
    pub panel_left: TextureHandle,
    pub square_pen: TextureHandle,
    pub plus: TextureHandle,
    pub chevron_down: TextureHandle,
    pub arrow_up: TextureHandle,
    pub check: TextureHandle,
    pub copy: TextureHandle,
    pub x: TextureHandle,
    pub stop: TextureHandle,
    pub refresh: TextureHandle,
    pub arrow_left: TextureHandle,
    pub external: TextureHandle,
    pub mail: TextureHandle,
    /// Rəngli (tint YOX) — Google G
    pub google: TextureHandle,
    /// Rəngli (tint YOX) — Github mark
    pub github: TextureHandle,
}

fn load_png(ctx: &Context, name: &str, bytes: &[u8]) -> TextureHandle {
    let img = image::load_from_memory(bytes).expect("ikon PNG dekodlaşdırıla bilmədi");
    let rgba = img.to_rgba8();
    let (w, h) = rgba.dimensions();
    let color = ColorImage::from_rgba_unmultiplied([w as usize, h as usize], &rgba);
    ctx.load_texture(name, color, egui::TextureOptions::LINEAR)
}

impl Icons {
    pub fn load(ctx: &Context) -> Self {
        Icons {
            panel_left: load_png(ctx, "panel_left", icon_bytes!("panel_left")),
            square_pen: load_png(ctx, "square_pen", icon_bytes!("square_pen")),
            plus: load_png(ctx, "plus", icon_bytes!("plus")),
            chevron_down: load_png(ctx, "chevron_down", icon_bytes!("chevron_down")),
            arrow_up: load_png(ctx, "arrow_up", icon_bytes!("arrow_up")),
            check: load_png(ctx, "check", icon_bytes!("check")),
            copy: load_png(ctx, "copy", icon_bytes!("copy")),
            x: load_png(ctx, "x", icon_bytes!("x")),
            stop: load_png(ctx, "stop", icon_bytes!("stop")),
            refresh: load_png(ctx, "refresh", icon_bytes!("refresh")),
            arrow_left: load_png(ctx, "arrow_left", icon_bytes!("arrow_left")),
            external: load_png(ctx, "external", icon_bytes!("external")),
            mail: load_png(ctx, "mail", icon_bytes!("mail")),
            google: load_png(ctx, "google", icon_bytes!("google")),
            github: load_png(ctx, "github", icon_bytes!("github")),
        }
    }
}

/// Təklif kartlarının şəkilləri (veb CDN-dən endirilmiş orijinallar)
pub struct Cards {
    pub fullstack: TextureHandle,
    pub writing: TextureHandle,
    pub data: TextureHandle,
    pub slides: TextureHandle,
    pub web: TextureHandle,
}

impl Cards {
    pub fn load(ctx: &Context) -> Self {
        Cards {
            fullstack: load_png(ctx, "card_fullstack", include_bytes!("../assets/cards/fullstack.png")),
            writing: load_png(ctx, "card_writing", include_bytes!("../assets/cards/writing.png")),
            data: load_png(ctx, "card_data", include_bytes!("../assets/cards/data.png")),
            slides: load_png(ctx, "card_slides", include_bytes!("../assets/cards/slides.png")),
            web: load_png(ctx, "card_web", include_bytes!("../assets/cards/web.png")),
        }
    }
}

/// İkonu verilmiş ölçüdə və rəngdə çək
pub fn draw(ui: &mut egui::Ui, tex: &TextureHandle, size: f32, tint: egui::Color32) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(size, size), egui::Sense::hover());
    egui::Image::from_texture(tex)
        .tint(tint)
        .fit_to_exact_size(egui::vec2(size, size))
        .paint_at(ui, rect);
    resp
}

/// Kliklənən ikon
pub fn draw_clickable(
    ui: &mut egui::Ui,
    tex: &TextureHandle,
    size: f32,
    hit: f32,
    tint: egui::Color32,
) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(hit, hit), egui::Sense::click());
    let img_rect = egui::Rect::from_center_size(rect.center(), egui::vec2(size, size));
    egui::Image::from_texture(tex)
        .tint(tint)
        .fit_to_exact_size(egui::vec2(size, size))
        .paint_at(ui, img_rect);
    resp
}
