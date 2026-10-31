package zai.mobile

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import zai.mobile.protocol.Transport
import zai.mobile.protocol.UrlConnectionTransport
import zai.mobile.protocol.ZaiHttp
import zai.mobile.state.Session

/**
 * Giriş nöqtəsi: crash yazıcı (sonrakı açılışda diaqnoz üçün) + tək ZaiHttp.
 * Heç bir statik data YOXDUR — hər şey canlı backend-dən gəlir.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash(e) }
            if (prev != null) prev.uncaughtException(t, e)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun writeCrash(e: Throwable) {
        val f = File(filesDir, LAST_CRASH)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        f.writeText(
            buildString {
                append("time: ").append(System.currentTimeMillis()).append('\n')
                append("version: ").append(version).append('\n')
                append("android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("model: ").append(Build.MODEL.orEmpty()).append('\n')
                append("stack:\n")
                append(Log.getStackTraceString(e))
            }
        )
    }

    /** Əvvəlki crash varsa oxu və sil — BootActivity göstərir. */
    fun takeLastCrash(): String? {
        val f = File(filesDir, LAST_CRASH)
        if (!f.exists()) return null
        val txt = runCatching { f.readText() }.getOrNull()
        f.delete()
        return txt?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val LAST_CRASH = "last_crash.txt"

        @Volatile
        var transportForTest: Transport? = null

        /** Testlər üçün IO keçidi (deterministik Robolectric). */
        @Volatile
        var ioForTest: kotlinx.coroutines.CoroutineDispatcher? = null

        fun io(): kotlinx.coroutines.CoroutineDispatcher = ioForTest ?: Dispatchers.IO

        @Volatile
        private var httpRef: ZaiHttp? = null

        /** Tək ZaiHttp — cookie jar proses boyu paylaşılır. */
        fun http(context: Context): ZaiHttp {
            httpRef?.let { return it }
            synchronized(this) {
                httpRef?.let { return it }
                val deviceId = Session(context).deviceId
                val transport = transportForTest ?: UrlConnectionTransport()
                val h = ZaiHttp(transport, deviceId)
                httpRef = h
                return h
            }
        }

        /** Yalnız testlər üçün. */
        fun resetForTest() {
            synchronized(this) {
                httpRef = null
                transportForTest = null
                ioForTest = null
            }
        }
    }
}
