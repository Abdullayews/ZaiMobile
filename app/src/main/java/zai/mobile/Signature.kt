package zai.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * chat.z.ai veb tətbiqinin imza alqoritmi — Rust versiyasından dəqiq port.
 * Tamamilə xalis Kotlin (JVM-də test olunur, Android asılılığı YOX).
 */
object Signature {

    const val FE_VERSION = "prod-fe-1.1.123"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val SIGNATURE_KEY = "key-@@@@)))()((9))-xxxx&&&%%%%%"

    fun nowMs(): Long = System.currentTimeMillis()

    fun genUuid(): String = UUID.randomUUID().toString()

    /** Retry gecikməsinə ±20% jitter. */
    fun jitterMs(ms: Long): Long {
        val f = Math.random()
        val delta = (ms * 0.2).toLong()
        return ms + (f * 2 * delta).toLong() - delta
    }

    // ---------------- HMAC-SHA256 → hex ----------------
    fun hmacSha256Hex(key: ByteArray, msg: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.size > 64) sha256(key) else key, "HmacSHA256"))
        return mac.doFinal(msg).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(b: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)

    // ---------------- əl ilə Base64 (portable; android.util.Base8 JVM-də yoxdur) ----------------
    fun base64(bytes: ByteArray): String {
        val T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(T[(n shr 18) and 63])
            sb.append(T[(n shr 12) and 63])
            sb.append(if (i + 1 < bytes.size) T[(n shr 6) and 63] else '=')
            sb.append(if (i + 2 < bytes.size) T[n and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    // ---------------- URLSearchParams (application/x-www-form-urlencoded) ----------------
    fun formEncode(s: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                    c == '*'.code || c == '-'.code || c == '.'.code || c == '_'.code -> sb.append(c.toChar())
                c == ' '.code -> sb.append('+')
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun utcStringNow(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    data class Info(val signature: String, val timestamp: String, val urlParams: String)

    /** Veb tətbiqinin imza alqoritmi (canlı yoxlanılmış protokol). */
    fun make(prompt: String, token: String, userId: String): Info {
        val ts = nowMs()
        val tsStr = ts.toString()
        val requestId = genUuid()
        val sortedPayload = "requestId,$requestId,timestamp,$tsStr,user_id,$userId"
        val promptB64 = base64(prompt.trim().toByteArray(Charsets.UTF_8))
        val data = "$sortedPayload|$promptB64|$tsStr"

        val g = ts / 300_000
        val subkey = hmacSha256Hex(SIGNATURE_KEY.toByteArray(Charsets.UTF_8), g.toString().toByteArray(Charsets.UTF_8))
        val sig = hmacSha256Hex(subkey.toByteArray(Charsets.UTF_8), data.toByteArray(Charsets.UTF_8))

        val fields: List<Pair<String, String>> = listOf(
            "timestamp" to tsStr,
            "requestId" to requestId,
            "user_id" to userId,
            "version" to "0.0.1",
            "platform" to "web",
            "token" to token,
            "user_agent" to MOBILE_UA,
            "language" to "en-US",
            "languages" to "en-US",
            "timezone" to "Asia/Baku",
            "cookie_enabled" to "true",
            "screen_width" to "1080",
            "screen_height" to "2400",
            "screen_resolution" to "1080x2400",
            "viewport_height" to "800",
            "viewport_width" to "400",
            "viewport_size" to "400x800",
            "color_depth" to "24",
            "pixel_ratio" to "2.625",
            "current_url" to "https://chat.z.ai/",
            "pathname" to "/",
            "search" to "",
            "hash" to "",
            "host" to "chat.z.ai",
            "hostname" to "chat.z.ai",
            "protocol" to "https:",
            "referrer" to "",
            "title" to "Z.ai - Advanced AI Chatbot & Agent powered by GLM-5.3-Flash",
            "timezone_offset" to "-240",
            "local_time" to isoNow(),
            "utc_time" to utcStringNow(),
            "is_mobile" to "true",
            "is_touch" to "true",
            "max_touch_points" to "5",
            "browser_name" to "Chrome",
            "os_name" to "Android",
        )
        val urlParams = fields.joinToString("&") { (k, v) -> "$k=${formEncode(v)}" }
        return Info(sig, tsStr, urlParams)
    }
}
