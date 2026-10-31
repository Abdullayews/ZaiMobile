// ============================================================================
// DƏRS 20 — FULLSCREEN / IMMERSIVE MODE + INSETS ★★★
// ----------------------------------------------------------------------------
// ZaiMobile-ın ƏSAS tələbi: tam ekran. Rust+Slint versiyasının ölüm səbəbi
// pəncərənin 915dp-ə PİNLƏNMƏSİ idi. Kotlin Activity-də bu struktur olaraq
// MÜMKÜN DEYİL — Activity bütün pəncərəni tutur. Burada:
//   1) system bar-ları GİZLƏT (immersive sticky)
//   2) insets-lərdən xəbərdar ol (məzmun bar-ların ALTINA keçməsin)
//   3) klaviatura (IME) insets-i ilə kompozitoru itələ
// android.jar-ə qarşı kompilyasiya olunur.
// ============================================================================

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout

// ---------- 1. IMMERSIVE — köhnə (API < 30) və yeni (API 30+) yollar ----------
// ƏSAS QAYDA: istifadəçi bar-ları müvəqqəti göstərəndə (swipe) FOKUS itirilir
// və bar-lar görünür → onWindowFocusChanged-də YENİDƏN gizlət!
fun Activity.immersiveGizlət() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {         // API 30+
        window.setDecorFitsSystemWindows(false)                   // məzmun bar-ların üstünə çəkilir
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior =                                  // swipe ilə qısa görünsün
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")                                   // API < 30 üçün hələ də düzgündür
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or                // swipe → müvəqqəti görünür
            View.SYSTEM_UI_FLAG_FULLSCREEN or                      // status bar gizli
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or                 // nav bar gizli
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or                   // layout dəyişməsin
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    // Theme-də (v27+/v29+): şəffaf status/nav bar + cutout=shortEdges — res/values/themes.xml
}

// ---------- 2. INSETS — "barların altına keçmə" probleminin həlli ----------
// Edge-to-edge olanda məzmun status barın ALTINDAN başlayır. Görünən hissəni
// insets-lə qoru: üst = status bar hündürlüyü, alt = nav bar hündürlüyü.
fun View.tətbiqInsets(üst: ViewGroup? = null, alt: ViewGroup? = null) {
    setOnApplyWindowInsetsListener { v, insets ->
        val sistemÜst = insets.getInsets(WindowInsets.Type.systemBars()).top
        val sistemAlt = insets.getInsets(WindowInsets.Type.systemBars()).bottom
        // API 30+: klaviatura (IME) insets-i də mövcuddur — klaviatura açılanda
        // kompozitor yuxarı itələnir (adjustResize-in müasir ekvivalenti):
        val klaviatura = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        üst?.setPadding(üst.paddingLeft, sistemÜst, üst.paddingRight, üst.paddingTop)
        alt?.setPadding(alt.paddingLeft, alt.paddingTop, alt.paddingRight,
            maxOf(sistemAlt, klaviatura))
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            insets.consumeSystemWindowInsets() else insets
    }
}

// ---------- 3. FULLSCREEN ACTIVITY — ZaiMobile modeli ----------
class Dərs20Activity : Activity() {

    lateinit var başlıqZolağı: LinearLayout      // üst (padding = status inset)
    lateinit var kompozitor: LinearLayout        // alt (padding = nav/ime inset)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme (res/values/themes.xml) artıq verir:
        //   windowDrawsSystemBarBackgrounds=true, statusBar/navBar = transparent,
        //   windowBackground = #161616
        // Cutout (kamera dəliyi) — v27+:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // windowSoftInputMode="adjustResize" (manifest) — klaviatura ölçünü sıxışdırır

        val kök = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161616"))
        }
        başlıqZolağı = LinearLayout(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        kompozitor = LinearLayout(this).apply { setBackgroundColor(Color.parseColor("#2b2b2b")) }
        val məzmun = LinearLayout(this)
        kök.addView(başlıqZolağı, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        kök.addView(məzmun, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        kök.addView(kompozitor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(kök)

        // Insets + immersive:
        kök.tətbiqInsets(üst = başlıqZolağı, alt = kompozitor)
        immersiveGizlət()
    }

    // ---------- ƏSAS: fokus qayıdanda yenidən gizlət ----------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersiveGizlət()         // swipe-dən sonra bar-lar qayıdır → GİZLƏT
    }
}

// ---------- 4. NİYƏ BU DƏRS KRİTİKDİR? ----------
// Slint versiyası: pəncərə 915dp sabit → ekranın 73.6%-i — "full screen yoxdur".
// Kotlin Activity: setContentView ilə View HƏMİŞƏ tam pəncərəni tutur;
// immersive + insets + adjustResize = HƏQİQİ tam ekran tətbiqi.
// Növbəti addım: ZaiMobile-ın özündə bu kodun istehsal versiyası.
