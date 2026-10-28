//! Desktop preview bin (Android APK-da istifadə olunmur)

fn main() {
    #[cfg(not(target_os = "android"))]
    {
        let _ = env_logger::try_init();
    }
    zai_mobile::run_app();
}
