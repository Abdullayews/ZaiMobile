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

## 2026-10-31 — Mərhələ 1.6: size-matrix qərarı + beta.29 auditı

### Size-matrix nəticələri (run 41126551848, aarch64-linux-android, .so bayt)
| Variant | .so ölçüsü | Δ (hazırkına) | Build vaxtı |
|---|---|---|---|
| opt2-thin (əvvəlki) | 19,517,720 | — | 205s |
| opt3-thin | 19,856,056 | +338,336 | 209s |
| **opts-thin (seçilib)** | **19,365,240** | **−152,480** | **120s** |
| optz-thin | 18,875,032 | −642,688 | 173s |
| opt2-fat | 19,518,136 | +416 | 142s |
| opts-fat | 19,367,736 | +2,496 | 185s |

**Qərar: opt-level="s", lto="thin" (codegen-units=1, strip, abort saxlanıldı)**
- fat-LTO ölçüyə demək olar heç nə vermir (+416 bayt opt2, +2.5KB opts) → rədd
- opt3 həm ən böyük (+338KB), həm ən yavaş build → rədd
- optz ən kiçik (−643KB), amma runtime cihazda ölçülməyib → ehtiyatlı seçim "s";
  istilik yolu (SSE parse 1.7µs / 60ms flush pəncərəsi) üçün ~35,000x ehtiyat var,
  "s" vs "2" runtime fərqi bu iş yükü üçün noise səviyyəsindədir
- Yan təsir: CI build 205s→120s (release-lar daha tez çıxır)

### beta.29 auditı (2026-10-30 21:32 UTC)
- CI 16/16 addım yaşıl: clippy host+android (-D warnings), testlər, build,
  ölçü hesabatı, imza+verify, artifakt, release
- APK 10,021,273 bayt (9.56MB), versionName 6.0.0-beta.29, versionCode 29,
  V3.0 imza: CN=Z.ai Mobile, OU=Mobile, O=Abdullayews, L=Baku, C=AZ
  (SHA-256 b08d9f43a7ab85acb60976a695f512215fd16944230e7d54d12f8a85eddf0111)
- .so 19,534,104 bayt — CI hesabatı ilə bayt-bayt üst-üstə düşür
- ZIP integriteti OK, 29 fayl (bütün DPI ikonlar + adaptive + monochrome),
  APK Sig Block v2/v3 mövcud
- Düzəlişlər: hesabatda `rg`→`grep` (runner-da ripgrep yox idi, sayı səssizcə
  0 çıxırdı), release qeydləri clipboard razılığı əks etdirir (aae013b-dən bəri
  avtomatik deyil)

### beta.30 (opt-level "s" təsbiqi)
- APK: 9.56MB → **9.15MB** (−410KB, −4.3%) | .so: 19,534,104 → 19,377,528
  (−156KB — matrix proqnozu −152KB ilə üst-üstə düşür)
- Asılılıq sayı artıq düzgün: 323 (əvvəl səssizcə 0 idi)
- CI 16/16 addım yaşıl, imza eyni keystore (CN=Z.ai Mobile, O=Abdullayews)

## 2026-10-31 — Mərhələ 1.3/1.4/1.5: axın həyat dövrü + lazy markdown

### Axın həyat dövrü (1.4 — "keçid zamanı köhnə axını dayandır")
PROBLEM: axın zamanı başqa söhbətə keçid / yeni chat / sign-out köhnə axını
dayandırmırdı. Emit `messages.last_mut()`-a yazdığından köhnə axının
tokenləri YENİ söhbətin son mesajına «qan qarıışırdır»; retry loop-u
`core.chat_id`-ni yenidən oxuyduğundan köhnə prompt yeni söhbətin
chat_id-si ilə göndərilirdi; köhnə job-un finish-i yeni job-un streaming
vəziyyətini sıfırlayırdı.

HƏLL (mərhələli müdafiə):
1. `stop_current_job()` — open_conversation / new_chat / sign_out /
   spawn_send başlanğıcında: stop bayrağı + current_job-in təmizlənməsi
2. `is_current_job()` (Arc::ptr_eq) — emit-in ƏVVƏLİNDƏ (bütün hadisələr
   udulur — stop bayrağı bloklanan şəbəkə oxunda gec görülür), finish_stream
   və mark_retrying girişində, hər retry yenidən göndərməsindən əvvəl
