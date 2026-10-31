package zai.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Markdown → Android view-ları (render tərəfi). Parser: MarkdownParse.kt */
object Markdown {

    fun render(ctx: Context, nodes: List<MdNode>, into: LinearLayout) {
        into.removeAllViews()
        val d = Ui.dp(ctx, 1f)
        for (node in nodes) {
            val view: android.view.View = when (node) {
                is MdNode.Heading -> spanView(ctx, node.parts,
                    sizeSp = (25f - node.level * 1.5f).coerceIn(16f, 23f), bold = true).apply {
                    setPadding(0, 4 * d, 0, 2 * d)
                }
                is MdNode.Code -> codeView(ctx, node).apply { setPadding(0, 4 * d, 0, 4 * d) }
                is MdNode.ListItem -> spanView(ctx, node.parts,
                    prefix = if (node.ordered) "${node.index}. " else "•  ").apply {
                    setPadding(6 * d, 0, 0, 0)
                }
                is MdNode.Quote -> spanView(ctx, node.parts, color = P.TEXT2, italic = true).apply {
                    background = Ui.roundBg(0x14FFFFFF, 0)
                    setPadding(10 * d, 4 * d, 8 * d, 4 * d)
                }
                is MdNode.Par -> spanView(ctx, node.parts).apply {
                    setPadding(0, 2 * d, 0, 2 * d)
                }
            }
            into.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun spanView(ctx: Context, parts: List<MdSpan>, sizeSp: Float = 15f,
                         bold: Boolean = false, color: Int = P.TEXT, italic: Boolean = false,
                         prefix: String = ""): TextView {
        val sb = SpannableStringBuilder()
        if (prefix.isNotEmpty()) sb.append(prefix)
        for (p in parts) {
            val start = sb.length
            sb.append(p.text)
            val end = sb.length
            if (end <= start) continue
            if (p.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (p.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (p.code) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(P.CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFF8BE9FD.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (p.link != null) {
                sb.setSpan(URLSpan(p.link), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(P.ACCENT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return TextView(ctx).apply {
            text = sb
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (parts.any { it.link != null }) {
                movementMethod = LinkMovementMethod.getInstance()
            }
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
    }

    private fun codeView(ctx: Context, code: MdNode.Code): TextView {
        val label = if (code.lang.isEmpty()) "" else code.lang + "\n"
        return TextView(ctx).apply {
            text = label + code.body
            textSize = 13f
            setTextColor(P.TEXT)
            typeface = Typeface.MONOSPACE
            background = Ui.roundBg(P.CODE_BG, Ui.dp(ctx, 8f))
            setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 10f), Ui.dp(ctx, 12f), Ui.dp(ctx, 10f))
            setTextIsSelectable(true)
            setOnLongClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("code", code.body))
                Toast.makeText(ctx, "Code copied", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
