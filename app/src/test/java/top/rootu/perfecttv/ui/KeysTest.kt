package top.rootu.perfecttv.ui

import android.view.KeyEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

/** Разбор клавиш с поддельными часами и таймерами (без Android) */
class KeysTest {

    private class Fake : Keys.Scheduler {
        var now = 0L
        private val tasks = ArrayList<Pair<Long, Runnable>>()
        override fun postDelayed(r: Runnable, ms: Long) { tasks.add(now + ms to r) }
        override fun removeCallbacks(r: Runnable) { tasks.removeAll { it.second === r } }

        /** Прокрутить время, выполняя созревшие таймеры по порядку */
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second.run()
            }
            now = end
        }
    }

    private class Rec(private val longs: Set<String> = emptySet(), override val holdRepeat: Boolean = true) : Layer {
        override val view: View? = null
        val log = ArrayList<String>()
        override fun hasLong(name: String) = name in longs
        override fun key(name: String, long: Boolean, digit: Int) { log.add(if (name == "digit") "d$digit" else if (long) "long:$name" else name) }
        override fun keyUp(name: String) { log.add("up:$name") }
        override fun enter() { log.add("enter") }
        override fun long() { log.add("longok") }
        override fun back() { log.add("back") }
        override fun media(name: String) { log.add("m:$name") }
    }

    private fun keys(layer: Layer, f: Fake) = Keys({ layer }, f, { f.now })

    @Test fun short_press_without_long_fires_on_down() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        assertEquals(listOf("left"), l.log)
        k.onKeyUp(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf("left", "up:left"), l.log)
    }

    @Test fun long_action_short_on_up_long_after_500ms() {
        val f = Fake(); val l = Rec(setOf("down")); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        f.advance(200)
        k.onKeyUp(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("down"), l.log)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        f.advance(500)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 1)   // автоповтор после долгого — игнорируется
        k.onKeyUp(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("down", "long:down"), l.log)
    }

    @Test fun ok_short_and_long() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, 0); f.advance(100); k.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER)
        k.onKeyDown(KeyEvent.KEYCODE_ENTER, 0); f.advance(800); k.onKeyUp(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("enter", "longok"), l.log)
    }

    @Test fun hold_up_repeats_with_own_timer_and_stops_on_up() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, 0)
        // системный автоповтор не добавляет шагов, но продлевает удержание
        for (i in 1..20) { f.advance(50); k.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, i) }
        val steps = l.log.count { it == "up" }
        // 1 сразу + через 350 мс шаги с интервалом ~90 мс → за 1000 мс ~8
        assert(steps in 6..10) { "шагов $steps" }
        k.onKeyUp(KeyEvent.KEYCODE_DPAD_UP)
        val after = l.log.count { it == "up" }
        f.advance(1000)
        assertEquals(after, l.log.count { it == "up" })
    }

    @Test fun hold_stops_when_keyup_lost() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        f.advance(3000)   // ни повторов, ни keyup — дольше GAP: удержание кончилось
        val n = l.log.count { it == "down" }
        f.advance(3000)
        assertEquals(n, l.log.count { it == "down" })
    }

    @Test fun no_repeat_layer_takes_each_press_once() {
        val f = Fake(); val l = Rec(holdRepeat = false); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, 0)
        for (i in 1..10) { f.advance(50); k.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, i) }
        f.advance(2000)
        assertEquals(listOf("up"), l.log)
    }

    @Test fun system_repeat_of_left_passes_through() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 1)
        k.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 2)
        assertEquals(listOf("right", "right", "right"), l.log)
    }

    @Test fun digits_back_media() {
        val f = Fake(); val l = Rec(); val k = keys(l, f)
        k.onKeyDown(KeyEvent.KEYCODE_7, 0)
        k.onKeyDown(KeyEvent.KEYCODE_NUMPAD_3, 0)
        k.onKeyDown(KeyEvent.KEYCODE_BACK, 0)
        k.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        k.onKeyDown(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 0)
        k.onKeyDown(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 1)
        k.onKeyDown(KeyEvent.KEYCODE_CHANNEL_UP, 0)
        assertEquals(listOf("d7", "d3", "back", "m:playpause", "m:forward", "m:forward", "pgup"), l.log)
    }
}
