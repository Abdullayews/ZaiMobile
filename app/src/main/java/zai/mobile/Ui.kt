package zai.mobile

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * chat.z.ai veb dizayn sisteminin rəng tokenləri (dark tema — saytın CSS
 * dəyişənlərindən: --color-bg-page #161616, --color-bg-bg #2b2b2b, ...).
 */
object P {
    const val PAGE: Int = 0xFF161616.toInt()        // əsas fon
    const val BG: Int = 0xFF2B2B2B.toInt()          // kompozitor/bubble səthi
    const val SURFACE: Int = 0xFF202020.toInt()     // kart səthi
    const val TEXT: Int = 0xFFF8F8F8.toInt()
    const val TEXT2: Int = 0xFFADADAD.toInt()
    const val TEXT3: Int = 0xFF747474.toInt()
    const val PLACEHOLDER: Int = 0xFF474747.toInt()
    const val ACCENT: Int = 0xFF4099FF.toInt()
    const val DANGER: Int = 0xFFFFB3B3.toInt()
    const val DANGER_BG: Int = 0xFF420000.toInt()
    const val SUCCESS: Int = 0xFF88DDA8.toInt()
    const val CODE_BG: Int = 0xFF181818.toInt()
    const val DISABLED: Int = 0xFF363636.toInt()
    const val INK: Int = 0xFF0D0D0D.toInt()         // ağ düymənin mətni (tünd)

    /** Sərhəd rəngi: #ffffff1a (26/255 alfa ağ) */
    const val BORDER: Int = 0x1AFFFFFF
    const val BORDER_STRONG: Int = 0x26FFFFFF
}

/** Proqramatik UI üçün köməkçilər — bütün layout kod-da qurulur (WebView YOX,
 *  XML-in runtime riskləri yoxdur — kompilyator hər xassəni yoxlayır). */
object Ui {

    fun dp(ctx: Context, v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** Bucaqlı arxa fon (radius px ilə; dp üçün dp() ilə çevirin). */
    fun roundBg(color: Int, radiusPx: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            if (strokeColor != 0 && strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Dairəvi arxa fon. */
    fun ovalBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); shape = GradientDrawable.OVAL
    }

    /** Standart mətn görünüşü yarat. */
    fun text(
        ctx: Context,
        s: CharSequence? = null,
        sizeSp: Float = 15f,
        color: Int = P.TEXT,
        bold: Boolean = false,
        mono: Boolean = false,
        serif: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        typeface = when {
            mono -> Typeface.MONOSPACE
            serif -> Typeface.SERIF
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        includeFontPadding = true
    }

    fun llParams(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply { this.weight = weight }

    fun margins(lp: ViewGroup.MarginLayoutParams, l: Int, t: Int, r: Int, b: Int): ViewGroup.MarginLayoutParams =
        lp.apply { setMargins(l, t, r, b) }
}

/**
 * FULLSCREEN / IMMERSIVE — ZaiMobile-ın əsas tələbi (Dərs 20-dəki məntiqin
 * istehsal versiyası). Rust+Slint versiyasının 915dp pinləmə bug-u burada
 * struktur olaraq mümkün deyil: Activity həmişə tam pəncərəni tutur.
 */
object Fullscreen {

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun onFocus(activity: Activity, hasFocus: Boolean) {
        if (hasFocus) apply(activity)
    }

    /**
     * Insets qorunması: başlıq zolağı status bar hündürlüyü qədər aşağı,
     * kompozitor nav bar / klaviatura qədər yuxarı itələnir.
     * API 30+: getInsets(Type...) (ime daxil); API <30: systemWindowInset*
     * (klaviatura adjustResize ilə pəncərə ölçüsündən çıxılır).
     */
    fun applyInsets(root: View, topPad: View, bottomPad: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                topPad.setPadding(topPad.paddingLeft, bars.top, topPad.paddingRight, topPad.paddingBottom)
                bottomPad.setPadding(bottomPad.paddingLeft, bottomPad.paddingTop,
                    bottomPad.paddingRight, maxOf(bars.bottom, ime.bottom))
                insets
            } else {
                @Suppress("DEPRECATION")
                topPad.setPadding(topPad.paddingLeft, insets.systemWindowInsetTop,
                    topPad.paddingRight, topPad.paddingBottom)
                @Suppress("DEPRECATION")
                bottomPad.setPadding(bottomPad.paddingLeft, bottomPad.paddingTop,
                    bottomPad.paddingRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets
            }
        }
    }
}
