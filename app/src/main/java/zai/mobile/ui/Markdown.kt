package zai.mobile.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import zai.mobile.R

/**
 * Markdown — streaming təhlükəsiz parser + renderer.
 * Parser saf Kotlin-dır (LogicTest-də yoxlanılır); renderer Android view-ları qurur.
 * Hər delta-da yalnız AXINAN mesajın konteyneri yenidən render olunur.
 */
object Md {

    // ---------------- Parser ----------------

    sealed class Inline {
        class Txt(val v: String) : Inline()
        class Bold(val inner: List<Inline>) : Inline()
        class It(val inner: List<Inline>) : Inline()
        class Mono(val v: String) : Inline()
        class Link(val text: String, val url: String) : Inline()
        class Strike(val inner: List<Inline>) : Inline()
    }

    sealed class Block {
        class Heading(val level: Int, val inl: List<Inline>) : Block()
        class Para(val inl: List<Inline>) : Block()
        class ListBlock(val ordered: Boolean, val items: List<List<Inline>>) : Block()
        class Quote(val blocks: List<Block>) : Block()
        class Code(val lang: String, val text: String) : Block()
        object Hr : Block()
    }

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>(16)
        if (src.isBlank()) return out
        val lines = src.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty()) {
                i++
                continue
            }
            // fenced code
            if (t.startsWith("```")) {
                val lang = t.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // bağlanış ```
                out.add(Block.Code(lang, sb.toString()))
                continue
            }
            // hr
            if (t == "---" || t == "***" || t == "___") {
                out.add(Block.Hr)
                i++
                continue
            }
            // heading
            val h = headingLevel(t)
            if (h in 1..6) {
                out.add(Block.Heading(h, parseInline(t.substring(h).trim())))
                i++
                continue
            }
            // sitat
            if (t.startsWith(">")) {
                val inner = ArrayList<String>(4)
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    inner.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                out.add(Block.Quote(parse(inner.joinToString("\n"))))
                continue
            }
            // siyahı
            if (listMarker(t) != null) {
                val ordered = listMarker(t) == "ol"
                val items = ArrayList<List<Inline>>(4)
                while (i < lines.size) {
                    val lt = lines[i].trim()
                    val m = listMarker(lt)
                    if (m == null || (m == "ol") != ordered) break
                    items.add(parseInline(stripMarker(lt)))
                    i++
                }
                out.add(Block.ListBlock(ordered, items))
                continue
            }
            // paraqraf — tək sətir
            out.add(Block.Para(parseInline(t)))
            i++
        }
        return out
    }

    private fun headingLevel(t: String): Int {
        var n = 0
        while (n < t.length && t[n] == '#') n++
        return if (n in 1..6 && n < t.length && t[n] == ' ') n else 0
    }

    /** "ul" | "ol" | null */
    private fun listMarker(t: String): String? {
        if (t.length >= 2 && (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ "))) return "ul"
        var d = 0
        while (d < t.length && t[d].isDigit()) d++
        if (d in 1..3 && d < t.length && t[d] == '.' && d + 1 < t.length && t[d + 1] == ' ') return "ol"
        return null
    }

    private fun stripMarker(t: String): String = when {
        t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> t.substring(2).trim()
        else -> {
            var d = 0
            while (d < t.length && t[d].isDigit()) d++
            t.substring(d + 2).trim()
        }
    }

    /** [from, to) penceresində needle axtar (idx + len penceredən çıxmamalıdır). */
    private fun find(s: String, needle: String, from: Int, to: Int): Int {
        val idx = s.indexOf(needle, from)
        if (idx < 0 || idx < from) return -1
        return if (idx + needle.length > to) -1 else idx
    }

    private fun findCh(s: String, c: Char, from: Int, to: Int): Int {
        val idx = s.indexOf(c, from)
        if (idx < 0 || idx < from) return -1
        return if (idx + 1 > to) -1 else idx
    }

    /** Inline: **bold** *it* `mono` ~~strike~~ [t](url) */
    fun parseInline(s: String): List<Inline> {
        val out = ArrayList<Inline>(8)
        parseInlineInto(s, 0, s.length, out)
        if (out.isEmpty()) out.add(Inline.Txt(""))
        return out
    }

    private fun parseInlineInto(s: String, from: Int, to: Int, out: MutableList<Inline>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.add(Inline.Txt(plain.toString()))
                plain.setLength(0)
            }
        }
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '*' && i + 1 < to && s[i + 1] == '*' -> {
                    val close = find(s, "**", i + 2, to)
                    if (close > i + 1) {
                        flush()
                        val inner = ArrayList<Inline>(4)
                        parseInlineInto(s, i + 2, close, inner)
                        out.add(Inline.Bold(inner))
                        i = close + 2
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '*' -> {
                    val close = findCh(s, '*', i + 1, to)
                    if (close > i) {
                        flush()
                        val inner = ArrayList<Inline>(4)
                        parseInlineInto(s, i + 1, close, inner)
                        out.add(Inline.It(inner))
                        i = close + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '`' -> {
                    val close = findCh(s, '`', i + 1, to)
                    if (close > i) {
                        flush()
                        out.add(Inline.Mono(s.substring(i + 1, close)))
                        i = close + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '~' && i + 1 < to && s[i + 1] == '~' -> {
                    val close = find(s, "~~", i + 2, to)
                    if (close > i + 1) {
                        flush()
                        val inner = ArrayList<Inline>(4)
                        parseInlineInto(s, i + 2, close, inner)
                        out.add(Inline.Strike(inner))
                        i = close + 2
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '[' -> {
                    val closeBracket = findCh(s, ']', i + 1, to)
                    if (closeBracket > i && closeBracket + 1 < to && s[closeBracket + 1] == '(') {
                        val closeParen = findCh(s, ')', closeBracket + 2, to)
                        if (closeParen > closeBracket + 1) {
                            flush()
                            out.add(
                                Inline.Link(
                                    s.substring(i + 1, closeBracket),
                                    s.substring(closeBracket + 2, closeParen),
                                )
                            )
                            i = closeParen + 1
                        } else {
                            plain.append(c); i++
                        }
                    } else {
                        plain.append(c); i++
                    }
                }
                else -> {
                    plain.append(c); i++
                }
            }
        }
        flush()
    }

    // ---------------- Renderer ----------------

    /**
     * blocks → view-lar. into removeAllViews + yenidən doldurulur
     * (streaming-də yalnız axın konteyneri üçün çağrılır).
     */
    fun render(
        ctx: Context,
        into: LinearLayout,
        blocks: List<Block>,
        onCopyCode: (String) -> Unit,
    ) {
        into.removeAllViews()
        renderInto(ctx, into, blocks, onCopyCode)
    }

    private fun renderInto(
        ctx: Context,
        into: LinearLayout,
        blocks: List<Block>,
        onCopyCode: (String) -> Unit,
    ) {
        for (b in blocks) renderBlock(ctx, into, b, onCopyCode)
    }

    private fun renderBlock(ctx: Context, parent: LinearLayout, b: Block, onCopyCode: (String) -> Unit) {
        when (b) {
            is Block.Hr -> {
                val v = View(ctx)
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1f),
                ).apply {
                    topMargin = ctx.dp(10f)
                    bottomMargin = ctx.dp(10f)
                }
                v.background = ctx.roundRect(ctx.color(R.color.border), 0f)
                parent.addView(v)
            }
            is Block.Heading -> {
                val t = inlineTextView(ctx, b.inl)
                t.textSize = when (b.level) {
                    1 -> ctx.sp(21f)
                    2 -> ctx.sp(19f)
                    3 -> ctx.sp(17.5f)
                    else -> ctx.sp(16.5f)
                }
                t.setTypeface(t.typeface, Typeface.BOLD)
                t.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = ctx.dp(10f)
                    bottomMargin = ctx.dp(4f)
                }
                parent.addView(t)
            }
            is Block.Para -> {
                val t = inlineTextView(ctx, b.inl)
                t.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = ctx.dp(8f) }
                parent.addView(t)
            }
            is Block.ListBlock -> {
                val t = TextView(ctx)
                t.textSize = ctx.sp(15.5f)
                t.setTextColor(ctx.color(R.color.text))
                t.setLineSpacing(ctx.dpf(3f), 1f)
                val sb = SpannableStringBuilder()
                b.items.forEachIndexed { idx, item ->
                    val itemStart = sb.length // paraqraf başlanğıcı — PARAGRAPH span üçün şərt
                    val prefix = if (b.ordered) "${idx + 1}.  " else "•  "
                    sb.append(prefix)
                    appendInl(ctx, sb, item)
                    sb.setSpan(
                        LeadingMarginSpan.Standard(0, ctx.dp(16f)),
                        itemStart, sb.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    if (idx < b.items.size - 1) sb.append('\n')
                }
                t.text = sb
                t.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = ctx.dp(8f) }
                parent.addView(t)
            }
            is Block.Quote -> {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                val bar = View(ctx)
                bar.background = ctx.roundRect(ctx.color(R.color.accent), 2f)
                bar.layoutParams = LinearLayout.LayoutParams(ctx.dp(3f), ViewGroup.LayoutParams.MATCH_PARENT)
                val box = LinearLayout(ctx)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(ctx.dp(10f), ctx.dp(6f), 0, ctx.dp(6f))
                renderInto(ctx, box, b.blocks, onCopyCode)
                row.addView(bar)
                row.addView(
                    box,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.background = ctx.roundRect(ctx.color(R.color.surface), 8f)
                row.setPadding(ctx.dp(8f), ctx.dp(2f), ctx.dp(8f), ctx.dp(2f))
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = ctx.dp(8f) }
                parent.addView(row)
            }
            is Block.Code -> {
                val wrap = LinearLayout(ctx)
                wrap.orientation = LinearLayout.VERTICAL
                wrap.background = ctx.roundRect(ctx.color(R.color.code_bg), 10f, stroke = ctx.color(R.color.border))
                wrap.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = ctx.dp(10f)
                    topMargin = ctx.dp(2f)
                }

                val head = LinearLayout(ctx)
                head.orientation = LinearLayout.HORIZONTAL
                head.gravity = Gravity.CENTER_VERTICAL
                val lang = TextView(ctx)
                lang.text = b.lang.ifBlank { "code" }
                lang.textSize = ctx.sp(11.5f)
                lang.setTextColor(ctx.color(R.color.text3))
                lang.typeface = monoType()
                head.addView(
                    lang,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                val btn = ImageButton(ctx)
                btn.setImageResource(R.drawable.ic_copy)
                btn.imageTintList = android.content.res.ColorStateList.valueOf(ctx.color(R.color.text2))
                btn.background = ctx.rippleBorderless()
                btn.contentDescription = ctx.getString(R.string.cd_copy)
                btn.setOnClickListener { onCopyCode(b.text) }
                head.addView(btn)
                head.setPadding(ctx.dp(12f), ctx.dp(6f), ctx.dp(6f), 0)
                wrap.addView(head)

                val sv = HorizontalScrollView(ctx)
                isHorizontalScrollBarEnabledFalse(sv)
                val code = TextView(ctx)
                code.typeface = monoType()
                code.textSize = ctx.sp(13f)
                code.setTextColor(ctx.color(R.color.text))
                code.setLineSpacing(ctx.dpf(2f), 1f)
                code.text = b.text
                code.setPadding(ctx.dp(12f), ctx.dp(8f), ctx.dp(12f), ctx.dp(10f))
                sv.addView(
                    code,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                wrap.addView(sv)
                parent.addView(wrap)
            }
        }
    }

    private fun inlineTextView(ctx: Context, inl: List<Inline>): TextView {
        val t = TextView(ctx)
        t.textSize = ctx.sp(15.5f)
        t.setTextColor(ctx.color(R.color.text))
        t.setLineSpacing(ctx.dpf(3f), 1f)
        val sb = SpannableStringBuilder()
        appendInl(ctx, sb, inl)
        t.text = sb
        t.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        return t
    }

    private fun appendInl(ctx: Context, sb: SpannableStringBuilder, inl: List<Inline>) {
        for (e in inl) {
            when (e) {
                is Inline.Txt -> sb.append(e.v)
                is Inline.Mono -> {
                    val s = sb.length
                    sb.append(e.v)
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0x26FFFFFF), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Link -> {
                    val s = sb.length
                    sb.append(e.text)
                    sb.setSpan(URLSpan(e.url), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(
                        TextAppearanceSpan(null, 0, ctx.sp(15.5f).toInt(), android.content.res.ColorStateList.valueOf(ctx.color(R.color.accent)), null),
                        s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is Inline.Bold -> {
                    val s = sb.length
                    appendInl(ctx, sb, e.inner)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.It -> {
                    val s = sb.length
                    appendInl(ctx, sb, e.inner)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Strike -> {
                    val s = sb.length
                    appendInl(ctx, sb, e.inner)
                    sb.setSpan(StrikethroughSpan(), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun isHorizontalScrollBarEnabledFalse(sv: HorizontalScrollView) {
        sv.isHorizontalScrollBarEnabled = false
    }
}
