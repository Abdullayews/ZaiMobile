//! Z.ai native Android klienti — 100% Rust (WebView yoxdur)

pub mod api;
pub mod md;
pub mod platform;
pub mod screens;
pub mod store;
pub mod stream;
pub mod ui;

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(app: winit::platform::android::activity::AndroidApp) {
    if let Some(p) = app.internal_data_path() {
        store::set_android_data_dir(p);
    }
    let options = eframe::NativeOptions {
        android_app: Some(app),
        ..Default::default()
    };
    eframe::run_native(
        "Z.ai",
        options,
        Box::new(|cc| Ok(Box::new(ui::MyApp::new(cc)))),
    )
    .expect("eframe run failed");
}

#[cfg(not(target_os = "android"))]
#[allow(dead_code)]
fn main() -> eframe::Result {
    env_logger::init(); // desktop-da log
    let options = eframe::NativeOptions {
        ..Default::default()
    };
    eframe::run_native(
        "Z.ai",
        options,
        Box::new(|cc| Ok(Box::new(ui::MyApp::new(cc)))),
    )
}
