package top.rootu.perfecttv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="test">
  <channel id="1tv"><display-name lang="ru">Первый канал</display-name><display-name>Channel One</display-name><icon src="http://i/1.png"/></channel>
  <channel id="ntv"><display-name lang="ru">НТВ</display-name></channel>
  <channel id="skip"><display-name>Skip</display-name></channel>
  <programme start="20231114220000 +0300" stop="20231114230000 +0300" channel="1tv">
    <title lang="en">News</title><title lang="ru">Новости</title>
    <desc lang="ru"><![CDATA[Главное & <важное>]]></desc>
    <category lang="ru">Новости</category>
  </programme>
  <programme start="20231114230000 +0300" channel="1tv"><title>Без конца</title></programme>
  <programme start="20231115000000 +0300" stop="20231115010000 +0300" channel="1tv"><title>Ночь &amp; день</title></programme>
  <programme start="20231114190000" stop="20231114200000" channel="ntv"><title>Сегодня</title></programme>
  <programme start="20231114190000 +0000" stop="20231114200000 +0000" channel="skip"><title>X</title></programme>
</tv>"""

    private fun run(bytes: ByteArray, wanted: Set<String>): Triple<XmltvParser, List<EpgChannelRaw>, List<EpgProgramme>> {
        val ch = ArrayList<EpgChannelRaw>()
        val pr = ArrayList<EpgProgramme>()
        var before = 0
        val p = XmltvParser(listOf("ru"), { ch.add(it) }, { before++ }, { it in wanted }, { pr.add(it) })
        p.parse(Streams.open(ByteArrayInputStream(bytes)))
        assertEquals(1, before)
        return Triple(p, ch, pr)
    }

    @Test fun channels_and_programmes() {
        val (p, ch, pr) = run(xml.toByteArray(), setOf("1tv", "ntv"))
        assertEquals(3, ch.size)
        assertEquals(listOf("Первый канал", "Channel One"), ch[0].names)
        assertEquals("http://i/1.png", ch[0].icon)
        assertEquals(1, p.skipped)
        val first = pr.first { it.channel == "1tv" }
        assertEquals("Новости", first.title)
        assertEquals("Главное & <важное>", first.desc)
        assertEquals("Новости", first.category)
        assertEquals(1699988400L, first.start)   // 22:00 +0300 = 19:00Z
        val noStop = pr.first { it.title == "Без конца" }
        assertEquals(noStop.start + 3600, noStop.stop)                    // до следующей передачи канала
        assertEquals("Ночь & день", pr.first { it.start == noStop.stop }.title)
        assertEquals(XmltvParser.parseTime("20231114190000 +0000"), pr.first { it.channel == "ntv" }.start)
    }

    @Test fun acceptSkipsProgrammesWithoutParsingThem() {
        val pr = ArrayList<EpgProgramme>()
        val asked = ArrayList<Triple<String, Long, Long>>()
        val p = XmltvParser(listOf("ru"), {}, {}, { it == "1tv" }, { pr.add(it) }) { ch, start, stop -> asked.add(Triple(ch, start, stop)); start >= 1699990000L }
        p.parse(Streams.open(ByteArrayInputStream(xml.toByteArray())))
        // передачи с stop спрашиваются (две у 1tv), передача без stop — нет; отклонённая (22:00 +0300 = 1699988400) не выдаётся
        assertEquals(2, asked.size)
        assertEquals(listOf("Без конца", "Ночь & день"), pr.map { it.title })
    }

    @Test fun gzip() {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(xml.toByteArray()) } }.toByteArray()
        val (_, _, pr) = run(bytes, setOf("1tv"))
        assertEquals(3, pr.size)
    }

    @Test fun windows1251() {
        val text = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><tv><channel id=\"a\"><display-name>Канал</display-name></channel>" +
            "<programme start=\"20231114190000 +0000\" stop=\"20231114200000 +0000\" channel=\"a\"><title>Передача</title></programme></tv>"
        val (_, ch, pr) = run(text.toByteArray(charset("windows-1251")), setOf("a"))
        assertEquals("Канал", ch[0].names[0])
        assertEquals("Передача", pr[0].title)
    }

    @Test fun time() {
        assertEquals(1699988400L, XmltvParser.parseTime("20231114220000 +0300"))
        assertEquals(1699988400L, XmltvParser.parseTime("20231114190000"))
        assertEquals(1699988400L, XmltvParser.parseTime("202311141900 +0000"))
        assertEquals(1699988400L, XmltvParser.parseTime("20231114160000 -0300"))
        assertEquals(0L, XmltvParser.parseTime("bad"))
    }
}

class XmltvEdgeTest {
    @Test fun comments_nested_tags_entities_selfclosed() {
        val xml = """<?xml version="1.0"?><!-- комментарий <programme channel="a"> -->
