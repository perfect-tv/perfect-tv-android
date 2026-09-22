package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Круг d-pad над клавиатурой телетекста (как в PIN «Google»): ← / OK / → — кнопки текущего ряда,
 * ▲ и ▼ — смена ряда. Цветные кнопки рисуются цветным кружком ([colors] — цвет или 0).
 */
class TeletextWheel(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var labels = arrayOf("", "", "")
    private var colors = IntArray(3)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pDigit = U.paint(1.7f, U.BLACK, bold = true)
    private val pLong = U.paint(1.2f, U.BLACK, bold = true)
    private val pCenter = U.paint(2f, U.WHITE, bold = true)
    private val pArrow = U.paint(1.1f, U.BLACK)

    init { for (p in listOf(pDigit, pLong, pCenter, pArrow)) { p.textAlign = Paint.Align.CENTER; p.isSubpixelText = true } }

    fun set(labels: Array<String>, colors: IntArray) {
        this.labels = labels
        this.colors = colors
        invalidate()
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint) = c.drawText(s, x, y - (p.descent() + p.ascent()) / 2, p)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = height / 2f - U.px(0.2f)
        ring.color = U.wa(0.85f)
        c.drawCircle(cx, cy, r, ring)
        ring.color = U.POPUP
        c.drawCircle(cx, cy, r * 0.38f, ring)
        val d = r * 0.7f
        val pos = arrayOf(cx - d to cy, cx to cy, cx + d to cy)
        for (i in 0 until 3) {
            val (x, y) = pos[i]
            if (colors[i] != 0) {
                ring.color = colors[i]
                c.drawCircle(x, y, r * (if (i == 1) 0.2f else 0.22f), ring)
            } else {
                val l = labels[i]
                text(c, l, x, y, if (i == 1) pCenter else if (l.length > 1) pLong else pDigit)
            }
        }
        text(c, "▲", cx, cy - d, pArrow)
        text(c, "▼", cx, cy + d, pArrow)
    }
}
