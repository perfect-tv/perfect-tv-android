package top.rootu.perfecttv.teletext

import java.util.concurrent.ConcurrentHashMap

/**
 * Телетекст DVB (EN 300 472 — упаковка в PES, EN 300 706 — страницы), уровень 1.0 с набором
 * кириллицы и быстрыми ссылками (Fastext). Без Android: разбор и раскладка страницы в сетку 40×25
 * проверяются юнит-тестами на записанных потоках (`tools/samples`).
 */

/** Цвета телетекста 0–7: чёрный, красный, зелёный, жёлтый, синий, пурпурный, голубой, белый */
val TTX_COLORS = intArrayOf(
    0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
    0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
)

/**
 * Принятая страница. [number] — номер в BCD (0x100…0x8FF), [subcode] — подстраница,
 * [rows] — строки 0…24 по 40 байт «как пришли» (7 бит, без бита чётности), [charset] — код набора
 * G0 (7 бит, см. [Ttx.glyph]), [links] — ссылки Fastext (красная, зелёная, жёлтая, голубая, белая,
 * индекс), 0 — нет, [enh] — слова пакетов X/26 (расширения уровня 1.5, по 18 бит), по порядку.
 */
class TtxPage(
    val number: Int,
    val subcode: Int,
    val flags: Int,
    val charset: Int,
    val rows: Array<ByteArray?>,
    val links: IntArray?,
    val enh: IntArray? = null
) {
    val isSubtitle get() = flags and F_SUBTITLE != 0
    val isNewsflash get() = flags and F_NEWSFLASH != 0
    val suppressHeader get() = flags and F_SUPPRESS_HEADER != 0

    companion object {
        const val F_ERASE = 1
        const val F_NEWSFLASH = 2
        const val F_SUBTITLE = 4
        const val F_SUPPRESS_HEADER = 8
        const val F_UPDATE = 16
        const val F_INTERRUPTED = 32
        const val F_INHIBIT = 64
        const val F_SERIAL = 128
    }
}

/** Приёмник готовых страниц */
fun interface TtxSink {
    fun onPage(page: TtxPage, timeUs: Long)
}

/**
 * Разбирает PES-пакеты телетекста (данные после заголовка PES, начиная с data_identifier),
 * собирает страницы по магазинам и складывает готовые в [store]. Вызывать из одного потока
 * (загрузчик сегментов); читать [store] можно откуда угодно — страницы после сборки не меняются.
 */
class TtxDecoder {
    /** Готовые страницы: ключ — `number shl 16 or subcode` */
    val store = ConcurrentHashMap<Int, TtxPage>()
    /** Растёт при каждой принятой странице — для перерисовки */
    @Volatile var version = 0
    /** Кириллица по умолчанию (язык дорожки rus/ukr/bul…), пока поток не назвал набор сам */
    @Volatile var cyrillicDefault = false

    private class Builder(val mag: Int, val number: Int, val subcode: Int, val flags: Int, val subset: Int) {
        val rows = arrayOfNulls<ByteArray>(25)
        var charset = -1
        var links: IntArray? = null
        // пакеты X/26 по номеру обозначения (0…15)
        val enh = arrayOfNulls<IntArray>(16)
        var enhOld: IntArray? = null
    }

    private val building = arrayOfNulls<Builder>(8)
    private val magCharset = IntArray(8) { -1 }

    /** В PMT потока есть дорожка телетекста */
    @Volatile var declared = false
        private set
    /** Номер страницы субтитров в потоке (0 — ещё не определена) */
    @Volatile var subtitleNumber = 0
        private set
    /** Последнее, что ушло в дорожку субтитров, — пустая страница (повторно гасить незачем) */
    @Volatile var subtitleEmpty = true
    private var declaredSubs: Set<Int> = emptySet()

    /** Дорожка из PMT: язык (кириллица по умолчанию для rus/ukr/bul…) и страницы субтитров из дескриптора */
    @Synchronized fun declare(lang: String, subtitlePages: List<Int>) {
        declared = true
        cyrillicDefault = lang.lowercase() in Ttx.CYRILLIC_LANGS
        declaredSubs = subtitlePages.filter { it != 0x100 }.toSet()  // «100» в дескрипторе субтитров часто ошибочно
    }

