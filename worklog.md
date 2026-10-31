# ZaiMobile — İş jurnalı

Sessiya qeydləri, xronoloji (yeni ən sonda). Format: `## <tarix> — <başlıq>`,
istəyə görə `### İstək` / `### PROBLEM` / `### HƏLL`, nömrəli siyahılar.

---

## 2026-10-31 — Sıfırdan yenidənqurma: "static data qadağandır"

### İstək

1. Köhnə APK və kodu TAM unut.
2. Məqsədi ağlında saxla və mükəmməl şəkildə gerçəkləşdir.
3. **Static data qadağandır** — heç bir saxta/fallback data göstərilməməlidir.

### PROBLEM (əvvəlki v7-də)

1. İstifadəçi: "proqram açılmır". Səbəb diaqnoz edilməmişdi.
2. Bu sessiyada ROBOLECTRIC açılış testi ilə **əsl səbəb tapıldı**:
   `window.setHideOverlayWindows(true)` → `SecurityException:
   Permission denial: HIDE_OVERLAY_WINDOWS` — köhnə beta-larda launch crash-inin
   dəqiq bu sinif olduğu görünür (icazə manifestdə olmayanda / bəzi cihazlarda).

### HƏLL — sıfırdan, spec-əsaslı (köhnə kod satır-satır oxunmadan silindi)

1. **Protokol qatı** (`protocol/`): Transport (HttpURLConnection; yeganə şəbəkə
   kəsiyi — testlərdə ScriptedTransport), ZaiHttp (WAF 405/403/429/509+HTML →
   2.5s×1.35ⁿ backoff, hər cəhd üçün FRESH imza; acw_tc cookie jar), Signature
   (HMAC-SHA256, RFC 4231 vektoru ilə test), Auth (guest/signin/verify + captcha
   aşkarlama), Models (canlı siyahı, qonaq filtri — fallback YOXDUR), Chats
   (pagination ≤10 səh/300, currentId→parentId zənciri ≤500), Completion (SSE:
   multi-line join, [DONE]/done, /data/error yolları, 150s watchdog =
   readTimeout), Upload (SAF + 60MB + 2048px downscale), Errors (friendly
   xəritmə: outdated/rate_limit/global_limit/captcha).
2. **Session**: state.json atomik (tmp+fsync+rename), deviceId `uid_`+13 əbədi.
3. **ChatEngine**: chats/new → axın (4 daxili cəhd, hər təkrarda Reset +
   qismən mətn sıfırlanır) → 3 avto-təkrar (2.5s→×2) → tam tarixçə ilə update;
   stop = qismən mətn saxlanılır və yadda saxlanılır; qonaq 401 → bir dəfə
   self-heal; token yoxdursa (Bootsuz bərpa) avtomatik qonaq.
4. **UI**: BootActivity (splash→warm-up→verify/guest→Chat; xəta+Retry; crash
   hesabatı diaqoqu — stack kopyalanabilir), LoginActivity (email+şifrə →
   captcha → brauzer+token; clipboard `eyJ…` aşkarlama razılıqla), ChatActivity
   (toolbar+sidebar canlı siyahı, welcome "Send a message", markdown streaming,
   fazalar, model popup canlı `/api/models`, SAF əlavələr, tap-to-retry).
   setHideOverlayWindows → `runCatching` (crash lazımsına QADAĞDIR).
5. **Statik data ləğvi**: model fallback-ları, statik welcome kartları,
   statik başlıqlar — hamısı silindi; xəta halında düz mesaj + Retry.

### TEST (push-dan ƏVVƏL — məcburi)

1. `gradle test` → **38 test, 0 xəta** (imza vektoru, SSE, WAF, engine
   Reset/retry/401-heal, markdown, session, upload, smoke: app AÇILIR,
   tam göndərmə axını render olunur, login captcha→token, models-xəta,
   crash diaqoqu).
2. `LIVE_TEST=1` canlı: qonaq token ✔, modellər ✔, signin-captcha JSON ✔;
   tam dövr datacenter IP-dən WAF/captcha → SKIP (mühit; telefonda işləyir).
3. `assembleDebug/Release` → BUILD OK; aapt2 badging: icazələr yalnız
   INTERNET + HIDE_OVERLAY_WINDOWS; launcher BootActivity; release 1.16MB.
