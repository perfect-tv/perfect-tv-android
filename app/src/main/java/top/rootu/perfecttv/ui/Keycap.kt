package top.rootu.perfecttv.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan

/**
 * Надпись на клавише в строке подсказок. Стрелки ← → ↑ ↓ в шрифте стоят у нижнего края строки
 * и в рамке клавиши «прибиты ко дну», поэтому рисуются линиями — строго по вертикальному центру.
 * Остальной текст (OK, «Назад») — обычными буквами.
 */
object Keycap {
    private const val ARROWS = "←→↑↓"

    /** Ширина стрелки относительно размера текста */
    private const val W = 0.95f

    fun hasArrows(s: CharSequence) = s.any { it in ARROWS }

    /** Стрелка [ch] с центром в ([cx], [cy]); цвет и размер — из [p] */
    private fun arrow(c: Canvas, ch: Char, cx: Float, cy: Float, p: Paint) {
        val h = p.textSize * W / 2          // половина длины
        val a = h * 0.55f                   // «крылья» наконечника
        val old = Triple(p.style, p.strokeWidth, p.strokeCap)
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, p.textSize * 0.1f)
        p.strokeCap = Paint.Cap.ROUND
        when (ch) {
            '←' -> { c.drawLine(cx - h, cy, cx + h, cy, p); c.drawLine(cx - h, cy, cx - h + a, cy - a, p); c.drawLine(cx - h, cy, cx - h + a, cy + a, p) }
            '→' -> { c.drawLine(cx - h, cy, cx + h, cy, p); c.drawLine(cx + h, cy, cx + h - a, cy - a, p); c.drawLine(cx + h, cy, cx + h - a, cy + a, p) }
            '↑' -> { c.drawLine(cx, cy - h, cx, cy + h, p); c.drawLine(cx, cy - h, cx - a, cy - h + a, p); c.drawLine(cx, cy - h, cx + a, cy - h + a, p) }
            '↓' -> { c.drawLine(cx, cy - h, cx, cy + h, p); c.drawLine(cx, cy + h, cx - a, cy + h - a, p); c.drawLine(cx, cy + h, cx + a, cy + h - a, p) }
        }
        p.style = old.first; p.strokeWidth = old.second; p.strokeCap = old.third
    }

    private fun advance(ch: Char, p: Paint) = if (ch in ARROWS) p.textSize * (W + 0.15f) else p.measureText(ch.toString())

    fun width(s: CharSequence, p: Paint): Float = s.sumOf { advance(it, p).toDouble() }.toFloat()

    /** Надпись слева от [x] по вертикальному центру [cy] (Canvas) */
    fun draw(c: Canvas, s: CharSequence, x: Float, cy: Float, p: Paint) {
        var px = x
        val base = cy - (p.descent() + p.ascent()) / 2
        for (ch in s) {
            val w = advance(ch, p)
            if (ch in ARROWS) arrow(c, ch, px + w / 2, cy, p) else c.drawText(ch.toString(), px, base, p)
            px += w
        }
    }

    /** Текст для TextView: стрелки — span-ы, нарисованные по центру строки */
    fun text(s: String): CharSequence {
        if (!hasArrows(s)) return s
        val sb = SpannableStringBuilder(s)
        for (i in s.indices) if (s[i] in ARROWS) sb.setSpan(ArrowSpan(s[i]), i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private class ArrowSpan(private val ch: Char) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            // высота строки — как у текста: у надписи из одних стрелок иначе она схлопывается в ноль
            if (fm != null) {
                val m = paint.fontMetricsInt
                fm.ascent = m.ascent; fm.descent = m.descent; fm.top = m.top; fm.bottom = m.bottom
            }
            return Math.round(advance(ch, paint))
        }

        override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val cy = y + (paint.ascent() + paint.descent()) / 2
            arrow(c, ch, x + advance(ch, paint) / 2, cy, paint)
        }
    }
}