3. İstifadəçinin öz stop düyməsi current_job-i təmizləmir → köhnə
   davranış qorunur (qismən mətn saxlanılır, xəta göstərilmir)

### Lazy markdown parse (1.3/1.5 — uzun söhbət)
PROBLEM: `push_all_messages` tarixçədəki HƏR assistant mesajını dərhal
parse edib VecModel düzəldirdi — 200+ mesajlıq söhbətdə açılış vaxtı və
RAM bütün tarixçəyə bağlı idi (ListView-in virtualizasiyası delegate-ləri
təxirə salırdı, amma model başdan dolu idi).

HƏLL:
- `raw_to_ui(eager=false)` — assistant sətirləri BOŞ (amma həqiqi) VecModel
  alır; axın flush-u downcast-i işləsin deyə boş default ModelRc YOX
- `MessageRow.init` → `App.ensure-blocks-parsed(index)` → Rust yalnız həmin
  sətri parse edib `set_row_data` ilə doldurur (UI thread-də ~15-70µs/sətir)
- `update_last_message` (axın bitişi) eager qalır — init mövcud delegate-də
  yenidən atəşlənmir
- scroll-back → sətir yenidən yaradılır → init → «artıq parse olunub»
  yoxlaması → cheap early-return

Nəticə (struktur): açılışda N mesajlıq parse → yalnız görünən ~10 sətir;
RAM artımı görünən sətirlərlə məhdudlaşır. Kəmiyyət ölçümü cihazda
(adb dumpsys meminfo + gfxinfo) aparılmalıdır.

## Loop sessiyası — İterasiya 1: UI yerləşdirmə (welcome grid + composer)

PROBLEM 1 (welcome grid sıxılması — istifadəçi: «yerləşdirmələr doğru deyil»):
SuggestionCard içindəki `Image { width: 96px; }` yalnız en qoyurdu. Slint-də
explicit width həm də elementin MAX-width-i kimi layout-a yayılır → kartın
max eni 96+24=120px-ə çatırdı → GridLayout sütunu genişlənə bilmir, bütün
grid SOLDA 119px kartlarla sıxılırdı (sağda 138px boşluq), şəkillər solda
hizalanırdı, PPT kartının tünd qradient kartı qonşu kartın üstünə «X»-ə
bənzər ləkə kimi görünürdü.

SÜBUT (izolyasiya testləri, /home/z/my-project/scripts/imgtest):
- T5a (kartda preferred≈10px məzmun): grid DOLUR (175px kartlar)
- T5b/T5c/T5d (fixed-width uşaqlar): kartlar PREFERRED ölçüdə sıxılır
- F2/F4/F5/F6 (max-width / HLayout-center / %-width): DOLUR
→ səbəb-nəticə əlaqəsi sübuta bağlandı

HƏLL (ui/chat.slint):
- SuggestionCard: `in property <length> img-h` + `HorizontalLayout {
  alignment: center; Image { width: 96px; height: img-h } }` — həm mərkəzləmə,
  həm sütunun genişlənməsi (HLayout alignment:center kartın max-width-ini
  uşağın fixed eninə bağlamır)
- img-h hər PNG-nin təbii aspektinə görə: fullstack 55, writing 54,
  data 66, slides 55, web 55 (en/çəkil nisbəti, en=96px) — image-fit:contain
  YOX (femtovg-də sınaqlarla təsdiqlənmiş yerləşdirmə qərizası)

PROBLEM 2 (composer): TextInput `Math.min(preferred, 160px)` hündürlüklə
kəsilirdi — uzun mətn yazanda kursor görünməz olurdu, scroll yox idi.

HƏLL: Flickable + TextInput tam preferred hündürlükdə + rəsmi Slint
TextEditBase-in `cursor-position-changed` sürüşdurmə məntiqi (chat.slint).

QEYD (ölçmə aləti): scripts/shot.sh artıq `-draw_mouse 0` istifadə edir —
ffmpeg x11grab default olaraq X11 kursorunu (mərkəzdə «X» şəkilli default
kursor) görüntüyə çəkir və bu, yanlış «X artefaktı» diaqnozuna gətirib
çıxarırdı (izolyasiya testlərində sübut olundu: «X» həmişə pəncərə
mərkəzində idi, -draw_mouse 0 ilə yox oldu).

