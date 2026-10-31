package zai.mobile.state

import android.content.Context
import org.json.JSONObject
import zai.mobile.protocol.DeviceIds
import zai.mobile.protocol.AuthInfo
import java.io.File

/**
 * Yadda saxlanan vəziyyət — spec §3 (Persisted).
 * Fayl: filesDir/state.json, pretty JSON, atomik yazma (tmp → rename).
 * deviceId ƏBƏDİ saxlanılır; token/user dəyişə bilər; model seçimi qalır.
 */
class Session(context: Context) {

    var token: String = ""
        private set
    var userId: String = ""
        private set
    var userName: String = ""
        private set
    var isGuest: Boolean = true
        private set
    var deviceId: String = ""
        private set
    var modelId: String = ""
    var effort: String = ""

    val hasAuth: Boolean get() = token.isNotBlank()

    private val file: File
    private val tmp: File

    init {
        val dir = context.filesDir
        file = File(dir, "state.json")
        tmp = File(dir, "state.json.tmp")
        load()
        if (!DeviceIds.isValid(deviceId)) {
            deviceId = DeviceIds.new()
            save()
        }
    }

    private fun load() {
        val txt = runCatching { file.readText() }.getOrNull() ?: return
        val o = runCatching { JSONObject(txt) }.getOrNull() ?: return
        token = o.optString("token", "")
        userId = o.optString("user_id", "")
        userName = o.optString("user_name", "")
        isGuest = o.optBoolean("is_guest", true)
        deviceId = o.optString("device_id", "")
        modelId = o.optString("model_id", "")
        effort = o.optString("effort", "")
    }

    fun save() {
        val o = JSONObject()
            .put("token", token)
            .put("user_id", userId)
            .put("user_name", userName)
            .put("is_guest", isGuest)
            .put("device_id", deviceId)
            .put("model_id", modelId)
            .put("effort", effort)
        runCatching {
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(o.toString(2).toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            // Linux-də rename atomik olaraq əvəz edir (spec §3: tmp → rename)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun saveAuth(info: AuthInfo) {
        token = info.token
        userId = info.userId
        userName = info.userName
        isGuest = info.isGuest
        save()
    }

    /** Çıxış: token/user təmizlənir; deviceId + model seçimi QALIR. */
    fun clearAuth() {
        token = ""
        userId = ""
        userName = ""
        isGuest = true
        save()
    }

    fun saveModelSelection(modelId: String, effort: String) {
        this.modelId = modelId
        this.effort = effort
        save()
    }
}
