package top.rootu.perfecttv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class M3uParserTest {

    private val list = """#EXTM3U url-tvg="http://epg.example/epg.xml.gz" catchup="shift" catchup-days="3"
#EXTINF:-1 tvg-id="first" tvg-logo="http://logo/1.png" group-title="Новости",Первый HD
http://stream/1.m3u8
#EXTGRP:Кино
#EXTINF:-1 catchup-days="5",Кино ТВ
#EXTVLCOPT:http-user-agent=Test
http://stream/2.m3u8
#EXTINF:-1,YouTube
https://www.youtube.com/watch?v=x
#EXTINF:0 tvg-name=Some,Без группы
http://stream/3.m3u8
"""

    private fun parse(text: String, group: String = "Other"): Pair<M3uParser, List<RawChannel>> {
        val p = M3uParser(group)
        val out = ArrayList<RawChannel>()
        p.parse(BufferedReader(text.reader())) { out.add(it) }
        return p to out
    }

    @Test fun header_groups_options() {
        val (p, ch) = parse(list)
        assertTrue(p.isM3u)
        assertEquals("http://epg.example/epg.xml.gz", p.header["url-tvg"])
        assertEquals(3, ch.size)
        assertEquals(listOf("Новости", "Кино", "Кино"), ch.map { it.group })
        assertEquals("http://logo/1.png", ch[0].logo)
        assertEquals("Test", ch[1].userAgent)
        assertEquals("Без группы", ch[2].name)
        assertEquals("Some", ch[2].tvgName)
        assertEquals(listOf(1, 2, 3), ch.map { it.num })
        // архив: из заголовка (shift, 3 дня) и свой у канала (5 дней)
        assertEquals(Catchup("shift", "", 3), ch[0].catchup)
        assertEquals(5, ch[1].catchup!!.days)
    }

    @Test fun comma_inside_quoted_attribute() {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        val text = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"7843\" tvg-logo=\"https://example.com/J4zlRGv.png\" http-user-agent=\"$ua\" group-title=\"Украина 🇺🇦\",ICTV 2 HD\n" +
            "#EXTVLCOPT:http-user-agent=$ua\n" +
            "http://example.com/368/index.m3u8\n" +
            "#EXTINF:-1 group-title='A, B',Канал, с запятой\n" +
            "http://example.com/2.m3u8\n"
        val (_, ch) = parse(text)
        assertEquals("ICTV 2 HD", ch[0].name)
        assertEquals("Украина 🇺🇦", ch[0].group)
        assertEquals("7843", ch[0].tvgId)
        assertEquals(ua, ch[0].userAgent)
        assertEquals("Канал, с запятой", ch[1].name)
        assertEquals("A, B", ch[1].group)
    }

    @Test fun bom_crlf_and_not_m3u() {
        val (p, ch) = parse("﻿#EXTM3U\r\n#EXTINF:-1,A\r\nhttp://a\r\n")
        assertTrue(p.isM3u)
        assertEquals("A", ch[0].name)
        assertEquals("http://a", ch[0].url)
        val (p2, ch2) = parse("<html>\nhttp://a\n")
        assertFalse(p2.isM3u)
        assertTrue(ch2.isEmpty())
    }

    @Test fun catchup_disabled_and_enable() {
        val (_, ch) = parse("#EXTM3U catchup-days=\"7\"\n" +
            "#EXTINF:-1 catchup=\"disabled\",A\nhttp://a\n" +
            "#EXTINF:-1 catchup-enable=\"0\",B\nhttp://b\n" +
            "#EXTINF:-1 catchup=\"flussonic\",C\nhttp://c\n" +
            "#EXTINF:-1,D\nhttp://d\n")
        assertNull(ch[0].catchup)
        assertNull(ch[1].catchup)
        assertEquals(Catchup("flussonic", "", 7), ch[2].catchup)
        assertEquals(7, ch[3].catchup!!.days)
    }

    @Test fun extinf_without_info_and_referrer() {
        val (_, ch) = parse("#EXTM3U\nhttp://a\n#EXTINF:-1 http-referrer=\"http://r\",B\n#EXTHTTP:{\"Cookie\":\"x\"}\nhttp://b\n")
        assertEquals("Ch 1", ch[0].name)
        assertEquals("http://r", ch[1].referrer)
        assertEquals("{\"Cookie\":\"x\"}", ch[1].headersJson)
    }

    @Test fun several_epg_urls() {
        val h = M3uParser.parseAttrs(" url-tvg=\"http://a/epg.xml.gz,http://b/epg.xml http://a/epg.xml.gz\"")
        assertEquals(listOf("http://a/epg.xml.gz", "http://b/epg.xml"), M3uParser.epgUrls(h))
    }

    @Test fun gzip_stream() {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(list.toByteArray()) } }.toByteArray()
        val p = M3uParser()
        var n = 0
        p.parse(Streams.open(ByteArrayInputStream(bytes)).bufferedReader()) { n++ }
        assertEquals(3, n)
    }

    @Test fun names() {
        assertEquals("первыйhd", Names.key("Первый HD"))
        assertEquals("ICTV".lowercase(), Names.key("I.C.T.V"))
        assertEquals(Names.matchKey("НТВ"), Names.matchKey("HTB"))
        assertEquals(Names.matchKey("Телеканал Звезда"), Names.matchKey("Звезда"))
        assertEquals("россия1", Names.short("Россия 1"))
    }
}
