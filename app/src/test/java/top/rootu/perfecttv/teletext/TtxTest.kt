package top.rootu.perfecttv.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Разбор телетекста на записанных потоках (файлы `.ttx` в `tools/samples` — PES-пакеты, снятые
 * `tools/find_teletext.py --save-dir`). Нет файлов — тест пропускается.
 */
class TtxTest {

    private fun sample(name: String): File? =
        listOf("../tools/samples/$name", "tools/samples/$name").map(::File).firstOrNull { it.exists() }

    /** Прогнать весь файл через разборщик; вернуть его и список страниц, отданных в sink */
    private fun decode(name: String, cyr: Boolean = true): Pair<TtxDecoder, List<TtxPage>> {
        val f = sample(name)
        assumeTrue("нет образца $name", f != null)
        val d = f!!.readBytes()
        val dec = TtxDecoder().also { it.cyrillicDefault = cyr }
        val sunk = ArrayList<TtxPage>()
        var i = 0
        var t = 0L
        while (i + 4 <= d.size) {
            val n = ((d[i].toInt() and 0xFF) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            val p = i + 4
            val hdr = 9 + (d[p + 8].toInt() and 0xFF)  // заголовок PES: 9 байт + дополнение
            dec.feed(d, p + hdr, n - hdr, t++) { pg, _ -> sunk.add(pg) }
            i = p + n
        }
        return dec to sunk
    }

    private fun text(page: TtxPage): String {
        val g = Ttx.layout(page, Ttx.headerRow(page.number, page.rows[0]))
        return (0 until Ttx.ROWS).joinToString("\n") { g.rowText(it).trimEnd() }
    }

    @Test fun hamming84() {
        // все 16 кодовых слов узнаются, одна ошибка исправляется
        for (n in 0..15) {
            var found = false
            for (b in 0..255) if (Ttx.ham84(b.toByte()) == n && Integer.bitCount(b xor b) == 0) found = true
            assertTrue(found)
        }
        // два бита ошибки — не исправить (хотя бы одно слово)
        assertTrue((0..255).any { Ttx.ham84(it.toByte()) < 0 })
    }

    @Test fun ntvMurmanskIndexPage() {
        val (dec, _) = decode("НТВ_Мурманск.ttx")
        val pg = dec.subpages(0x100).firstOrNull()
        assertTrue("страница 100 не принята: ${dec.numbers()}", pg != null)
        val t = text(pg!!)
        println(t)
        assertTrue(t, t.contains("Для просмотра скрытых субтитров"))
        assertTrue(t, t.contains("выберите страницу 888"))
    }

    @Test fun russia1Subtitles() {
        val (dec, sunk) = decode("Россия_1_Калининград.ttx")
        println("принято: ${dec.numbers()}")
        val sub = sunk.filter { it.number == 0x888 }
        assertTrue("страница 888 не принята", sub.isNotEmpty())
        val lines = sub.map { pg -> Ttx.subtitleLines(Ttx.layout(pg)).joinToString(" / ") { l -> l.joinToString("") { it.text } } }
        println(lines.distinct().joinToString("\n"))
        assertTrue(lines.any { it.contains("ПРОГРАММА ТРАНСЛИРУЕТСЯ") })
    }

    @Test fun tvcMurmanskPages() {
        val (dec, _) = decode("ТВ_Центр_Мурманск.ttx")
        val nums = dec.numbers()
        println("принято: " + nums.joinToString { "%X".format(it) })
        assertTrue(nums.isNotEmpty())
        dec.subpages(nums.first()).firstOrNull()?.let { println(text(it)) }
        // быстрые ссылки хотя бы у одной страницы
        println("ссылки: " + dec.store.values.firstOrNull { it.links != null }?.links?.joinToString { "%X".format(it) })
    }

    @Test fun headerBitErrorsAreRare() {
        val (dec, _) = decode("НТВ_Мурманск.ttx")
        assertEquals(true, dec.numbers().isNotEmpty())
    }

    // ------------------------------------------------------------ раскладка уровня 1 (без файлов)

    private fun page(vararg rows: Pair<Int, List<Int>>, charset: Int = 0): TtxPage {
        val r = arrayOfNulls<ByteArray>(25)
        for ((i, bytes) in rows) r[i] = ByteArray(40) { bytes.getOrElse(it) { 0x20 }.toByte() }
        return TtxPage(0x100, 0, 0, charset, r, null)
    }

    private fun s(t: String) = t.map { it.code }

    @Test fun colourCodesAreSetAfter() {
        val g = Ttx.layout(page(1 to listOf(0x02) + s("A") + listOf(0x01) + s("B")))
        val r = Ttx.COLS  // строка 1
        assertEquals(0x20, g.ch[r])                 // в самой ячейке кода — пробел
        assertEquals('A'.code, g.ch[r + 1]); assertEquals(2, g.fg(r + 1))  // зелёный — со следующей ячейки
        assertEquals('B'.code, g.ch[r + 3]); assertEquals(1, g.fg(r + 3))
    }

    @Test fun blackAndNewBackground() {
        // 0x1D: фон = текущий цвет; текст чёрным (0x00) на белом фоне
        val g = Ttx.layout(page(2 to listOf(0x1D, 0x00) + s("Z")))
        val i = 2 * Ttx.COLS + 2
        assertEquals(0, g.fg(i)); assertEquals(7, g.bg(i))
    }

    @Test fun doubleHeightTakesNextRow() {
        val g = Ttx.layout(page(3 to listOf(0x0D) + s("H"), 4 to s("ignored")))
        val top = 3 * Ttx.COLS + 1
        val bottom = 4 * Ttx.COLS + 1
        assertTrue(g.has(top, Ttx.A_DH))
        assertTrue(g.has(bottom, Ttx.A_DH_BOTTOM))
        assertEquals('H'.code, g.ch[bottom])
        assertEquals(0x20, g.ch[4 * Ttx.COLS + 5])  // содержимое строки под двойной высотой не показывается
    }

    @Test fun mosaicHold() {
        // мозаика красным, блок, код удержания, потом управляющий код — показывает удержанный блок
        val g = Ttx.layout(page(5 to listOf(0x11, 0x2F, 0x1E, 0x12, 0x20)))
        assertTrue(g.has(1 + 5 * Ttx.COLS, Ttx.A_MOSAIC))
        assertEquals(0x2F, g.ch[3 + 5 * Ttx.COLS])  // ячейка кода 0x12 при удержании
    }

    @Test fun boxesLimitSubtitleText() {
        val row = s("   ") + listOf(0x0B, 0x0B) + s("Hi") + listOf(0x0A, 0x0A) + s("tail")
        val lines = Ttx.subtitleLines(Ttx.layout(page(20 to row)))
        assertEquals(1, lines.size)
        assertEquals("Hi", lines[0].joinToString("") { it.text }.trim())
    }

    @Test fun cyrillicAndNationalSets() {
        assertEquals('Д'.code, Ttx.glyph(0x44, 0x24))
        assertEquals('я'.code, Ttx.glyph(0x71, 0x24))
        assertEquals('ы'.code, Ttx.glyph(0x26, 0x24))
        assertEquals('Ы'.code, Ttx.glyph(0x5F, 0x24))  // у заглавной «Ы» и «Ъ» места иные, чем в КОИ-7
        assertEquals('Ъ'.code, Ttx.glyph(0x59, 0x24))
        assertEquals('£'.code, Ttx.glyph(0x23, 0))      // английский вариант
        assertEquals('Ä'.code, Ttx.glyph(0x5B, 1))      // немецкий
        assertEquals('A'.code, Ttx.glyph(0x41, 0))
    }

    @Test fun headerKeepsLatinLabel() {
        val p = page(0 to s("x"), charset = Ttx.CYRILLIC_RU)
        val g = Ttx.layout(p, Ttx.headerRow(0x100, p.rows[0]))
        assertEquals("P100", g.rowText(0).substring(1, 5))
    }
}
