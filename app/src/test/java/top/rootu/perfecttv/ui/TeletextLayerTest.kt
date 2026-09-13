package top.rootu.perfecttv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Номера страниц телетекста в BCD: шаги пропускают шестнадцатеричные «цифры» и идут по кругу 100…899 */
class TeletextLayerTest {
    @Test fun nextSkipsHexDigits() {
        assertEquals(0x110, TeletextLayer.step(0x109, 1))
        assertEquals(0x200, TeletextLayer.step(0x199, 1))
        assertEquals(0x100, TeletextLayer.step(0x899, 1))
    }

    @Test fun previousSkipsHexDigits() {
        assertEquals(0x109, TeletextLayer.step(0x110, -1))
        assertEquals(0x199, TeletextLayer.step(0x200, -1))
        assertEquals(0x899, TeletextLayer.step(0x100, -1))
    }

    @Test fun tenPages() {
        assertEquals(0x205, TeletextLayer.step(0x195, 10))
        assertEquals(0x195, TeletextLayer.step(0x205, -10))
    }
}
