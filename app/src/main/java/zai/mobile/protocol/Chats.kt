package zai.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Söhbətlərin idarəsi — spec §4/§7.
 * Siyahı, tarixçə (zəncir gəzintisi), yaratma, yeniləmə, silmə.
 * Bütün başlıqlar/tarixçə YALNIZ serverdən gəlir — statik data YOXDUR.
 */
data class ChatSummary(val id: String, val title: String, val updatedAt: Long)

data class AttachPart(val kind: String, val url: String) {
    companion object {
        val KINDS = listOf("image_url", "video_url", "file_url")
    }
}

data class HistoryMsg(
    val id: String,
    val role: String,
    val text: String,
    val attachments: List<AttachPart>,
    val timestamp: Long,
)

data class ChatDetail(
    val id: String,
    val title: String,
    val messages: List<HistoryMsg>,
)

class ChatsApi(private val http: ZaiHttp) {

    private fun auth(token: String) = listOf("Authorization" to "Bearer $token")

    /**
     * Söhbət siyahısı — səhifələnmiş (page 1..10): boş səhifə, qısa səhifə,
     * təkrar ilk-id və ya >300 → dayan (spec §4).
     */
    fun list(token: String): List<ChatSummary> {
        val out = ArrayList<ChatSummary>(32)
        var firstPageSize = -1
        var firstIdOfPage1: String? = null
        for (page in 1..MAX_PAGES) {
            val resp = http.json(
                attempts = LIST_ATTEMPTS,
                path = { "/api/v1/chats/?page=$page&type=default" },
                headers = { auth(token) },
            )
            if (resp.code !in 200..299) {
                throw ZaiException.fromHttp(resp.code, String(resp.bytes, Charsets.UTF_8))
            }
            val items = parseArray(resp.bytes)
            if (items.isEmpty()) break
            if (page == 1) {
                firstPageSize = items.size
                firstIdOfPage1 = items.first().id
            } else {
                if (items.first().id == firstIdOfPage1) break // server səhifələməni görmür
                if (items.size < firstPageSize) {
                    out.addAll(items); break
                }
            }
            out.addAll(items)
            if (out.size > MAX_CHATS) break
        }
        return out.take(MAX_CHATS)
    }

    private fun parseArray(bytes: ByteArray): List<ChatSummary> {
        val txt = String(bytes, Charsets.UTF_8)
        val arr = runCatching { JSONArray(txt) }.getOrNull()
            ?: txt.toJsonObject()?.optJSONArray("chats")
            ?: return emptyList()
        return arr.objs().mapNotNull { o ->
            val id = o.optString("id")
            if (id.isBlank()) null
            else ChatSummary(
                id = id,
                title = o.optString("title").ifBlank { "Untitled" },
                updatedAt = o.optLong("updated_at", 0L) * 1000L,
            )
        }
    }

    /**
     * Bir söhbətin tarixçəsi: currentId → parentId zənciri (maks 500), tərs çevrilmiş.
     * content: string və ya hissələr massivi (§4).
     */
    fun detail(token: String, chatId: String): ChatDetail {
        val resp = http.json(
            attempts = DETAIL_ATTEMPTS,
            path = { "/api/v1/chats/$chatId" },
            headers = { auth(token) },
        )
        if (resp.code !in 200..299) {
            throw ZaiException.fromHttp(resp.code, String(resp.bytes, Charsets.UTF_8))
        }
        val obj = String(resp.bytes, Charsets.UTF_8).toJsonObject()
            ?: throw ZaiException.Bug("chat detail: non-JSON 200 response")
        val title = obj.optString("title").ifBlank { "Untitled" }
        val history = obj.optJSONObject("history") ?: JSONObject()
        val messages = history.optJSONObject("messages") ?: JSONObject()
        val currentId = history.optString("currentId")

        // currentId-dən geriye: parentId zənciri
        val chain = ArrayList<HistoryMsg>(64)
        var cursor: String? = currentId.ifBlank { null }
        var hops = 0
        val seen = HashSet<String>()
        while (cursor != null && hops < MAX_HOPS && cursor !in seen) {
            seen.add(cursor)
            val m = messages.optJSONObject(cursor) ?: break
            val role = m.optString("role")
            val content = parseContent(m.opt("content"))
            chain.add(
                HistoryMsg(
                    id = cursor,
                    role = role,
                    text = content.first,
                    attachments = content.second,
                    timestamp = m.optLong("timestamp", 0L) * 1000L,
                )
            )
            cursor = m.opt("parentId") as? String
            if (cursor != null && cursor.isBlank()) cursor = null
            hops++
        }
        return ChatDetail(id = chatId, title = title, messages = chain.asReversed())
    }

    /** content: string → (text, []); array → text hissələr + attachment hissələri. */
    internal fun parseContent(raw: Any?): Pair<String, List<AttachPart>> {
        when (raw) {
            is String -> return raw to emptyList()
            is JSONArray -> {
                val texts = StringBuilder()
                val atts = ArrayList<AttachPart>()
                for (part in raw.objs()) {
                    when (part.optString("type")) {
                        "text" -> {
                            if (texts.isNotEmpty()) texts.append('\n')
                            texts.append(part.optString("text"))
                        }
                        "image_url" -> atts.add(
                            AttachPart("image_url", part.optJSONObject("image_url")?.optString("url").orEmpty())
                        )
                        "video_url" -> atts.add(
                            AttachPart("video_url", part.optJSONObject("video_url")?.optString("url").orEmpty())
                        )
                        "file_url" -> atts.add(
                            AttachPart("file_url", part.optJSONObject("file_url")?.optString("url").orEmpty())
                        )
                    }
                }
                return texts.toString() to atts
            }
            else -> return "" to emptyList()
        }
    }

