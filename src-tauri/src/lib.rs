//! Z.ai Mobile v5.0 — "Eyni sistem" (The Same System)
//!
//! Dörd native UI cəhdi (egui ×2, əl replikası, Slint) uğursuz olduqdan sonra
//! düzgün arxitektura: daxildə chat.z.ai-in ÖZ mühərriki işləyir — eyni UI,
//! eyni məntiq, eyni protokol, WAF/captcha də daxil. Rust örtüyü:
//!   • WebView pəncərəsi (wry) → https://chat.z.ai
//!   • Hər səhifəyə yeridilən (injected) bridge JS (pull-to-refresh, offline nəzarəti,
//!     endirmə kəsici, xarici keçidlər)
//!   • Kotlin __zaiNative interfeysi (gen/android): ACTION_VIEW, MediaStore
//!     endirmə, back düyməsi
//!
//! Bu fayl: pəncərə yaradıcı + lifecycle.

mod bridge_js;

pub const SITE_URL: &str = "https://chat.z.ai/";

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            use tauri::webview::WebviewWindowBuilder;
            use tauri::WebviewUrl;

            // Pəncərə yerli "bağlanır" səhifəsindən başlayır (ui/index.html) —
            // o, özü https://chat.z.ai/ səhifəsinə keçid edir. Beləcə soyuq
            // başlanğıcda brendli splash + offline vəziyyətdə avtomatik retry.
            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("Z.ai")
                .initialization_script(bridge_js::BRIDGE_JS)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
