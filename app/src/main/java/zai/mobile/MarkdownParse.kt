package zai.mobile

/**
 * Markdown SƏNƏD modeli + parser — xalis Kotlin (JVM-də test olunur).
 * Render Android tərəfi Markdown.kt-dədir.
 */

/** Sənəd strukturu. */
sealed class MdNode {
    data class Par(val parts: List<MdSpan>) : MdNode()
    data class Heading(val level: Int, val parts: List<MdSpan>) : MdNode()
    data class Code(val lang: String, val body: String) : MdNode()
    data class ListItem(val ordered: Boolean, val index: Int, val parts: List<MdSpan>) : MdNode()
    data class Quote(val parts: List<MdSpan>) : MdNode()
}

/** Sətir daxili span. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/**
 * Sadə, sürətli markdown parser — chat cavabları üçün: kod blokları,
 * başlıqlar, siyahılar, sitat, **qalın**, *meyl*, `kod`, [keçid](url).
 */
object MarkdownParse {

    fun parse(src: String): List<MdNode> {
        val nodes = mutableListOf<MdNode>()
        val lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var inCode = false
        val codeBody = StringBuilder()
        var codeLang = ""
        val parBuf = mutableListOf<String>()

        fun flushPar() {
            if (parBuf.isEmpty()) return
            val parts = parseInline(parBuf.joinToString("\n"))
            if (parts.isNotEmpty()) nodes += MdNode.Par(parts)
            parBuf.clear()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (inCode) {
                if (line.trim().startsWith("```")) {
                    nodes += MdNode.Code(codeLang, codeBody.toString().trimEnd('\n'))
                    codeBody.clear()
                    inCode = false
                } else {
                    codeBody.append(raw).append('\n')
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                flushPar()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                continue
            }
            if (trimmed.isEmpty()) { flushPar(); continue }

            val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
            if (heading != null) {
                flushPar()
                nodes += MdNode.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
                continue
            }
            if (trimmed.startsWith(">")) {
                flushPar()
                nodes += MdNode.Quote(parseInline(trimmed.removePrefix(">").trim()))
                continue
            }
            val ordered = Regex("^(\\d{1,3})[.)]\\s+(.*)$").find(trimmed)
            if (ordered != null) {
                flushPar()
                nodes += MdNode.ListItem(true, ordered.groupValues[1].toInt(), parseInline(ordered.groupValues[2]))
                continue
            }
            val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
            if (bullet != null) {
                flushPar()
                nodes += MdNode.ListItem(false, 0, parseInline(bullet.groupValues[1]))
                continue
            }
            parBuf += line
        }
        if (inCode) nodes += MdNode.Code(codeLang, codeBody.toString().trimEnd('\n'))
        flushPar()
        return nodes
    }

    fun parseInline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { out += MdSpan(sb.toString()); sb.clear() }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        flush()
                        out += MdSpan(text.substring(i + 1, close), code = true)
                        i = close + 1
                    } else { sb.append(c); i++ }
                }
                // **qalın** (açılışdan dərhal sonra boşluq = literal ulduz)
                c == '*' && i + 1 < text.length && text[i + 1] == '*' &&
                    (i + 2 >= text.length || text[i + 2] != ' ') -> {
                    val close = text.indexOf("**", i + 2)
                    if (close > i + 1) {
                        flush()
                        parseInline(text.substring(i + 2, close)).forEach { out += it.copy(bold = true) }
                        i = close + 2
                    } else { sb.append("**"); i += 2 }
                }
                // *meyl* (açılışdan sonra boşluq və ya ikinci ulduz = literal)
                c == '*' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close > i + 1) {
                        flush()
                        parseInline(text.substring(i + 1, close)).forEach { out += it.copy(italic = true) }
                        i = close + 1
                    } else { sb.append(c); i++ }
                }
                c == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket) {
                            flush()
                            val label = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            out += if (url.isEmpty()) MdSpan(label) else MdSpan(label, link = url)
                            i = closeParen + 1
                        } else { sb.append(c); i++ }
                    } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }
}
