package zai.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Aktiv söhbətin state-i (Activity sahiblik edir). */
class ChatState {
    var chatId: String = ""
    var chatTitle: String = "New Chat"
}

/**
 * Completion icrası: chat yarat → SSE oxu → xətalarada avtomatik bərpa →
 * serverdə saxla. Rust stream.rs-in Kotlin portları.
 *
 * Şəbəkə (IO) hadisələri Channel ilə Main thread-ə çatdırılır — UI callback-i
 * həmişə main-də işləyir.
 */
class ChatEngine(private val api: ZaiApi, private val session: Session) {

    fun startSend(
        scope: CoroutineScope,
        state: ChatState,
        history: List<Pair<String, String>>,      // (role, content) — keçmiş
        prompt: String,
        attachments: List<UploadedFile>,
        model: String,
        effort: String,
        isFirst: Boolean,
        onEvent: (StreamEvent) -> Unit,
    ): Job {
        val events = Channel<StreamEvent>(Channel.UNLIMITED)
        val cancel = CancelHandle()
        val userMsgId = Signature.genUuid()
        val assistantMsgId = Signature.genUuid()

        val job = scope.launch {
            // İstehsalçı — IO-də: bütün bloklayan şəbəkə işi burada
            launch(Dispatchers.IO) {
                try {
                    runCompletion(
                        events, cancel, state, history, prompt, attachments,
                        model, effort, userMsgId, assistantMsgId, isFirst,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    runCatching {
                        when {
                            e is ApiException && e.kind == ApiException.Kind.UNAUTHORIZED ->
                                events.send(StreamEvent.Unauthorized)
                            e is ApiException && e.kind == ApiException.Kind.CAPTCHA ->
                                events.send(StreamEvent.CaptchaRequired)
                            else -> events.send(StreamEvent.Error(e.message ?: "Unknown error"))
                        }
                    }
                } finally {
                    events.close()
                }
            }
            // İstehlakçı — Main-də: UI yeniləmələri
            for (ev in events) onEvent(ev)
        }
        // Job bitəndə (stop/logout/destroy) bloklayan oxunuş da kəsilsin:
        job.invokeOnCompletion { cancel.cancel() }
        return job
    }

    // ---------------- əsas axın (retry ilə) ----------------
    private suspend fun runCompletion(
        events: Channel<StreamEvent>,
        cancel: CancelHandle,
        state: ChatState,
        history: List<Pair<String, String>>,
        prompt: String,
        attachments: List<UploadedFile>,
        model: String,
        effort: String,
        userMsgId: String,
        assistantMsgId: String,
        isFirst: Boolean,
    ) {
        // 1. Chat yarat (lazımsa)
        if (state.chatId.isEmpty()) {
            state.chatId = api.chatNew(session.token, prompt, model, userMsgId, attachments)
            events.send(StreamEvent.ChatCreated(state.chatId))
        }

        // 2. Əsas axın — retry ilə
        var attempt = 0
        val maxAttempts = 4
        while (true) {
            attempt++
            try {
                runOnce(
                    events, cancel, state, history, prompt, attachments,
                    model, effort, userMsgId, assistantMsgId, isFirst,
                )
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val msg = e.message ?: ""
                when {
                    e is ApiException && e.kind == ApiException.Kind.UNAUTHORIZED -> throw e
                    e is ApiException && e.kind == ApiException.Kind.CAPTCHA -> throw e
                    msg.startsWith("RETRY:") || isRetryableError(msg) -> {
                        val inner = if (msg.startsWith("RETRY:")) msg.removePrefix("RETRY:") else cleanError(msg)
                        if (attempt >= maxAttempts) throw ApiException.other(inner)
                        events.send(StreamEvent.Reset)
                        events.send(StreamEvent.Phase("Retrying…"))
                        delay(Signature.jitterMs(1200 + 600L * attempt))
                    }
                    else -> throw e
                }
            }
        }
    }

    // ---------------- bir cəhd ----------------
    private suspend fun runOnce(
        events: Channel<StreamEvent>,
        cancel: CancelHandle,
        state: ChatState,
        history: List<Pair<String, String>>,
        prompt: String,
        attachments: List<UploadedFile>,
        model: String,
        effort: String,
        userMsgId: String,
        assistantMsgId: String,
        isFirst: Boolean,
    ) {
        val token = session.token
        val sig = Signature.make(prompt, token, session.userId)

        // mesajlar
        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        if (attachments.isEmpty()) {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        } else {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            for (f in attachments) {
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
            messages.put(JSONObject().put("role", "user").put("content", parts))
        }

        val body = JSONObject()
            .put("stream", true)
            .put("model", model)
            .put("messages", messages)
            .put("signature_prompt", prompt.trim())
            .put("params", JSONObject())
            .put("features", JSONObject()
                .put("image_generation", false)
                .put("web_search", false)
                .put("auto_web_search", false)
                .put("preview_mode", false)
                .put("flags", JSONArray().put("general_agent"))
                .put("vlm_tools_enable", false)
                .put("vlm_web_search_enable", false)
                .put("vlm_website_mode", false)
                .put("enable_thinking", true)
                .put("reasoning_effort", effort))
            .put("variables", JSONObject())
            .put("session_id", Signature.genUuid())
            .put("chat_id", state.chatId)
            .put("id", assistantMsgId)
            .put("current_user_message_id", userMsgId)
            .put("current_user_message_parent_id", JSONObject.NULL)
            .put("background_tasks", JSONObject()
                .put("title_generation", isFirst)
                .put("tags_generation", isFirst))
            .toString()

        val url = "${api.base()}/api/v2/chat/completions?${sig.urlParams}&signature_timestamp=${sig.timestamp}"

        val conn = try {
            api.openCompletions(token, url, sig.signature, body, cancel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException.other("RETRY:Stream interrupted (network).")
        }

        try {
            val code = try {
                conn.responseCode
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ApiException.other("RETRY:Stream interrupted (network).")
            }
            if (code == 401 || code == 403) {
                val txt = api.readAll(conn, code)
                if (txt.contains("captcha", ignoreCase = true)) throw ApiException.captcha()
                throw ApiException.unauthorized()
            }
            if (code !in 200..299) {
                val txt = api.readAll(conn, code)
                if (txt.contains("captcha", ignoreCase = true)) throw ApiException.captcha()
                throw ApiException.other("HTTP $code: ${txt.take(180)}")
            }

            // ---------- SSE oxu ----------
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val acc = Sse.Accumulator()
            val full = StringBuilder()
            var gotDone = false
            var hadError: String? = null

            while (true) {
                currentCoroutineContext().ensureActive()   // stop düyməsi
                val line = try {
                    reader.readLine() ?: break
                } catch (e: Exception) {
                    break // oxu xətası — aşağıda kəsinti kimi
                }
                val t = line.trim()
                if (t.isEmpty() || !t.startsWith("data:")) continue
                val payload = t.removePrefix("data:").trim()
                if (payload == "[DONE]") { gotDone = true; break }
                val fullPayload = acc.feed(payload) ?: continue
                when (val ev = Sse.parseEvent(fullPayload)) {
                    is StreamEvent.Delta -> { full.append(ev.text); events.send(ev) }
                    is StreamEvent.Phase -> events.send(ev)
                    StreamEvent.Reset -> Unit
                    StreamEvent.Done -> gotDone = true
                    is StreamEvent.Error -> hadError = ev.message
                    StreamEvent.CaptchaRequired -> throw ApiException.captcha()
                    StreamEvent.Unauthorized -> throw ApiException.unauthorized()
                    is StreamEvent.ChatCreated -> Unit
                    null -> Unit
                }
                if (hadError != null) break
                if (gotDone) break
            }

            // ---------- serverdə saxla (best-effort) ----------
            if (full.isNotEmpty()) {
                runCatching {
                    api.chatUpdate(
                        session.token,
                        buildChatUpdate(
                            state.chatId, state.chatTitle, model, effort, history,
                            userMsgId, assistantMsgId, prompt, full.toString(), attachments,
                        ),
                    )
                }
            }

            hadError?.let { throw ApiException.other(it) }
            if (!gotDone) throw ApiException.other("RETRY:Stream interrupted (network).")
        } finally {
            cancel.bind(null)
            conn.disconnect()
        }
    }

    // ---------------- serverdə saxlama obyekti (veb formatı) ----------------
    private fun buildChatUpdate(
        chatId: String,
        title: String,
        model: String,
        effort: String,
        history: List<Pair<String, String>>,
        userMsgId: String,
        assistantMsgId: String,
        prompt: String,
        fullContent: String,
        files: List<UploadedFile>,
    ): JSONObject {
        val ts = Signature.nowMs() / 1000
        val ids = (0 until history.size).map { "msg-$it" } + userMsgId + assistantMsgId

        val messages = JSONObject()
        history.forEachIndexed { i, (role, content) ->
            val id = ids[i]
            val child = ids.getOrNull(i + 1)
            messages.put(id, JSONObject()
                .put("id", id)
                .put("parentId", if (i > 0) ids[i - 1] else JSONObject.NULL)
                .put("childrenIds", child?.let { JSONArray().put(it) } ?: JSONArray())
                .put("role", role)
                .put("content", content)
                .put("timestamp", ts)
                .put("models", JSONArray().put(model)))
        }
        val n = ids.size
        val userContent: Any = if (files.isEmpty()) prompt else {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            for (f in files) {
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
            parts
        }
        messages.put(userMsgId, JSONObject()
            .put("id", userMsgId)
            .put("parentId", if (n >= 2) ids[n - 2] else JSONObject.NULL)
            .put("childrenIds", JSONArray().put(assistantMsgId))
            .put("role", "user")
            .put("content", userContent)
            .put("timestamp", ts)
            .put("models", JSONArray().put(model)))
        messages.put(assistantMsgId, JSONObject()
            .put("id", assistantMsgId)
            .put("parentId", userMsgId)
            .put("childrenIds", JSONArray())
            .put("role", "assistant")
            .put("content", if (fullContent.isEmpty()) "…" else fullContent)
            .put("timestamp", ts)
            .put("model", model)
            .put("modelName", model)
            .put("modelIdx", 0)
            .put("done", true)
            .put("error", false))

        return JSONObject()
            .put("id", chatId)
            .put("title", title)
            .put("models", JSONArray().put(model))
            .put("params", JSONObject())
            .put("history", JSONObject().put("messages", messages).put("currentId", assistantMsgId))
            .put("tags", JSONArray())
            .put("flags", JSONArray().put("general_agent"))
            .put("features", JSONArray()
                .put(JSONObject().put("server", "web_search_h").put("status", "hidden").put("type", "web_search"))
                .put(JSONObject().put("server", "tool_selector_h").put("status", "hidden").put("type", "tool_selector"))
                .put(JSONObject().put("server", "hidden-thinking").put("status", "hidden").put("type", "hidden-thinking")))
            .put("mcp_servers", JSONArray())
            .put("enable_thinking", true)
            .put("reasoning_effort", effort)
            .put("auto_web_search", false)
            .put("message_version", 1)
            .put("extra", JSONObject())
            .put("timestamp", Signature.nowMs())
            .put("type", "general_agent")
    }
}
