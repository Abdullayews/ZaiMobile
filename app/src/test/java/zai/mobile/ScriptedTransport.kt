package zai.mobile

import zai.mobile.protocol.Transport
import zai.mobile.protocol.TransportRequest
import zai.mobile.protocol.TransportResponse
import java.io.InputStream

/**
 * Test nəqliyyəti — skriptləşdirilmiş cavablar; bütün sorğləri qeyd edir.
 * Hər `open` çağırışı üçün uyğun ilk qayda işə düşür; handler (call, attempt)
 * alır — attempt = eyni method+url neçə dəfə çağırılıb (WAF ardıcıllığı üçün).
 */
class ScriptedTransport : Transport {

    class Call(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
    ) {
        val path: String
            get() {
                val i = url.indexOf('/', 9)
                return if (i >= 0) url.substring(i) else url
            }
        val bodyText: String get() = body?.toString(Charsets.UTF_8) ?: ""
        fun header(n: String): String? =
            headers.firstOrNull { it.first.equals(n, ignoreCase = true) }?.second
    }

    class Out(
        val code: Int,
        val headers: Map<String, List<String>> = emptyMap(),
        val stream: () -> InputStream,
    )

    private class Rule(
        val match: (Call) -> Boolean,
        val handler: (Call, Int) -> Out,
    )

    val calls = ArrayList<Call>()
    private val rules = ArrayList<Rule>()

    override fun open(req: TransportRequest): TransportResponse {
        val c = Call(req.method, req.url, req.headers, req.body)
        calls.add(c)
        val r = rules.firstOrNull { it.match(c) }
            ?: throw IllegalStateException("no script for ${req.method} ${req.url}")
        // imza hər cəhdə URL-i dəyişdirir → sorğu-sayğını query-siz path üzərindən aparrıq
        val attempt = calls.count {
            it.method == c.method &&
                it.path.substringBefore('?') == c.path.substringBefore('?')
        }
        val out = r.handler(c, attempt)
        return TransportResponse(out.code, out.headers, out.stream()) { }
    }

    fun on(match: (Call) -> Boolean, handler: (Call, Int) -> Out): ScriptedTransport {
        rules.add(Rule(match, handler))
        return this
    }

    /** Test üçün: bütün qaydaları at (yenidən qurulanda istifadə olunur). */
    fun clearRules() {
        rules.clear()
    }

    fun countOf(method: String, pathPrefix: String): Int =
        calls.count { it.method == method && it.path.startsWith(pathPrefix) }

    companion object {
        const val WAF_HTML =
            "<!doctypehtml><html lang=\"zh-cn\"><meta charset=\"utf-8\"><title>405</title>"

        fun ok(json: String): Out = Out(200) { json.byteInputStream() }

        fun sse(vararg lines: String): Out =
            Out(200) { lines.joinToString("\n") { "data: $it" }.byteInputStream() }

        fun waf(): Out = Out(405) { WAF_HTML.byteInputStream() }

        fun bad(code: Int, body: String): Out = Out(code) { body.byteInputStream() }
    }
}
