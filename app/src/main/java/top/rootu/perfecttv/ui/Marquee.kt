package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import top.rootu.perfecttv.core.Prefs

/**
 * Расчёт бегущей строки (чистая логика, без Android). Цикл одной строки, не влезающей в ширину:
 * пауза [PAUSE_MS] → прокрутка до конца со скоростью [SPEED_EM] → пауза [PAUSE_MS] на конце →
 * мгновенный возврат к началу и снова пауза. Смещение — на сколько пикселей текст сдвинут влево.
 */
object MarqueeMath {
    /** Пауза в начале и на конце, мс */
    const val PAUSE_MS = 1500L
    /** Скорость прокрутки, em в секунду (1 em = 10 dp) */
    const val SPEED_EM = 7f
    /** Шаг кадров при прокрутке, мс (≈30 к/с: гид перерисовывается целиком, приставки слабые) */
    const val FRAME_MS = 33L

    /** Длительность прокрутки, мс; [speed] — пикселей в секунду */
    fun scrollMs(overflow: Float, speed: Float): Long = if (overflow <= 0f || speed <= 0f) 0L else Math.round(overflow / speed * 1000f).toLong()

    fun cycleMs(overflow: Float, speed: Float): Long = PAUSE_MS + scrollMs(overflow, speed) + PAUSE_MS

    /** Смещение через [elapsed] мс после показа строки; [overflow] — ширина текста минус ширина места */
    fun offset(overflow: Float, speed: Float, elapsed: Long): Float {
        if (overflow <= 0f) return 0f
        val t = elapsed % cycleMs(overflow, speed)
        val run = t - PAUSE_MS
        if (run <= 0) return 0f
        return minOf(overflow, run / 1000f * speed)
    }

    /** Через сколько мс нужен следующий кадр (null — не нужен: строка влезает) */
    fun nextFrameMs(overflow: Float, speed: Float, elapsed: Long): Long? {
        if (overflow <= 0f) return null
        val scroll = scrollMs(overflow, speed)
        val t = elapsed % cycleMs(overflow, speed)
        return when {
            t < PAUSE_MS -> PAUSE_MS - t                  // стоим в начале — до старта прокрутки
            t < PAUSE_MS + scroll -> FRAME_MS             // едем
            else -> cycleMs(overflow, speed) - t          // стоим на конце — до возврата
        }
    }
}

/**
 * Бегущие строки на Canvas: [draw] рисует выделенную строку списка. Один таймер на View — кадр
 * запрашивается только пока что-то едет или ждёт смены фазы; нет вызовов [draw] за кадр (фокус ушёл,
 * экран закрыт) — таймер сам не продлевается. Строки без выделения рисуются как обычно
 * ([U.drawText]). Вызывать [end] в конце onDraw.
 *
 * Слот — место строки на экране (канал, передача, ячейка…): у каждого свой отсчёт; смена ключа
 * строки или потеря выделения в слоте — начало цикла заново (пауза).
 */
class Marquee(private val view: View) {
    private class Slot {
        var key = NONE
        var t0 = 0L
        var used = false
        var overflow = 0f
        var elapsed = 0L
    }

    private val slots = Array(SLOTS) { Slot() }
    private var scheduled = false
    private val frame = Runnable { scheduled = false; view.invalidate() }

    /**
     * Строка [s] в место [x]..[x]+[width] с вертикальным центром [cy]. [key] — что за строка
     * (передача, канал): при смене отсчёт начинается заново. [active] — выделена ли строка;
     * иначе (или настройка выключена) — обрезка многоточием.
     */
    fun draw(c: Canvas, slot: Int, key: Long, active: Boolean, s: CharSequence, x: Float, cy: Float, width: Float, p: TextPaint) {
        if (width <= 0) return
        val tw = p.measureText(s, 0, s.length)
        if (!active || !Prefs.marquee || tw <= width) return U.drawText(c, s, x, cy, width, p)
        val st = slots[slot]
        val now = SystemClock.uptimeMillis()
        if (st.key != key) { st.key = key; st.t0 = now }
        st.used = true
        st.overflow = tw - width
        st.elapsed = now - st.t0
        val off = MarqueeMath.offset(st.overflow, U.px(MarqueeMath.SPEED_EM), st.elapsed)
        val h = p.fontSpacing
        c.save()
        c.clipRect(x, cy - h, x + width, cy + h)
        c.drawText(s, 0, s.length, x - off, cy - (p.descent() + p.ascent()) / 2, p)
        c.restore()
    }

    /** Конец onDraw: слоты без выделения сбрасываются, запрашивается следующий кадр */
    fun end() {
        var wait = -1L
        for (st in slots) {
            if (!st.used) { st.key = NONE; continue }
            st.used = false
            val n = MarqueeMath.nextFrameMs(st.overflow, U.px(MarqueeMath.SPEED_EM), st.elapsed) ?: continue
            if (wait < 0 || n < wait) wait = n
        }
        if (wait >= 0 && !scheduled) {
            scheduled = true
            view.postDelayed(frame, wait)
        }
    }

    /** Остановить (экран закрыт) */
    fun stop() {
        view.removeCallbacks(frame)
        scheduled = false
        slots.forEach { it.key = NONE; it.used = false }
    }

    companion object {
        const val SLOTS = 4
        const val NONE = Long.MIN_VALUE

        /** Ключ строки: канал + начало передачи (без выделений на кадр) */
        fun key(channelKey: String, start: Long = 0L): Long = (channelKey.hashCode().toLong() shl 32) xor start
    }
}

/**
 * Однострочный TextView, который при [active] прокручивает название, не влезающее в ширину
 * (циклом из [MarqueeMath]); иначе — обычная обрезка многоточием. Текст выровнен по левому краю.
 */
class MarqueeTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private var t0 = 0L
    private var shownText: CharSequence? = null
    private var running = false
    private val frame = Runnable { invalidate() }

    override fun onDraw(c: Canvas) {
        val lay = layout
        val w = width - paddingLeft - paddingRight
        val s = text
        val tw = if (w > 0 && s.isNotEmpty()) paint.measureText(s, 0, s.length) else 0f
        if (!Prefs.marquee || !isShown || tw <= w) {
            running = false
            return super.onDraw(c)
        }
        val now = SystemClock.uptimeMillis()
        if (!running || shownText != s) { running = true; shownText = s; t0 = now }
        val elapsed = now - t0
        val overflow = tw - w
        val speed = U.px(MarqueeMath.SPEED_EM)
        val off = MarqueeMath.offset(overflow, speed, elapsed)
        paint.color = currentTextColor
        c.save()
        c.clipRect(paddingLeft.toFloat(), 0f, (width - paddingRight).toFloat(), height.toFloat())
        c.drawText(s, 0, s.length, paddingLeft - off, extendedPaddingTop + lay.getLineBaseline(0).toFloat(), paint)
        c.restore()
        MarqueeMath.nextFrameMs(overflow, speed, elapsed)?.let { removeCallbacks(frame); postDelayed(frame, it) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }
}
