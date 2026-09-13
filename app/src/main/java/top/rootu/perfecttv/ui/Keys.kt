package top.rootu.perfecttv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Разбор нажатий пульта («Долгие нажатия»).
 *
 * Если у слоя для направления есть долгое действие ([Layer.hasLong]), короткое срабатывает
 * на keyup, а долгое — через [LONG_PRESS] удержания. Если нет — сразу на keydown;
 * удержание ↑/↓ листает своим таймером с ускорением ([Hold]), автоповтор системы
 * для них игнорируется. Автоповтор ←/→ передаётся слою как есть (перемотка считает его сама).
 * OK: короткое — enter() на keyup, долгое (800 мс) — long().
 */
class Keys(
    private val handler: () -> Layer?,
    private val main: Scheduler = MainScheduler(),
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) {

    /** Таймеры (в тестах — поддельные) */
    interface Scheduler {
        fun postDelayed(r: Runnable, ms: Long)
        fun removeCallbacks(r: Runnable)
    }

    class MainScheduler : Scheduler {
        private val h = Handler(Looper.getMainLooper())
        override fun postDelayed(r: Runnable, ms: Long) { h.postDelayed(r, ms) }
        override fun removeCallbacks(r: Runnable) = h.removeCallbacks(r)
    }

    private class Held(val name: String, val layer: Layer) {
        var fired = false
    }

    private var held: Held? = null
    private var heldTimer: Runnable? = null
    private var repeat: Repeat? = null

    private class Repeat(val name: String, val layer: Layer, val since: Long, var last: Long) {
        var timer: Runnable? = null
    }

    fun onKeyDown(code: Int, e: KeyEvent): Boolean = onKeyDown(code, e.repeatCount)

    /**
     * @param repeatCount 0 — нажатие, больше — автоповтор системы
     * @return true — клавиша наша
     */
    fun onKeyDown(code: Int, repeatCount: Int): Boolean {
        digit(code)?.let { d ->
            if (repeatCount == 0) handler()?.key("digit", false, d)
            return true
        }
        if (isOk(code)) {
            if (repeatCount > 0) return true
            val layer = handler() ?: return true
            startHeld("ok", layer, OK_LONG_PRESS)
            return true
        }
        if (isBack(code)) {
            if (repeatCount == 0) handler()?.back()
            return true
        }
        media(code)?.let { m ->
            // автоповтор ⏪/⏩ — удержание перемотки
            if (repeatCount == 0 || m == "rewind" || m == "forward") handler()?.media(m)
            return true
        }
        val name = NAMES[code] ?: return false
        val layer = handler() ?: return true
        val h = held
        if (h != null && h.name == name) return true
        if (layer.hasLong(name)) {
            if (repeatCount > 0) return true
            startHeld(name, layer, LONG_PRESS)
            return true
        }
        if (!layer.holdRepeat) {
            if (repeatCount == 0) { release(); layer.key(name, false, 0) }
            return true
        }
        val r = repeat
        if (r != null && r.name == name) {
            r.last = clock()
            return true
        }
        release()
        layer.key(name, false, 0)
        if ((name == "up" || name == "down") && repeatCount == 0) startRepeat(name, layer)
        return true
    }

    fun onKeyUp(code: Int): Boolean {
        val name = if (isOk(code)) "ok" else NAMES[code]
        if (name == null) return digit(code) != null || isBack(code) || media(code) != null
        val r = repeat
        if (r != null && r.name == name) stopRepeat()
        val h = held
        if (h == null || h.name != name) {
            if (name != "ok") handler()?.keyUp(name)
            return true
        }
        held = null
        heldTimer?.let { main.removeCallbacks(it) }
        if (!h.fired && handler() === h.layer) {
            if (name == "ok") h.layer.enter() else h.layer.key(name, false, 0)
        }
        return true
    }

    private fun startHeld(name: String, layer: Layer, delay: Long) {
        release()
        val h = Held(name, layer)
        held = h
        val t = Runnable {
            if (held !== h) return@Runnable
            h.fired = true
            if (handler() !== layer) return@Runnable
            if (name == "ok") layer.long() else layer.key(name, true, 0)
        }
        heldTimer = t
        main.postDelayed(t, delay)
    }

    private fun startRepeat(name: String, layer: Layer) {
        val rep = Repeat(name, layer, clock() + Hold.DELAY, clock())
        repeat = rep
        val tick = object : Runnable {
            override fun run() {
                if (repeat !== rep) return
                val t = clock()
                if (t - rep.last > Hold.GAP || handler() !== layer) return stopRepeat()
                layer.key(name, false, 0)
                val k = minOf(1f, (t - rep.since).toFloat() / Hold.RAMP)
                val spent = clock() - t
                main.postDelayed(this, maxOf(16L, (Hold.START - (Hold.START - Hold.MIN) * k).toLong() - spent))
            }
        }
        rep.timer = tick
        main.postDelayed(tick, Hold.DELAY)
    }

    private fun stopRepeat() {
        repeat?.timer?.let { main.removeCallbacks(it) }
        repeat = null
    }

    /** Сбросить удержания (смена экрана) */
    fun release() {
        heldTimer?.let { main.removeCallbacks(it) }
        held = null
        stopRepeat()
    }

    /** Удержание ↑/↓ в списках, мс: через DELAY, интервал от START до MIN за RAMP; GAP — keyup потерялся */
    object Hold {
        const val DELAY = 350L
        const val START = 90f
        const val MIN = 35f
        const val RAMP = 1500f
        const val GAP = 700L
    }

    companion object {
        const val LONG_PRESS = 500L
        const val OK_LONG_PRESS = 800L

        private val NAMES = mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to "left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "right",
            KeyEvent.KEYCODE_DPAD_UP to "up",
            KeyEvent.KEYCODE_DPAD_DOWN to "down",
            KeyEvent.KEYCODE_PAGE_UP to "pgup",
            KeyEvent.KEYCODE_CHANNEL_UP to "pgup",
            KeyEvent.KEYCODE_PAGE_DOWN to "pgdown",
            KeyEvent.KEYCODE_CHANNEL_DOWN to "pgdown"
        )

        fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

        fun isBack(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE

        fun digit(code: Int): Int? = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }

        /** Медиа- и служебные клавиши → имя действия */
        fun media(code: Int): String? = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "playpause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            KeyEvent.KEYCODE_GUIDE -> "guide"
            KeyEvent.KEYCODE_INFO -> "info"
            KeyEvent.KEYCODE_MENU -> "menu"
            KeyEvent.KEYCODE_LAST_CHANNEL -> "history"
            KeyEvent.KEYCODE_SEARCH -> "search"
            KeyEvent.KEYCODE_TV_TELETEXT -> "teletext"
            KeyEvent.KEYCODE_PROG_RED -> "red"
            KeyEvent.KEYCODE_PROG_GREEN -> "green"
            KeyEvent.KEYCODE_PROG_YELLOW -> "yellow"
            KeyEvent.KEYCODE_PROG_BLUE -> "blue"
            else -> null
        }
    }
}
