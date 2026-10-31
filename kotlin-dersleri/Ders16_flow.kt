// ============================================================================
// DƏRS 16 — FLOW: asinxron DATA AXINLARI
// ----------------------------------------------------------------------------
// Sequencesuspend-edə bilmir; Flow — "suspend-ə bilən lazy axın"dır.
// Soyuq (cold) axın: hər toplayıcı üçün YENİDƏN başlayır.
// ZaiMobile-da SSE (server-sent events) məhz Flow ilə modellənəcək!
// ============================================================================

@file:OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.system.measureTimeMillis

fun main() = runBlocking {
    // ---------- 1. flow { } — soyuq axın yaratma ----------
    val sayğacAxını = flow {
        for (i in 1..3) {
            delay(50)                       // suspend MÜMKÜNDÜR (Sequence-dən fərq!)
            emit(i)                         // hər elementi "yay"
        }
    }
    // İKİ ayrı toplayış → axın İKİ DƏFƏ başlayır (cold!):
    check(sayğacAxını.toList() == listOf(1, 2, 3))
    check(sayğacAxını.toList() == listOf(1, 2, 3))

    // ---------- 2. Operatorlar (Sequence-ə oxşar, amma suspend-ə qadirdir) ----------
    val nəticə = sayğacAxını
        .map { it * it }                    // çevir
        .filter { it > 1 }                  // süz
        .onEach { println("  keçir: $it") } // yan təsir
        .toList()
    check(nəticə == listOf(4, 9))

    // take/drop/transform/takeWhile:
    check((1..10).asFlow().take(3).toList() == listOf(1, 2, 3))
    check((1..10).asFlow().drop(7).toList() == listOf(8, 9, 10))
    val transformEdilmiş = flowOf(1, 2).transform {
        emit("başladı: $it")
        emit(it * 100)
    }.toList()
    check(transformEdilmiş == listOf("başladı: 1", 100, "başladı: 2", 200))

    // scan — kümülativ (running) dəyər:
    check(flowOf(1, 2, 3).scan(0) { akk, v -> akk + v }.toList() == listOf(0, 1, 3, 6))

    // ---------- 3. Vaxt operatorları ----------
    val gecikmiş = flow {
        emit("a"); delay(120); emit("b"); delay(120); emit("c")
    }
    check(gecikmiş.debounce(50).toList() == listOf("a", "b", "c"))
    // buffer — istehsal/toplama paralelə düşür:
    val paralelVaxt = measureTimeMillis {
        flow { emit(1); delay(100); emit(2) }
            .buffer()
            .collect { delay(100) }         // toplama da vaxt aparır
    }
    // buferlə: ~200ms (paralel), bufersız olardı ~300ms (serial)
    check(paralelVaxt < 280)
    // conflate — aradakıları AT, yalnız ən yenini saxla (UI üçün ideal):
    val conflated = flow { emit(1); emit(2); emit(3) }.conflate().toList()
    check(conflated.contains(3))

    // ---------- 4. flowOn — YUXARIDAKI operatorları hansı kontekstdə işlət ----------
    // flowOn(Dispatchers.IO) → emit-lər IO thread-lərində baş verir:
    val axın = (1..2).asFlow()
        .map { it * 10 }
        .flowOn(Dispatchers.IO)             // yuxarısı IO-da
        // aşağısı toplayıcının kontekstində qalır
    check(axın.toList() == listOf(10, 20))

    // ---------- 5. Birləşmə ----------
    val a = flowOf(1, 2).onEach { delay(40) }
    val b = flowOf(10, 20).onEach { delay(60) }
    // zip — cütlüklər üzrə:
    check(a.zip(b) { x, y -> x + y }.toList() == listOf(11, 22))
    // combine — İSTƏR İSTƏMƏZ dəyişəndə yenidən hesabla (ən son dəyərlərlə):
    val dəyişən = MutableStateFlow(1)
    val vurma = combine(dəyişən, flowOf(10)) { x, y -> x * y }
    check(vurma.first() == 10)
    dəyişən.value = 5
    check(vurma.first() == 50)

    // flatMapConcat / flatMapMerge — axın içindən axın:
    check(flowOf(1, 2)
        .flatMapConcat { flowOf(it, it * 10) }
        .toList() == listOf(1, 10, 2, 20))

    // ---------- 6. Xəta idarəsi ----------
    val xətalıAxın = flow {
        emit(1)
        throw IllegalStateException("şəbəkə kəsildi")
    }
    // catch — yuxarıdakı xətaları tutar, alternativ dəyər verə bilər:
    check(xətalıAxın.catch { emit(-1) }.toList() == listOf(1, -1))
    // retry — avtomatik təkrar (ZaiMobile-ın WAF retry məntiqinin ruhu!):
    var cəhd = 0
    val yenidənDenənən = flow {
        cəhd++
        if (cəhd < 3) throw RuntimeException("WAF")
        emit("uğur ($cəhd cəhd)")
    }.retry(retries = 5) { it is RuntimeException }
    check(yenidənDenənən.toList() == listOf("uğur (3 cəhd)"))
    check(cəhd == 3)
    // onCompletion — hər halda çağırılır (finally kimi):
    var tamamlandı = false
    xətalıAxın.catch { }.onCompletion { tamamlandı = true }.collect { }
    check(tamamlandı)

    // ---------- 7. STATE FLOW — İSTİLİ (hot) vəziyyət axını ----------
    // Bir DƏYƏR saxlayır (state), hər yeni abunəçiyə DƏRHAL cari dəyəri verir.
    val vəziyyət = MutableStateFlow(0)
    check(vəziyyət.value == 0)
    val abunəliklər = mutableListOf<Int>()
    val iş = launch {
        vəziyyət.collect { abunəliklər += it }    // hər dəyişikliyi izlə
    }
    delay(50)                                      // 0 toplandı
    vəziyyət.value = 1                             // update də olur: vəziyyət.update { it+1 }
    vəziyyət.update { it + 1 }
    delay(50)
    iş.cancel()
    check(abunəliklər.contains(0))
    check(abunəliklər.contains(2))
    // stateIn — soyuq axını istiliyə çevir (UI state üçün əvəzolunmaz):
    val istili = flowOf(5, 6, 7).stateIn(this)
    check(istili.value in setOf(5, 6, 7))          // tamamlanmış axın sonuncunu saxlayır

    // ---------- 8. SHARED FLOW — event yayını (hot) ----------
    // Dəyər SAXBAYNA BİLMƏZ (null yoxdur); replay — yeni abunəçiyə son N element.
    val events = MutableSharedFlow<String>(replay = 2)
    events.emit("a"); events.emit("b"); events.emit("c")     // replay = son 2: b, c
    check(events.replayCache == listOf("b", "c"))
    val görülənlər = mutableListOf<String>()
    val iş2 = launch { events.collect { görülənlər += it } } // b, c ilə başlayır
    delay(50)
    events.emit("d")
    delay(50)
    iş2.cancel()
    check(görülənlər == listOf("b", "c", "d"))

    // ---------- 9. collectLatest — yeni element gələndə KÖHNƏ toplamanı ləğv et ----------
    val gəncəAxın = flow { emit(1); delay(10); emit(2); delay(10); emit(3) }
    val sonNəticələr = mutableListOf<Int>()
    gəncəAxın.collectLatest { v ->                  // hər element üçün YENİ blok
        delay(20)                                   // gecikmə içində yeni gəlsə → bu blok LƏĞV
        sonNəticələr += v
    }
    check(sonNəticələr == listOf(3))                // yalnız sonuncu başa çatdı!

    println("Dərs 16: hamısı keçdi ✔")
}