Yoxlama: cargo check/clippy -D warnings/testlər keçdi; desktop preview
(Xvfb) — kartlar 25..199 və 212..386 (175px, tam en), şəkillər mərkəzdə,
VLM audit: artefakt yoxdur.

## Loop sessiyası — İterasiya 2: axın məzmun düzgünlüyü (context + bərpa)

PROBLEM 1 (tarixçə kəsimi — controller.rs run_send): göndərmədən əvvəl
collect_history(bütün messages) + truncate(len-2) edilirdi. Boş assistant
placeholder collect_history tərəfindən ZATƏN süzülür → truncate son REAL
assistant cavabını da kəsirdi. Nəticə: hər növbəti turda model öz əvvəlki
cavabını KONTROKSDƏ GÖRMÜRDÜ (u1,a1,u2 + yeni prompt — a2 yox idi), cavab
keyfiyyəti tədricən düşürdü.

HƏLL: siyahının son 2 sətri (yeni user + placeholder) başdan dilimlənir:
`collect_history(&msgs[..len-2])` → tam turlar qorunur. 2 regressiya testi
əlavə olundu (A2-nin qalması + xətalı sətirlərin süzülməsi).

PROBLEM 2 (SSE Reset ikilənməsi — controller.rs emit): şəbəkə kəsintisindən
sonra avtomatik bərpa `StreamEvent::Reset` göndərir, handler isə yalnız
markdown parse ofsetlərini (stable/base) sıfırlayırdı — RawMsg.text
tƏMİZLƏNMİRDİ. Bərpa axını cavabı başdan yenidən çatdırır → mətn
"HelloHello world…" kimi İKİLƏŞİRDİ (həm UI-da, həm copy-də, həm
növbəti turun kontekstində).

HƏLL: Reset handlerində son mesajın text-i təmizlənir və UI blokları
dərhal yenilənir (update_last_message — köhnə bloklar gözləmədən silinir).

## Loop sessiyası — İterasiya 3: ölü-bağlantı aşkarlanması + əlavə (attachment) düzəlişi

PROBLEM 1 (donmuş SSE — api.rs): blocking SSE oxunda read-timeout yox idi.
stream.rs-dəki 150s idle watchdog yalnız SƏTİR GƏLƏNDƏ yoxlanıla bilirdi —
server qəfil susarsa (mobil şəbəkə black-hole) reader.lines() bloklayır,
watchdown heç vaxt çatmır, axın thread-i QALICI asılı qalır (UI «streaming»
vəziyyətində donur, stop belə işləmir).

HƏLL: TCP səviyyəsində ölü-bağlantı aşkarlanması — `tcp_keepalive(60s)` +
`tcp_user_timeout(180s)` (Linux/Android). Zondlar ACK alınmayanda bağlantı
xəta ilə bağlanır → bloklanan oxu qayıdır → RETRY bərpası işə düşür.
Normal «server düşünür» fasiləsində zondlar ACK alınır → yalançı kəsinti
yoxdur. QEYD: reqwest blocking ClientBuilder-də read_timeout API-si yoxdur
(yalnız async-də) — ona görə TCP səviyyəli həll seçildi.

PROBLEM 2 (əlavələr — controller.rs): ÜÇ bir-birilə bağlı bug:
a) `uploads` (bitmiş) və `upload_state` (pending daxil) AYRI siyahılar idi —
   UI indeksi `uploads`-a görə idi, status `upload_state`-dən adla tapılırdı;
   silmə eyni indekslə hər ikisindən edilirdi → fərqli quruluşlara görə
   YANLIŞ element silinirdi (pending upload varken).
b) Pending upload çipləri UI-da heç göstərilmirdi (istifadəçi fayl seçəndə
   heç nə olmamış kimi görünürdü — «…» statuslu çip dead code idi).
c) on_remove_attachment UI modelini yeniləmirdi → silinən çip ekranda
   QALIRDI (və növbəti silmə köhnə indekslə yanlış gedirdi).

HƏLL: vahid `UploadEntry { local_id, name, media, size, status, file }`
strukturu: pending dərhal «…» çipi ilə görünür; silmə yerli unikal id ilə
(eyni adlı fayllar təhlükəsiz); UI dərhal refresh olunur; send yalnız
HAZIR (file=Some) entry-ləri göndərir.

