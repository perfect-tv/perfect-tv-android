package top.rootu.perfecttv.player

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CookiesTest {
    /** Ответ [url] с заголовками Set-Cookie — как сохраняет их OkHttp (невалидные для хоста отбрасываются при разборе) */
    private fun Cookies.got(url: String, vararg set: String) {
        val u = url.toHttpUrl()
        saveFromResponse(u, set.mapNotNull { Cookie.parse(u, it) })
    }

    private fun Cookies.sent(url: String) = loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }

    @Test fun sameHost() {
        val c = Cookies()
        c.got("http://a.example/live/index.m3u8", "sid=1; Path=/live/", "tok=2")
        assertEquals("sid=1; tok=2", c.sent("http://a.example/live/seg1.ts"))
        assertEquals("", c.sent("http://a.example/other/x"))   // Path (у tok путь по умолчанию — каталог /live)
        assertEquals("", c.sent("http://b.example/live/seg1.ts"))   // чужой хост
    }

    @Test fun domainSharedBetweenHosts() {
        val c = Cookies()
        c.got("http://cdn.example.com/p.m3u8", "sid=1; Domain=example.com")
        assertEquals("sid=1", c.sent("http://seg.example.com/s.ts"))
        assertEquals("", c.sent("http://example.org/s.ts"))
    }

    @Test fun foreignDomainRejected() {
        val c = Cookies()
        c.got("http://a.example/p.m3u8", "sid=1; Domain=other.tld")
        assertEquals("", c.sent("http://a.example/s.ts"))
        assertEquals("", c.sent("http://other.tld/s.ts"))
    }

    @Test fun secureNotOverHttp() {
        val c = Cookies()
        c.got("https://a.example/p.m3u8", "sid=1; Secure")
        assertEquals("sid=1", c.sent("https://a.example/s.ts"))
        assertEquals("", c.sent("http://a.example/s.ts"))
    }

    @Test fun replaceDeleteClear() {
        val c = Cookies()
        c.got("http://a.example/p", "sid=1", "tok=2")
        c.got("http://a.example/p", "sid=3", "tok=; Max-Age=0")
        assertEquals("sid=3", c.sent("http://a.example/s"))
        c.clear()
        assertEquals("", c.sent("http://a.example/s"))
    }
}
