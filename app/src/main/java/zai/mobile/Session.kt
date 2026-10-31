package zai.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Daimi state — SharedPreferences (Rust versiyasındakı state.json-un Kotlin
 * ekvivalenti; kiçik data üçün atomik və sadədir).
 */
class Session(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zai_state", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    var userId: String
        get() = prefs.getString(K_USER_ID, "") ?: ""
        set(v) = prefs.edit().putString(K_USER_ID, v).apply()

    var userName: String
        get() = prefs.getString(K_USER_NAME, "") ?: ""
        set(v) = prefs.edit().putString(K_USER_NAME, v).apply()

    var isGuest: Boolean
        get() = prefs.getBoolean(K_IS_GUEST, true)
        set(v) = prefs.edit().putBoolean(K_IS_GUEST, v).apply()

    var deviceId: String
        get() = (prefs.getString(K_DEVICE_ID, "") ?: "").ifEmpty { makeDeviceId().also { deviceId = it } }
        set(v) = prefs.edit().putString(K_DEVICE_ID, v).apply()

    var modelIndex: Int
        get() = prefs.getInt(K_MODEL, 0)
        set(v) = prefs.edit().putInt(K_MODEL, v).apply()

    var effortIndex: Int
        get() = prefs.getInt(K_EFFORT, 2)
        set(v) = prefs.edit().putInt(K_EFFORT, v).apply()

    val hasAuth: Boolean get() = token.isNotEmpty()

    fun saveAuth(a: AuthInfo) {
        prefs.edit()
            .putString(K_TOKEN, a.token)
            .putString(K_USER_ID, a.userId)
            .putString(K_USER_NAME, a.userName)
            .putBoolean(K_IS_GUEST, a.isGuest)
            .apply()
    }

    fun clearAuth() {
        prefs.edit()
            .remove(K_TOKEN)
            .remove(K_USER_ID)
            .remove(K_USER_NAME)
            .remove(K_IS_GUEST)
            .apply()
    }

    companion object {
        private const val K_TOKEN = "token"
        private const val K_USER_ID = "user_id"
        private const val K_USER_NAME = "user_name"
        private const val K_IS_GUEST = "is_guest"
        private const val K_DEVICE_ID = "device_id"
        private const val K_MODEL = "model_index"
        private const val K_EFFORT = "effort_index"

        /** uid_ + 13 əlifba-rəqəm (brauzerin formatı). */
        fun makeDeviceId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            var seed = System.currentTimeMillis() xor (android.os.Process.myPid().toLong() shl 32)
            val sb = StringBuilder("uid_")
            repeat(13) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                sb.append(chars[((seed ushr 33) % chars.length).toInt()])
            }
            return sb.toString()
        }
    }
}