Yoxlama: cargo check/clippy/test (9 test) keçdi. CANLI send sınağı:
sandbox IP-si /api/v2/chat/completions POST üçün WAF blokludur (curl:
POST→403, GET auths→200) — mühit məhdudiyyəti, kod regressiyası deyil;
real cihazda (fərqli IP) yoxlanmalıdır.

## Loop sessiyası — İterasiya 4: markdown nömrəli siyahılar + tarixçə səhifələməsi + kiçik düzəlişlər

1. Nömrəli siyahı (markdown.rs): yalnız təkrəqəmli "1. " tanınırdı — "10. ",
   "123. " paraqraf kimi render olunurdu. İndi istənilən rəqəm sayı
   tanınır; "1.5" kimi versiya mətni siyahı SAYILMIR (". " boşluq tələb
   edir). Test əlavə olundu (indi 10 test).

2. Tarixçə səhifələməsi (api.rs conversations): yalnız ?page=1 çəkilirdi —
   1-ci səhifədən çox söhbəti olan istifadəçinin tarixçəsi kəsilirdi.
   İndi səhifələr ardıcıl çəkilir (maks 10 səhifə / 300 söhbət); API
   səhifələməni iqnor edib eyni cavabı qaytarsa (ilk id təkrarı) dayanır;
   qısa səhifə = sonuncu.

3. Lazy-parse keşi (state.slint + controller.rs): "artıq parse olunub"
   yoxlaması `blocks.row_count() > 0` idi — "***" kimi 0 bloka parse
   olunan mətnlər hər scroll-da yenidən parse olunurdu. ChatMessage-ə
   `parsed` flag-i əlavə olundu; user/xəta sətirləri hazır doğulur.

4. Sidebar silmə toxunma sahəsi (sidebar.slint): del TouchArea 54px idi,
   sətirin daxili hündürlüyü 42px → 6px qonşu sətirlərin üstünə daşırdı
   (yanlış silmə riski). 42px-ə salındı.

5. chat_update (stream.rs): reasoning_effort həmişə "high" hardkod
   edilirdi — seçilmiş effort indi saxlanılır.

## Loop sessiyası — İterasiya 5: açılış flaşı + shutdown-panik + popup clamp

1. AÇILIŞ FLAŞI (state.slint): `booting` heç vaxt true edilmirdi (yalnız
   set_booting(false) var idi) → splash dead-code idi və saxlanmış sessiyası
   olan istifadəçi hər açılışda qısa LOGİN EKRANI FLAŞI görürdü, sonra
   chat-ə düşürdü. İndi booting=true başlayır → splash dərhal göstərilir,
   sessiya tətbiq olunana qədər heç bir ekran flaş etmir.

2. SHUTDOWN PANİKİ (controller.rs): 6 UI callback-də `weak.upgrade().unwrap()`
   var idi — app bağlanarkən gecikən callback None qaytarır → unwrap →
   panic → panic="abort" bütün PROSESSİ öldürürdü (təmiz çıxış əvəzinə
   crash). Hamısı zərif `else { return }` ilə əvəz olundu.

3. ModelPopup clamp (modals.slint): uzun model siyahısında popup-un y-i
   mənfi ola bilirdi (başlıq ekrandan yuxarıda qalırdı). Math.max(60px, …).

## Loop sessiyası — İterasiya 6: SSE multi-line + sign-in təmizliyi

1. SSE MULTI-LINE (stream.rs): SSE spesifikasiyası bir hadisəni bir neçə
   `data:` sətirinə bölə bilər — əvvəl hər sətir ayrıca JSON parse
   olunurdu, bölünmüş hadisələr SİLİNİRDİ (məzmun itkisi). İndi: sətir
   tək başına JSON deyilsə, növbəti sətirlə birləşdirilib təkrar sınaq
   olunur (tək-sətirli normal axın dəyişməz — sürətli yol qorunur).

2. SİGN-IN TƏMİZLİYİ (controller.rs): qonaq kimi söhbət edib sonra hesaba
   daxil olanda köhnə QONAQ mesajları ekranda qalırdı VƏ yeni hesabın yeni
   chat-inə kontekst kimi göndərilirdi (yalnız chat_id sıfırlanırdı).
   İndi uğurlu girişin üç yolunda da (email, token, clipboard-token)
   reset_conversation: mesajlar, başlıq, input, UI modeli tam təmizlənir,
   aktiv axın dayandırılır. Sign-out yolu da eyni helper-dən istifadə edir.

