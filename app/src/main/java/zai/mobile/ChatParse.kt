package zai.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat detalı (GET /api/v1/chats/{id}) təhlili — xalis Kotlin.
 * history.messages: { id → {parentId, childrenIds, role, content, ...} }
 * Zəncir root-dan (parentId=null) childrenIds ilə gəzilir.
 */
object ChatParse {

    /** content sahəsi string VƏ YA massiv (parts) ola bilər — mətni çıxarır. */
    fun contentToText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    val t = part.optString("text")
                    if (t.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(t)
                    }
                }
            }
        }
        else -> ""
    }

    /** content massivindən əlavələri (image_url/video_url/file_url) çıxarır. */
    fun contentToAttachments(content: Any?): List<UploadedFile> {
        val arr = content as? JSONArray ?: return emptyList()
        val out = mutableListOf<UploadedFile>()
        for (i in 0 until arr.length()) {
            val part = arr.optJSONObject(i) ?: continue
            val type = part.optString("type")
            val url = when (type) {
                "image_url" -> part.optJSONObject("image_url")?.optString("url")
                "video_url" -> part.optJSONObject("video_url")?.optString("url")
                "file_url" -> part.optJSONObject("file_url")?.optString("url")
                else -> null
            } ?: continue
            if (url.isEmpty()) continue
            val media = when (type) {
                "image_url" -> "image"
                "video_url" -> "video"
                else -> "doc"
            }
            out += UploadedFile(id = url, name = "", media = media, size = 0, directUrl = url)
        }
        return out
    }

    /**
     * Chat detallarından səliqəli mesaj siyahısı düzəldir.
     * Zəncir: root (parentId null) → childrenIds[0] → ...
     * Zəncir qırıq olarsa timestamp sıralamasına düşür.
     */
    fun messages(detail: JSONObject): List<ChatMsg> {
        val messagesObj = detail.optJSONObject("history")?.optJSONObject("messages")
            ?: return emptyList()
        val byId = mutableMapOf<String, JSONObject>()
        val keys = messagesObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            messagesObj.optJSONObject(k)?.let { byId[k] = it }
        }
        if (byId.isEmpty()) return emptyList()

        // root-ları tap
        val roots = byId.values.filter {
            it.isNull("parentId") || it.optString("parentId").isEmpty()
        }
        val ordered = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var cursor: JSONObject? = roots.minByOrNull { it.optLong("timestamp") }
        while (cursor != null) {
            val id = cursor.optString("id")
            if (id.isEmpty() || !seen.add(id)) break
            ordered += cursor
            val children = cursor.optJSONArray("childrenIds")
            var next: JSONObject? = null
            if (children != null) {
                for (i in 0 until children.length()) {
                    val cid = children.optString(i)
                    val child = byId[cid]
                    if (child != null && !seen.contains(cid)) { next = child; break }
                }
            }
            cursor = next
        }
        // zəncir hamını əhatə etmirsə — qalanları timestamp ilə əlavə et
        if (ordered.size < byId.size) {
            val remaining = byId.values
                .filter { !seen.contains(it.optString("id")) }
                .sortedBy { it.optLong("timestamp") }
            ordered += remaining
        }

        return ordered.mapNotNull { m ->
            val role = m.optString("role")
            if (role != "user" && role != "assistant") return@mapNotNull null
            val rawContent: Any? = if (!m.isNull("content")) m.opt("content") else null
            ChatMsg(
                id = m.optString("id").ifEmpty { Signature.genUuid() },
                role = role,
                content = contentToText(rawContent),
                attachments = contentToAttachments(rawContent),
            )
        }
    }

    /** Söhbət başlığı. */
    fun title(detail: JSONObject): String =
        detail.optString("title").ifEmpty { "Chat" }
}
