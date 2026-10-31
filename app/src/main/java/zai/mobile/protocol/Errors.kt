package zai.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Z.ai xətaları — spec §5 (friendly_error) və §8 (WAF) xəritmələri.
 * Mesajlar istifadəçiyə birbaşa göstərilən mətndlərdir (UI dili: EN, sayt kimi).
 */
sealed class ZaiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Aliyun WAF bloku: 405/403/429/509 + HTML body, bütün cəhdlər tükəndi. */
    class WafBlocked(val attempts: Int) :
        ZaiException("z.ai blocked this connection ($attempts tries). Try again soon.")

    /** Şəbəkə xətası (timeout, DNS, connection reset). */
    class Network(cause: Throwable) :
        ZaiException("Connection problem. Check your internet and try again.", cause)

    /** 401/403 — token etibarsızdır. */
    class Unauthorized(val detail: String) :
        ZaiException("Session expired.")

    /** Server captcha tələb edir (FRONTEND_CAPTCHA_REQUIRED / signin captcha). */
    class Captcha(val detail: String) :
        ZaiException("z.ai needs browser verification.")

    /** Rate limit — avtomatik təkrarlanabilən. */
    class RateLimit(val detail: String) :
        ZaiException("Rate limit reached. Waiting a moment usually helps.")

    /** Server məşğuldur — avtomatik təkrarlanabilən. */
    class Busy(val detail: String) :
        ZaiException("The server is busy. Try again soon.")

    /** 426 / "outdated" — tətbiq versiyası köhnədir. */
    class Outdated :
        ZaiException("App version is outdated. Update Z.ai and try again.")

    /** Digər HTTP xətası. */
    class Http(val code: Int, val detail: String) :
        ZaiException("Error $code${if (detail.isNotBlank()) ": $detail" else ""}")

    /** Axın ortasında server xətası hadisəsi. */
    class StreamError(val code: String, val detail: String) :
        ZaiException("Stream error ${if (code.isNotBlank()) code else "—"}${if (detail.isNotBlank()) ": $detail" else ""}")

    /** Parametr xətası / proqramlaşdırma bug-u — heç vaxt retry edilməməli. */
    class Bug(message: String) : ZaiException(message)

    /** Axın tamamlanmadan kəsildi — avtomatik təkrarlanabilən (retryable). */
    class Dropped :
        ZaiException("The stream was interrupted.")

    /** İstifadəçi dayandırdı — xəta deyil. */
    class Stopped : ZaiException("Stopped")

    val isRetryable: Boolean
        get() = this is WafBlocked || this is Network || this is RateLimit ||
            this is Busy || this is Dropped

    companion object {
        /** 401/403 + digər statuslar üçün body-dən oxunan detail. */
        fun fromHttp(code: Int, body: String): ZaiException {
            val detail = extractDetail(body)
            if (code == 401 || code == 403) {
                if (looksLikeCaptcha(body)) return Captcha(detail)
                return Unauthorized(detail)
            }
            if (looksLikeCaptcha(body)) return Captcha(detail)
            return mapApiDetail(code, detail, body)
        }

        /** Axın içindəki {code|error_code, detail|message} obyektindən. */
        fun fromStreamError(err: JSONObject): ZaiException {
            val code = err.optString("code", err.optString("error_code", ""))
            val detail = err.optString("detail", err.optString("message", ""))
            if (code == "426" || detail.contains("outdated", true)) return Outdated()
            return mapApiDetail(0, detail, "$code $detail")
        }

        private fun mapApiDetail(code: Int, detail: String, raw: String): ZaiException {
            val all = raw.lowercase()
            if (all.contains("user_limit") || all.contains("rate_limit")) return RateLimit(detail)
            if (all.contains("global_limit")) return Busy(detail)
            if (all.contains("busy") || all.contains("overloaded") || all.contains("temporarily")) return Busy(detail)
            if (all.contains("captcha")) return Captcha(detail)
            return Http(code, detail)
        }

        fun looksLikeCaptcha(body: String): Boolean =
            body.contains("captcha", ignoreCase = true)

        /** JSON body-dən detail (string) —{"detail": "..."} və {"message": "..."} hər ikisi. */
        fun extractDetail(body: String): String {
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(180).replace('\n', ' ')
            return obj.optString("detail", obj.optString("message", obj.optString("error", "")))
                .ifBlank { body.take(180).replace('\n', ' ') }
        }
    }
}

/** JSON köməkçiləri. */
fun JSONArray?.objs(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        (opt(i) as? JSONObject)?.let { out.add(it) }
    }
    return out
}

fun String.toJsonObject(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

fun JSONObject?.optArr(name: String): JSONArray? = this?.optJSONArray(name)
