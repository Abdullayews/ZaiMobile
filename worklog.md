# ZaiMobile v6.0 — İş jurnalı

## 2026-10-29 — v6.0 "Adi hesab girişi" (WebView tamamilə silindi)

### İstək
"webwiev ilə alınmır. PreStream sistemə bax — hesaba giriş edib api ilə baxırdıq,
indi lazımdır ki adi hesaba giriş məntiqində olsun. webview olmadan saytın bütün
hərşeyini mobil üçün kopyala, uyğun hala gətir."

### Araşdırma nəticələri (canlı, headless Chromium ilə)
1. **Signin API**: `POST /api/v1/auths/signin {email, password, captcha_verify_param}`
   → captcha_verify_param **həmişə** tələb olunur ("The captcha verification failed").
2. **Captcha sistemi**: Aliyun Captcha 2.0 (SceneId 36qgs6xb, region sgp, prefix no8xfe,
   INPAINTING jigsaw). Protokol: InitCaptchaV3/VerifyCaptchaV3 (HMAC-SHA1 imzalı RPC) +
   FeiLin cihaz barmaq izi (deviceToken) + şifrələnmiş sürüşmə trayektoriyası (data).
   → Native replikasiya mümkün deyil (kommersiya anti-bot şifrələməsi).
3. **OAUTH yolu**: /auth/oauth/authorize yalnız z.ai-nin öz klientləri üçün (subscribe) — bağlı.
4. **Upload**: `POST /api/v1/storage/pfs/files/upload` (multipart "file") →
   `{code:0, data:{file:{id,...}}}` → content massivi: image_url/video_url/file_url (id ilə).
   Qonaq üçün 403 — yalnız hesablı token ilə.
5. **Dark tema palette-i**: saytın index CSS-indən (.dark bloku) dəqiq çıxarıldı.
6. **Risk idarəetməsi**: bu serverin IP-si (datacenter) bu günkü çoxsaylı testlərdən sonra
   FRONTEND_CAPTCHA_REQUIRED üçün işarələnib — tamamlama (completion) qonaq üçün bloklanır.
   Təmiz mobil IP-də (istifadəçinin telefonu) bu aktiv olmamalıdır. Hesablı token ilə
   adətən tələb olunmur.

### v6.0 memarlıq
- **Baza**: v4.0 (Slint 1.18 + NativeActivity + Skia) — sübut olunmuş API qatı
  (imza HMAC-SHA256, WAF retry, SSE watchdog, 401 bərpası) qorunub saxlanıldı.
- **WebView yoxdur**: src-tauri (v5.x) tamamilə silindi.
- **Giriş**: tam ekran login səhifəsi (saytın /auth replikası):
  - Addım 0: Continue with Google / Email / Github + Skip for now
  - Addım 1: Email + şifrə (native) → POST signin → uğurlusa birbaşa daxil ol
  - Addım 2 (captcha fallback): sistem brauzerində (ACTION_VIEW, WebView YOX) bir dəfə
    giriş + clipboard-dan JWT-nin AVTOMATİK aşkarlanması (3s poll, "eyJ" + uzunluq)
  - Addım 3: token əl ilə yapışdır
- **Dark tema standart** (saytın dəqiq palette-i) + light dəstəyi (Theme.dark flag).
- **Fayl əlavələri**: MediaStore native seçici (JNI ContentResolver.query: Images/Video/Downloads)
  → icazə istəyi (READ_MEDIA_*) → ContentResolver.openInputStream → multipart upload →
  content massivi ilə göndəriş → server tarixçəsində də saxlanılır (chat update).
- **Avtomatik yenidən göndərmə**: RETRYABLE xətalar (busy/rate/WAF/5xx/şəbəkə) üçün
  4 cəhd, eksponensial gecikmə, FAYLLAR VƏ MESAJ SAXLANILIR. UI "Retrying automatically…".
- **Klaviatura fix**: Theme.NoTitleBar.Fullscreen → Theme.Material.NoActionBar
  (FLAG_FULLSCREEN + adjustResize klassik conflict-i aradan qaldırıldı) + targetSdk 34
  (Android 15 məcburi edge-to-edge-dən yayınma — sistemin inset idarəetməsi qalır).
