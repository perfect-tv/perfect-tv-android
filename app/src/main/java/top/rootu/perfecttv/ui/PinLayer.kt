package top.rootu.perfecttv.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.core.Prefs

/**
 * Ввод PIN из 4 цифр: цифрами пульта или экранной клавиатурой (у многих пультов Android TV
 * цифр нет) — стрелки по кнопкам 1–9, ⌫, 0, OK. Четвёртая цифра проверяет сразу.
 *
 * Режимы: CHECK — сверить с [Prefs.pin]; CREATE — задать новый (ввод дважды).
 * [onDone] получает введённый PIN (в CREATE — уже сохранённый), [onCancel] — «Назад».
 */
class PinLayer(
    context: Context,
    private val mode: Mode,
    title: String,
    private val onDone: (String) -> Unit,
    private val onCancel: () -> Unit
) : PopupLayer(context, 26f) {

    enum class Mode { CHECK, CREATE }

    private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
    private val views = ArrayList<TextView>()
    private var index = 4          // «5» — центр клавиатуры
    private var buf = ""
    private var first: String? = null   // CREATE: первый ввод
    private val dots = U.text(context, 2.2f, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
    private val hint = U.text(context, 1f, 0.65f).apply { gravity = Gravity.CENTER }
    private val titleView = U.text(context, 1.3f, bold = true).apply { gravity = Gravity.CENTER; text = title }

    init {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(U.pxi(1.5f), U.pxi(1.4f), U.pxi(1.5f), U.pxi(1.4f))
        }
        col.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        col.addView(dots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.8f) })
        col.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.2f) })
        for (r in 0 until 4) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (c in 0 until 3) {
                val i = r * 3 + c
                val v = U.text(context, 1.4f).apply {
                    text = keys[i]
                    gravity = Gravity.CENTER
                    setOnClickListener { index = i; press(keys[i]) }
                }
                views.add(v)
                row.addView(v, LinearLayout.LayoutParams(U.pxi(4.6f), U.pxi(3f)).apply { setMargins(U.pxi(0.25f), U.pxi(0.25f), U.pxi(0.25f), U.pxi(0.25f)) })
            }
            col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = if (r == 0) U.pxi(0.9f) else 0 })
        }
        box.addView(col)
        hint.text = if (mode == Mode.CREATE) "Придумайте PIN из 4 цифр" else "Введите PIN"
        render()
    }

    private fun render() {
        dots.text = (0 until 4).joinToString(" ") { if (it < buf.length) "●" else "○" }
        views.forEachIndexed { i, v ->
            v.background = U.bg(if (i == index) U.WHITE else U.FILL_BTN, U.RADIUS_S)
            v.setTextColor(if (i == index) U.BLACK else U.WHITE)
        }
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> buf = buf.dropLast(1)
            "OK" -> if (buf.length == 4) return submit()
            else -> if (buf.length < 4) buf += k
        }
        render()
        if (buf.length == 4) submit()
    }

    private fun submit() {
        val pin = buf
        buf = ""
        when (mode) {
            Mode.CHECK -> if (pin == Prefs.pin) return onDone(pin) else hint.text = "Неверный PIN"
            Mode.CREATE -> {
                val f = first
                if (f == null) {
                    first = pin
                    hint.text = "Повторите PIN"
                } else if (f == pin) {
                    Prefs.pin = pin
                    return onDone(pin)
                } else {
                    first = null
                    hint.text = "Не совпало — придумайте PIN заново"
                }
            }
        }
        render()
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return press(digit.toString())
        val r = index / 3
        val c = index % 3
        index = when (name) {
            "left" -> r * 3 + (c + 2) % 3
            "right" -> r * 3 + (c + 1) % 3
            "up" -> ((r + 3) % 4) * 3 + c
            "down" -> ((r + 1) % 4) * 3 + c
            else -> index
        }
        render()
    }

    override fun enter() = press(keys[index])

    override fun back() = onCancel()
}