    /**
     * Страница — субтитры? Берётся первая страница, объявленная субтитрами, помеченная в заголовке (C6)
     * или 888 (так у российских каналов); дальше только она.
     */
    @Synchronized fun acceptSubtitle(p: TtxPage): Boolean {
        if (subtitleNumber == 0) {
            if (p.isSubtitle || p.number in declaredSubs || p.number == 0x888) subtitleNumber = p.number else return false
        }
        return p.number == subtitleNumber
    }

    @Synchronized fun reset() {
        declared = false
        subtitleNumber = 0
        subtitleEmpty = true
        declaredSubs = emptySet()
        store.clear()
        building.fill(null)
        magCharset.fill(-1)
        version++
    }

    /** Страница [number] (BCD) — подстраницы по возрастанию кода */
    fun subpages(number: Int): List<TtxPage> =
        store.entries.filter { it.key ushr 16 == number }.sortedBy { it.key }.map { it.value }

    /** Номера принятых страниц по возрастанию */
    fun numbers(): List<Int> = store.keys.map { it ushr 16 }.distinct().sorted()

    /**
     * Один PES-пакет: [buf] с [off], [len] байт — data_identifier и блоки данных.
     * Готовые страницы идут в [sink] (если задан) с временем [timeUs].
     */
    @Synchronized
    fun feed(buf: ByteArray, off: Int, len: Int, timeUs: Long, sink: TtxSink? = null) {
        if (len < 3) return
        val end = off + len
        val id = buf[off].toInt() and 0xFF
        if (id !in 0x10..0x1F) return
        var i = off + 1
        while (i + 2 <= end) {
            val uid = buf[i].toInt() and 0xFF
            val ul = buf[i + 1].toInt() and 0xFF
            val u = i + 2
            i = u + ul
            if (i > end) break
            if ((uid == 0x02 || uid == 0x03) && ul == 44 && (buf[u + 1].toInt() and 0xFF) == 0xE4) unit(buf, u + 2, timeUs, sink)
        }
    }

    // пакет телетекста: 2 байта «магазин, строка» и 40 байт данных, биты в каждом байте — наоборот
    private fun unit(b: ByteArray, p: Int, timeUs: Long, sink: TtxSink?) {
        val a = Ttx.ham84(Ttx.rev(b[p])); val c = Ttx.ham84(Ttx.rev(b[p + 1]))
        if (a < 0 || c < 0) return
        val addr = a or (c shl 4)
        val mag = addr and 7
        val row = addr shr 3
        val d = ByteArray(40) { Ttx.rev(b[p + 2 + it]) }
        when (row) {
            0 -> header(mag, d, timeUs, sink)
            in 1..24 -> building[mag]?.let { bl -> bl.rows[row] = ByteArray(40) { (d[it].toInt() and 0x7F).toByte() } }
            26 -> building[mag]?.let { bl -> enhancement(bl, d) }
            27 -> building[mag]?.let { bl -> if (Ttx.ham84(d[0]) == 0) fastext(bl, d) }
            28 -> building[mag]?.let { bl -> designation(d)?.let { cs -> if (Ttx.ham84(d[0]).let { it == 0 || it == 4 } && bl.charset < 0) bl.charset = cs } }
            29 -> if (Ttx.ham84(d[0]).let { it == 0 || it == 4 }) designation(d)?.let { magCharset[mag] = it }
        }
    }

