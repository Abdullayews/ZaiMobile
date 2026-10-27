use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone)]
pub struct PersistedState {
    pub token: Option<String>,
    pub model: Option<String>,
    pub session_id: Option<String>,
    pub theme_dark: Option<bool>,
}

impl Default for PersistedState {
    fn default() -> Self {
        PersistedState {
            token: None,
            model: Some("x-preview-l".into()),
            session_id: None,
            theme_dark: Some(true),
        }
    }
}

#[cfg(target_os = "android")]
static DATA_DIR: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

#[cfg(target_os = "android")]
pub fn set_android_data_dir(p: PathBuf) {
    let _ = DATA_DIR.set(p);
}

pub fn data_dir() -> PathBuf {
    #[cfg(target_os = "android")]
    {
        if let Some(p) = DATA_DIR.get() {
            return p.clone();
        }
        return PathBuf::from("/data/data/zai.mobile/files");
    }
    #[cfg(not(target_os = "android"))]
    {
        if let Some(home) = std::env::var_os("HOME") {
            let p = PathBuf::from(home).join(".zai-mobile");
            let _ = std::fs::create_dir_all(&p);
            return p;
        }
        std::env::temp_dir().join("zai-mobile")
    }
}

fn state_path() -> PathBuf {
    data_dir().join("state.json")
}

pub fn load() -> PersistedState {
    match std::fs::read_to_string(state_path()) {
        Ok(txt) => serde_json::from_str(&txt).unwrap_or_default(),
        Err(_) => PersistedState::default(),
    }
}

pub fn save(st: &PersistedState) {
    let dir = data_dir();
    let _ = std::fs::create_dir_all(&dir);
    if let Ok(json) = serde_json::to_string_pretty(st) {
        let tmp = dir.join("state.json.tmp");
        if std::fs::write(&tmp, json).is_ok() {
            let _ = std::fs::rename(&tmp, state_path());
        }
    }
}
