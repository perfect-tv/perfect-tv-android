package top.rootu.perfecttv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.rootu.perfecttv.data.Programme

class CellsTest {
    private fun p(start: Long, stop: Long) = Programme(start, stop, "p$start", "", "", "", "")
    private val h = 1_700_000_000L - 1_700_000_000L % 1800   // начало получаса

    @Test fun gaps_are_half_hour_slots_aligned() {
        val list = listOf(p(h + 3600, h + 5400))
        val c = Cells.cells(list, h + 600, h + 7200)
        // 0:10–0:30, 0:30–1:00 — слоты; 1:00–1:30 — передача; 1:30–2:00 — слот
        assertEquals(listOf(h + 600, h + 1800, h + 3600, h + 5400), c.map { it.start })
        assertEquals(listOf(null, null, "p${h + 3600}", null), c.map { it.program?.title })
        assertEquals(h + 7200, c.last().end)
    }

    @Test fun at_inside_before_after() {
        val list = listOf(p(h + 3600, h + 5400), p(h + 5400, h + 9000))
        assertEquals(h + 3600, Cells.at(list, h + 4000).start)
        val before = Cells.at(list, h + 100)
        assertNull(before.program)
        assertEquals(h, before.start); assertEquals(h + 1800, before.end)
        val after = Cells.at(list, h + 9100)
        assertEquals(h + 9000, after.start); assertEquals(h + 10800, after.end)
        // слот перед передачей не заходит на неё
        val gap = Cells.at(listOf(p(h + 1000, h + 2000)), h + 500)
        assertEquals(h + 1000, gap.end)
    }

    @Test fun empty_epg_is_slots() {
        val c = Cells.cells(emptyList(), h, h + 3600)
        assertEquals(2, c.size)
        assertEquals("", c[0].title)
    }
}
