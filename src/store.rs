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

pub fn save(p: &Persisted) {
    if let Ok(s) = serde_json::to_string_pretty(p) {
        let _ = std::fs::write(state_path(), s);
    }
}
