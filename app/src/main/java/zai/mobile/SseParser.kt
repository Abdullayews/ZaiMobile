package zai.mobile

import org.json.JSONObject

/**
 * SSE (server-sent events) təhlili — xalis Kotlin, JVM-də test olunur.
 * chat.z.ai cavabının bir neçə format variantını dəstəkləyir (veb tətbiqi
 * dəyişə bilən daxili qatlar: /data/message, /data/data/message, ...).
 */
object Sse {

    /** Bir SSE JSON payload-ını StreamEvent-ə çevirir. Null = görməzdən gəl. */
    fun parseEvent(payload: String): StreamEvent? {
        val ev = runCatching { JSONObject(payload) }.getOrNull() ?: return null

        val m = ev.optJSONObject("data")?.optJSONObject("message")
            ?: ev.optJSONObject("data")?.optJSONObject("data")?.optJSONObject("message")
            ?: ev.optJSONObject("message")
            ?: ev.optJSONObject("data")?.optJSONObject("data")?.optJSONObject("data")

        if (m != null) {
            // "error": null halında optJSONObject null qaytarır → err != null bəsdir
            val err = m.optJSONObject("error")
            if (err != null) return errFrom(err)
            if (m.optBoolean("done", false)) return StreamEvent.Done
            val phase = m.optString("phase")
            if (phase.isNotEmpty() && phase != "answer" && phase != "other")
                return StreamEvent.Phase(phaseLabel(phase))
            val delta = m.optString("delta_content")
            if (delta.isNotEmpty()) return StreamEvent.Delta(delta)
            return null
        }

        val err = ev.optJSONObject("data")?.optJSONObject("error")
            ?: ev.optJSONObject("data")?.optJSONObject("data")?.optJSONObject("error")
            ?: ev.optJSONObject("error")
        if (err != null) return errFrom(err)
        return null
    }

    private fun errFrom(err: JSONObject): StreamEvent {
        var code = err.optString("code")
        if (code.isEmpty()) code = err.optString("error_code")
        var detail = err.optString("detail")
        if (detail.isEmpty()) detail = err.optString("message", "Error")
        return if (code.contains("CAPTCHA") || detail.contains("captcha"))
            StreamEvent.CaptchaRequired
        else
            StreamEvent.Error(friendlyError(code, detail))
    }

    /**
     * Çoxsətirli SSE dəstəyi: server bir hadisəni birdən çox "data:" sətirinə
     * bölə bilər. Sürətli yol — sətir tək başına tam JSON-dursa birbaşa qaytar;
     * deyilsə əvvəlki qalıqla birləşdirib yenidən sına (veb protokolunun dəqiq
     * davranışı).
     */
    class Accumulator {
        private var pending = ""

        /** Tam JSON payload hazırdırsa qaytarır, yoxsa null (gözləməyə alır). */
        fun feed(payload: String): String? {
            if (isValidJson(payload)) { pending = ""; return payload }
            if (pending.isEmpty()) { pending = payload; return null }
            val joined = pending + "\n" + payload
            return if (isValidJson(joined)) { pending = ""; joined } else { pending = payload; null }
        }

        fun reset() { pending = "" }
    }

    fun isValidJson(s: String): Boolean =
        runCatching { JSONObject(s) }.isSuccess
}
