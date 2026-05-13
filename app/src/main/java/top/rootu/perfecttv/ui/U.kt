package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import top.rootu.perfecttv.core.Clock
import java.util.Calendar

/**
 * Палитра, размеры и общие приёмы рисования (аналог styles/_vars.scss и _mixins.scss плагина).
 * Размеры — в «em» плагина: 1 em = высота экрана / 54 (13,3 px на 720p, 20 px на 1080p).
 */
object U {
    var em = 13.3f
        private set

    fun init(context: Context) {
        val dm = context.resources.displayMetrics
        em = minOf(dm.heightPixels, dm.widthPixels * 9 / 16) / 54f
    }

    fun px(e: Float) = e * em
    fun pxi(e: Float) = Math.round(e * em)

    /** Белый с прозрачностью */
    fun wa(a: Float) = Color.argb(Math.round(a * 255), 255, 255, 255)

    val WHITE = Color.WHITE
    val BLACK = Color.BLACK
    val FILL = wa(0.05f)            // строка гида
    val FILL_SOFT = wa(0.06f)       // область логотипа
    val FILL_CELL = wa(0.09f)       // ячейка передачи
    val FILL_BTN = wa(0.12f)        // кнопка в окнах
    val FILL_NOW = wa(0.17f)        // текущая передача
    val FILL_MARKED = wa(0.18f)     // отмечено (играет)
    val FILL_ACTIVE = wa(0.22f)     // строка в фокусе вне активной зоны
    val LINE = wa(0.08f)
    val PANEL = Color.argb(235, 26, 27, 28)
    val PANEL_CARD = Color.argb(217, 26, 27, 28)
    val PANEL_CARD_ON = Color.argb(242, 26, 27, 28)
    val POPUP = Color.argb(240, 30, 31, 32)
    val OVERLAY = Color.argb(115, 0, 0, 0)
    val GUIDE_TOP = Color.argb(247, 12, 13, 14)
    val GUIDE_BOTTOM = Color.argb(204, 12, 13, 14)
    val LIVE = Color.parseColor("#e53935")

    const val RADIUS_S = 0.4f
    const val RADIUS = 0.6f
    const val RADIUS_L = 1f

    fun bg(color: Int, radiusEm: Float = RADIUS): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radiusEm)
    }

    fun text(context: Context, sizeEm: Float, alpha: Float = 1f, bold: Boolean = false, lines: Int = 1): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizeEm))
        setTextColor(wa(alpha))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            if (lines == 1) isSingleLine = true
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    fun paint(sizeEm: Float, color: Int = WHITE, bold: Boolean = false): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = px(sizeEm)
        this.color = color
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radiusPx: Float, color: Int) {
        fill.color = color
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radiusPx, radiusPx, fill)
    }

    /** Текст в одну строку по базовой линии с вертикальным центром [cy], обрезка многоточием */
    fun drawText(c: Canvas, s: CharSequence, x: Float, cy: Float, width: Float, p: TextPaint) {
        if (width <= 0) return
        val t = if (p.measureText(s, 0, s.length) > width) TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END) else s
        c.drawText(t, 0, t.length, x, cy - (p.descent() + p.ascent()) / 2, p)
    }

    fun show(v: View, on: Boolean) {
        v.visibility = if (on) View.VISIBLE else View.GONE
    }
}

/** Форматирование времени (порт core/time.js плагина) */
object Fmt {
    private val WEEK = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    private val MONTH = arrayOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")
    private val cal = Calendar.getInstance()

    private fun at(sec: Long): Calendar {
        cal.timeInMillis = sec * 1000
        return cal
    }

    private fun pad2(n: Int) = if (n < 10) "0$n" else n.toString()

    fun hhmm(sec: Long): String {
        val c = at(sec)
        return pad2(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(Calendar.MINUTE))
    }

    /** Номер локального дня */
    fun dayIndex(sec: Long): Long {
        val c = at(sec)
        return Math.floorDiv(sec + c.get(Calendar.ZONE_OFFSET) / 1000 + c.get(Calendar.DST_OFFSET) / 1000, 86400L)
    }

    /** Начало локального дня, unix-секунды */
    fun dayStart(sec: Long): Long {
        val c = at(sec)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }

    /** «Сегодня», «Вчера», «Завтра» или «Пн, 27.10» */
    fun dayLabel(sec: Long): String {
        val diff = dayIndex(sec) - dayIndex(Clock.now())
        return when (diff) {
            0L -> "Сегодня"
            -1L -> "Вчера"
            1L -> "Завтра"
            else -> {
                val c = at(sec)
                WEEK[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + pad2(c.get(Calendar.DAY_OF_MONTH)) + "." + pad2(c.get(Calendar.MONTH) + 1)
            }
        }
    }

    /** Подпись вкладки дня: «ср,\n28 окт.» */
    fun dayTab(sec: Long): String {
        val c = at(sec)
        return WEEK[c.get(Calendar.DAY_OF_WEEK) - 1].lowercase() + ",\n" + c.get(Calendar.DAY_OF_MONTH) + " " + MONTH[c.get(Calendar.MONTH)]
    }

    fun timeRange(start: Long, end: Long) = hhmm(start) + " – " + hhmm(end)

    fun dayTimeRange(start: Long, end: Long) = dayLabel(start) + ", " + timeRange(start, end)

    /** Прогресс 0..1 */
    fun progress(start: Long, stop: Long, t: Long): Float = ((t - start).toFloat() / maxOf(1L, stop - start)).coerceIn(0f, 1f)

    /** Длительность: 1:05:09 / 5:09 */
    fun duration(sec0: Long): String {
        val sec = maxOf(0L, sec0)
        val h = sec / 3600
        val m = (sec % 3600 / 60).toInt()
        val s = (sec % 60).toInt()
        return (if (h > 0) "$h:" + pad2(m) else m.toString()) + ":" + pad2(s)
    }

    /** «1д 2ч 5м» */
    fun until(sec0: Long): String {
        val sec = maxOf(0L, sec0)
        val d = sec / 86400
        val h = sec % 86400 / 3600
        val m = (sec % 3600 + 59) / 60
        val out = ArrayList<String>()
        if (d > 0) out.add("${d}д")
        if (h > 0) out.add("${h}ч")
        if (m > 0 || out.isEmpty()) out.add("${m}м")
        return out.joinToString(" ")
    }
}
