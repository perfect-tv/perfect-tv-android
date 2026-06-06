package top.rootu.perfecttv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class CatchupUrlTest {
    @Test fun shift() {
        assertEquals("http://h/ch/index.m3u8?utc=\${start}&lutc=\${timestamp}", CatchupUrl.template("http://h/ch/index.m3u8", "shift", ""))
        assertEquals("http://h/ch/index.m3u8?a=1&utc=\${start}&lutc=\${timestamp}", CatchupUrl.template("http://h/ch/index.m3u8?a=1", "", ""))
    }

    @Test fun flussonic() {
        assertEquals("http://h/ch/archive-\${start}-\${duration}.m3u8?token=1", CatchupUrl.template("http://h/ch/index.m3u8?token=1", "flussonic", ""))
        assertEquals("http://h/ch/timeshift_abs-\${start}.ts?token=1", CatchupUrl.template("http://h/ch/mpegts?token=1", "fs", ""))
        // примеры из tv_plugin/legacy/tv.js
        assertEquals("http://h/325/mono-\${start}-\${duration}.m3u8?token=s", CatchupUrl.template("http://h/325/mono.m3u8?token=s", "flussonic-hls", ""))
        assertEquals("http://h/325/video-\${start}-\${duration}.ts", CatchupUrl.template("http://h/325/video.ts", "flussonic-ts", ""))
        assertEquals("http://h/325/archive-\${start}-\${duration}.m3u8", CatchupUrl.template("http://h/325/playlist.m3u8", "Flussonic", ""))
    }

    /** Flussonic — окно фиксированной длины в секундах от начала (не «-now»), как в legacy/tv.js */
    @Test fun flussonic_prepare() {
        val tpl = CatchupUrl.template("http://h/ch/index.m3u8?token=1", "flussonic", "")!!
        assertEquals("http://h/ch/archive-1700000000-5400.m3u8?token=1", CatchupUrl.prepare(tpl, 1700000000, 5400, 1700003000))
    }

    @Test fun xc() {
        assertEquals("http://h:8080/timeshift/u/p/\${(d)M}/\${(b)yyyy-MM-dd:HH-mm}/1477.m3u8", CatchupUrl.template("http://h:8080/live/u/p/1477.m3u8", "xc", ""))
    }

    @Test fun append_disabled() {
        assertEquals("http://h/ch.m3u8?s=\${start}", CatchupUrl.template("http://h/ch.m3u8", "append", "?s=\${start}"))
        assertNull(CatchupUrl.template("http://h/ch.m3u8", "disabled", ""))
    }

    @Test fun prepare() {
        val url = CatchupUrl.prepare("http://h/archive-\${start}-\${duration}.m3u8?d=\${(d)M}&b=\${(bu)yyyy-MM-dd:HH-mm}", 1700000000, 3600, 1700010000)
        assertEquals("http://h/archive-1700000000-3600.m3u8?d=60&b=2023-11-14%3A22-13", url)
        val msk = CatchupUrl.prepare("\${(b)HH:mm}|\${(b+1)HH}|\${offset}|\${timestamp}", 1700000000, 3600, 1700010000, TimeZone.getTimeZone("Europe/Moscow"))
        assertEquals("01%3A13|02|10000|1700010000", msk)
    }
}
