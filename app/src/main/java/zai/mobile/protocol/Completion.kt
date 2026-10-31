package zai.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * /api/v2/chat/completions — spec §5.
 * SSE: data: sətirlər, çoxsətirli birləşdirmə, [DONE], delta_content / phase /
 * done, /data/error yolları. 150s sükut → readTimeout (Transport) → DROPPED.
 */
sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data class Phase(val raw: String, val label: String) : StreamEvent()
    object Reset : StreamEvent()
    object Done : StreamEvent()
    data class Error(val ex: ZaiException) : StreamEvent()
}

enum class StreamOutcome {
    /** [DONE] və ya done=true — normal bitiş. */
    COMPLETED,
    /** İstifadəçi dayandırdı — qismən mətn saxlanılır, xəta yox. */
    STOPPED,
    /** Axın kəsildi (EOF/xəta) — çağıran tərəf təkrar edə bilər. */
    DROPPED,
    /** Server xətası — onEvent(Error) artıq göndərilib. */
    FAILED,
}

/** Faza etiketləri — spec §5 (UI dili EN, sayt kimi). */
object Phases {
    fun label(raw: String?): String {
        val p = raw.orEmpty().lowercase()
        return when {
            p in setOf("think", "thinking", "hidden-thinking", "reasoning") -> "Thinking…"
            p in setOf("search", "web_search") -> "Searching the web…"
            p.startsWith("tool") -> "Using tools…"
            p.startsWith("code") -> "Running code…"
            p == "title" -> "Writing title…"
            else -> "Preparing…"
        }
    }
}

class CompletionApi(private val http: ZaiHttp) {

    /**
     * Sorğu body-si — spec §5, dəqiq sahələr.
     * messages = əvvəlki növbələr (string content) + cari user mesajı
     * (attachment varsa content massivi).
     */
    fun buildBody(
        model: String,
        history: List<Pair<String, String>>,
        prompt: String,
        promptAttachments: List<AttachPart>,
        signaturePrompt: String,
        sessionId: String,
        chatId: String,
        assistantMsgId: String,
        userMsgId: String,
        isFirst: Boolean,
        effort: String,
    ): ByteArray {
        val prior = JSONArray()
        for ((role, text) in history) {
            prior.put(JSONObject().put("role", role).put("content", text))
        }
        val currentContent: Any = if (promptAttachments.isEmpty()) {
            prompt
        } else {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", prompt))
            for (a in promptAttachments) {
                arr.put(JSONObject().put("type", a.kind).put(a.kind, JSONObject().put("url", a.url)))
            }
            arr
        }
        prior.put(JSONObject().put("role", "user").put("content", currentContent))
        val body = JSONObject()
            .put("stream", true)
            .put("model", model)
            .put("messages", prior)
            .put("signature_prompt", signaturePrompt.trim())
            .put("params", JSONObject())
            .put(
                "features",
                JSONObject()
                    .put("image_generation", false)
                    .put("web_search", false)
                    .put("auto_web_search", false)
                    .put("preview_mode", false)
                    .put("flags", JSONArray().put("general_agent"))
                    .put("vlm_tools_enable", false)
                    .put("vlm_web_search_enable", false)
                    .put("vlm_website_mode", false)
                    .put("enable_thinking", true)
                    .put("reasoning_effort", effort),
            )
            .put("variables", JSONObject())
            .put("session_id", sessionId)
            .put("chat_id", chatId)
            .put("id", assistantMsgId)
            .put("current_user_message_id", userMsgId)
            .put("current_user_message_parent_id", JSONObject.NULL)
            .put(
                "background_tasks",
                JSONObject()
                    .put("title_generation", isFirst)
                    .put("tags_generation", isFirst),
            )
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Bir POST + tam SSE oxunuşu. Hər WAF-təkrarında imza YENİDƏN hesablanır
     * (build lambda'sı http.stream içində cəhd başına çağrılır).
     */
    fun streamOnce(
        token: String,
        userId: String,
        fingerprint: Signature.Fingerprint,
        prompt: String,
        bodyBytes: ByteArray,
        onEvent: (StreamEvent) -> Unit,
        isStopped: () -> Boolean,
    ): StreamOutcome {
        val out = http.stream(
            attempts = HTTP_ATTEMPTS,
            build = {
                val s = Signature.sign(prompt, userId, token, fingerprint)
                ZaiHttp.StreamCall(
                    path = s.path,
                    headers = listOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "application/json",
                        "Accept" to "text/event-stream",
                        "X-Signature" to s.xSignature,
                    ),
                    body = bodyBytes,
                )
            },
        )
        if (out.code != 200) {
            val bodyTxt = String(out.errorBody(), Charsets.UTF_8)
            onEvent(StreamEvent.Error(ZaiException.fromHttp(out.code, bodyTxt)))
            return StreamOutcome.FAILED
        }
        return Sse.read(out.body ?: ByteArray(0).inputStream(), out.cancel, onEvent, isStopped)
    }

    companion object {
        const val HTTP_ATTEMPTS = 12
    }
}

/**
 * SSE oxuyucusu — spec §5-in dəqiq portuna əsasən, protocol qatından
 * asılı olmayan funksiya (testlərdə birbaşa çağrılır).
 */
object Sse {

