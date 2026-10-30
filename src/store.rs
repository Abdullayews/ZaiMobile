//! Yaddaşda saxlanılan vəziyyət (state.json) + data qovluğu yolları.

use crate::api;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Persisted {
    pub token: String,
    pub user_id: String,
    pub user_name: String,
    pub is_guest: bool,
    pub device_id: String,
    pub model_index: usize,
    pub effort_index: usize,
}

impl Persisted {
    pub fn new() -> Self {
        Persisted {
            device_id: api::make_device_id(),
            model_index: 0,
            effort_index: 2,
            ..Default::default()
        }
    }
}

/// App data qovluğu:
/// - Android: /data/data/zai.mobile/files (NativeActivity paketi)
/// - Desktop: ./data
pub fn data_dir() -> PathBuf {
    #[cfg(target_os = "android")]
    {
        let p = PathBuf::from("/data/data/zai.mobile/files");
        let _ = std::fs::create_dir_all(&p);
        p
    }
    #[cfg(not(target_os = "android"))]
    {
        let p = PathBuf::from("data");
        let _ = std::fs::create_dir_all(&p);
        p
    }
}

pub fn state_path() -> PathBuf {
    data_dir().join("state.json")
}

pub fn load() -> Persisted {
    match std::fs::read_to_string(state_path()) {
        Ok(s) => serde_json::from_str(&s).unwrap_or_default(),
        Err(_) => Persisted::new(),
    }
}

/// ATOMİK yazma: tmp fayl + fsync + rename. Elektrik kəsilməsi/zorla öldürülmə
/// zamanı state.json yarımçıq qalmır (token itirilmir). Yazma tezliyi aşağıdır
/// (yalnız auth/model/effort dəyişəndə) — debounce lazım deyil.
pub fn save(p: &Persisted) {
    let path = state_path();
    let tmp = data_dir().join("state.json.tmp");
    let s = match serde_json::to_string_pretty(p) {
        Ok(s) => s,
        Err(_) => return,
    };
    let ok = std::fs::write(&tmp, s)
        .and_then(|_| {
            // durability: diskə düşsün (tokenin itməməsi üçün)
            let f = std::fs::File::open(&tmp)?;
            f.sync_all()
        })
        .and_then(|_| std::fs::rename(&tmp, &path))
        .is_ok();
    if !ok {
        // fallback: birbaşa yaz (ən pis hal köhnə davranış)
        let _ = std::fs::write(&path, serde_json::to_string_pretty(p).unwrap_or_default());
    }
}
