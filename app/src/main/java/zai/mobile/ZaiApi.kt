package zai.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** API xətaları — növünə görə UI fərqli reaksiya verir. */
class ApiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { UNAUTHORIZED, CAPTCHA, OTHER }
    companion object {
        fun other(msg: String) = ApiException(Kind.OTHER, msg)
        fun unauthorized() = ApiException(Kind.UNAUTHORIZED, "UNAUTHORIZED")
        fun captcha() = ApiException(Kind.CAPTCHA, "CAPTCHA")
    }
}

/** Axını kəsmək üçün — aktiv bağlantını disconnect edir (bloklayan oxunuş qayıdır). */
class CancelHandle {
    @Volatile private var conn: HttpURLConnection? = null
    fun bind(c: HttpURLConnection?) { conn = c }
    fun cancel() { try { conn?.disconnect() } catch (_: Exception) {} }
}

/**
 * chat.z.ai HTTP API — veb tətbiqinin dəqiq protokolu (WebView YOXDUR).
 * Bütün sorğular: cookie (qlobal CookieManager — App-də qurulur) + Origin/Referer
 * + X-Device-ID/X-FE-Version başlıqları, WAF 405/403 bloklarına qarşı retry.
 */
class ZaiApi(private val deviceId: String) {

    fun base(): String = baseOverride ?: "https://chat.z.ai"

    // ---------------- Köməkçilər ----------------

    private fun open(method: String, url: String, readTimeoutMs: Int = 30_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", Signature.MOBILE_UA)
        c.setRequestProperty("X-Device-ID", deviceId)
        c.setRequestProperty("X-FE-Version", Signature.FE_VERSION)
        c.setRequestProperty("Accept-Language", "en-US")
        c.setRequestProperty("Origin", base())
        c.setRequestProperty("Referer", "https://chat.z.ai/")
        return c
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..399) {
            runCatching { conn.inputStream }.getOrNull()
        } else {
            runCatching { conn.errorStream }.getOrNull()
                ?: runCatching { conn.inputStream }.getOrNull()
        }
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun parseJson(s: String): JSONObject? = runCatching { JSONObject(s) }.getOrNull()

    private fun isHtml(s: String): Boolean =
        s.contains("<!doctype", ignoreCase = true) || s.contains("<html", ignoreCase = true)

    /**
     * WAF bloklarına qarşı retry ilə sorğu göndər → (status, body).
     * WAF statusu + HTML body = blok → jitter-li backoff ilə təkrar;
     * real API xətasıdırsa → cavab birbaşa qaytarılır.
     */
    private fun sendForText(
        method: String,
        url: String,
        attempts: Int,
        cancel: CancelHandle? = null,
        readTimeoutMs: Int = 30_000,
        configure: (HttpURLConnection) -> Unit = {},
    ): Pair<Int, String> {
        var delay = 2.5
        var lastErr = "WAF block"
        var ioErrors = 0
        repeat(attempts) {
            val conn = open(method, url, readTimeoutMs)
            cancel?.bind(conn)
            configure(conn)
            try {
                val code = conn.responseCode
                val body = readBody(conn, code)
                if (isWafStatus(code) && isHtml(body)) {
                    lastErr = "WAF block (HTTP $code)"
                    Thread.sleep(Signature.jitterMs((delay * 1000).toLong()))
                    delay = (delay * 1.35).coerceAtMost(9.0)
                } else {
                    return code to body
                }
            } catch (e: java.io.IOException) {
                // şəbəkə kəsintisi — qısa retry, sonra dostcasına xəta
                ioErrors++
                if (ioErrors >= 3) {
                    throw ApiException.other("Connection error — check your internet and try again.")
                }
                lastErr = "Connection error (${e.message ?: "network"})"
                Thread.sleep(Signature.jitterMs((delay * 1000).toLong()))
                delay = (delay * 1.35).coerceAtMost(9.0)
            } finally {
                conn.disconnect()
            }
        }
        throw ApiException.other("$lastErr. Please check your connection and try again.")
    }

    // ---------------- Endpointlər ----------------

