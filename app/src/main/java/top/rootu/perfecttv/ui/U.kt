package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import top.rootu.perfecttv.core.Clock
import java.util.Calendar

/**
 * Палитра, размеры и общие приёмы рисования (аналог styles/_vars.scss и _mixins.scss плагина).
 * Размеры — в «em» плагина: 1 em = высота экрана / 54 (13,3 px на 720p, 20 px на 1080p).
 */
object U {
    var em = 13.3f
        private set

    /** Сенсорное устройство, но не ТВ: показываем кнопки в инфопанели и ✕ в гиде (как плагин) */
    var touch = false
        private set

    fun init(context: Context) {
        val dm = context.resources.displayMetrics
        em = minOf(dm.heightPixels, dm.widthPixels * 9 / 16) / 54f
        val tvMode = (context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        touch = !tvMode && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun px(e: Float) = e * em
    fun pxi(e: Float) = Math.round(e * em)

    /** Белый с прозрачностью */
    fun wa(a: Float) = Color.argb(Math.round(a * 255), 255, 255, 255)

    // палитра — из res/values/colors.xml (там же — для XML-разметки)
    private fun c(id: Int) = top.rootu.perfecttv.core.Lang.res.getColor(id, null)
    val WHITE = Color.WHITE
    val BLACK = Color.BLACK
    val FILL = c(R.color.fill)                  // строка гида
    val FILL_SOFT = c(R.color.fill_soft)        // область логотипа
    val FILL_CELL = c(R.color.fill_cell)        // ячейка передачи
    val FILL_BTN = c(R.color.fill_btn)          // кнопка в окнах
    val FILL_NOW = c(R.color.fill_now)          // текущая передача
    val FILL_MARKED = c(R.color.fill_marked)    // отмечено (играет)
    val FILL_ACTIVE = c(R.color.fill_active)    // строка в фокусе вне активной зоны
    val LINE = c(R.color.line)
    val PANEL = c(R.color.panel)
    val PANEL_CARD = c(R.color.panel_card)
    val PANEL_CARD_ON = c(R.color.panel_card_on)
    val POPUP = c(R.color.popup)
    val OVERLAY = c(R.color.overlay)
    val GUIDE_TOP = c(R.color.guide_top)
    val GUIDE_BOTTOM = c(R.color.guide_bottom)
    val LIVE = c(R.color.live)

    const val RADIUS_S = 0.4f
    const val RADIUS = 0.6f
    const val RADIUS_L = 1f

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
    // дни недели и месяцы — на текущем языке (после смены языка — новые)
    private val WEEK get() = top.rootu.perfecttv.core.Lang.res.getStringArray(R.array.week_short)
    private val MONTH get() = top.rootu.perfecttv.core.Lang.res.getStringArray(R.array.month_short)
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
            0L -> str(R.string.today)
            -1L -> str(R.string.yesterday)
            1L -> str(R.string.tomorrow)
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
        if (d > 0) out.add(str(R.string.x_d_2, d))
        if (h > 0) out.add(str(R.string.x_h_2, h))
        if (m > 0 || out.isEmpty()) out.add(str(R.string.x_m, m))
        return out.joinToString(" ")
    }
}

/**
 * Затемнение краёв прокручиваемого списка: содержимое у верхнего/нижнего края плавно гаснет,
 * если в ту сторону есть ещё строки. Рисуется поверх содержимого внутри saveLayer той же
 * области (DST_OUT вычитает альфу), поэтому работает на полупрозрачном окне над видео.
 */
object Fade {
    private val paint = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val matrix = android.graphics.Matrix()
    private var shader: android.graphics.LinearGradient? = null
    private var shaderLen = 0f

    /** Длина затемнения, px */
    val length get() = U.px(3f)

    private fun shader(len: Float): android.graphics.LinearGradient {
        if (shader == null || shaderLen != len) {
            shaderLen = len
            shader = android.graphics.LinearGradient(0f, 0f, 0f, len, Color.BLACK, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        }
        return shader!!
    }

    /** Начать область: вернуть save-счётчик для [end] */
    fun begin(c: Canvas, l: Float, t: Float, r: Float, b: Float): Int = c.saveLayer(l, t, r, b, null)

    /**
     * Затемнить края и закрыть область.
     * @param top, bottom — сила 0..1 (0 — у края списка ничего нет, не затемнять)
     */
    fun end(c: Canvas, save: Int, l: Float, t: Float, r: Float, b: Float, top: Float, bottom: Float) {
        val len = minOf(length, (b - t) / 3)
        val s = shader(len)
        if (top > 0f) {
            matrix.setTranslate(0f, t)
            s.setLocalMatrix(matrix)
            paint.shader = s
            paint.alpha = Math.round(255 * top.coerceAtMost(1f))
            c.drawRect(l, t, r, t + len, paint)
        }
        if (bottom > 0f) {
            // тот же градиент, отражённый: чёрный у нижнего края
            matrix.setScale(1f, -1f)
            matrix.postTranslate(0f, b)
            s.setLocalMatrix(matrix)
            paint.shader = s
            paint.alpha = Math.round(255 * bottom.coerceAtMost(1f))
            c.drawRect(l, b - len, r, b, paint)
        }
        c.restoreToCount(save)
    }

    /** Встроенное затухание краёв у ScrollView (показывается, только если есть куда прокрутить) */
    fun scroll(v: android.view.View) {
        v.isVerticalFadingEdgeEnabled = true
        v.setFadingEdgeLength(Math.round(length))
    }
}
