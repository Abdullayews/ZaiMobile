// ============================================================================
// DƏRS 09 — KOLLEKSİYALAR VƏ SEQUENCE-LƏR
// ----------------------------------------------------------------------------
// Kotlin kolleksiyaları: READ-ONLY görünüş (List) vs MUTABLE (MutableList).
// Read-only ≠ dəyişməz (immutable) — yalnız İNTERFEYS interfeysidir.
// ============================================================================

fun main() {
    // ---------- 1. Yaratma ----------
    val oxunan: List<Int> = listOf(1, 2, 3)          // read-only GÖRÜNÜŞ
    val dəyişən = mutableListOf(1, 2, 3)              // mutable
    val xəritə = mapOf("az" to "Azerbaycan", "tr" to "Turkiye")
    val dəst = setOf(1, 2, 2, 3)                      // təkrarsız
    check(dəst == setOf(1, 2, 3))
    check(listOfNotNull(1, null, 3) == listOf(1, 3))
    dəyişən.add(4)
    check(dəyişən == listOf(1, 2, 3, 4))
    // oxunan.add(4)      // XƏTA: read-only görünüşdə add YOXDUR
    // List<Int> = java.util.List-in Kotlin görünüşüdür; altinda eyni obyekt!

    // Massivlər (array): primitive versiyaları boxing etmir (performans!)
    val massiv = arrayOf("a", "b")
    val ədədMassivi = intArrayOf(3, 1, 2)
    check(ədədMassivi.sum() == 6 && massiv.size == 2)
    check(ədədMassivi.sortedArray().contentEquals(intArrayOf(1, 2, 3)))

    // ---------- 2. Element girişi ----------
    check(oxunan[0] == 1)
    check(oxunan.getOrNull(5) == null)          // IndexOutOfBoundsException YOX
    check(oxunan.getOrElse(5) { -1 } == -1)
    check(oxunan.first() == 1 && oxunan.last() == 3)
    check(oxunan.first { it > 1 } == 2)         // şərtə uyğun ilk
    check(oxunan.firstOrNull { it > 99 } == null)
    check(xəritə["az"] == "Azerbaycan")
    check(xəritə.getValue("tr") == "Turkiye")   // açar yoxdursa exception
    check(xəritə.getOrDefault("en", "naməlum") == "naməlum")

    // ---------- 3. TRANSFORMASİYALAR (funksional əməliyyatlar) ----------
    check(listOf(1, 2, 3).map { it * it } == listOf(1, 4, 9))
    check(listOf(1, 2, 3).mapIndexed { i, v -> "$i:$v" } == listOf("0:1", "1:2", "2:3"))
    check(listOf(1, null, 3).mapNotNull { it } == listOf(1, 3))
    check(listOf(1, 2, 3).flatMap { listOf(it, it * 10) } == listOf(1, 10, 2, 20, 3, 30))
    check(listOf(1, 2, 3, 4).filter { it % 2 == 0 } == listOf(2, 4))
    check(listOf("a", 1, "b", 2).filterIsInstance<String>() == listOf("a", "b"))
    check(listOf(1, 2, 3, 4).partition { it > 2 }.let { (böyük, kiçik) ->
        böyük == listOf(3, 4) && kiçik == listOf(1, 2)
    })

    // groupBy / associate — ZaiMobile-da söhbətləri qruplaşdırmaq üçün istifadə edəcəyik
    data class Söhbət(val id: String, val tip: String)
    val söhbətlər = listOf(Söhbət("a", "agent"), Söhbət("b", "sadə"), Söhbət("c", "agent"))
    val qrup = söhbətlər.groupBy { it.tip }
    check(qrup["agent"]!!.size == 2)
    check(söhbətlər.associateBy { it.id }["b"]!!.tip == "sadə")
    check(listOf(1, 2, 3).associateWith { it * 100 } == mapOf(1 to 100, 2 to 200, 3 to 300))

    // ---------- 4. Sıralama / axtarış / hesab ----------
    check(listOf(3, 1, 2).sorted() == listOf(1, 2, 3))
    check(listOf(3, 1, 2).sortedDescending() == listOf(3, 2, 1))
    data class Adam(val ad: String, val yaş: Int)
    val adamlar = listOf(Adam("A", 30), Adam("B", 25), Adam("C", 35))
    check(adamlar.sortedBy { it.yaş }.first().ad == "B")
    check(adamlar.maxByOrNull { it.yaş }!!.ad == "C")
    check(adamlar.sumOf { it.yaş } == 90)
    check(listOf(1, 2, 3).fold(10) { akk, x -> akk + x } == 16)  // başlanğıc DƏYƏRLİ
    check(listOf(1, 2, 3).reduce { akk, x -> akk + x } == 6)     // başlanğıcsız (ilk element)
    check(listOf(5, 8, 2).runningFold(0) { a, b -> a + b } == listOf(0, 5, 13, 15))

    // ---------- 5. Bölmə / birləşmə ----------
    check(listOf(1, 2, 3, 4).chunked(2) == listOf(listOf(1, 2), listOf(3, 4)))
    check(listOf(1, 2, 3, 4).windowed(2) == listOf(listOf(1, 2), listOf(2, 3), listOf(3, 4)))
    check(listOf(1, 2, 3).zip(listOf("a", "b", "c")) == listOf(1 to "a", 2 to "b", 3 to "c"))
    val (ədədlər2, sözlər) = listOf(1 to "a", 2 to "b").unzip()
    check(ədədlər2 == listOf(1, 2) && sözlər == listOf("a", "b"))

    // ---------- 6. digər faydalılar ----------
    check(listOf(1, 2, 3, 2).distinct() == listOf(1, 2, 3))
    check(listOf(1, 2, 3).reversed() == listOf(3, 2, 1))
    check(listOf(1, 2, 3).take(2) == listOf(1, 2))
    check(listOf(1, 2, 3).drop(2) == listOf(3))
    check(listOf(1, 2, 3).takeLast(2) == listOf(2, 3))
    check(listOf(1, 2, 3).joinToString(prefix = "[", postfix = "]", separator = " | ") == "[1 | 2 | 3]")
    check(listOf(1, 2, 3).joinToString { "n$it" } == "n1, n2, n3")
    check(listOf(1, 2, 3).contains(2) && 2 in listOf(1, 2, 3))
    check(listOf(1, 2, 3) + listOf(4) == listOf(1, 2, 3, 4))
    check(listOf(1, 2, 3) - 2 == listOf(1, 3))
    check(listOf(1, 2, 3).all { it > 0 })
    check(listOf(1, -2, 3).any { it < 0 })
    check(listOf(1, 2, 3).none { it > 10 })
    // isNullOrEmpty — null VƏ ya boş → true (nullable qəbul edir!):
    val boşSiyahı: List<Int>? = emptyList()
    val heçYox: List<Int>? = null
    check(boşSiyahı.isNullOrEmpty())
    check(heçYox.isNullOrEmpty())
    check(listOf(1).isNullOrEmpty().not())

    // ---------- 7. SEQUENCE — TƏXİRƏ SALINMIŞ (lazy) zəncir ----------
    // List əməliyyatları HƏR addımda YENİ kolleksiya yaradır (ara massivlər).
    // Sequence elementləri K-E-Ç-İ-K bir-bir axıdır — sona qədər heç nə yığılmır.
    val sıra = (1..10).asSequence()
        .map { it * 2 }          // hər element dərhal keçir
        .filter { it > 10 }
        .take(2)                 // 2 element tapılınca axın DAYANIR
        .toList()
    check(sıra == listOf(12, 14))
    // Fərq: 1..1_000_000 üçün sequence map-i 1M dəfə YOX, lazım olduqca işlədir.

    // generateSequence — sonsolğun/şərtli generasiya
    val həddindənArtıq = generateSequence(1) { it * 3 }.takeWhile { it < 100 }.toList()
    check(həddindənArtıq == listOf(1, 3, 9, 27, 81))
    check(generateSequence(5) { if (it > 1) it / 2 else null }.toList() == listOf(5, 2, 1))

    // Sequence vs List davranış fərqi (yan təsirlərdən görünür):
    var işlənən = 0
    listOf(1, 2, 3).map { işlənən++; it * 2 }.filter { it > 2 }  // 3 map + 3 filter
    check(işlənən == 3)
    işlənən = 0
    listOf(1, 2, 3).asSequence().map { işlənən++; it * 2 }.filter { it > 2 }.first()
    check(işlənən == 2)   // first() 4-ü tapana qədər (1→map→filter, 2→map→filter) sonra DAYANDI

    println("Dərs 09: hamısı keçdi ✔")
}
