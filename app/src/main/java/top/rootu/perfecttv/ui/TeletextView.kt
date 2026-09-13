package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import top.rootu.perfecttv.teletext.TTX_COLORS
import top.rootu.perfecttv.teletext.Ttx

/**
 * Страница телетекста 40×25 на Canvas: область 4:3 по центру экрана, ячейка рисуется целиком
 * (фон, символ или блок мозаики 2×3). Двойная высота — символ вдвое выше через две ячейки,
 * мигание — по фазе [flashOn], скрытый текст — пока не включено [reveal].
 */
class TeletextView(context: Context) : View(context) {

    var grid: Ttx.Grid? = null
        set(v) { field = v; invalidate() }
    var flashOn = true
        set(v) { if (field != v) { field = v; if (grid?.hasFlash() == true) invalidate() } }
    var reveal = false
        set(v) { field = v; invalidate() }
    /** Подпись по центру (ожидание страницы), пусто — нет */
    var message = ""
        set(v) { field = v; invalidate() }

    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val tmp = FloatArray(1)

    init { setBackgroundColor(Color.BLACK) }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = minOf(width.toFloat(), h * 4f / 3f)
        val x0 = (width - w) / 2f
        val cw = w / Ttx.COLS
        val ch = h / Ttx.ROWS
        text.textSize = ch * 0.82f
        // растянуть знак по ширине ячейки: у моноширинного шрифта он уже, чем у телетекста
        val adv = text.measureText("0").coerceAtLeast(1f)
        val sx = (cw * 0.98f / adv).coerceIn(0.6f, 2f)
        val g = grid
        if (g != null) {
            for (r in 0 until Ttx.ROWS) for (col in 0 until Ttx.COLS) {
                val i = r * Ttx.COLS + col
                val a = g.attr[i]
                val left = x0 + col * cw
                val top = r * ch
                val bg = (a shr 3) and 7
                if (bg != 0) { fill.color = TTX_COLORS[bg]; c.drawRect(left, top, left + cw, top + ch, fill) }
                val hidden = (a and Ttx.A_CONCEAL != 0 && !reveal) || (a and Ttx.A_FLASH != 0 && !flashOn)
                val code = g.ch[i]
                if (hidden || code == 0x20) continue
                val fg = TTX_COLORS[a and 7]
                val bottom = a and Ttx.A_DH_BOTTOM != 0
                val double = a and Ttx.A_DH != 0 || bottom
                c.save()
                c.clipRect(left, top, left + cw, top + ch)
                // двойная высота: знак верхней ячейки растянут вдвое от её верха, в этой ячейке видна нужная половина
                val dTop = if (bottom) top - ch else top
                if (double) c.scale(1f, 2f, 0f, dTop)
                fill.color = fg
                if (a and Ttx.A_MOSAIC != 0) mosaic(c, code, left, dTop, cw, ch, a and Ttx.A_SEPARATED != 0)
                else glyph(c, code, left, dTop, cw, ch, sx, fg)
                c.restore()
            }
        }
        if (message.isNotEmpty()) {
            text.textScaleX = 1f
            text.color = Color.WHITE
            text.textSize = ch * 1.1f
            val mw = text.measureText(message)
            c.drawText(message, (width - mw) / 2f, h / 2f, text)
        }
    }

    private fun glyph(c: Canvas, code: Int, left: Float, top: Float, cw: Float, ch: Float, sx: Float, color: Int) {
        val s = String(Character.toChars(code))
        text.color = color
        text.textScaleX = sx
        val wid = text.measureText(s, 0, s.length)
        // широкий знак (Ж, Ш, Щ…) сжимаем, чтобы не залезал на соседей
        text.textScaleX = if (wid > cw) sx * cw / wid else sx
        tmp[0] = text.measureText(s)
        val x = left + (cw - tmp[0]) / 2f
        val y = top + ch * 0.80f
        c.drawText(s, x, y, text)
    }

    // 2×3 блока: биты 0x01 0x02 / 0x04 0x08 / 0x10 0x40; separated — блоки с зазором
    private fun mosaic(c: Canvas, code: Int, left: Float, top: Float, cw: Float, ch: Float, separated: Boolean) {
        val bits = (code and 0x1F) or ((code and 0x40) shr 1)  // 0x40 → бит 0x20
        val gx = if (separated) cw / 12f else 0f
        val gy = if (separated) ch / 12f else 0f
        for (row in 0 until 3) for (cx in 0 until 2) {
            val bit = 1 shl (row * 2 + cx)
            if (bits and bit == 0) continue
            val l = left + cx * cw / 2f
            val t = top + row * ch / 3f
            c.drawRect(l + gx, t + gy, l + cw / 2f - gx, t + ch / 3f - gy, fill)
        }
    }
}