    private fun header(mag: Int, d: ByteArray, timeUs: Long, sink: TtxSink?) {
        val units = Ttx.ham84(d[0]); val tens = Ttx.ham84(d[1])
        val s1 = Ttx.ham84(d[2]); val s2 = Ttx.ham84(d[3]); val s3 = Ttx.ham84(d[4]); val s4 = Ttx.ham84(d[5])
        val c7 = Ttx.ham84(d[6]); val c11 = Ttx.ham84(d[7])
        if (units < 0 || tens < 0 || s1 < 0 || s2 < 0 || s3 < 0 || s4 < 0 || c7 < 0 || c11 < 0) {
            finish(mag, timeUs, sink)  // испорченный заголовок: прежняя страница закончилась
            building[mag] = null
            return
        }
        val flags = (if (s2 and 8 != 0) TtxPage.F_ERASE else 0) or
            (if (s4 and 4 != 0) TtxPage.F_NEWSFLASH else 0) or (if (s4 and 8 != 0) TtxPage.F_SUBTITLE else 0) or
            (if (c7 and 1 != 0) TtxPage.F_SUPPRESS_HEADER else 0) or (if (c7 and 2 != 0) TtxPage.F_UPDATE else 0) or
            (if (c7 and 4 != 0) TtxPage.F_INTERRUPTED else 0) or (if (c7 and 8 != 0) TtxPage.F_INHIBIT else 0) or
            (if (c11 and 1 != 0) TtxPage.F_SERIAL else 0)
        // последовательный режим: любой заголовок завершает страницу в любом магазине
        if (flags and TtxPage.F_SERIAL != 0) for (m in 0..7) finish(m, timeUs, sink) else finish(mag, timeUs, sink)
        building[mag] = null
        val number = ((if (mag == 0) 8 else mag) shl 8) or (tens shl 4) or units
        if (number and 0xFF == 0xFF) return  // «пустые» заголовки для заполнения времени
        val sub = s1 or ((s2 and 7) shl 4) or (s3 shl 8) or ((s4 and 3) shl 12)
        val b = Builder(mag, number, sub, flags, (c11 shr 1) and 7)
        // без бита стирания страница дополняет прежнюю
        if (flags and TtxPage.F_ERASE == 0) store[(number shl 16) or sub]?.let { old -> for (r in 1..24) b.rows[r] = old.rows[r]; b.links = old.links; b.charset = old.charset; b.enhOld = old.enh }
        b.rows[0] = ByteArray(40) { (d[it].toInt() and 0x7F).toByte() }
        building[mag] = b
    }

    private fun finish(mag: Int, timeUs: Long, sink: TtxSink?) {
        val b = building[mag] ?: return
        building[mag] = null
        val cs = when {
            b.charset >= 0 -> b.charset
            magCharset[mag] >= 0 -> magCharset[mag]
            cyrillicDefault -> Ttx.CYRILLIC_RU
            else -> b.subset
        }
        val packets = b.enh.filterNotNull()
        val enh = if (packets.isNotEmpty()) packets.flatMap { it.asList() }.toIntArray() else b.enhOld
        val page = TtxPage(b.number, b.subcode, b.flags, cs, b.rows, b.links, enh)
        store[(b.number shl 16) or b.subcode] = page
        version++
        sink?.onPage(page, timeUs)
    }

    // X/26: 13 слов по 18 бит (адрес 6 бит, режим 5, данные 7) после номера обозначения
    private fun enhancement(b: Builder, d: ByteArray) {
        val des = Ttx.ham84(d[0])
        if (des !in 0..15) return
        b.enh[des] = IntArray(13) { Ttx.ham2418(d, 1 + it * 3) ?: 0x1F shl 6 or 0x3F }
    }

    // Fastext: шесть ссылок по шесть полубайт (единицы, десятки, S1…S4 с битами магазина)
    private fun fastext(b: Builder, d: ByteArray) {
        val out = IntArray(6)
        for (k in 0 until 6) {
            val q = 1 + k * 6
            val n = IntArray(6) { Ttx.ham84(d[q + it]) }
            if (n.any { it < 0 }) continue
            val mag = b.mag xor ((n[3] shr 3) and 1 or ((n[5] shr 1) and 6))
            val num = ((if (mag == 0) 8 else mag) shl 8) or (n[1] shl 4) or n[0]
            if (num and 0xFF != 0xFF) out[k] = num
        }
        b.links = out
    }

    // кодовая страница из первого трёхбайтового слова пакета X/28 и M/29: биты 8–14 слова
    private fun designation(d: ByteArray): Int? {
        val t = Ttx.ham2418(d, 1) ?: return null
        return (t shr 7) and 0x7F
    }
}