- **Rəsmi loqo**: v5.1-dən adaptive + monochrome ikonlar port edildi (bütün DPI-lər).
- **Sürətli açılış**: boot → guest auth paralel, model siyahısı arxa planda, splash native.

### Fayl struktur
```
src/api.rs       — HTTP + imza + signin + upload (UploadedFile, media_kind, human_size)
src/stream.rs    — SSE + files content massivi + is_retryable_error (avtomatik retry)
src/controller.rs— login axınları, əlavələr, clipboard poll, retry, bütün callback-lər
src/picker.rs    — JNI: MediaStore, ACTION_VIEW, clipboard oxu, icazələr
ui/login.slint   — 4 addımlı giriş səhifəsi
ui/theme.slint   — dark+light (sayt CSS palette-i)
ui/chat.slint    — əlavə önizləmələri (chip-lər), retry göstəricisi
ui/modals.slint  — ModelPopup + FilePickerModal (MediaStore siyahısı)
ui/sidebar.slint — hesab sırası (sign out / sign in)
```

### Yoxlamalar
- Desktop build: təmiz (0 xəbərdarlıq) ✓
- api_smoke (headless): guest auth ✓, models ✓ (GLM-5.3-Flash x-preview-l), chats/new ✓,
  completion — IP işarələndiyindən captcha sorğusu (gözlənilən, təmiz IP-də olmayacaq) ⚠
- Android target: aapt2 + NDK keçidi CI-də (eyni sxem v4.0-dan sübut olunub)
- CI: v6.0.0-beta.N, imzalı, pre-release

## 2026-10-31 — Mərhələ 1: Performans optimizasiyası (ölçüyə əsaslanan)

### Baseline (host x86_64 release, 24KB cavab / 120 flush)
- tam parse/flush: 67µs orta, 142µs ən pis | model qurumu: 16µs | mətn clone: 5µs
- HƏR flush-da ~337 BlockRow yenidən qurulurdu (əsl jank mənbəyi)
- push_all clone: 100 mesaj ≈ 130µs + 0.7MB RAM
- APK 9.9MB, .so 19.38MB, assets 2.28MB (fonts 1.76), state.json 151B (nadir yazma)

### Dəyişikliklər (4 commit)
1. markdown: parse_spanned + open_from (artımlı; 7 test, hər-prefiks konsistensiyası)
2. controller/stream: 60ms flush + quyruq parse (1.7µs — 39.9x az CPU) +
   yalnız-son-blok model yeniləməsi (~89x az sıra churn) + Reset (kəsinti
   bərpasında qismən mətn bug-fix) + optimistiq boot (qayıdan istifadəçi
   şəbəkə gözləmir) + warmup paralel + qonaq sessiya davamlılığı + Arc mesajlar
   + dot-thread qapısı + jitter
3. api/store/picker: upload Vec-move + şəkil downscale (>1MB & >2048px →
   2048/JPEG q82) + çünklü JNI oxu (~2x az transient RAM) + atomik state.json
   (tmp+fsync+rename) + uuid crate silindi + reqwest dedup + ZAI_BASE
4. sec: clipboard token yalnız açıq razılıqla (avto-signin ləğv) +
   allowBackup=false

### Nəticə (host, eyni iş yükü)
- flush CPU: 67→1.7µs (39.9x) | model ops: 337→3.8/flush (~89x)
- bitişdə bütün-siyahı rebuild → yalnız son sıra
- açılış: kritik yolda 2 ardıcıl şəbəkə çağırışı → 0 (optimistiq) / 1 RTT (yeni)

### CI
- build.yml: clippy (host+android, -D warnings) + test + ölçü hesabatı addımları
- size-matrix.yml: opt-level × LTO paralel ölçüm (workflow_dispatch)

### Cihazda yoxlanmalı (adb)
- ZAI_TIMING=1: `adb shell am start -W -n zai.mobile/android.app.NativeActivity` +
  `adb logcat -s timing` → açılış xətti, ilk token, upload vaxtları
- RAM/jank: `adb shell dumpsys gfxinfo zai.mobile` (axın zamanı)
