package zai.mobile.protocol

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * X-Device-ID: uid_ + 13 simvol (alnum) — spec §2.
 */
object DeviceIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun new(): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(17)
        sb.append("uid_")
        repeat(13) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun isValid(id: String): Boolean =
        id.length == 17 && id.startsWith("uid_") &&
            id.substring(4).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
}

/**
 * /api/v2/chat/completions imzası — spec §5, dəqiq port.
 *
 * 1. ts = unix ms; requestId = uuid-v4
 * 2. sorted_payload = "requestId,{rid},timestamp,{ts},user_id,{uid}"
 * 3. prompt_b64 = base64(prompt.trim())
 * 4. data = "{sorted}|{b64}|{ts}"
 * 5. g = ts / 300000; subkey = HMAC_SHA256_hex(KEY, str(g))
 * 6. signature = HMAC_SHA256_hex(subkey_as_key, data)
 * 7. url_params — dəqiq sıralanan sahələr, form_encode (A-Za-z0-9*-._ saxla,
 *    boşluq → +, qalanı %XX böyük hex)
 */
object Signature {

    private const val KEY = "key-@@@@)))()((9))-xxxx&&&%%%%%"

    data class Result(
        /** "/api/v2/chat/completions?…&signature_timestamp={ts}" */
        val path: String,
        val timestamp: Long,
        /** X-Signature başlığı. */
        val xSignature: String,
    )

    /** Real cihaz barmaq izi — ekrandan və sistem saatından canlı ölçülür. */
    data class Fingerprint(
        val screenWidth: Int,
        val screenHeight: Int,
        val pixelRatio: String,
        val timezone: String,
        val timezoneOffsetMinutes: Int,
    )

    fun hmacHex(key: ByteArray, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val n = hex.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** form_encode: A-Za-z0-9*-._ saxla; boşluq → '+'; qalanı %XX (böyük hex). */
    fun formEncode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '*' || c == '-' || c == '.' || c == '_') {
                sb.append(c)
            } else if (c == ' ') {
                sb.append('+')
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[(c.code ushr 4) and 0xF])
                sb.append("0123456789ABCDEF"[c.code and 0xF])
            }
        }
        return sb.toString()
    }

    fun sign(prompt: String, userId: String, token: String, fp: Fingerprint): Result {
        val ts = System.currentTimeMillis()
        val requestId = java.util.UUID.randomUUID().toString()
        val sortedPayload = "requestId,$requestId,timestamp,$ts,user_id,$userId"
        val promptB64 = Base64.encodeToString(
            prompt.trim().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val data = "$sortedPayload|$promptB64|$ts"
        val g = ts / 300_000
        val subkey = hmacHex(KEY.toByteArray(Charsets.UTF_8), g.toString())
        val signature = hmacHex(hexToBytes(subkey), data)

        val now = System.currentTimeMillis()
        val utc = Date(now)
        val localIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(now)) + String.format(
            Locale.US, ".%03dZ", (now % 1000L + 1000L) % 1000L,
        )
        val utcTime = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(utc) + " GMT"

        val sw = fp.screenWidth
        val sh = fp.screenHeight
        val params = linkedMapOf(
            "timestamp" to ts.toString(),
            "requestId" to requestId,
            "user_id" to userId,
            "version" to "0.0.1",
            "platform" to "web",
            "token" to token,
            "user_agent" to ZaiHttp.UA,
            "language" to "en-US",
            "languages" to "en-US",
            "timezone" to fp.timezone,
            "cookie_enabled" to "true",
            "screen_width" to sw.toString(),
            "screen_height" to sh.toString(),
            "screen_resolution" to "${sw}x$sh",
            "viewport_height" to "800",
            "viewport_width" to "400",
            "viewport_size" to "400x800",
            "color_depth" to "24",
            "pixel_ratio" to fp.pixelRatio,
            "current_url" to "https://chat.z.ai/",
            "pathname" to "/",
            "search" to "",
            "hash" to "",
            "host" to "chat.z.ai",
            "hostname" to "chat.z.ai",
            "protocol" to "https:",
            "referrer" to "",
            "title" to ZaiHttp.PAGE_TITLE,
            "timezone_offset" to fp.timezoneOffsetMinutes.toString(),
            "local_time" to localIso,
            "utc_time" to utcTime,
            "is_mobile" to "true",
            "is_touch" to "true",
            "max_touch_points" to "5",
            "browser_name" to "Chrome",
            "os_name" to "Android",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${formEncode(v)}"
        }
        return Result(
            path = "/api/v2/chat/completions?$query&signature_timestamp=$ts",
            timestamp = ts,
            xSignature = signature,
        )
    }
}