object Ttx {
    val CYRILLIC_LANGS = setOf("rus", "bul", "ukr", "bel", "srp", "mkd", "mac", "kaz")
    /** Код набора «русский/болгарский» (кириллица, вариант 2) */
    const val CYRILLIC_RU = 0x24

    private val REV = ByteArray(256) { Integer.reverse(it).ushr(24).toByte() }
    fun rev(b: Byte): Byte = REV[b.toInt() and 0xFF]

    // Хэмминг 8/4: P1 D1 P2 D2 P3 D3 P4 D4 (младший бит первым), нечётная чётность; исправляется одна ошибка
    private fun enc84(n: Int): Int {
        val d1 = n and 1; val d2 = (n shr 1) and 1; val d3 = (n shr 2) and 1; val d4 = (n shr 3) and 1
        val p1 = 1 xor d1 xor d3 xor d4
        val p2 = 1 xor d1 xor d2 xor d4
        val p3 = 1 xor d1 xor d2 xor d3
        val low = p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (d4 shl 7)
        val p4 = (Integer.bitCount(low) + 1) and 1  // всего единиц — нечётное число
        return low or (p4 shl 6)
    }

    private val H84 = IntArray(256) { b ->
        var r = -1
        for (n in 0..15) if (Integer.bitCount(b xor enc84(n)) <= 1) r = n
        r
    }

    /** Полубайт из байта Хэмминга 8/4 (биты уже в прямом порядке); -1 — не исправить */
    fun ham84(b: Byte): Int = H84[b.toInt() and 0xFF]

    /** 18 бит данных из трёх байт Хэмминга 24/18; без проверки ошибок. null — не хватает данных */
    fun ham2418(d: ByteArray, p: Int): Int? {
        if (p + 3 > d.size) return null
        val b0 = d[p].toInt() and 0xFF; val b1 = d[p + 1].toInt() and 0x7F; val b2 = d[p + 2].toInt() and 0x7F
        val lo = ((b0 shr 2) and 1) or (((b0 shr 4) and 1) shl 1) or (((b0 shr 5) and 1) shl 2) or (((b0 shr 6) and 1) shl 3)
        return lo or (b1 shl 4) or (b2 shl 11)
    }

    // ---------------------------------------------------------------- наборы G0

