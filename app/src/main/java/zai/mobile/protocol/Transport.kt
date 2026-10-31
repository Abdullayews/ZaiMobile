package zai.mobile.protocol

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * HTTP nəqliyyəti — yeganə şəbəkə kəsiyi.
 *
 * Real: HttpURLConnection. Testlərdə: ScriptedTransport (app/src/test).
 * Hər sorğu YALNIZ bir dəfə açılır; kod + body axını qaytarır.
 */
class TransportRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray? = null,
    /** true = SSE axını: readTimeout = 150s sükut watchdog-u (spec §5). */
    val streaming: Boolean = false,
)

class TransportResponse(
    val code: Int,
    /** Aşağı регистrli başlıq adı → dəyərlər (Set-Cookie üçün). */
    val headers: Map<String, List<String>> = emptyMap(),
    val body: InputStream,
    /** Oxunu bloklayan thread-i ləğv etmək üçün (stop düyməsi). */
    val cancel: () -> Unit = {},
)

interface Transport {
    fun open(req: TransportRequest): TransportResponse
}

/** Production nəqliyyətı — HttpURLConnection (xarici asılılıq YOXDUR). */
class UrlConnectionTransport : Transport {

    override fun open(req: TransportRequest): TransportResponse {
        val conn = (URI(req.url).toURL().openConnection() as HttpURLConnection)
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = if (req.streaming) SSE_WATCHDOG_MS else READ_TIMEOUT_MS
        conn.requestMethod = req.method
        conn.instanceFollowRedirects = true
        for (h in req.headers) conn.setRequestProperty(h.first, h.second)
        val body = req.body
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val respHeaders = HashMap<String, List<String>>()
        for ((k, v) in conn.headerFields) {
            if (k != null && v != null) respHeaders[k.lowercase()] = v
        }
        val ok = code in 200..299
        val stream: InputStream = if (ok) {
            try {
                conn.inputStream
            } catch (e: IOException) {
                ByteArrayInputStream(ByteArray(0))
            }
        } else {
            conn.errorStream ?: (try {
                conn.inputStream
            } catch (e: IOException) {
                null
            } ?: ByteArrayInputStream(ByteArray(0)))
        }
        return TransportResponse(code, respHeaders, stream) { conn.disconnect() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        /** SSE: 150s sükut → SocketTimeout → DROPPED (spec §5 watchdog). */
        const val SSE_WATCHDOG_MS = 150_000
    }
}