<tv><channel id="a&amp;b"><display-name>A &amp; B</display-name></channel>
<programme start="20231114190000 +0000" stop="20231114200000 +0000" channel="a&amp;b">
<title lang="ru">Кино &#171;Х&#xBB;</title><desc>Текст <b>жирный</b> и &lt;угол&gt;</desc><icon src="http://i/x.png" /></programme>
<programme start="20231114200000 +0000" stop="20231114210000 +0000" channel="a&amp;b"/>
<programme channel="other" start="20231114200000 +0000" stop="20231114210000 +0000"><title>skip</title></programme>
</tv>"""
        val pr = ArrayList<EpgProgramme>()
        val ch = ArrayList<EpgChannelRaw>()
        val p = XmltvParser(listOf("ru"), { ch.add(it) }, {}, { it == "a&b" }, { pr.add(it) })
        p.parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        assertEquals("a&b", ch[0].id)
        assertEquals("A & B", ch[0].names[0])
        assertEquals(2, pr.size)
        assertEquals("Кино «Х»", pr[0].title)
        assertEquals("Текст жирный и <угол>", pr[0].desc)
        assertEquals("http://i/x.png", pr[0].icon)
        assertEquals("", pr[1].title)
        assertEquals(1, p.skipped)
    }

    /**
     * Скорость: синтетический XMLTV (2000 каналов, нужны 5%) потоком без хранения в памяти.
     * Печатает МБ/с — ориентир для цели ТЗ (1 ГБ за 60 с на S905X, т.е. ≥ 17 МБ/с там).
     */
    @Test fun throughput() {
        val mb = (System.getProperty("xmltv.mb") ?: "200").toInt()
        // данные готовятся заранее (повтор одного блока): меряем разбор, а не генератор
        val input = Synthetic(mb.toLong() * 1024 * 1024, 2000)
        input.prepare()
        var n = 0
        val p = XmltvParser(listOf("ru"), {}, {}, { it.hashCode() % 20 == 0 }, { n++ })
        val t0 = System.nanoTime()
        p.parse(input)
        val sec = (System.nanoTime() - t0) / 1e9
        println("XMLTV ${input.produced / 1048576} МБ за ${"%.2f".format(sec)} с: ${"%.0f".format(input.produced / 1048576 / sec)} МБ/с, передач взято $n, пропущено ${p.skipped}")
        assert(n > 0 && p.skipped > n)
    }

    /** Поток синтетического XMLTV заданного размера */
    private class Synthetic(private val size: Long, private val channels: Int) : java.io.InputStream() {
        var produced = 0L
        private var chunk = ByteArray(0)
        private var pos = 0
        private var k = 0
        private var header = true

        private fun next() {
            val sb = StringBuilder()
            if (header) {
                header = false
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"synthetic\">\n")
                for (c in 0 until channels) sb.append("<channel id=\"ch$c\"><display-name lang=\"ru\">Канал $c</display-name><icon src=\"http://logo/$c.png\"/></channel>\n")
            } else if (produced >= size) {
                sb.append("</tv>\n")
            } else if (blocks != null) {
                chunk = blocks!![bi++ % blocks!!.size]
                pos = 0
                return
            } else {
                for (r in 0 until 50) {
                    val c = k % channels
                    val t = 1700000000L + (k / channels) * 1800
                    k++
                    sb.append("<programme start=\"").append(fmt(t)).append(" +0300\" stop=\"").append(fmt(t + 1800)).append(" +0300\" channel=\"ch").append(c).append("\">\n")
                        .append("  <title lang=\"ru\">Передача номер ").append(k).append(" &amp; продолжение</title>\n")
                        .append("  <desc lang=\"ru\">Описание передачи: сюжет, ведущие, гости и прочие подробности, которые занимают место в файле программы. Серия ").append(k).append(".</desc>\n")
                        .append("  <category lang=\"ru\">Развлекательные</category>\n</programme>\n")
                }
            }
            chunk = sb.toString().toByteArray()
            pos = 0
        }

        private var done = false
        private var blocks: List<ByteArray>? = null
        private var bi = 0

        /** Заранее собрать ~8 МБ блоков передач и дальше отдавать их по кругу */
        fun prepare() {
            val list = ArrayList<ByteArray>()
            var total = 0
            val saveHeader = header
            header = false
            while (total < 8 * 1024 * 1024) {
                next()
                list.add(chunk)
                total += chunk.size
            }
            header = saveHeader
            blocks = list
            chunk = ByteArray(0)
            k = 0
        }

        private fun fmt(t: Long): String {
            val d = java.time.LocalDateTime.ofEpochSecond(t, 0, java.time.ZoneOffset.ofHours(3))
            return "%04d%02d%02d%02d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second)
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= chunk.size) {
                if (done) return -1
                if (produced >= size) done = true
                next()
            }
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            produced += n
            return n
        }
    }
}
