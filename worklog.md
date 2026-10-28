# ZaiMobile v4.0 — İş jurnalı (2026-10-28/29)

## Kök səbəb analizi (nə üçün köhnə app işləmirdi)
1. **egui/eframe Android dəstəyi etibarsızdır** — IME (klaviatura) və GPU init cihazdan-cihaza qırılır
2. **chat.z.ai serveri FRONTEND_CAPTCHA_REQUIRED tələb etməyə başlayıb** — köhnə app bunu işləmirdi
3. **Alibaba CDN WAF 405 blokları** — veb app-də belə yoxdur, köhnə app-də retry yox idi

## v4.0 həlli
- **UI**: egui → **Slint 1.18** (backend-android-activity-06 = NativeActivity + Skia renderer).
  Səbəb: Slint-in rəsmi, kommersiya dəstəkli Android backend-i (IME, clipboard, vs. javahelper-də).
- **Şriftlər** `.slint` faylında `import "…ttf"` ilə binariyə gömülür (sistem şrift riski sıfır).
- **Captcha araşdırması** (tam): AliyunCaptcha v3 protokolu deşifrə edildi
  (InitCaptchaV3/VerifyCaptchaV3, SceneId=didk33e0, puzzle template-matching + kvadratik
  slider xəritələməsi piece(d)=0.00355d²+0.0769d) — amma `data` sahəsi şifrələnir və
  deviceToken FeiLin brauzer barmaq izindən gəlir → native həll mümkün deyil.
  HƏLL: captcha yalnız riskli IP-lərdə tələb olunur → app 3 giriş yolu verir
  (email+şifrə / token yapışdır / qonaq) + captcha xətasında aydın yönləndirmə.
- **WAF retry**: bütün endpoint-lərdə 405/403 HTML → 12 cəhd, 2.5s→9s backoff (veb app-də yoxdur!).
- **Canlı testlər** (desktop preview + Xvfb + VLM):
  - UI render: nav/welcome/kart şəbəkəsi/composer — veb dizaynı ilə uyğun ✓
  - Tam axın: qonaq token → modellər → chats/new (retry keçdi!) → SSE → captcha aşkarlandı ✓
  - Xəta UI: banner + qırmızı mətn düzgün render olunur ✓

## Build/CI
- CI sübut olunub: aarch64-linux-android + aapt2 + apksigner + beta release
- v4.0.0-beta.N, prerelease, make_latest: false
