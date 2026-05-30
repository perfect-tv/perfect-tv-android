package top.rootu.perfecttv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {
    private val m = EpgMatcher().apply {
        addChannel(EpgChannelRaw("1tv", listOf("Первый канал", "Channel One"), ""))
        addChannel(EpgChannelRaw("NTV", listOf("НТВ"), ""))
        addChannel(EpgChannelRaw("rtr", listOf("Россия 1"), ""))
        addChannel(EpgChannelRaw("zvezda", listOf("Телеканал Звезда"), ""))
        addChannel(EpgChannelRaw("karusel2", listOf("Карусель (+2)"), ""))
    }

    @Test fun by_tvg_id_exact_and_case() {
        assertEquals("1tv", m.match("1tv", "", "что угодно"))
        assertEquals("NTV", m.match("ntv", "", ""))
    }

    @Test fun by_tvg_name_then_name() {
        assertEquals("rtr", m.match("unknown", "Россия 1", "Другое"))
        assertEquals("rtr", m.match("", "", "Россия-1"))
        assertEquals("1tv", m.match("", "", "Первый"))          // «канал» в конце отбрасывается
    }

    @Test fun translit_and_prefixes() {
        assertEquals("NTV", m.match("", "", "HTB"))              // похожие буквы
        assertEquals("zvezda", m.match("", "", "Звезда"))        // «Телеканал» в начале
        assertEquals("karusel2", m.match("", "", "Карусель +2"))
    }

    @Test fun no_match() {
        assertNull(m.match("", "", "Неизвестный канал"))
    }
}
