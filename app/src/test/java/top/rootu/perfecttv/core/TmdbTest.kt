package top.rootu.perfecttv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика поиска TMDB */
class TmdbTest {
    private fun hit(title: String, date: String = "", type: String = "movie", original: String = "", backdrop: String = "") =
        Tmdb.Hit(title, original, date, backdrop, "", "", 0.0, type)

    @Test fun query_cleanup() {
        assertTrue(Tmdb.searchQuery("т/с Белые волки 2. 9 с.").isNotEmpty())
        assertEquals("Шерлок", Tmdb.searchQuery("Шерлок (2010) 16+"))
        assertEquals("Брат 2", Tmdb.searchQuery("«Брат 2». Фильм"))
    }

    @Test fun year() {
        assertEquals(2010, Tmdb.guessYear("Шерлок (2010)", ""))
        assertEquals(1997, Tmdb.guessYear("Брат", "Россия, 1997 г. Режиссёр..."))
        assertEquals(0, Tmdb.guessYear("Новости", "Главные события дня"))
    }

    @Test fun similarity() {
        assertEquals(1.0, Tmdb.similarity("Ёлки!", "елки"), 1e-9)
        assertTrue(Tmdb.similarity("Верить и ждать", "Верить и ждать 2") > 0.8)
        assertTrue(Tmdb.similarity("Новости", "Новый Человек-паук") < 0.72)
    }

    @Test fun pick_by_title_and_year() {
        val results = listOf(hit("Брат 2", "2000-05-11", backdrop = "/a.jpg"), hit("Брат", "1997-12-12", backdrop = "/b.jpg"), hit("Брат", type = "person"))
        assertEquals("1997-12-12", Tmdb.pick(results, "Брат", 1997)?.date)
        assertEquals("Брат 2", Tmdb.pick(results, "Брат 2", 2000)?.title)
        assertNull(Tmdb.pick(listOf(hit("Новый Человек-паук", type = "tv")), "Новости", 0))
    }

    @Test fun pick_without_year_only_unique_exact() {
        val today = hit("Сегодня", "1952-01-14", "tv", "Today")
        assertNull(Tmdb.pick(listOf(today, hit("Сегодня вечером")), "Сегодня", 0))
        assertEquals(today, Tmdb.pick(listOf(today), "Сегодня", 0))
        assertNull(Tmdb.pick(listOf(hit("Сегодня вечером")), "Сегодня", 0))
    }
}
