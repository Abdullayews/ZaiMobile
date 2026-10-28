//! chat.z.ai veb dizayn sisteminin dəqiq tokenləri (light tema).
//! Bütün dəyərlər brauzerdən ölçülüb: computed styles + DOM audit (2025-10).

use egui::{Color32, CornerRadius, Margin, Shadow, Stroke};

// ---- Rənglər ----
/// Ana fon — html/body: rgb(240,240,240)
pub const BG: Color32 = Color32::from_rgb(240, 240, 240);
/// Yuxarı naviqasiya paneli: rgb(248,248,248)
pub const NAV_BG: Color32 = Color32::from_rgb(248, 248, 248);
/// Ağ səth: giriş kapsulu, popup, modal
pub const SURFACE: Color32 = Color32::WHITE;
/// İstifadəçi mesaj baloncuğu: rgb(234,234,234), radius 12, padding 12
pub const BUBBLE_USER: Color32 = Color32::from_rgb(234, 234, 234);
/// Hover / aktiv element fonu
pub const HOVER: Color32 = Color32::from_rgb(240, 240, 240);
/// Mətn (əsas): rgb(13,13,13)
pub const TEXT: Color32 = Color32::from_rgb(13, 13, 13);
/// Mətn (ikinci dərəcəli): rgb(92,92,92)
pub const TEXT_2: Color32 = Color32::from_rgb(92, 92, 92);
/// Mətn (üçüncü dərəcəli / placeholder): rgb(136,136,136)
pub const TEXT_3: Color32 = Color32::from_rgb(136, 136, 136);
/// Kopyalama düyməsi mətni: rgb(103,103,103)
pub const TEXT_MUTED: Color32 = Color32::from_rgb(103, 103, 103);
/// Qara əsas düymə (Sign in): rgb(13,13,13), radius 6, h=32
pub const BTN_DARK: Color32 = Color32::from_rgb(13, 13, 13);
/// Deaktiv göndər düyməsi: rgb(230,230,230)
pub const BTN_DISABLED: Color32 = Color32::from_rgb(230, 230, 230);
/// Zəif iframe/Google düymə fonu
pub const BTN_GRAY: Color32 = Color32::from_rgb(243, 244, 246);

/// Sərhəd: rgba(13,13,13,0.1) — veb-in alfa sərhədi
pub fn border_alpha() -> Color32 {
    Color32::from_black_alpha(26)
}
/// Sərhəd stroke-u
pub fn border_stroke() -> Stroke {
    Stroke::new(1.0, border_alpha())
}
/// Giriş kapsulunun kölgəsi: rgba(0,0,0,0.05) 0 4px 16px
pub fn capsule_shadow() -> Shadow {
    Shadow {
        offset: [0, 4],
        blur: 16,
        spread: 0,
        color: Color32::from_rgba_premultiplied(0, 0, 0, 13),
    }
}
/// Popup/modal kölgəsi
pub fn popup_shadow() -> Shadow {
    Shadow {
        offset: [0, 8],
        blur: 24,
        spread: 0,
        color: Color32::from_rgba_premultiplied(0, 0, 0, 26),
    }
}

/// Veb proporsiyaları
pub const NAV_H: f32 = 48.0;
pub const SCREEN_PAD: f32 = 24.0;
pub const CONTENT_MAX_W: f32 = 720.0;
pub const CAPSULE_RADIUS: f32 = 24.0;
pub const BTN_RADIUS: f32 = 6.0;

/// Məzmun sütununun eni (veb: max-w 48rem, mobil: full - padding)
pub fn content_width(ui: &egui::Ui) -> f32 {
    (ui.available_width() - SCREEN_PAD * 2.0).min(CONTENT_MAX_W).max(200.0)
}

/// Serif başlıq ailəsi ("More than coding..." — Crimson Text əvəzinə Tinos)
pub fn serif_family() -> egui::FontFamily {
    egui::FontFamily::Name(std::sync::Arc::from("serif"))
}
/// Bold ailə
pub fn bold_family() -> egui::FontFamily {
    egui::FontFamily::Name(std::sync::Arc::from("bold"))
}

// ---- Paylaşılan vidcetlər ----

/// Dairəvi ikon düyməsi (veb nav ikonları kimi: şəffaf, hit sahəsi 40x40)
pub fn icon_btn(ui: &mut egui::Ui, tex: &egui::TextureHandle, size: f32, tint: Color32) -> egui::Response {
    let btn_size = 40.0;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(btn_size, btn_size), egui::Sense::click());
    let img_size = egui::vec2(size, size);
    let img_rect = egui::Rect::from_center_size(rect.center(), img_size);
    egui::Image::from_texture(tex)
        .tint(tint)
        .fit_to_exact_size(img_size)
        .paint_at(ui, img_rect);
    resp
}

/// Qara pill düymə ("Sign in"): bg #0D0D0D, radius 6, ağ mətn
pub fn dark_pill(ui: &mut egui::Ui, label: &str, height: f32, font_size: f32) -> egui::Response {
    let pad = 14.0;
    let galley = ui.ctx().fonts_mut(|f| {
        f.layout_no_wrap(
            format!("{}  ", label),
            egui::FontId::proportional(font_size),
            Color32::WHITE,
        )
    });
    let text_w = galley.size().x;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(text_w + pad * 2.0, height), egui::Sense::click());
    let fill = if resp.hovered() {
        Color32::from_rgb(45, 45, 45)
    } else {
        BTN_DARK
    };
    ui.painter().rect_filled(rect, CornerRadius::same(BTN_RADIUS as u8), fill);
    ui.painter().galley(
        egui::pos2(rect.center().x - galley.size().x / 2.0, rect.center().y - galley.size().y / 2.0),
        galley,
        Color32::WHITE,
    );
    resp
}

