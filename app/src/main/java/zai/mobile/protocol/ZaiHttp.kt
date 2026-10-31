package zai.mobile.protocol

import java.io.IOException
import java.io.InputStream

/**
 * Z.ai HTTP klienti — spec §2/§8.
 *
 * Hər sorğunun əvvəlində: UA, X-Device-ID, X-FE-Version, Accept-Language,
 * Origin, Referer, mövcud WAF cookie-ləri (acw_tc).
 * WAF bloku (405/403/429/509 + HTML body): backoff 2.5s×1.35ⁿ (maks 9s),
 * ±20% jitter ilə təkrar — hər cəhd üçün sorğu SIFIRDAN qurulur (fresh imza).
 *
 * Sinxron API-dir; coroutine tərəfdə Dispatchers.IO-da çağırılır.
 */
class ZaiHttp(
    internal val transport: Transport,
    val deviceId: String,
    baseUrl: String = PROD_BASE,
) {
    val base: String = baseUrl.trimEnd('/')
    val cookies = LinkedHashMap<String, String>()
    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun common(extra: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(extra.size + 7)
        out.add("User-Agent" to UA)
        out.add("X-Device-ID" to deviceId)
        out.add("X-FE-Version" to FE_VERSION)
        out.add("Accept-Language" to "en-US")
        out.add("Origin" to base)
        out.add("Referer" to "$base/")
        if (cookies.isNotEmpty()) out.add("Cookie" to cookieHeader())
        out.addAll(extra)
        return out
    }

    private fun harvestCookies(h: Map<String, List<String>>) {
        for (line in h["set-cookie"].orEmpty()) {
            val idx = line.indexOf(';').let { if (it < 0) line.length else it }
            val nv = line.substring(0, idx)
            val eq = nv.indexOf('=')
            if (eq > 0) cookies[nv.substring(0, eq)] = nv.substring(eq + 1)
        }
    }

    /** GET {base}/ — WAF warm-up: acw_tc cookie-lərini yığır (spec §3.1). */
    fun warmUp() {
        val out = json(
            attempts = WARMUP_ATTEMPTS,
            method = "GET",
            path = { "/" },
        )
        if (out.code !in 200..299) {
            throw ZaiException.fromHttp(out.code, String(out.bytes))
        }
    }

    class JsonOut(val code: Int, val bytes: ByteArray)

    /**
     * JSON (stream olmayan) sorğu. WAF bloklarında daxildən təkrarlanır;
     * digər statuslarda dərhal qaytarır (xəta xəritməsi çağıran tərəfin işidir).
     */
    fun json(
        attempts: Int,
        method: String = "GET",
        path: () -> String,
        headers: () -> List<Pair<String, String>> = { emptyList() },
        body: (() -> ByteArray?)? = null,
    ): JsonOut {
        var delay = BASE_DELAY_MS
        var lastWaf: ZaiException.WafBlocked? = null
        for (attempt in 1..attempts) {
            try {
                val resp = transport.open(
                    TransportRequest(
                        method = method,
                        url = base + path(),
                        headers = common(headers()),
                        body = body?.invoke(),
                    )
                )
                harvestCookies(resp.headers)
                val bytes = resp.body.use { it.readBytes() }
                if (resp.code in WAF_CODES && looksLikeHtmlBody(bytes)) {
                    log("WAF block ${resp.code} (attempt $attempt/$attempts) at ${path()}")
                    lastWaf = ZaiException.WafBlocked(attempts)
                    if (attempt == attempts) break
                    sleepJitter(delay)
                    delay = nextDelay(delay)
                    continue
                }
                return JsonOut(resp.code, bytes)
            } catch (e: IOException) {
                throw ZaiException.Network(e)
            }
        }
        throw lastWaf ?: ZaiException.WafBlocked(attempts)
    }

    /**
     * Bir SSE sorğusunun tam spesifikasiyası — hər cəhd üçün SIFIRDAN
     * qurulur (fresh imza; spec §8 "re-build each attempt").
     */
    class StreamCall(
        val path: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    class StreamOut(
        val code: Int,
        /** 200 olduqda açıq SSE axını; əks halda null. */
        val body: InputStream?,
        val cancel: () -> Unit,
        /** 200 olmayan cavabların body-si (oxunmuş). */
        val errorBody: () -> ByteArray,
    )

    /**
     * SSE sorğusu — build hər cəhd üçün yenidən çağrılır.
     */
    fun stream(
        attempts: Int,
        build: () -> StreamCall,
    ): StreamOut {
        var delay = BASE_DELAY_MS
        var lastWaf: ZaiException.WafBlocked? = null
        for (attempt in 1..attempts) {
            try {
                val spec = build()
                val resp = transport.open(
                    TransportRequest(
                        method = "POST",
                        url = base + spec.path,
                        headers = common(spec.headers),
                        body = spec.body,
                        streaming = true,
                    )
                )
                harvestCookies(resp.headers)
                if (resp.code in 200..299) {
                    return StreamOut(resp.code, resp.body, resp.cancel) { ByteArray(0) }
                }
                val errBytes = resp.body.use { s -> s.readBytes() }
                if (resp.code in WAF_CODES && looksLikeHtmlBody(errBytes)) {
                    log("WAF block ${resp.code} (attempt $attempt/$attempts) at ${spec.path.take(40)}…")
                    lastWaf = ZaiException.WafBlocked(attempts)
                    if (attempt == attempts) break
                    sleepJitter(delay)
                    delay = nextDelay(delay)
                    continue
                }
                return StreamOut(resp.code, null, resp.cancel) { errBytes }
            } catch (e: IOException) {
                throw ZaiException.Network(e)
            }
        }
        throw lastWaf ?: ZaiException.WafBlocked(attempts)
    }

    companion object {
        const val PROD_BASE = "https://chat.z.ai"
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        const val FE_VERSION = "prod-fe-1.1.123"
        const val PAGE_TITLE = "Z.ai - Advanced AI Chatbot & Agent powered by GLM-5.3-Flash"

        val WAF_CODES = intArrayOf(405, 403, 429, 509)
        const val WARMUP_ATTEMPTS = 6
        const val BASE_DELAY_MS = 2500L
        const val MAX_DELAY_MS = 9000L

        internal fun nextDelay(d: Long): Long = minOf(MAX_DELAY_MS, (d * 1.35).toLong())

        internal fun sleepJitter(base: Long) {
            val j = base * (0.8 + Math.random() * 0.4)
            Thread.sleep(j.toLong().coerceAtLeast(1))
        }

        internal fun looksLikeHtmlBody(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8)
                .trimStart()
                .lowercase()
            return head.startsWith("<!doctype") || head.startsWith("<html")
        }
    }
}
