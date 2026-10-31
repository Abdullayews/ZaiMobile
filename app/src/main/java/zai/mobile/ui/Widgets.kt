package zai.mobile.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import zai.mobile.R

/**
 * Kiçik təkrar istifadə olunan view-lar.
 */

fun Context.iconButton(
    iconRes: Int,
    desc: String,
    sizeDp: Int = 44,
    iconDp: Int = 22,
    tint: Int? = null,
    onClick: (View) -> Unit,
): ImageButton {
    val b = ImageButton(this)
    b.layoutParams = ViewGroup.LayoutParams(dp(sizeDp.toFloat()), dp(sizeDp.toFloat()))
    b.setImageResource(iconRes)
    if (tint != null) b.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    b.background = rippleBorderless()
    b.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    val pad = dp(((sizeDp - iconDp) / 2f))
    b.setPadding(pad, pad, pad, pad)
    b.contentDescription = desc
    b.setOnClickListener { onClick(b) }
    return b
}

fun Context.textButton(
    label: String,
    filled: Boolean = true,
    allCaps: Boolean = false,
    onClick: (View) -> Unit,
): android.widget.Button {
    val b = android.widget.Button(this)
    b.text = label
    b.isAllCaps = allCaps
    b.textSize = sp(15f)
    b.typeface = Typeface.create(Typeface.DEFAULT, if (filled) Typeface.BOLD else Typeface.NORMAL)
    b.setTextColor(color(if (filled) R.color.page else R.color.text))
    b.background = if (filled) {
        roundRect(color(R.color.accent), 10f)
    } else {
        roundRect(color(R.color.bg), 10f, stroke = color(R.color.border))
    }
    b.setPadding(dp(22f), 0, dp(22f), 0)
    b.minimumHeight = dp(46f)
    b.setOnClickListener { onClick(b) }
    return b
}

fun Context.field(hint: String, password: Boolean = false, singleLine: Boolean = true): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setTextColor(color(R.color.text))
    e.setHintTextColor(color(R.color.text3))
    e.textSize = sp(15.5f)
    e.background = roundRect(color(R.color.bg), 10f, stroke = color(R.color.border))
    e.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
    e.minLines = 1
    if (singleLine) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    if (password) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    return e
}

fun Context.label(text: String, sizeSp: Float = 13f, colorId: Int = R.color.text2): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sp(sizeSp)
    t.setTextColor(color(colorId))
    return t
}

fun Context.row(vararg children: View, gravity: Int = Gravity.CENTER_VERTICAL): android.widget.LinearLayout {
    val l = android.widget.LinearLayout(this)
    l.orientation = android.widget.LinearLayout.HORIZONTAL
    l.gravity = gravity
    for (c in children) l.addView(c)
    return l
}

/** Accent rəngli kliklənən mətn (keçid-düymə). */
fun Context.link(text: String, sizeSp: Float = 13.5f): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sp(sizeSp)
    t.setTextColor(color(R.color.accent))
    t.background = rippleRect()
    t.setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
    return t
}
