// ============================================================================
// DƏRS 15 — KORUTİNLƏR (COROUTINES): asinxron proqramlaşdırma
// ----------------------------------------------------------------------------
// Korutin = "yüngül thread" — dayandırıla bilən hesabllama. Thread-dən fərqi:
// bloklamır, çox ucuzdur (minlərlə eyni anda), strukturlaşdırılmışdır.
// ZaiMobile-da bütün şəbəkə əməliyyatları korutinlərlə işləyəcək.
// QEYD: Bu dərs kotlinx-coroutines kitabxanası tələb edir (sinif yolu ilə).
// ============================================================================

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

// suspend funksiya — DİGƏR suspend funksiyalardan və korutinlərdən çağırılır;
// "mən dayana bilirəm (suspend ola bilirəm), amma bloklamıram" deməkdir.
suspend fun şəbəkədənGəl(gecikmə: Long, nəticə: String): String {
    delay(gecikmə)                       // ƏSAS FƏRQ: delay BLOKLAMIR (Thread.sleep isə bloklayır!)
    return nəticə
}

fun main() = runBlocking {               // runBlocking — əsas funksiya ilə körpü (yalnız test/kötük üçün!)

    // ---------- 1. launch — "at, düşünmə" (nəticə lazım deyil) ----------
    val işlər = mutableListOf<Job>()
    repeat(3) { i ->
        val job = launch {
            delay(100 - 10L * i)
            println("  launch $i bitdi")
        }
        işlər += job
    }
    check(işlər.size == 3)
    işlər.joinAll()                      // hamısının bitməsini gözlə (bloklamadan!)

    // ---------- 2. async/await — nəticə lazımdır ----------
    // PARALEL işləmə: ikisi eyni anda başlayır → ümumi vaxt ~100ms, 200ms YOX!
    val vaxt = measureTimeMillis {
        val a = async { şəbəkədənGəl(100, "modellər") }
        val b = async { şəbəkədənGəl(100, "söhbətlər") }
        check(a.await() == "modellər")
        check(b.await() == "söhbətlər")
    }
    check(vaxt < 180)                    // 200ms olsaydı serial icra olardı

    // ---------- 3. Dispatchers — HARADA icra olunur ----------
    // Dispatchers.Default — CPU-heavy (hesablama); thread sayı = nüvə sayı
    // Dispatchers.IO       — şəbəkə/disk (bloklayan IO); böyük thread hovuzu
    // Dispatchers.Main     — UI (Android!; desktop-da yoxdur)
    val hansıThread = withContext(Dispatchers.Default) {
        Thread.currentThread().name
    }
    check(hansıThread.contains("DefaultDispatcher"))
    val ioThread = withContext(Dispatchers.IO) { Thread.currentThread().name }
    check(ioThread.contains("DefaultDispatcher"))    // IO hovuzu da eyni ada malikdir

    // ---------- 4. Strukturlaşdırılmış konkurrensiya (structured concurrency) ----------
    // Valideyn scope bitəndə BÜTÜN uşaqları da ləğv olunur — "sizsız işlək thread" YOX:
    coroutineScope {
        val uşaq = launch {
            delay(10_000)                          // uzun iş
            check(false)                           // gəlib çatmamalıdır
        }
        launch { delay(50); }                      // qısa iş
        // scope bitəndə uşaq hələ də işlidirsə... gözlənilir; amma CANCEL-də:
        uşaq.cancel()                              // manual ləğv
        check(uşaq.isCancelled)
    }

    // ---------- 5. Ləğv (cancellation) — əməkdaşlıq edən (cooperative) ----------
    val uzunİş = launch {
        try {
            repeat(1000) { i ->
                if (!isActive) return@launch       // 1) aktivliyi YOXLA
                // yield()                         // 2) və ya söz ver (yield)
                delay(10)                          // 3) delay özü ləğvi yoxlayır
                if (i == 999) check(false)
            }
        } finally {
            println("  uzunİş: təmizlənmə (finally) icra olundu")
        }
    }
    delay(80)                                      // bir az işləsin
    uzunİş.cancel()                                // LƏĞV
    check(uzunİş.isCancelled)

    // CancellationException ÖZÜ tutulmamalıdır (təmizlik kodu üçün finally istifadə et):
    val tutulan = runCatching {
        val j = launch { delay(10_000) }
        j.cancelAndJoin()
        "ləğv təmiz keçdi"
    }
    check(tutulan.getOrNull() == "ləğv təmiz keçdi")

    // ---------- 6. withTimeout — müddət məhdudiyyəti ----------
    val nəticə = withTimeoutOrNull(150) {
        şəbəkədənGəl(300, "gec cavab")
    }
    check(nəticə == null)                          // 150ms-də gəlmədi → null
    check(withTimeoutOrNull(500) { şəbəkədənGəl(50, "tez") } == "tez")

    // ---------- 7. Xətalar ----------
    // launch-dan atılan xəta VALİDEYN scope-u QƏTİL edir ("struktur"):
    val xətaTutuldu = runCatching {
        coroutineScope {
            launch { throw IllegalStateException(" şəbəkə qəzası") }
        }
    }
    check(xətaTutuldu.exceptionOrNull() is IllegalStateException)

    // supervisorScope — uşaqların xətası valideyni və QARDAŞLARINI öldürməsin:
    supervisorScope {
        val uşaq1 = launch { throw IllegalStateException("bir uşaq qəzalandı") }
        val uşaq2 = launch { delay(50); check(true) }   // sağ qalır!
        joinAll(uşaq1, uşaq2)
        check(uşaq2.isCompleted)
    }

    // CoroutineExceptionHandler — "yaxalanan son çarə" handler-i:
    val tutuldu = mutableListOf<Throwable>()
    val handler = CoroutineExceptionHandler { _, e -> tutuldu += e }
    val job = GlobalScope.launch(handler) {
        throw ArithmeticException("sıfıra böl")
    }
    job.join()
    check(tutuldu.first() is ArithmeticException)

    // ---------- 8. Channels — korutinlərarası data axını ----------
    val kanal = Channel<Int>(capacity = 3)
    val istehsalçı = launch {
        for (i in 1..5) kanal.send(i)
        kanal.close()                                // axını BAĞLA (son siqnal)
    }
    val qəbulOlan = mutableListOf<Int>()
    for (v in kanal) qəbulOlan += v                  // closed olana qədər oxu
    istehsalçı.join()
    check(qəbulOlan == listOf(1, 2, 3, 4, 5))

    // ---------- 9. Mutex — korutinlər üçün qarşılıqlı istisna (lock) ----------
    val mutex = Mutex()
    var qorunmuş = 0
    coroutineScope {
        repeat(100) {
            launch(Dispatchers.Default) {
                repeat(100) {
                    mutex.withLock { qorunmuş++ }    // atomic artım
                }
            }
        }
    }
    check(qorunmuş == 10_000)

    println("Dərs 15: hamısı keçdi ✔")
}
