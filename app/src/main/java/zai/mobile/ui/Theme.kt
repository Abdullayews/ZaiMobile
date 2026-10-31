package zai.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import java.util.Locale
import java.util.TimeZone
import zai.mobile.protocol.Signature

/**
 * UI yardımçiləri — rənglər values/colors.xml-dən (tək qaranlıq tema),
 * ölçülər dp ilə, fonlar GradientDrawable. Hamısı top-level extension.
 */

fun Context.color(id: Int): Int = resources.getColor(id, theme)

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** dp — Float qaytaran variant (setLineSpacing kimi float parametrlər üçün). */
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.sp(v: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
)

/** Radiuslu düzbucaq fon. */
fun Context.roundRect(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.setColor(fill)
    d.cornerRadius = dp(radiusDp).toFloat()
    if (stroke != null) d.setStroke(dp(strokeDp), stroke)
    return d
}

/** Dairəvi fon. */
fun Context.circle(fill: Int): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.OVAL
    d.setColor(fill)
    return d
}

/** Framework ripple — limitsiz toxunma effekti (ikon düymələri). */
fun Context.rippleBorderless(): android.graphics.drawable.Drawable {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    return resources.getDrawable(tv.resourceId, theme)
}

fun Context.rippleRect(): android.graphics.drawable.Drawable {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return resources.getDrawable(tv.resourceId, theme)
}

fun Context.serif(): android.graphics.Typeface = android.graphics.Typeface.SERIF

fun monoType(): android.graphics.Typeface = android.graphics.Typeface.MONOSPACE

/**
 * Canlı barmaq izi — Signature üçün real ekran/saat data-sı.
 * Statik dəyər YOXDUR: ölçülər cihazdan, timezone sistemdən gəlir.
 */
fun liveFingerprint(ctx: Context): Signature.Fingerprint {
    val dm = ctx.resources.displayMetrics
    val w = dm.widthPixels.coerceAtLeast(320)
    val h = dm.heightPixels.coerceAtLeast(480)
    val ratio = String.format(
        Locale.US, "%.3f",
        (dm.densityDpi / 160f).toDouble().coerceAtLeast(1.0),
    )
    val tz = TimeZone.getDefault()
    val offMinutes = -(tz.rawOffset / 60000)
    return Signature.Fingerprint(
        screenWidth = w,
        screenHeight = h,
        pixelRatio = ratio,
        timezone = tz.id.ifBlank { "UTC" },
        timezoneOffsetMinutes = offMinutes,
    )
}
