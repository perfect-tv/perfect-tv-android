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
