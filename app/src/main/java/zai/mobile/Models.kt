package zai.mobile

import org.json.JSONArray
import org.json.JSONObject

/** API-dən gələn model. */
data class ModelInfo(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val effortLevels: List<String> = emptyList(),
)

/** Söhbət siyahısı elementi. */
data class ConvSummary(val id: String, val title: String, val updatedAt: Long)

/** Yüklənmiş əlavə fayl. */
data class UploadedFile(
    val id: String,
    val name: String,
    val media: String,        // "image" | "video" | "doc"
    val size: Long,
    val directUrl: String,
)

/** Autentikasiya məlumatı. */
data class AuthInfo(
    val token: String,
    val userId: String,
    val userName: String,
    val isGuest: Boolean,
)

/** Chatdəki bir mesaj (UI modeli). */
data class ChatMsg(
    val id: String,
    val role: String,                 // "user" | "assistant"
    var content: String = "",
    var attachments: List<UploadedFile> = emptyList(),
    var streaming: Boolean = false,
    var error: Boolean = false,
)

/** SSE axınından gələn hadisələr (Dərs 06-dakı sealed modelin istehsal versiyası). */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Phase(val label: String) : StreamEvent
    /** Axın sıfırdan başlayır (avtomatik bərpa) — qismən mətn silinməlidir. */
    data object Reset : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
    data object CaptchaRequired : StreamEvent
    data object Unauthorized : StreamEvent
    data class ChatCreated(val chatId: String) : StreamEvent
}

/** WAF-in blok statusu olub-olmaması (Alibaba CDN 405/403/429/509). */
fun isWafStatus(code: Int): Boolean = code == 405 || code == 403 || code == 429 || code == 509

/** Xətanın avtomatik təkrar üçün uyğunluğu. */
fun isRetryableError(msg: String): Boolean =
    msg.startsWith("RETRYABLE:") ||
    msg.contains("Stream interrupted") ||
    msg.contains("WAF block") ||
    msg.contains("HTTP 502") ||
    msg.contains("HTTP 503") ||
    msg.contains("HTTP 504") ||
    msg.contains("HTTP 429")

fun cleanError(msg: String): String = msg.removePrefix("RETRYABLE:")

/** Axın fazasının insan-dostu etiketi. */
fun phaseLabel(p: String): String = when (p) {
    "think", "thinking", "hidden-thinking", "reasoning" -> "Thinking…"
    "search", "web_search" -> "Searching the web…"
    "tool", "tool_response", "tool_call" -> "Using tools…"
    "code", "code_interpreter" -> "Running code…"
    "title" -> "Writing title…"
    else -> "Preparing…"
}

/** API xətalarının dostlaşdırılmış formaları. */
fun friendlyError(code: String, detail: String): String {
    if (code == "426" || detail.contains("outdated")) return "App version is outdated — please update."
    if (detail.contains("user_limit") || detail.contains("rate_limit"))
        return "RETRYABLE:Rate limit reached — retrying automatically…"
    if (detail.contains("global_limit"))
        return "RETRYABLE:The server is busy — retrying automatically…"
    if (detail.contains("busy") || detail.contains("overloaded") || detail.contains("temporarily"))
        return "RETRYABLE:Server is busy — retrying automatically…"
    return detail
}

/**
 * chats/new və chat_update üçün chat obyekti (veb tətbiqinin dəqiq formatı).
 */
fun chatNewObject(prompt: String, model: String, userMsgId: String, ts: Long): JSONObject {
    val userMsg = JSONObject()
        .put("id", userMsgId)
        .put("parentId", JSONObject.NULL)
        .put("childrenIds", JSONArray())
        .put("role", "user")
        .put("content", prompt)
        .put("timestamp", ts / 1000)
        .put("models", JSONArray().put(model))
    return JSONObject()
        .put("id", "")
        .put("title", "New Chat")
        .put("models", JSONArray().put(model))
        .put("params", JSONObject())
        .put("history", JSONObject()
            .put("messages", JSONObject().put(userMsgId, userMsg))
            .put("currentId", userMsgId))
        .put("tags", JSONArray())
        .put("flags", JSONArray().put("general_agent"))
        .put("features", JSONArray()
            .put(JSONObject().put("server", "web_search_h").put("status", "hidden").put("type", "web_search"))
            .put(JSONObject().put("server", "tool_selector_h").put("status", "hidden").put("type", "tool_selector"))
            .put(JSONObject().put("server", "hidden-thinking").put("status", "hidden").put("type", "hidden-thinking")))
        .put("mcp_servers", JSONArray())
        .put("enable_thinking", true)
        .put("reasoning_effort", "high")
        .put("auto_web_search", false)
        .put("message_version", 1)
        .put("extra", JSONObject())
        .put("timestamp", ts)
        .put("type", "general_agent")
}
