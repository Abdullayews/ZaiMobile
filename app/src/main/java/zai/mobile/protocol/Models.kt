package zai.mobile.protocol

import org.json.JSONObject

/**
 * Modellər — GET /api/models (spec §4/§6).
 * Siyahı TAMAMILə serverdən gəlir; heç bir statik fallback YOXDUR —
 * boşdursa çağıran tərəf xəta/retry göstərir.
 */
data class ZaiModel(
    val id: String,
    val name: String,
    val minLevel: Int,
    val effortLevels: List<String>,
) {
    /** Qonaq üçün görünür? (min_available_level <= 1) */
    val guestVisible: Boolean get() = minLevel <= 1
}

class ModelsApi(private val http: ZaiHttp) {

    /** Siyahı + qonaq filtri (isGuest → yalnız guestVisible). */
    fun list(token: String, isGuest: Boolean): List<ZaiModel> {
        val out = http.json(
            attempts = LIST_ATTEMPTS,
            path = { "/api/models" },
            headers = { listOf("Authorization" to "Bearer $token") },
        )
        if (out.code !in 200..299) {
            throw ZaiException.fromHttp(out.code, String(out.bytes, Charsets.UTF_8))
        }
        val root = String(out.bytes, Charsets.UTF_8).toJsonObject()
            ?: throw ZaiException.Bug("models: non-JSON 200 response")
        val all = root.optJSONArray("data").objs().mapNotNull { m ->
            val id = m.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val info = m.optJSONObject("info") ?: JSONObject()
            val metaInfo = (info.optJSONObject("meta") ?: JSONObject())
                .optJSONObject("info") ?: JSONObject()
            ZaiModel(
                id = id,
                name = m.optString("name").ifBlank { id },
                minLevel = info.optInt("min_available_level", 0),
                effortLevels = metaInfo.optJSONArray("thinking_effort_level")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                } ?: emptyList(),
            )
        }
        return if (isGuest) all.filter { it.guestVisible } else all
    }

    companion object {
        const val LIST_ATTEMPTS = 6
    }
}
