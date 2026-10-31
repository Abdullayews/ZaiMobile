package zai.mobile

import android.app.Application
import java.net.CookieHandler
import java.net.CookieManager

/** Tətbiq — qlobal cookie idarəsi (WAF acw_tc cookie-ləri avtomatik daşınsın). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager())
        }
        CrashLog.install(this)
    }
}
