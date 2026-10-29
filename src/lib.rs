//! Z.ai — chat.z.ai-nin 100% native Android klienti (Rust + Slint).
//! WebView yoxdur; UI Slint ilə, şəbəkə reqwest ilə, imza HMAC-SHA256 ilə.

slint::include_modules!();

pub mod api;
mod clipboard;
mod controller;
pub mod markdown;
mod picker;
mod store;
pub mod stream;

/// Giriş nöqtəsi: Android (cdylib → android_main) və Desktop (main.rs bin)
pub fn run_app() {
    controller::run();
}

/// Android: Slint backend-android-activity (NativeActivity + Skia)
#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: slint::android::AndroidApp) {
    slint::android::init(app).expect("Slint Android backend init");
    run_app();
}