    private val LATIN_NATIONAL = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)
    private val LATIN_OPTIONS = arrayOf(
        "£\$@←½→↑#―¼‖¾÷",    // английский
        "#\$§ÄÖÜ^_°äöüß",    // немецкий
        "#¤ÉÄÖÅÜ_éäöåü",    // шведский / финский
        "£\$é°ç→↑#ùàòèì",    // итальянский
        "éïàëêùî#èâôûç",    // французский
        "ç\$¡áéíóú¿üñèà",    // испанский / португальский
        "#ůčťžýířéáěúš"     // чешский / словацкий
    )
    private const val CYR_UP = "ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЪЗШЭЩЧ"   // 0x40…0x5E (0x5F — «Ы»)
    private const val CYR_LOW = "юабцдефгхийклмнопярстужвьъзшэщч"  // 0x60…0x7E («ы» — на месте «&», 0x26)

    /** Символ Юникода для кода [c] (0x20…0x7F) в наборе G0 с кодом [charset] (7 бит) */
    fun glyph(c: Int, charset: Int): Int {
        if (c == 0x7F) return 0x25A0
        if (charset == 0x24 || charset == 0x25) {
            // кириллица: украинский вариант показываем по русскому — различаются единичными буквами
            if (c == 0x26) return 'ы'.code
            if (c in 0x40..0x5E) return CYR_UP[c - 0x40].code
            if (c == 0x5F) return 'Ы'.code
            if (c in 0x60..0x7E) return CYR_LOW[c - 0x60].code
            return c
        }
        val opt = LATIN_OPTIONS.getOrNull(charset and 7) ?: LATIN_OPTIONS[0]
        val k = LATIN_NATIONAL.indexOf(c)
        return if (k >= 0) opt[k].code else c
    }

    // G2 (латиница, дополнительные знаки) 0x20…0x7F
    private const val G2 = " ¡¢£\$¥#§¤‘“«←↑→↓" + "°±²³×µ¶·÷’”»¼½¾¿" + " `´ˆ˜¯˘˙¨ ˚¸_˝˛ˇ" + "―¹®©™♪₠‰α   ⅛⅜⅝⅞" +
        "ΩÆĐªĦ ĲĿŁØŒºÞŦŊŉ" + "ĸæđðħıĳŀłøœßþŧŋ■"
    private val COMBINING = intArrayOf(0, 0x300, 0x301, 0x302, 0x303, 0x304, 0x306, 0x307, 0x308, 0, 0x30A, 0x327, 0, 0x30B, 0x328, 0x30C)

    /** Знак G2 по коду 0x20…0x7F */
    fun g2(c: Int): Int = G2.getOrNull(c - 0x20)?.code ?: 0x20

    /** Знак набора G0 с диакритикой ([mark] 0…15, как в режимах 0x10…0x1F пакета X/26): «е» + «¨» = «ё» */
    fun accented(c: Int, charset: Int, mark: Int): Int {
        val base = glyph(c, charset)
        val comb = COMBINING.getOrElse(mark) { 0 }
        if (comb == 0) return base
        val n = java.text.Normalizer.normalize(String(Character.toChars(base)) + String(Character.toChars(comb)), java.text.Normalizer.Form.NFC)
        return if (n.codePointCount(0, n.length) == 1) n.codePointAt(0) else base
    }

    // ---------------------------------------------------------------- раскладка в сетку 40×25

    const val COLS = 40
    const val ROWS = 25

    const val A_MOSAIC = 1 shl 6
    const val A_SEPARATED = 1 shl 7
    /** Верхняя половина двойной высоты (символ рисуется вдвое выше) */
    const val A_DH = 1 shl 8
    /** Нижняя половина: символ — копия верхней ячейки из строки выше */
    const val A_DH_BOTTOM = 1 shl 9
    const val A_FLASH = 1 shl 10
    const val A_CONCEAL = 1 shl 11
    /** Внутри «окна» (между кодами начала и конца окна) — для субтитров */
    const val A_BOX = 1 shl 12
    /** Двойная ширина: знак вдвое шире, занимает эту и правую ячейку */
    const val A_DW = 1 shl 13
    /** Правая половина знака двойной ширины (своего знака нет) */
    const val A_DW_RIGHT = 1 shl 14

    /**
     * Сетка: [ch] — код символа (Юникод; для мозаики — исходный 0x20…0x7F), [attr] — цвет символа
     * (биты 0–2), цвет фона (3–5) и признаки A_*.
     */
    class Grid(val ch: IntArray = IntArray(COLS * ROWS) { 0x20 }, val attr: IntArray = IntArray(COLS * ROWS) { 7 }) {
        fun fg(i: Int) = attr[i] and 7
        fun bg(i: Int) = (attr[i] shr 3) and 7
        fun has(i: Int, flag: Int) = attr[i] and flag != 0
        fun hasBox() = attr.any { it and A_BOX != 0 }
        fun hasFlash() = attr.any { it and A_FLASH != 0 }
        fun hasConceal() = attr.any { it and A_CONCEAL != 0 }
        /** Текст строки [r] (мозаика — пробелы) */
        fun rowText(r: Int) = buildString { for (c in 0 until COLS) { val i = r * COLS + c; append(if (attr[i] and A_MOSAIC != 0) ' ' else String(Character.toChars(ch[i]))) } }
    }

    /**
     * Раскладка страницы по правилам уровня 1: управляющие коды — «последовательные атрибуты»
     * (цвет, мозаика, высота, окно, удержание мозаики). [row0] — готовая строка 0 для заголовка
     * (иначе строка 0 пустая).
     */
    fun layout(page: TtxPage, row0: ByteArray? = null): Grid {
        val g = Grid()
        var skipRow = -1       // эта строка занята нижней половиной двойной высоты
        var skipFrom: Grid? = null
        for (r in 0 until ROWS) {
            val data = if (r == 0) row0 else page.rows[r]
            if (r == skipRow) {
                // нижняя половина: копия верхних ячеек; там, где выше обычные символы — их фон
                for (c in 0 until COLS) {
                    val up = (r - 1) * COLS + c
                    val i = r * COLS + c
                    if (g.has(up, A_DH)) { g.ch[i] = g.ch[up]; g.attr[i] = g.attr[up] and A_DH.inv() or A_DH_BOTTOM }
                    else { g.ch[i] = 0x20; g.attr[i] = (g.bg(up) shl 3) or 7 }
                }
                skipRow = -1
                continue
            }
            if (data == null) continue
            var fg = 7; var bg = 0; var mosaic = false; var sep = false
            var hold = false; var held = 0x20; var heldSep = false
            var dh = false; var dw = false; var flash = false; var conceal = false; var box = false
            var rightHalf = false  // эта ячейка — правая половина знака двойной ширины
            var prev = 0
            var anyDh = false
            for (c in 0 until COLS) {
                val code = data[c].toInt() and 0x7F
                var shown: Int
                var shownMosaic = false
                var shownSep = sep
                if (code >= 0x20) {
                    if (mosaic && (code < 0x40 || code >= 0x60)) { shown = code; shownMosaic = true; held = code; heldSep = sep }
                    // «P100» в заголовке — всегда латиница
                    else shown = glyph(code, if (r == 0 && c < 8) 0 else page.charset)
                } else {
                    // «Set-At»: действуют уже на самой управляющей ячейке
                    when (code) {
                        0x09 -> flash = false
                        0x0C -> { dh = false; dw = false }
                        0x18 -> conceal = true
                        0x19 -> sep = false
                        0x1A -> sep = true
                        0x1C -> bg = 0
                        0x1D -> bg = fg
                        0x1E -> hold = true
                    }
                    // ячейка управляющего кода — пробел или удержанная мозаика
                    if (hold && mosaic) { shown = held; shownMosaic = true; shownSep = heldSep } else { shown = 0x20; shownSep = sep }
                }
                // двойная ширина: знак занимает две ячейки, знак правой не показывается
                val wide = dw && !rightHalf && code >= 0x20 && c < COLS - 1
                val a = (fg and 7) or ((bg and 7) shl 3) or (if (shownMosaic) A_MOSAIC else 0) or (if (shownMosaic && shownSep) A_SEPARATED else 0) or
                    (if (dh) A_DH else 0) or (if (flash) A_FLASH else 0) or (if (conceal) A_CONCEAL else 0) or (if (box) A_BOX else 0) or
                    (if (wide) A_DW else 0) or (if (rightHalf) A_DW_RIGHT else 0)
                rightHalf = wide
                val i = r * COLS + c
                g.ch[i] = shown; g.attr[i] = a
                if (dh) anyDh = true
                if (code < 0x20) {
                    // «Set-After»: со следующей ячейки
                    when (code) {
                        // коды 0x00 и 0x10 — чёрный (так их понимают современные приёмники; в уровне 1 они зарезервированы)
                        in 0x00..0x07 -> { fg = code; mosaic = false; held = 0x20 }
                        in 0x10..0x17 -> { fg = code - 0x10; mosaic = true }
                        0x08 -> flash = true
                        0x0A -> if (prev == 0x0A) box = false
                        0x0B -> if (prev == 0x0B) box = true
                        0x0D -> { dh = true; dw = false }
                        0x0E -> { dw = true; dh = false }
                        0x0F -> { dh = true; dw = true }
                        0x1F -> { hold = false }
                    }
                }
                prev = code
            }
            if (anyDh && r < ROWS - 1) skipRow = r + 1
        }
        page.enh?.let { enhance(page, g) }
        return g
    }

    /**
     * Расширения уровня 1.5 (пакеты X/26): слова «адрес, режим, данные». Группа строки (адрес 40…63)
     * задаёт позицию (режим 4: строка = адрес − 40, 40 — строка 24, столбец — в данных); группа
     * столбца (0…39) — знак в этом столбце текущей строки: режим 9 — знак G0, 15 — знак G2,
     * 16…31 — знак G0 с диакритикой (режим − 16). Цвета, мозаика G3, объекты и DRCS пропускаются.
     */
    private fun enhance(page: TtxPage, g: Grid) {
        val t = page.enh ?: return
        var row = 0
        for (w in t) {
            val addr = w and 0x3F
            val mode = (w shr 6) and 0x1F
            val data = (w shr 11) and 0x7F
            if (addr >= 40) {
                when (mode) {
                    0x04 -> row = if (addr == 40) 24 else addr - 40
                    0x1F -> return  // конец
                }
            } else if (row in 1..24 && data >= 0x20) {
                val ch = when {
                    mode == 0x09 -> glyph(data, page.charset)
                    mode == 0x0F -> g2(data)
                    mode >= 0x10 -> accented(data, page.charset, mode - 0x10)
                    else -> continue
                }
                val i = row * COLS + addr
                if (g.attr[i] and A_MOSAIC == 0) g.ch[i] = ch
            }
        }
        // нижние половины двойной высоты повторяют верхние
        for (i in COLS until COLS * ROWS) if (g.attr[i] and A_DH_BOTTOM != 0) g.ch[i] = g.ch[i - COLS]
    }

    /** Строка 0 для показа: «P100» слева, остальное — из заголовка страницы (дата, время) */
    fun headerRow(number: Int, rawHeader: ByteArray?, typed: String = ""): ByteArray {
        val out = ByteArray(40) { 0x20 }
        // при наборе номера — набранные цифры и чёрточки: «P12-»
        val label = if (typed.isEmpty()) "P%03X".format(number) else "P" + typed.padEnd(3, '-')
        for (i in label.indices) out[1 + i] = label[i].code.toByte()
        if (rawHeader != null) for (i in 8 until 40) out[i] = rawHeader[i]
        return out
    }

    // ---------------------------------------------------------------- субтитры

    /** Отрезок текста одного цвета в строке субтитра */
    class Run(val text: String, val fg: Int, val bg: Int)

    /**
     * Строки субтитра из сетки: только «окна» (если они есть), без строки 0 и нижних половин
     * двойной высоты. Пустые строки пропускаются, края — без пробелов.
     */
    fun subtitleLines(g: Grid): List<List<Run>> {
        val boxes = g.hasBox()
        val out = ArrayList<List<Run>>()
        for (r in 1 until ROWS) {
            val runs = ArrayList<Run>()
            val sb = StringBuilder()
            var rf = -1; var rb = -1
            fun flush() { if (sb.isNotEmpty()) runs.add(Run(sb.toString(), rf, rb)); sb.setLength(0) }
            for (c in 0 until COLS) {
                val i = r * COLS + c
                val visible = (!boxes || g.has(i, A_BOX)) && !g.has(i, A_DH_BOTTOM) && !g.has(i, A_CONCEAL)
                if (!visible) { flush(); continue }
                val text = if (g.has(i, A_MOSAIC)) 0x20 else g.ch[i]
                // пробел внутри окна остаётся, но на границах строки обрезается ниже
                if (sb.isNotEmpty() && (rf != g.fg(i) && text != 0x20 || rb != g.bg(i))) flush()
                if (sb.isEmpty()) { rf = g.fg(i); rb = g.bg(i) }
                sb.appendCodePoint(text)
            }
            flush()
            // обрезать пробелы по краям строки
            while (runs.isNotEmpty() && runs.last().text.isBlank()) runs.removeAt(runs.size - 1)
            while (runs.isNotEmpty() && runs.first().text.isBlank()) runs.removeAt(0)
            if (runs.isEmpty()) continue
            val f = runs.first(); val l = runs.last()
            if (f.text.startsWith(' ')) runs[0] = Run(f.text.trimStart(), f.fg, f.bg)
            if (l.text.endsWith(' ')) runs[runs.size - 1] = Run(runs.last().text.trimEnd(), l.fg, l.bg)
            out.add(runs)
        }
        return out
    }
}
