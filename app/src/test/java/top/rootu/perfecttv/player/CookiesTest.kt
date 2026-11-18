package top.rootu.perfecttv.player

import org.junit.Assert.*
import org.junit.Test

class CookiesTest {
    @Test fun parse() {
        assertEquals("sid" to "abc", Cookies.parse("sid=abc; Path=/; HttpOnly"))
        assertEquals("t" to "x y", Cookies.parse("t=\"x y\"; Domain=other.example; Secure"))
        assertEquals("sid" to null, Cookies.parse("sid=; Max-Age=0"))
        assertEquals("a" to "1", Cookies.parse("a=1; Max-Age=3600"))
        assertNull(Cookies.parse("=oops"))
        assertNull(Cookies.parse("novalue"))
    }

    @Test fun storeAndHeader() {
        val c = Cookies()
        assertNull(c.header("a.example"))
        c.store("a.example", listOf("sid=1; Path=/; Domain=cdn.other; Secure", "tok=2"))
        assertEquals("sid=1; tok=2", c.header("a.example"))
        assertNull(c.header("b.example"))
        c.store("a.example", listOf("sid=3", "tok=; Max-Age=0"))
        assertEquals("sid=3", c.header("a.example"))
        c.clear()
        assertNull(c.header("a.example"))
    }
}
