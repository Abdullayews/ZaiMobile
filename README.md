# Z.ai Mobile — "Eyni sistem"

Z.ai-nin rəsmi görünümlü Android tətbiqi. Daxildə chat.z.ai-in öz mühərriki işləyir —
UI, məntiq və protokol saytın öz kodudur; native örtük fayl seçici, endirmə,
pull-to-refresh, offline bərpa və back düyməsi əlavə edir.

## Arxitektura
```
┌─────────────────────────────────────────────┐
│  Android (zai.mobile)                       │
│  ┌───────────────────────────────────────┐  │
│  │ Tauri 2 + wry (system WebView)        │  │
│  │  ui/index.html → https://chat.z.ai    │  │
│  │  bridge JS: PTR · offline · download  │  │
│  └───────────────────────────────────────┘  │
│  __zaiNative (Kotlin): ACTION_VIEW ·        │
│  MediaStore save · back düyməsi            │
└─────────────────────────────────────────────┘
```

## Build
CI hər push-da imzalalı APK buraxır (beta pre-release).
Lokal: `cd src-tauri && cargo tauri android build --apk --target aarch64`
