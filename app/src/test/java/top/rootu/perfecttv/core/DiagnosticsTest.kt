package top.rootu.perfecttv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticsTest {
    @Test fun addressesAreShortenedToHost() {
        assertEquals("epg https://iptvx.one/… ok", Diagnostics.redact("epg https://iptvx.one/EPG ok"))
        assertEquals("open http://host.example:8080/…", Diagnostics.redact("open http://user:pass@host.example:8080/live/token123/1.m3u8?x=1"))
        assertEquals("plain https://only.host", Diagnostics.redact("plain https://only.host"))
        val r = Diagnostics.redact("GET http://a.b/c/secret_login/secret_pass/1.ts failed; udp://239.1.1.1:1234/x")
        assertFalse(r.contains("secret"))
        assertEquals("GET http://a.b/… failed; udp://239.1.1.1:1234/…", r)
    }
}
