package zai.mobile.protocol

import org.json.JSONObject

/**
 * Autentifikasiya — spec §3.
 * Qonaq: GET /api/v1/auths/ (Authorization YOXDUR).
 * Email: POST /api/v1/auths/signin {"email","password"}.
 * Yoxlama: GET /api/v1/auths/ + Bearer.
 */
data class AuthInfo(
    val token: String,
    val userId: String,
    val userName: String,
    val isGuest: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject): AuthInfo = AuthInfo(
            token = o.optString("token"),
            userId = o.optString("id"),
            userName = o.optString("name"),
            isGuest = o.optString("role") == "guest",
        )
    }
}

sealed class AuthResult {
    class Ok(val info: AuthInfo) : AuthResult()

    /** Server captcha tələb edir — brauzer + token yolu (spec §3.4). */
    class Captcha(val detail: String) : AuthResult()

    class Failed(val error: ZaiException) : AuthResult()
}

class AuthApi(private val http: ZaiHttp) {

    private fun authHeaders(token: String?): List<Pair<String, String>> =
        if (token == null) emptyList() else listOf("Authorization" to "Bearer $token")

    private fun parse(out: ZaiHttp.JsonOut): AuthInfo {
        if (out.code !in 200..299) {
            throw ZaiException.fromHttp(out.code, String(out.bytes, Charsets.UTF_8))
        }
        val obj = String(out.bytes, Charsets.UTF_8).toJsonObject()
            ?: throw ZaiException.Bug("auth: non-JSON 200 response")
        val info = AuthInfo.fromJson(obj)
        if (info.token.isBlank()) throw ZaiException.Bug("auth: empty token")
        return info
    }

    /** GET /api/v1/auths/ Authorization-siz → qonaq tokeni. Şəbəkə xətasında 6s sonra 1 təkrar (spec §3.3). */
    fun guest(): AuthInfo {
        var lastError: ZaiException.Network? = null
        repeat(2) {
            try {
                return parse(
                    http.json(
                        attempts = AUTH_ATTEMPTS,
                        path = { "/api/v1/auths/" },
                    )
                )
            } catch (e: ZaiException.Network) {
                lastError = e
                if (it == 0) Thread.sleep(6000)
            }
        }
        throw lastError ?: ZaiException.Network(IllegalStateException("unreachable"))
    }

    /** POST signin — captcha cavabı AuthResult.Captcha olur (login 2-ci addım). */
    fun signin(email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            val out = http.json(
                attempts = AUTH_ATTEMPTS,
                method = "POST",
                path = { "/api/v1/auths/signin" },
                headers = { listOf("Content-Type" to "application/json") },
                body = { body },
            )
            AuthResult.Ok(parse(out))
        } catch (e: ZaiException.Http) {
            if (ZaiException.looksLikeCaptcha(e.detail)) AuthResult.Captcha(e.detail)
            else AuthResult.Failed(e)
        } catch (e: ZaiException.Captcha) {
            AuthResult.Captcha(e.detail)
        } catch (e: ZaiException.Unauthorized) {
            if (ZaiException.looksLikeCaptcha(e.detail)) AuthResult.Captcha(e.detail)
            else AuthResult.Failed(e)
        }
    }

    /** Bearer ilə yoxlama; etibarsızsa null (qonaq düşmə), şəbəkə xətasında throw. */
    fun verify(token: String): AuthInfo? {
        val out = http.json(
            attempts = AUTH_ATTEMPTS,
            path = { "/api/v1/auths/" },
            headers = { authHeaders(token) },
        )
        if (out.code !in 200..299) return null
        return parse(out)
    }

    /** Token-sə giriş (brauzer/köçürmə yolu) — yoxla və tətbiq et. */
    fun applyToken(token: String): AuthInfo {
        val t = token.trim()
        require(t.length > 20) { "token too short" }
        val info = verify(t) ?: throw ZaiException.Unauthorized("Token not accepted by z.ai")
        return info
    }

    companion object {
        const val AUTH_ATTEMPTS = 6
    }
}