## Loop sessiyası — İterasiya 7: "tap to retry" işləmirdi + ölü state təmizliyi

1. RETRY TOXUNMASI (chat.slint + controller.rs): xəta mesajı "Tap to retry
   manually" deyirdi, amma (a) sətirdə TouchArea YOX İDİ — toxunma heç nə
   etmirdi; (b) on_retry_send son mesajın role=="user" olmasını gözləyirdi,
   halbuki axının sonunda XETA ASSISTANT sətri dayanır → şərt heç vaxt
   doğru olmurdu. İndi: xəta sətri tam toxunulandır (App.retry-send) və
   Rust tərəfi xətalı assistant sətirlərini silib son USER mesajını
   (əlavələri ilə) təkrar göndərir.

2. Ölü state-lər (state.slint): need-refresh (heç yerde oxunmur/yazılmır)
   və signed-in (yalnız yazılır, heç yerde oxunmur) silindi.

## Loop sessiyası — İterasiya 8: açıq söhbətin silinməsi + asset təmizliyi

1. AÇIQ SÖHBƏTİ SİLMƏK (controller.rs): istifadəçi hazırda açıq olan söhbəti
   siləndə chat_id silinmiş söhbətə işarə etməyə davam edirdi → növbəti
   göndəriş silinmiş chat_id ilə gedib xəta verərdi. İndi: silinən id cari
   söhbətdirsə, söhbət tam sıfırlanır (reset_conversation).

2. assets/cards/logo.svg (11KB) — heç yerdə istifadə olunmur, silindi.

## Loop sessiyası — İterasiya 9: binary ölçüsü −1.29MB (font subset + kart 2x)

ÖLÇMƏ (host bin, eyni profil): 19,641,336 → 18,289,656 bayt (−1,351,680;
−6.9%).

1. FONT SUBSET (assets/fonts): 4 TTF tam gömülürdü (1,714 KB). pyftsubset
   ilə işlədiyi diapazonlar saxlandı (Basic Latin, Latin-1, Latin Ext A/B —
   Əə Ğğ Iı İi, IPA, combining, Yunan, Kirill, durğu, valyuta, riyazi,
   box-drawing, misc symbols), hinting atıldı (mobil yüksək DPI), ad cədvəli
   minimala endirildi → 696 KB (−59%). VLM render doğrulaması: AZ/TR/Kiril/
   Yunan/simvollar hamısı düzgün; ₼ (U+20BC) orijinal Carlito-da ZATƏN
   yox idi (regresiya yoxdur).

2. KART ŞƏKİLLƏRİ (assets/cards): 688-832px PNG-lər 96px göstərilirdi →
   192px (2x) LANCZOS endirildi: 438 KB → 134 KB (−69%). VLM: kəskinlik
   və layout qorunur.

3. İstifadəsiz 13 ikon PNG-si silindi (yalnız google/github istifadə
   olunur — Path ikonlara keçid hələ v4-də olub).

## Loop sessiyası — İterasiya 10: upload ön-yoxlama + nav başlıq sinxronu

1. BÖYÜK FAYL OOM QORUMASI (picker.rs + controller.rs): 60MB limiti
   fayl TAMAMı oxunduqdan SONRA yoxlanılırdı — 500MB video əvvəlcə tam
   RAM-a düşür, sonra rədd edilirdi (köhnə cihazlarda OOM/kill). İndi
   read_uri_bytes _display_name ilə bir sorğuda _size DA oxuyur və
   60MB-dən böyük faylı oxumadan rədd edir (FILE_TOO_LARGE:<size>).
   Controller oxunuş ölçüsünü toast-da göstərir; post-oxu yoxlaması
   (-1 halı üçün) fallback qalır.

2. NAV BAŞLIQ SINXRONU (controller.rs): ilk cavabdan sonra server söhbət
   başlığını avtomatik yaratdırır, amma nav-da "New Chat" qalırdı.
   spawn_load_conversations aktiv söhbətin boş olmayan başlığını tapıb
   nav + core.chat_title-a tətbiq edir.

3. (Sınaqdan keçməyən optimallaşdırma ləğv edildi: blocking multipart
   Part::bytes Bytes qəbul etmir — retry başına bir memcpy qalır,
   ölçüsü əhəmiyyətsizdir.)

## Loop sessiyası — İterasiya 11: FULL SCREEN + welcome geometriyası (veb 1:1)

