package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.core.Prefs

/**
 * Ввод PIN (4–6 цифр). Видны только набранные точки, по центру — длина PIN не видна.
 * Цифры пульта работают всегда. Способы ([Prefs.pinInput]):
 *
 *   RING (как Fire TV) — ↑ → ↓ ← = 1 2 3 4, OK = 0; «Меню» или удерж. OK — раскладка
 *        5–9: ↑ → ↓ ← = 5 6 7 8, OK = 9;
 *   ROWS (как Google)  — ряд 1-2-3: ← = 1, OK = 2, → = 3; ↑/↓ — другой ряд, с 1-2-3 вверх —
 *        на 7-8-9; на ряду 7-8-9 ↓ = 0;
 *   PAD — сетка 1–9, ⌫, 0, «Готово», стрелки по кнопкам.
 *
 * В RING и ROWS нажатая цифра не подсвечивается — со стороны не видно, что набрано.
 * «Назад» стирает цифру, на пустом поле — закрывает окно.
 * CHECK — принимается, как только набран верный PIN; 6 цифр и не совпало — «Неверный PIN».
 * CREATE — всегда клавиатура: длина любая от 4 до 6, «Готово», ввод дважды; PIN сохраняется.
 */
class PinLayer(
    context: Context,
    private val mode: Mode,
    title: String,
    private val onDone: (String) -> Unit,
    private val onCancel: () -> Unit
) : PopupLayer(context, 30f) {

    enum class Mode { CHECK, CREATE }
    enum class Style { RING, ROWS, PAD }

    private val style = if (mode == Mode.CREATE) Style.PAD else Style.values()[Prefs.pinInput.coerceIn(0, 2)]
    private var buf = ""
    private var first: String? = null          // CREATE: первый ввод
    private var high = false                   // RING: раскладка 5–9
    private var row = 0                        // ROWS: ряд 0..2
    private var padIndex = 4                   // PAD: кнопка в фокусе («5»)
    private val padKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", str(R.string.done))
    private val padViews = ArrayList<TextView>()
    private val dots = Dots(context)
    private val hint = U.text(context, 1f, 0.65f).apply { gravity = Gravity.CENTER }
    private val help = U.text(context, 0.85f, 0.5f, lines = 3).apply { gravity = Gravity.CENTER }
    private val wheel = Wheel(context)

    init {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(U.pxi(1.5f), U.pxi(1.4f), U.pxi(1.5f), U.pxi(1.4f))
        }
        col.addView(U.text(context, 1.3f, bold = true).apply { gravity = Gravity.CENTER; text = title }, LinearLayout.LayoutParams(-1, -2))
        col.addView(dots, LinearLayout.LayoutParams(-1, U.pxi(2.6f)).apply { topMargin = U.pxi(0.6f) })
        col.addView(hint, LinearLayout.LayoutParams(-1, -2))
        if (style == Style.PAD) {
            for (r in 0 until 4) {
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (c in 0 until 3) {
                    val i = r * 3 + c
                    val v = U.text(context, if (i == 11) 1.05f else 1.4f).apply {
                        text = padKeys[i]
                        gravity = Gravity.CENTER
                        setOnClickListener { padIndex = i; padPress(padKeys[i]) }
                    }
                    padViews.add(v)
                    line.addView(v, LinearLayout.LayoutParams(U.pxi(5.4f), U.pxi(3f)).apply { setMargins(U.pxi(0.25f), U.pxi(0.25f), U.pxi(0.25f), U.pxi(0.25f)) })
                }
                col.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (r == 0) U.pxi(0.9f) else 0 })
            }
        } else {
            col.addView(wheel, LinearLayout.LayoutParams(-1, U.pxi(13f)).apply { topMargin = U.pxi(0.8f) })
            if (style == Style.ROWS) help.text = str(R.string.up_down_another_row_on_7_8_9_down_0)
        }
        col.addView(help, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.6f) })
        box.addView(col)
        hint.text = when {
            mode == Mode.CREATE -> str(R.string.create_a_pin_of_4_6_digits)
            else -> str(R.string.enter_pin)
        }
        if (mode == Mode.CREATE) help.text = str(R.string.done_when_entered_back_delete)
        render()
    }

    private fun render() {
        dots.count = buf.length
        if (style == Style.PAD) padViews.forEachIndexed { i, v ->
            v.background = U.bg(if (i == padIndex) U.WHITE else U.FILL_BTN, U.RADIUS_S)
            v.setTextColor(if (i == padIndex) U.BLACK else U.WHITE)
        }
        if (style == Style.RING) help.text = str(R.string.menu_or_hold_ok_digits) + (if (high) "0–4" else "5–9") + str(R.string.back_delete)
        wheel.invalidate()
    }

    private fun digit(d: Int) {
        if (buf.length >= MAX) return
        buf += d
        hint.text = if (mode == Mode.CREATE) (if (first == null) str(R.string.create_a_pin_of_4_6_digits) else str(R.string.repeat_the_pin)) else str(R.string.enter_pin)
        render()
        if (mode == Mode.CHECK) {
            if (buf == Prefs.pin) return onDone(buf)
            if (buf.length >= MAX) fail(str(R.string.wrong_pin))
        }
    }

    private fun fail(text: String) {
        buf = ""
        hint.text = text
        render()
    }

    /** CREATE: «Готово» */
    private fun confirm() {
        if (buf.length < MIN) return fail(str(R.string.x_to_x_digits_required, MIN, MAX))
        val f = first
        if (f == null) {
            first = buf
            buf = ""
            hint.text = str(R.string.repeat_the_pin)
            return render()
        }
        if (f == buf) {
            Prefs.pin = buf
            return onDone(buf)
        }
        first = null
        fail(str(R.string.did_not_match_create_the_pin_again))
    }

    private fun padPress(k: String) {
        when (k) {
            "⌫" -> { buf = buf.dropLast(1); render() }
            str(R.string.done) -> if (mode == Mode.CREATE) confirm() else if (buf.isNotEmpty()) fail(str(R.string.wrong_pin))
            else -> digit(k.toInt())
        }
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return digit(digit)
        when (style) {
            Style.PAD -> {
                val r = padIndex / 3
                val c = padIndex % 3
                padIndex = when (name) {
                    "left" -> r * 3 + (c + 2) % 3
                    "right" -> r * 3 + (c + 1) % 3
                    "up" -> ((r + 3) % 4) * 3 + c
                    "down" -> ((r + 1) % 4) * 3 + c
                    else -> padIndex
                }
                render()
            }
            Style.RING -> {
                val base = if (high) 5 else 1
                when (name) {
                    "up" -> digit(base)
                    "right" -> digit(base + 1)
                    "down" -> digit(base + 2)
                    "left" -> digit(base + 3)
                }
            }
            Style.ROWS -> when (name) {
                "left" -> digit(row * 3 + 1)
                "right" -> digit(row * 3 + 3)
                "up" -> { row = (row + 2) % 3; render() }
                "down" -> if (row == 2) digit(0) else { row++; render() }
            }
        }
    }

    override fun enter() {
        when (style) {
            Style.PAD -> padPress(padKeys[padIndex])
            Style.RING -> digit(if (high) 9 else 0)
            Style.ROWS -> digit(row * 3 + 2)
        }
    }

    /** RING: удерж. OK — другая раскладка */
    override fun long() {
        if (style == Style.RING) {
            high = !high
            render()
        }
    }

    override fun media(name: String) {
        if (name == "menu" && style == Style.RING) long()
    }

    // стрелки здесь — цифры: удержание не должно набирать их подряд
    override val holdRepeat get() = false

    override fun back() {
        if (buf.isEmpty()) return onCancel()
        buf = buf.dropLast(1)
        render()
    }

    /** Набранные точки по центру (пустых мест под оставшиеся цифры нет) */
    private class Dots(ctx: Context) : View(ctx) {
        var count = 0
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            val r = U.px(0.5f)
            val gap = U.px(1.9f)
            val w = (count - 1) * gap
            val x0 = width / 2f - w / 2
            for (i in 0 until count) U.fill.let {
                it.color = U.WHITE
                c.drawCircle(x0 + i * gap, height / 2f, r, it)
            }
            // строка под точками, чтобы было видно, куда вводить, даже без точек
            U.roundRect(c, width / 2f - U.px(6f), height - U.px(0.15f), width / 2f + U.px(6f), height.toFloat(), U.px(0.1f), U.wa(0.25f))
        }
    }

    /**
     * Круг с цифрами текущей раскладки: RING — 4 направления и центр; ROWS — ←/OK/→ ряда
     * и стрелки ↑/↓, справа — все цифры с выделенным рядом.
     */
    @SuppressLint("ViewConstructor")
    private inner class Wheel(ctx: Context) : View(ctx) {
        private val pDigit = U.paint(1.8f, U.BLACK, bold = true)
        private val pCenter = U.paint(2.1f, U.WHITE, bold = true)
        private val pSmall = U.paint(1.05f, U.wa(0.5f))
        private val pSmallOn = U.paint(1.05f, U.WHITE, bold = true)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
            c.drawText(s, x - p.measureText(s) / 2, y - (p.descent() + p.ascent()) / 2, p)
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val r = h / 2 - U.px(0.2f)
            val cx = if (style == Style.ROWS) width / 2f - U.px(4f) else width / 2f
            val cy = h / 2
            ring.color = U.wa(0.85f)
            c.drawCircle(cx, cy, r, ring)
            ring.color = U.POPUP
            c.drawCircle(cx, cy, r * 0.38f, ring)
            val d = r * 0.7f
            if (style == Style.RING) {
                val b = if (high) 5 else 1
                text(c, "$b", cx, cy - d, pDigit)
                text(c, "${b + 1}", cx + d, cy, pDigit)
                text(c, "${b + 2}", cx, cy + d, pDigit)
                text(c, "${b + 3}", cx - d, cy, pDigit)
                text(c, if (high) "9" else "0", cx, cy, pCenter)
            } else {
                text(c, "${row * 3 + 1}", cx - d, cy, pDigit)
                text(c, "${row * 3 + 2}", cx, cy, pCenter)
                text(c, "${row * 3 + 3}", cx + d, cy, pDigit)
                text(c, "▲", cx, cy - d, pDigit)
                text(c, if (row == 2) "0" else "▼", cx, cy + d, pDigit)
                // справа: все цифры, текущий ряд выделен
                val gx = cx + r + U.px(2.4f)
                val step = U.px(1.7f)
                for (rr in 0 until 3) for (cc in 0 until 3) {
                    val on = rr == row
                    text(c, "${rr * 3 + cc + 1}", gx + cc * step, cy - 1.5f * step + rr * step, if (on) pSmallOn else pSmall)
                }
                text(c, "0", gx + step, cy + 1.5f * step, if (row == 2) pSmallOn else pSmall)
                ring.style = Paint.Style.STROKE
                ring.strokeWidth = U.px(0.1f)
                ring.color = U.wa(0.7f)
                val top = cy - 1.5f * step + row * step - step / 2
                c.drawRoundRect(gx - step * 0.7f, top, gx + 2 * step + step * 0.7f, top + step, step / 2, step / 2, ring)
                ring.style = Paint.Style.FILL
            }
        }
    }

    companion object {
        const val MIN = 4
        const val MAX = 6
    }
}
