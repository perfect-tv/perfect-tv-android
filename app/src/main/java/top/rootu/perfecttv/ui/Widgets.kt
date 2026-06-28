package top.rootu.perfecttv.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.R
import top.rootu.perfecttv.data.Channel

/**
 * Карусель каналов над инфопанелью при ←/→: лента карточек категории
 * (carousel.xml, carousel_card.xml), выбранная — по центру в рамке. Каналов меньше,
 * чем карточек — повторяются по кругу.
 */
class Carousel(private val row: LinearLayout) {
    private val cards = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(row.context)
        for (i in 0 until SIZE) cards.add(inflater.inflate(R.layout.carousel_card, row, false).also { row.addView(it) })
    }

    /** Показать ленту [list] с выбранным [index] над [above] */
    fun show(list: List<Channel>, index: Int, above: View) {
        val n = list.size
        if (n == 0) return
        val mid = SIZE / 2
        for (k in 0 until SIZE) {
            val card = cards[k]
            if (n == 1 && k != mid) {
                card.visibility = View.INVISIBLE
                continue
            }
            card.visibility = View.VISIBLE
            val ch = list[((index + k - mid) % n + n) % n]
            Logos.into(card.findViewById(R.id.logo), ch)
            card.findViewById<TextView>(R.id.name).text = ch.name
            card.isSelected = k == mid
        }
        (row.layoutParams as FrameLayout.LayoutParams).bottomMargin = above.height - above.paddingTop + U.pxi(0.8f)   // над содержимым панели (её верх — затемнение)
        row.requestLayout()
        row.visibility = View.VISIBLE
        row.bringToFront()
    }

    fun hide() {
        if (row.visibility == View.VISIBLE) Anim.hide(row)
    }

    companion object {
        const val SIZE = 7
    }
}

/** Сообщение с кнопками (экран ошибки плейлиста) */
class MessageLayer(
    context: Context,
    title: String,
    text: String,
    private val actions: List<Pair<String, String>>,
    private val onAction: (String) -> Unit
) : PopupLayer(context, 40f) {
    private var index = 0
    private val views: List<TextView>

    init {
        head(null, title, text)
        views = buttons(actions.map { it.first }) { index = it; enter() }
        focus()
    }

    private fun focus() = select(views, index)

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "left" && index > 0) index--
        if (name == "right" && index < views.size - 1) index++
        focus()
    }

    override fun enter() = onAction(actions[index].second)
    override fun back() = onAction("back")
}

/**
 * Форма из полей ввода и кнопок (form.xml: добавление/правка плейлиста, TMDB, PIN). Поля —
 * обычные EditText с системной клавиатурой; стрелки ↑/↓ ходят по полям и кнопкам сами (фокус Android).
 */
class FormView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val inflater = LayoutInflater.from(context)
    private var buttonsRow: LinearLayout? = null
    private var buttonRows = 0

    /** Следующие кнопки — новым рядом */
    fun newRow() {
        buttonsRow = null
    }

    fun field(label: String, value: String, url: Boolean = false, pin: Boolean = false): EditText {
        val f = inflater.inflate(R.layout.form_field, this, false)
        f.findViewById<TextView>(R.id.field_label).text = label
        val e = f.findViewById<EditText>(R.id.field_edit)
        e.setText(value)
        e.inputType = when {
            pin -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            url -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (pin) e.filters = arrayOf(InputFilter.LengthFilter(6))
        addView(f)
        return e
    }

    fun button(title: String, onClick: () -> Unit): TextView {
        val row = buttonsRow ?: (inflater.inflate(R.layout.form_buttons, this, false) as LinearLayout).also {
            if (buttonRows++ == 0) (it.layoutParams as LayoutParams).topMargin = U.pxi(1.4f)
            addView(it)
            buttonsRow = it
        }
        val b = inflater.inflate(R.layout.form_button, row, false) as TextView
        b.text = title
        b.setOnClickListener { onClick() }
        b.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_UP && Keys.isOk(code)) { onClick(); true } else false }
        row.addView(b)
        return b
    }

    /** Пояснение под кнопками (статус проверки) */
    fun note(): TextView = (inflater.inflate(R.layout.settings_note, this, false) as TextView).also {
        (it.layoutParams as LayoutParams).topMargin = U.pxi(1f)
        addView(it)
    }

    companion object {
        fun create(parent: LinearLayout, title: String, hint: String): FormView {
            val f = LayoutInflater.from(parent.context).inflate(R.layout.form, parent, false) as FormView
            f.findViewById<TextView>(R.id.form_title).text = title
            f.findViewById<TextView>(R.id.form_hint).apply { text = hint; U.show(this, hint.isNotEmpty()) }
            parent.addView(f)
            return f
        }
    }
}

