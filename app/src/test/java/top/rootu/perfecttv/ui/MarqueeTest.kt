package top.rootu.perfecttv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarqueeTest {
    private val speed = 100f // px/с: overflow 200 px едет 2 с

    @Test fun startsWithPause() {
        assertEquals(0f, MarqueeMath.offset(200f, speed, 0), 0f)
        assertEquals(0f, MarqueeMath.offset(200f, speed, MarqueeMath.PAUSE_MS), 0f)
    }

    @Test fun scrollsAtSpeed() {
        assertEquals(50f, MarqueeMath.offset(200f, speed, MarqueeMath.PAUSE_MS + 500), 0.01f)
        assertEquals(200f, MarqueeMath.offset(200f, speed, MarqueeMath.PAUSE_MS + 2000), 0.01f)
    }

    @Test fun pausesAtEndThenRestarts() {
        val end = MarqueeMath.PAUSE_MS + 2000
        assertEquals(200f, MarqueeMath.offset(200f, speed, end + MarqueeMath.PAUSE_MS - 1), 0.01f)
        // цикл: после паузы на конце — снова начало и пауза
        assertEquals(0f, MarqueeMath.offset(200f, speed, MarqueeMath.cycleMs(200f, speed)), 0f)
        assertEquals(0f, MarqueeMath.offset(200f, speed, MarqueeMath.cycleMs(200f, speed) + MarqueeMath.PAUSE_MS - 1), 0f)
        assertEquals(50f, MarqueeMath.offset(200f, speed, MarqueeMath.cycleMs(200f, speed) + MarqueeMath.PAUSE_MS + 500), 0.01f)
    }

    @Test fun fitsNoScroll() {
        assertEquals(0f, MarqueeMath.offset(0f, speed, 5000), 0f)
        assertEquals(0f, MarqueeMath.offset(-10f, speed, 5000), 0f)
        assertNull(MarqueeMath.nextFrameMs(0f, speed, 0))
    }

    @Test fun nextFrame() {
        // в паузе — ждём до старта; едем — кадр через шаг; на конце — до возврата
        assertEquals(MarqueeMath.PAUSE_MS - 100, MarqueeMath.nextFrameMs(200f, speed, 100))
        assertEquals(MarqueeMath.FRAME_MS, MarqueeMath.nextFrameMs(200f, speed, MarqueeMath.PAUSE_MS + 10))
        assertEquals(MarqueeMath.PAUSE_MS - 10, MarqueeMath.nextFrameMs(200f, speed, MarqueeMath.PAUSE_MS + 2000 + 10))
    }

    @Test fun keyDiffers() {
        assert(Marquee.key("a", 1) != Marquee.key("a", 2))
        assert(Marquee.key("a", 1) != Marquee.key("b", 1))
    }
}
