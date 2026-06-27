package top.rootu.perfecttv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test
    fun monogram() {
        assertEquals("НТВ", Names.monogram("НТВ"))
        assertEquals("КА", Names.monogram("Карусель"))
        assertEquals("ПК", Names.monogram("Первый канал"))
        assertEquals("МТ", Names.monogram("Матч! ТВ"))
        assertEquals("Р1", Names.monogram("Россия 1"))
        assertEquals("Р24", Names.monogram("Россия 24"))
        assertEquals("ПКH", Names.monogram("Первый канал HD"))
        assertEquals("5К", Names.monogram("5 канал"))
        assertEquals("М20", Names.monogram("Музыка 2000"))
        assertEquals("TV", Names.monogram(" tv "))
        assertEquals("?", Names.monogram("!!!"))
        assertEquals("?", Names.monogram(""))
    }
}