/// Ağ/boz konturlu pill düymə ("Skip for now")
pub fn outline_pill(ui: &mut egui::Ui, label: &str, height: f32, font_size: f32) -> egui::Response {
    let pad = 14.0;
    let galley = ui.ctx().fonts_mut(|f| {
        f.layout_no_wrap(
            format!("{}  ", label),
            egui::FontId::proportional(font_size),
            TEXT,
        )
    });
    let text_w = galley.size().x;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(text_w + pad * 2.0, height), egui::Sense::click());
    let fill = if resp.hovered() { HOVER } else { SURFACE };
    ui.painter().rect_filled(rect, CornerRadius::same(BTN_RADIUS as u8), fill);
    ui.painter().rect_stroke(rect, CornerRadius::same(BTN_RADIUS as u8), border_stroke(), egui::StrokeKind::Inside);
    ui.painter().galley(
        egui::pos2(rect.center().x - galley.size().x / 2.0, rect.center().y - galley.size().y / 2.0),
        galley,
        TEXT,
    );
    resp
}

/// Tam enli təbəqə düyməsi (modal içi: Google/Email/Github)
pub fn full_btn(
    ui: &mut egui::Ui,
    label: &str,
    icon: Option<&egui::TextureHandle>,
    icon_tint: Color32,
    dark: bool,
    height: f32,
    radius: f32,
) -> egui::Response {
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, height), egui::Sense::click());
    let (fill, txt) = if dark {
        (BTN_DARK, Color32::WHITE)
    } else {
        (BTN_GRAY, TEXT)
    };
    let fill = if resp.hovered() && !dark {
        Color32::from_rgb(233, 234, 237)
    } else if resp.hovered() && dark {
        Color32::from_rgb(45, 45, 45)
    } else {
        fill
    };
    ui.painter()
        .rect_filled(rect, CornerRadius::same(radius as u8), fill);

    // ikon solda, mətn mərkəzdə
    if let Some(tex) = icon {
        let s = height * 0.5;
        let img_rect =
            egui::Rect::from_center_size(egui::pos2(rect.left() + 14.0 + s / 2.0, rect.center().y), egui::vec2(s, s));
        egui::Image::from_texture(tex)
            .tint(icon_tint)
            .fit_to_exact_size(egui::vec2(s, s))
            .paint_at(ui, img_rect);
    }
    let galley = ui
        .ctx()
        .fonts_mut(|f| f.layout_no_wrap(label.to_string(), egui::FontId::proportional(15.0), txt));
    ui.painter().galley(
        egui::pos2(rect.center().x - galley.size().x / 2.0, rect.center().y - galley.size().y / 2.0),
        galley,
        txt,
    );
    resp
}

/// Sadə mətn düyməsi (API, Skip for now link)
pub fn text_btn(ui: &mut egui::Ui, label: &str, size: f32, color: Color32, bold: bool) -> egui::Response {
    let galley = ui.ctx().fonts_mut(|f| {
        let font = if bold {
            egui::FontId::new(size, bold_family())
        } else {
            egui::FontId::proportional(size)
        };
        f.layout_no_wrap(label.to_string(), font, color)
    });
    let (rect, resp) = ui.allocate_exact_size(galley.size() + egui::vec2(8.0, 10.0), egui::Sense::click());
    ui.painter().galley(
        egui::pos2(rect.left() + 4.0, rect.center().y - galley.size().y / 2.0),
        galley,
        color,
    );
    resp
}

/// Xəta/info banneri (veb stili: yumşaq qırmızı fon)
pub fn error_banner(ui: &mut egui::Ui, msg: &str) {
    egui::Frame::new()
        .fill(Color32::from_rgb(254, 243, 242))
        .corner_radius(8.0)
        .inner_margin(Margin::same(10))
        .stroke(Stroke::new(1.0, Color32::from_rgb(250, 205, 202)))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(
                egui::RichText::new(format!("⚠ {}", msg))
                    .size(13.5)
                    .color(Color32::from_rgb(190, 40, 30)),
            );
        });
}

pub fn info_banner(ui: &mut egui::Ui, msg: &str) {
    egui::Frame::new()
        .fill(Color32::from_rgb(243, 246, 252))
        .corner_radius(8.0)
        .inner_margin(Margin::same(10))
        .stroke(Stroke::new(1.0, Color32::from_rgb(215, 223, 240)))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(
                egui::RichText::new(format!("ℹ {}", msg))
                    .size(13.5)
                    .color(Color32::from_rgb(60, 80, 140)),
            );
        });
}

/// Dəqiq ölçülü child region (egui-in *_centered/with_layout regionləri
/// qalan bütün hündürlüyü tələb edə bilər — bu, deterministikdir).
pub fn region<R>(
    ui: &mut egui::Ui,
    size: egui::Vec2,
    layout: egui::Layout,
    content: impl FnOnce(&mut egui::Ui) -> R,
) -> R {
    let (rect, _) = ui.allocate_exact_size(size, egui::Sense::hover());
    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect).layout(layout));
    content(&mut child)
}

/// Tam enli, dəqiq hündürlüklü region
pub fn full_row<R>(
    ui: &mut egui::Ui,
    height: f32,
    layout: egui::Layout,
    content: impl FnOnce(&mut egui::Ui) -> R,
) -> R {
    let w = ui.available_width();
    region(ui, egui::vec2(w, height), layout, content)
}
