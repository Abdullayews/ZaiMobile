// ============================================================================
// DƏRS 13 — DELEGASİYA (DELEGATION): by lazy, observable, by map,
//          sinif delegasiyası, xüsusi delegate-lər
// ----------------------------------------------------------------------------
// "property-ni başqa bir obyektə HƏVALƏ ET" — Kotlin-in ən zərif xüsusiyyətlərindən.
// ============================================================================

import kotlin.properties.Delegates
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// ---------- 1. Sinif delegasiyası: `by` — irs əvəzinə kompozisiya ----------
interface Göndərici {
    fun göndər(kime: String, mətn: String): String
}
class HttpGöndərici : Göndərici {
    override fun göndər(kime: String, mətn: String) = "HTTP → $kime: $mətn"
}
class FaksGöndərici : Göndərici {
    override fun göndər(kime: String, mətn: String) = "FAKS → $kime: $mətn"
}
// BÜTÜN Göndərici metodlarını HttpGöndəçi-yə DELEGASIYA edirik (wrapping):
class BildirişXidməti(göndərici: Göndərici) : Göndərici by göndərici {
    // yalnız FƏRQLİ davranışı özümüz yazırıq, qalanı avtomatik ötürülür:
    fun göndərHamıya(mətn: String) = göndər("all", mətn)
}

// ---------- 2. Xüsusi delegate — getValue/setValue operatorları ----------
// String-i saxlayan, amma hər yazıda TRİM edən delegate:
class TrimEdən : ReadWriteProperty<Any?, String> {
    private var saxlanılan = ""
    override fun getValue(thisRef: Any?, property: KProperty<*>) = saxlanılan
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        saxlanılan = value.trim()
    }
}
// Daha sadə forma — birbaşa operator funksiyaları (interfeyssiz):
class Sayğaç(private var n: Int = 0) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = n
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        n = value.coerceAtLeast(n)          // yalnız ARTA bilər
    }
}

fun main() {
    // ---------- by lazy (təkrar xatirə: Dərs 04) ----------
    class Bahalı {
        val hesab by lazy { List(1000) { it * it }.sum() }
    }
    check(Bahalı().hesab == List(1000) { it * it }.sum())

    // ---------- Delegates.observable — dəyişikliyi İZLƏ ----------
    class Söhbət {
        var başlıq: String by Delegates.observable("New Chat") { _, köhnə, yeni ->
            println("  başlıq dəyişdi: '$köhnə' → '$yeni'")
        }
    }
    val s = Söhbət()
    s.başlıq = "Kotlin dərsləri"
    check(s.başlıq == "Kotlin dərsləri")

    // ---------- Delegates.vetoable — dəyişməyİ VETO et ----------
    class Sərhədli {
        var faiz: Int by Delegates.vetoable(0) { _, _, yeni -> yeni in 0..100 }
    }
    val faiz = Sərhədli()
    faiz.faiz = 50
    check(faiz.faiz == 50)
    faiz.faiz = 150                    // VETO! dəyişiklik UĞURSUZ
    check(faiz.faiz == 50)
    faiz.faiz = -10                    // VETO!
    check(faiz.faiz == 50)

    // ---------- Delegates.notNull — "lateinit kimi, amma hər tip üçün" ----------
    class Bağlantı {
        var port: Int by Delegates.notNull()
        fun qoşul(p: Int) { port = p }  // ilk dəfə burada təyin olur
    }
    val b = Bağlantı()
    // check(b.port == 0)               // XƏTA atardı: notNull hələ təyin edilməyib
    b.qoşul(8080)
    check(b.port == 8080)

    // ---------- by map — xəritədən property kimi oxu ----------
    class Konfiq(val məlumat: Map<String, Any?>) {
        val url: String by məlumat
        val timeout: Int by məlumat
        val token: String? by məlumat              // null da ola bilər
    }
    val k = Konfiq(mapOf("url" to "https://chat.z.ai", "timeout" to 30, "token" to null))
    check(k.url == "https://chat.z.ai")
    check(k.timeout == 30)
    check(k.token == null)
    // mutable map → var property:
    class Dinamik(val m: MutableMap<String, Any?>) {
        var sayğac: Int by m
    }
    val d = Dinamik(mutableMapOf("sayğac" to 1))
    d.sayğac++
    check(d.m["sayğac"] == 2)                     // map-də də dəyişdi!

    // ---------- Xüsusi delegate-lərimiz ----------
    class İstifadəçi {
        var ad: String by TrimEdən()              // hər yazıda trim olunur
        var səviyyə: Int by Sayğaç()              // yalnız arta bilər
    }
    val i = İstifadəçi()
    i.ad = "  Zaur  "
    check(i.ad == "Zaur")
    i.səviyyə = 5
    i.səviyyə = 3                                 // rədd edildi (3 < 5)
    check(i.səviyyə == 5)
    i.səviyyə = 7
    check(i.səviyyə == 7)

    // ---------- Sinif delegasiyası ----------
    val httpXidmət = BildirişXidməti(HttpGöndərici())
    val faksXidmət = BildirişXidməti(FaksGöndərici())
    check(httpXidmət.göndər("user", "salam") == "HTTP → user: salam")
    check(faksXidmət.göndər("user", "salam") == "FAKS → user: salam")
    check(httpXidmət.göndərHamıya("yenilik") == "HTTP → all: yenilik")

    // ---------- LOKAL delegated property ----------
    fun hesabla(): Int {
        val keşlənən by lazy { 21 * 2 }           // funksiya daxilində belə!
        return keşlənən + 0
    }
    check(hesabla() == 42)

    // ---------- lateInit vs lazy vs delegate müqayisəsi ----------
    // lateinit var   — sonradan TƏYİN ediləcək, var, non-null obyekt tipləri
    // by lazy        — ilk İSTİFADƏDƏ hesablanır, val, thread-safe
    // custom delegate — davranışı tam SƏN idarə edirsən
    println("Dərs 13: hamısı keçdi ✔")
}