    /** Ana səhifə — WAF cookie-lərini (acw_tc) almaq üçün. */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open("GET", base() + "/")
                try { c.responseCode } finally { c.disconnect() }
            }
            Unit
        }
    }

    /** Qonaq tokeni: GET /api/v1/auths/ */
    suspend fun guestAuth(): AuthInfo = withContext(Dispatchers.IO) {
        val (code, body) = sendForText("GET", "${base()}/api/v1/auths/", 6)
        val json = parseJson(body)
        if (code !in 200..299 || json == null) {
            throw ApiException.other("Guest sign-in failed: ${json?.optString("detail") ?: "HTTP $code"}")
        }
        AuthInfo(
            token = json.optString("token"),
            userId = json.optString("id"),
            userName = json.optString("name").ifEmpty { "Guest" },
            isGuest = json.optString("role") == "guest",
        )
    }

    /** Email + şifrə ilə giriş: POST /api/v1/auths/signin */
    suspend fun signin(email: String, password: String): AuthInfo = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("email", email).put("password", password).toString()
        val (code, body) = sendForText("POST", "${base()}/api/v1/auths/signin", 6) { c ->
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }
        val json = parseJson(body)
        if (code !in 200..299) {
            val detail = json?.optString("detail") ?: ""
            if (detail.contains("captcha")) throw ApiException.captcha()
            if (detail.isEmpty()) throw ApiException.other("Sign-in failed: check your email and password")
            throw ApiException.other("Sign-in failed: $detail")
        }
        if (json == null || json.optString("token").isEmpty())
            throw ApiException.other("Sign-in failed: token missing")
        AuthInfo(
            token = json.optString("token"),
            userId = json.optString("id"),
            userName = json.optString("name").ifEmpty { "User" },
            isGuest = json.optString("role") == "guest",
        )
    }

    /** Token yoxla: GET /api/v1/auths/ + Bearer */
    suspend fun verifyToken(token: String): AuthInfo = withContext(Dispatchers.IO) {
        val (code, body) = sendForText("GET", "${base()}/api/v1/auths/", 6) { c ->
            c.setRequestProperty("Authorization", "Bearer $token")
        }
        if (code == 401 || code == 403) throw ApiException.unauthorized()
        val json = parseJson(body) ?: throw ApiException.unauthorized()
        if (code !in 200..299) throw ApiException.unauthorized()
        AuthInfo(
            token = token,
            userId = json.optString("id"),
            userName = json.optString("name").ifEmpty { "User" },
            isGuest = json.optString("role") == "guest",
        )
    }

    /** Modellər: GET /api/models — guest üçün min_available_level filtri. */
    suspend fun models(token: String, isGuest: Boolean): List<ModelInfo> = withContext(Dispatchers.IO) {
        val (code, body) = sendForText("GET", "${base()}/api/models", 6) { c ->
            c.setRequestProperty("Authorization", "Bearer $token")
        }
        val json = parseJson(body)
        if (code !in 200..299 || json == null) return@withContext fallbackModels()
        val out = mutableListOf<ModelInfo>()
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val id = m.optString("id")
                if (id.isEmpty()) continue
                val info = m.optJSONObject("info")
                val minLevel = info?.optLong("min_available_level") ?: 1L
                val effortsArr = info?.optJSONObject("meta")?.optJSONObject("info")
                    ?.optJSONArray("thinking_effort_level")
                val efforts = mutableListOf<String>()
                if (effortsArr != null) {
                    for (j in 0 until effortsArr.length()) {
                        val e = effortsArr.optString(j)
                        if (e.isNotEmpty()) efforts += e
                    }
                }
                out += ModelInfo(
                    id = id,
                    name = m.optString("name").ifEmpty { id },
                    enabled = !isGuest || minLevel <= 1L,
                    effortLevels = efforts,
                )
            }
        }
        if (out.isEmpty()) fallbackModels() else out
    }

    private fun fallbackModels(): List<ModelInfo> = listOf(
        ModelInfo("x-preview-l", "GLM-5.3-Flash", true, listOf("low", "high", "max")),
    )

    /** Söhbət siyahısı: GET /api/v1/chats/?page=N&type=default — bütün səhifələr. */
    suspend fun conversations(token: String): List<ConvSummary> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ConvSummary>()
        var prevFirstId: String? = null
        var firstPageLen: Int? = null
        var page = 1
        while (true) {
            val (_, body) = sendForText("GET", "${base()}/api/v1/chats/?page=$page&type=default", 6) { c ->
                c.setRequestProperty("Authorization", "Bearer $token")
            }
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: break
            if (arr.length() == 0) break
            val curFirstId = arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
            if (page > 1 && curFirstId != null && curFirstId == prevFirstId) break
            prevFirstId = curFirstId
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                if (id.isEmpty()) continue
                out += ConvSummary(id, c.optString("title").ifEmpty { "Chat" }, c.optLong("updated_at"))
            }
            val fl = firstPageLen
            if (fl == null) firstPageLen = out.size
            else if (out.size - fl < fl) break
            page++
            if (page > 10 || out.size > 300) break
        }
        out
    }

    /** Söhbət detalı: GET /api/v1/chats/{id} */
    suspend fun chatDetail(token: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val (code, body) = sendForText("GET", "${base()}/api/v1/chats/$id", 6) { c ->
            c.setRequestProperty("Authorization", "Bearer $token")
        }
        if (code !in 200..299) throw ApiException.other("Chat load failed (HTTP $code)")
        parseJson(body) ?: throw ApiException.other("Chat load failed: bad response")
    }

    /** Söhbət sil: DELETE /api/v1/chats/{id} */
    suspend fun chatDelete(token: String, id: String) {
        withContext(Dispatchers.IO) {
            val (code, _) = sendForText("DELETE", "${base()}/api/v1/chats/$id", 4) { c ->
                c.setRequestProperty("Authorization", "Bearer $token")
            }
            if (code !in 200..299) throw ApiException.other("Delete failed (HTTP $code)")
        }
    }

    /** Yeni söhbət: POST /api/v1/chats/new — əlavələrlə (content massivi). */
    suspend fun chatNew(
        token: String,
        prompt: String,
        model: String,
        userMsgId: String,
        files: List<UploadedFile>,
    ): String = withContext(Dispatchers.IO) {
        val chat = chatNewObject(prompt, model, userMsgId, Signature.nowMs())
        if (files.isNotEmpty()) {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            for (f in files) appendFilePart(parts, f)
            val msg = chat.optJSONObject("history")?.optJSONObject("messages")?.optJSONObject(userMsgId)
            msg?.put("content", parts)
        }
        postChat(token, "${base()}/api/v1/chats/new", chat, 12, "Chat could not be created")
    }

    /** Söhbəti yenilə (tarixçəni saxla): POST /api/v1/chats/{id} — best-effort. */
    suspend fun chatUpdate(token: String, chat: JSONObject) {
        withContext(Dispatchers.IO) {
            runCatching {
                val id = chat.optString("id")
                postChat(token, "${base()}/api/v1/chats/$id", chat, 3, "Chat update failed")
            }
            Unit
        }
    }

    private fun postChat(token: String, url: String, chat: JSONObject, attempts: Int, errPrefix: String): String {
        val payload = JSONObject().put("chat", chat).toString()
        val (code, body) = sendForText("POST", url, attempts) { c ->
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("x-region", "overseas")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }
        val json = parseJson(body)
        if (code !in 200..299) {
            throw ApiException.other("$errPrefix (HTTP $code): ${json?.optString("detail") ?: "?"}")
        }
        val id = json?.optString("id") ?: ""
        if (id.isEmpty()) throw ApiException.other("$errPrefix: chat id missing")
        return id
    }

    private fun appendFilePart(parts: JSONArray, f: UploadedFile) {
        val url = f.directUrl.ifEmpty { f.id }
        when (f.media) {
            "image" -> parts.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", url)))
            "video" -> parts.put(JSONObject().put("type", "video_url")
                .put("video_url", JSONObject().put("url", url)))
            else -> parts.put(JSONObject().put("type", "file_url")
                .put("file_url", JSONObject().put("url", url)))
        }
    }

    /**
     * SSE completion POST-u açır (WAF retry daxil). Qaytarılan bağlantının
     * axını (inputStream) ÇAĞIRAN tərəfindən oxunur; responseCode yoxlanılır.
     */
    fun openCompletions(
        token: String,
        url: String,
        sig: String,
        body: String,
        cancel: CancelHandle,
    ): HttpURLConnection {
        var delay = 2.5
        var lastErr = "WAF block"
        repeat(12) {
            fun buildAndPost(): HttpURLConnection {
                val c = open("POST", url, readTimeoutMs = 180_000)
                cancel.bind(c)
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "text/event-stream")
                c.setRequestProperty("X-Signature", sig)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                return c
            }
            val conn = buildAndPost()
            val code = conn.responseCode
            if (isWafStatus(code)) {
                val text = readBody(conn, code)
                conn.disconnect()
                if (isHtml(text)) {
                    lastErr = "WAF block (HTTP $code)"
                    Thread.sleep(Signature.jitterMs((delay * 1000).toLong()))
                    delay = (delay * 1.35).coerceAtMost(9.0)
                    return@repeat
                }
                // real API xətası — təzə bağlantı qaytar (body oxunulmaz qalsın)
                return buildAndPost()
            }
            return conn
        }
        throw ApiException.other("$lastErr. Please check your connection and try again.")
    }

    /** Bağlantının body-sini oxu (status yoxlaması üçün). */
    fun readAll(conn: HttpURLConnection, code: Int): String = readBody(conn, code)

    // ---------------- Fayl yükləmə ----------------

    /** POST /api/v1/storage/pfs/files/upload (multipart "file"). */
    suspend fun uploadFile(token: String, bytes: ByteArray, filename: String, mime: String): UploadedFile =
        withContext(Dispatchers.IO) {
            val (data, outMime, rawName) = maybeDownscale(bytes, mime, filename)
            val safeName = rawName.replace("\"", "").replace("\r", " ").replace("\n", " ")
            val boundary = "----ZaiMobileForm${Signature.nowMs()}"
            val head = ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                "Content-Type: $outMime\r\n\r\n").toByteArray(Charsets.UTF_8)
            val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            val body = head + data + tail

            val (code, resp) = sendForText("POST", "${base()}/api/v1/storage/pfs/files/upload", 4) { c ->
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val json = parseJson(resp) ?: throw ApiException.other("Upload failed (HTTP $code)")
            if (code !in 200..299 || json.optInt("code", -1) != 0) {
                val detail = json.optString("detail").ifEmpty { json.optString("message", "upload failed") }
                throw ApiException.other("Upload failed: $detail")
            }
            val f = json.optJSONObject("data")?.optJSONObject("file")
                ?: throw ApiException.other("Upload failed: file object missing")
            val id = f.optString("id").ifEmpty { f.optString("file_id") }
            if (id.isEmpty()) throw ApiException.other("Upload failed: file id missing")
            UploadedFile(
                id = id,
                name = safeName,
                media = mediaKind(outMime, safeName),
                size = f.optJSONObject("meta")?.optLong("size") ?: data.size.toLong(),
                directUrl = f.optString("direct_url"),
            )
        }

    /** Böyük şəkli endir: >1MB + tərəflərdən biri >2048px → 2048px JPEG q82. */
    private fun maybeDownscale(bytes: ByteArray, mime: String, filename: String): Triple<ByteArray, String, String> {
        val maxSide = 2048
        val threshold = 1024 * 1024
        if (!mime.startsWith("image/") || mime == "image/gif" || bytes.size <= threshold) {
            return Triple(bytes, mime, filename)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || (w <= maxSide && h <= maxSide)) return Triple(bytes, mime, filename)
        var sample = 1
        var w2 = w
        var h2 = h
        while (w2 / 2 >= maxSide || h2 / 2 >= maxSide) {
            sample *= 2; w2 /= 2; h2 /= 2
            if (sample >= 8) break
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Triple(bytes, mime, filename)
        val scale = minOf(maxSide.toFloat() / decoded.width, maxSide.toFloat() / decoded.height, 1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        val bos = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)) return Triple(bytes, mime, filename)
        val out = bos.toByteArray()
        if (out.size >= bytes.size) return Triple(bytes, mime, filename)
        val name = if (filename.lowercase().endsWith(".png")) {
            filename.substringBeforeLast('.') + ".jpg"
        } else filename
        return Triple(out, "image/jpeg", name)
    }

    companion object {
        /** Test hook (Rust ZAI_BASE ekvivalenti) — normalda null. */
        var baseOverride: String? = null

        /** MIME/ad → media növü (veb tətbiqinin təsnifatı). */
        fun mediaKind(mime: String, name: String): String {
            val m = mime.lowercase()
            if (m.startsWith("image/")) return "image"
            if (m.startsWith("video/")) return "video"
            val n = name.lowercase()
            if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") ||
                n.endsWith(".webp") || n.endsWith(".bmp")) return "image"
            if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".mkv") ||
                n.endsWith(".webm")) return "video"
            return "doc"
        }

        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