    /** POST /api/v1/chats/new → server chat_id (12 cəhd, spec §8). */
    fun create(token: String, chatObject: JSONObject): String {
        val body = JSONObject().put("chat", chatObject).toString().toByteArray(Charsets.UTF_8)
        val resp = http.json(
            attempts = CREATE_ATTEMPTS,
            method = "POST",
            path = { "/api/v1/chats/new" },
            headers = {
                listOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "x-region" to "overseas",
                )
            },
            body = { body },
        )
        if (resp.code !in 200..299) {
            throw ZaiException.fromHttp(resp.code, String(resp.bytes, Charsets.UTF_8))
        }
        val id = String(resp.bytes, Charsets.UTF_8).toJsonObject()?.optString("id").orEmpty()
        if (id.isBlank()) throw ZaiException.Bug("chats/new: no id in response")
        return id
    }

    /** POST /api/v1/chats/{id} — tarixçəni serverdə saxla (3 cəhd). */
    fun update(token: String, chatId: String, chatObject: JSONObject) {
        val body = JSONObject().put("chat", chatObject).toString().toByteArray(Charsets.UTF_8)
        val resp = http.json(
            attempts = UPDATE_ATTEMPTS,
            method = "POST",
            path = { "/api/v1/chats/$chatId" },
            headers = {
                listOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                )
            },
            body = { body },
        )
        if (resp.code !in 200..299) {
            throw ZaiException.fromHttp(resp.code, String(resp.bytes, Charsets.UTF_8))
        }
    }

    /** DELETE /api/v1/chats/{id} (4 cəhd). */
    fun delete(token: String, chatId: String) {
        val resp = http.json(
            attempts = DELETE_ATTEMPTS,
            method = "DELETE",
            path = { "/api/v1/chats/$chatId" },
            headers = { auth(token) },
        )
        if (resp.code !in 200..299) {
            throw ZaiException.fromHttp(resp.code, String(resp.bytes, Charsets.UTF_8))
        }
    }

    companion object {
        const val MAX_PAGES = 10
        const val MAX_CHATS = 300
        const val MAX_HOPS = 500
        const val LIST_ATTEMPTS = 6
        const val CREATE_ATTEMPTS = 12
        const val UPDATE_ATTEMPTS = 3
        const val DELETE_ATTEMPTS = 4
        const val DETAIL_ATTEMPTS = 6
    }
}

/**
 * /api/v1/chats/… üçün göndərilən obyektlər — spec §7, dəqiq formalarda.
 */
object ChatObjects {

    /** İlk mesajla yeni söhbət (chats/new body içində "chat"). */
    fun newChat(model: String, prompt: String, userMsgId: String, ts: Long): JSONObject {
        val history = JSONObject()
            .put(
                "messages",
                JSONObject().put(
                    userMsgId,
                    JSONObject()
                        .put("id", userMsgId)
                        .put("parentId", JSONObject.NULL)
                        .put("childrenIds", JSONArray())
                        .put("role", "user")
                        .put("content", prompt)
                        .put("timestamp", ts / 1000)
                        .put("models", JSONArray().put(model)),
                )
            )
            .put("currentId", userMsgId)
        return envelope(model, history, "high", ts)
    }

    /**
     * Tamamlanmadan sonra tam tarixçə (chats/{id} body içində "chat"):
     * sintetik id-lər msg-0..N + real user/assistant id-ləri (spec §7).
     */
    fun updatedChat(
        model: String,
        modelName: String,
        turns: List<Pair<String, String>>,
        userMsgId: String,
        assistantMsgId: String,
        reasoningEffort: String,
        ts: Long,
    ): JSONObject {
        val messages = JSONObject()
        var synth = 0
        for (i in turns.indices) {
            val (role, text) = turns[i]
            val isLastUser = i == turns.size - 2 && role == "user"
            val isLastAssistant = i == turns.size - 1 && role == "assistant"
            val id = when {
                isLastUser -> userMsgId
                isLastAssistant -> assistantMsgId
                else -> "msg-${synth++}"
            }
            val m = JSONObject()
                .put("id", id)
                .put("parentId", JSONObject.NULL)
                .put("childrenIds", JSONArray())
                .put("role", role)
                .put("content", text)
                .put("timestamp", ts / 1000 + i)
            if (isLastAssistant) {
                m.put("model", model)
                    .put("modelName", modelName)
                    .put("modelIdx", 0)
                    .put("done", true)
                    .put("error", false)
            } else {
                m.put("models", JSONArray().put(model))
            }
            messages.put(id, m)
        }
        val history = JSONObject()
            .put("messages", messages)
            .put("currentId", assistantMsgId)
        return envelope(model, history, reasoningEffort, ts)
    }

    private fun envelope(model: String, history: JSONObject, effort: String, ts: Long): JSONObject {
        return JSONObject()
            .put("id", "")
            .put("title", "New Chat")
            .put("models", JSONArray().put(model))
            .put("params", JSONObject())
            .put("history", history)
            .put("tags", JSONArray())
            .put("flags", JSONArray().put("general_agent"))
            .put(
                "features",
                JSONArray()
                    .put(JSONObject().put("server", "web_search_h").put("status", "hidden").put("type", "web_search"))
                    .put(JSONObject().put("server", "tool_selector_h").put("status", "hidden").put("type", "tool_selector"))
                    .put(JSONObject().put("server", "hidden-thinking").put("status", "hidden").put("type", "hidden-thinking")),
            )
            .put("mcp_servers", JSONArray())
            .put("enable_thinking", true)
            .put("reasoning_effort", effort)
            .put("auto_web_search", false)
            .put("message_version", 1)
            .put("extra", JSONObject())
            .put("timestamp", ts)
            .put("type", "general_agent")
    }
}