    /** "/a/b" formasındakı yolu JSON-da gəzir; tapılmadıqda null. */
    fun at(obj: JSONObject?, path: String): JSONObject? {
        var cur: Any? = obj ?: return null
        for (seg in path.trim('/').split('/')) {
            if (seg.isBlank()) continue
            cur = (cur as? JSONObject)?.opt(seg) ?: return null
        }
        return cur as? JSONObject
    }

    fun read(
        body: InputStream,
        cancel: () -> Unit,
        onEvent: (StreamEvent) -> Unit,
        isStopped: () -> Boolean,
    ): StreamOutcome {
        var pending = ""
        val reader = BufferedReader(InputStreamReader(body, Charsets.UTF_8))
        try {
            while (true) {
                val raw = reader.readLine() ?: break
                if (isStopped()) {
                    cancel()
                    return StreamOutcome.STOPPED
                }
                val line = raw.trim()
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") {
                    onEvent(StreamEvent.Done)
                    return StreamOutcome.COMPLETED
                }
                var ev: JSONObject? = null
                try {
                    ev = JSONObject(payload)
                    pending = ""
                } catch (e: org.json.JSONException) {
                    if (pending.isNotEmpty()) {
                        val joined = "$pending\n$payload"
                        try {
                            ev = JSONObject(joined)
                            pending = ""
                        } catch (e2: org.json.JSONException) {
                            pending = joined
                        }
                    } else {
                        pending = payload
                    }
                }
                if (ev == null) continue

                val err = at(ev, "/data/error")
                    ?: at(ev, "/data/data/error")
                    ?: at(ev, "/error")
                if (err != null) {
                    onEvent(StreamEvent.Error(ZaiException.fromStreamError(err)))
                    return StreamOutcome.FAILED
                }

                val msg = at(ev, "/data/message")
                    ?: at(ev, "/data/data/message")
                    ?: at(ev, "/message")
                    ?: at(ev, "/data/data/data")
                if (msg != null) {
                    val delta = msg.optString("delta_content", "")
                    if (delta.isNotEmpty()) onEvent(StreamEvent.Delta(delta))
                    val phase = msg.optString("phase", "")
                    if (phase.isNotBlank() && phase != "answer" && phase != "other") {
                        onEvent(StreamEvent.Phase(phase, Phases.label(phase)))
                    }
                    if (msg.optBoolean("done", false)) {
                        onEvent(StreamEvent.Done)
                        return StreamOutcome.COMPLETED
                    }
                }
            }
        } catch (e: IOException) {
            cancel()
            return if (isStopped()) StreamOutcome.STOPPED else StreamOutcome.DROPPED
        } finally {
            runCatching { body.close() }
        }
        // EOF — [DONE]/done olmadan: şəbəkə kəsilməsi (spec §5)
        return if (isStopped()) StreamOutcome.STOPPED else StreamOutcome.DROPPED
    }
}
