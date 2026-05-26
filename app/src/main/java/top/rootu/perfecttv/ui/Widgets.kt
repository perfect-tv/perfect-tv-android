package top.rootu.perfecttv.ui

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.data.Channel

/**
 * Карусель каналов над инфопанелью при ←/→ (ui/carousel.js): лента карточек категории,
 * выбранная — по центру в рамке. Каналов меньше, чем карточек — повторяются по кругу.
 */
class Carousel(private val context: Context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val cards = ArrayList<Pair<ImageView, TextView>>()
    private val frames = ArrayList<LinearLayout>()

    fun attach(root: FrameLayout) {
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        for (i in 0 until SIZE) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(U.pxi(0.5f), U.pxi(0.5f), U.pxi(0.5f), U.pxi(0.4f))
            }
            val logo = ImageView(context)
            val name = U.text(context, 0.85f, 0.85f).apply { gravity = Gravity.CENTER }
            card.addView(logo, LinearLayout.LayoutParams(U.pxi(6.5f), U.pxi(4.2f)))
            card.addView(name, LinearLayout.LayoutParams(U.pxi(7f), -2).apply { topMargin = U.pxi(0.3f) })
            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = U.pxi(0.35f); rightMargin = U.pxi(0.35f) })
            cards.add(logo to name)
            frames.add(card)
        }
    }

    /** Показать ленту [list] с выбранным [index] над [above] */
    fun show(list: List<Channel>, index: Int, above: View) {
        val n = list.size
        if (n == 0) return
        val mid = SIZE / 2
        for (k in 0 until SIZE) {
            val card = frames[k]
            if (n == 1 && k != mid) {
                card.visibility = View.INVISIBLE
                continue
            }
            card.visibility = View.VISIBLE
            val ch = list[((index + k - mid) % n + n) % n]
            Logos.into(cards[k].first, ch)
            cards[k].second.text = ch.name
            card.background = if (k == mid) U.bg(U.PANEL_CARD_ON, U.RADIUS).apply { setStroke(U.pxi(0.18f), U.WHITE) } else U.bg(U.PANEL_CARD, U.RADIUS)
        }
        (row.layoutParams as FrameLayout.LayoutParams).bottomMargin = above.height + U.pxi(2.2f)
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
    private val buttons: List<Pair<String, String>>,
    private val onAction: (String) -> Unit
) : PopupLayer(context, 40f) {
    private val views = ArrayList<TextView>()
    private var index = 0

    init {
        head(null, title, text)
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(U.pxi(1.5f), U.pxi(0.6f), U.pxi(1.5f), U.pxi(1.4f)) }
        buttons.forEachIndexed { i, b ->
            val v = U.text(context, 1.05f).apply {
                this.text = b.first
                setPadding(U.pxi(1.1f), U.pxi(0.55f), U.pxi(1.1f), U.pxi(0.55f))
                setOnClickListener { index = i; enter() }
            }
            views.add(v)
            bar.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = U.pxi(0.6f) })
        }
        box.addView(bar)
        focus()
    }

    private fun focus() = views.forEachIndexed { i, v ->
        v.background = U.bg(if (i == index) U.WHITE else U.FILL_BTN, U.RADIUS_S)
        v.setTextColor(if (i == index) U.BLACK else U.WHITE)
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "left" && index > 0) index--
        if (name == "right" && index < views.size - 1) index++
        focus()
    }

    override fun enter() = onAction(buttons[index].second)
    override fun back() = onAction("back")
}

/**
 * Форма из полей ввода и кнопок (добавление/правка плейлиста, EPG). Поля — обычные EditText
 * с системной клавиатурой; стрелки ↑/↓ ходят по полям и кнопкам сами (фокус Android).
 */
class FormView(context: Context, title: String, hint: String) : LinearLayout(context) {
    private val fields = ArrayList<EditText>()
    private val buttonsRow = LinearLayout(context).apply { orientation = HORIZONTAL }

    init {
        orientation = VERTICAL
        background = U.bg(U.POPUP, U.RADIUS_L)
        setPadding(U.pxi(1.8f), U.pxi(1.5f), U.pxi(1.8f), U.pxi(1.5f))
        addView(U.text(context, 1.5f, bold = true).apply { text = title })
        if (hint.isNotEmpty()) addView(U.text(context, 0.95f, 0.6f, lines = 3).apply { text = hint }, LayoutParams(-1, -2).apply { topMargin = U.pxi(0.4f) })
    }

    fun field(label: String, value: String, url: Boolean = false, pin: Boolean = false): EditText {
        addView(U.text(context, 0.95f, 0.7f).apply { text = label }, LayoutParams(-1, -2).apply { topMargin = U.pxi(1f) })
        val e = EditText(context).apply {
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, U.px(1.15f))
            setTextColor(U.WHITE)
            setHintTextColor(U.wa(0.4f))
            isSingleLine = true
            inputType = when {
                pin -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                url -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (pin) filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(U.pxi(0.8f), U.pxi(0.6f), U.pxi(0.8f), U.pxi(0.6f))
            focusBg(this)
        }
        addView(e, LayoutParams(-1, -2).apply { topMargin = U.pxi(0.3f) })
        fields.add(e)
        return e
    }

    fun button(title: String, onClick: () -> Unit): TextView {
        if (buttonsRow.parent == null) addView(buttonsRow, LayoutParams(-1, -2).apply { topMargin = U.pxi(1.4f) })
        val b = U.text(context, 1.05f).apply {
            text = title
            isFocusable = true
            setPadding(U.pxi(1.1f), U.pxi(0.6f), U.pxi(1.1f), U.pxi(0.6f))
            setOnClickListener { onClick() }
            setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) { onClick(); true } else false }
            focusBg(this)
        }
        buttonsRow.addView(b, LayoutParams(-2, -2).apply { rightMargin = U.pxi(0.6f) })
        return b
    }

    private fun focusBg(v: TextView) {
        fun apply(f: Boolean) {
            v.background = U.bg(if (f) U.WHITE else U.FILL_BTN, U.RADIUS_S)
            v.setTextColor(if (f) U.BLACK else U.WHITE)
        }
        apply(false)
        v.setOnFocusChangeListener { _, f -> apply(f) }
    }
}