Şikayət: "Düzəlməyib" + "full screen mode". Piksel analiz (1080x2400
screenshot): UI yalnız ekranın 73.6%-ini tuturdu — pəncərə DƏQİQ 915dp
hündürlükdə idi (app.slint-də elan edilən `height: 915px`), cihazın məntiqi
ekranı isə 559x1241dp (density 1.93). Səbəb: Slint-də Window üzrə SABİT
`width/height` pəncərəni həmin ölçüyə PINLƏYİR (dok: "Setting the width will
result in a fixed width") — native window ölçüsü (tam ekran) ignore olunurdu.
Boşluq riyazı da bunu təsdiqləyir: 2400px − 915dp×1.93 = 633px qara zona =
ölçülmüş 633px.

1. FULL SCREEN (app.slint + res/): `width/height` → `preferred-width/
   preferred-height` (Android native window ölçüsünü götürür, desktop 412x915
   açılır). Edge-to-edge: nav bloku status-inset qədər uzanır, məzmun
   `safe-area-insets` (Slint 1.18 Window xüsusiyyəti, backend systemBars+
   cutout verir) ilə aşağı sürüşür; footer nav-inset qədər yuxarıdan
   qorunur. Theme res: Theme.Zai (şəffaf status/nav bar-lar,
   windowDrawsSystemBarBackgrounds, v27 shortEdges cutout, v29 contrast
   off) — vivi-music modelindəki kimi. Və: chat/login/sidebar inset-ləri
   qəbul edir (desktop-da 0).

2. WELCOME VEYB-1:1 (chat.z.ai 412x915 və 559x1241 viewport-da DOM ölçülüb
   — agent-browser + computed styles): başlıq 24px/700 serif SOLA yaslı
   (34px mərkəzləmiş idi — səhv), padding-top 24 (cihazda status24+nav48+24
   = 96px = vebdəki h1 Y-dəyişimi), grid 2 sütun (2+2+1, veb də son xananı
   boş qoyur!), gap 16, kart = 140px box (r16, --bg-overlay #2b2b2b, kənar
   yoxdur) + 6 + 18px label (13px/500 text-2); şəkil regionu inset-x13 /
   top25 / 116px, image-fit:contain (veb object-contain — per-kart
   img-h tuning-ləri silindi). Kart PNG-lər 192px→432px (3x; istifadəçinin
   cihazında 217.5dp×1.93=420px fiziki — 1:1 kəskin; 576K, +438K binary).

3. COMPOSER VEYB-1:1: səth --color-bg-bg (#2b2b2b; əvvəl səhv surface
   #202020 idi), h 132 = 12+52+24+30+12+2border (textarea min-h 52, font
   16), alt sıra 30px: plus (veb "More") solda, model chip DÜZ mətn+şevron
   (16px, həbs olunan pill silindi), göndər 30x30 dairə (disabled:
   --bg-disabled + text-2 ikon). Ekran kənarı 28px (veb kimi; 14 idi).

4. FOOTER: veb mətni ilə əvəz olundu — "Generated by AI. For reference
   only." + "Terms of Service and Privacy Policy" (10px, 2 sətir, gap 8,
   mb 2+nav-inset). Köhnə "By using Z.ai you agree..." mətni vebsaytda
   YOXDUR (ölçüldü).

5. GİZLİ LAYOUT BUG (beta.41-də də var idi!): `alignment: start` stretch-
   spacer-ləri SÖNDÜRÜR — köhnə screenshot-da pill x339..483-də idi (sağda
   yox!). Nav və kompozitor sıralarından `alignment: start` silindi; düymələr
   artıq vebdəki kimi: pill/plus/model/göndər öz yerlərində. Nav ikonları
   text (ağ) → text-2 (veb #adadad dim), pill 32px/r6/14px (veb kimi).

6. Sign in pill: vebdəki kimi 32px hündürlük, radius 6, 14px (30px/r15/13px
   idi). Nav başlıq ikonları dim text-2.

Yoxlama: desktop render (Xvfb+ffmpeg, 412x915) piksel testi 21/23 (2
artefakt: AA kənarları) + VLM təsdiqi: hər element vebdəki mövqesində.
Vebsaytın özü 559x1241-də ölçüldü: kompozitor 1043..1175, footer
1199..1239 — bizim düstur eyni rəqəmləri verir (915−2−40−24=849 ≈
veb 412x915-dəki 849).
