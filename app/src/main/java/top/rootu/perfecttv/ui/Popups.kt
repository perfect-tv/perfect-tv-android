package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.rootu.perfecttv.data.Channel

/**
 * Окно поверх плеера: затемнение и коробка по центру (layer_popup.xml, PopupLayer плагина).
 * Тап вне коробки — back(). Содержимое — разметки окна, вставленные в [box].
 */
abstract class PopupLayer(context: Context, widthEm: Float, heightFraction: Float = 0f) : Layer {
    protected val inflater: LayoutInflater = LayoutInflater.from(context)
    // корень окна вставляет Tv в overlay со своими параметрами — родителя при разборе нет
    @SuppressLint("InflateParams")
    protected val root = inflater.inflate(R.layout.layer_popup, null) as FrameLayout
    protected val box: LinearLayout = root.findViewById(R.id.box)
    override val view: View get() = root

    init {
        val dm = context.resources.displayMetrics
        box.layoutParams.width = minOf(U.pxi(widthEm), dm.widthPixels - U.pxi(2f))
        if (heightFraction > 0) box.layoutParams.height = Math.round(dm.heightPixels * heightFraction)
        root.setOnClickListener { back() }
    }

    /** Вставить разметку в коробку окна и вернуть её */
    protected fun <T : View> add(layout: Int): T {
        @Suppress("UNCHECKED_CAST")
        val v = inflater.inflate(layout, box, false) as T
        box.addView(v)
        return v
    }

    /** Шапка окна (popup_head.xml): логотип канала или значок, заголовок, подзаголовок; вернёт подпись справа */
    protected fun head(ch: Channel?, title: String, subtitle: String = "", side: String = "", icon: Icons.Kind? = null): TextView {
        val row = add<View>(R.layout.popup_head)
        if (icon != null) row.findViewById<ImageView>(R.id.head_icon).apply {
            setImageDrawable(Icons.drawable(icon).constantState?.newDrawable()?.mutate())
            visibility = View.VISIBLE
        } else if (ch != null) row.findViewById<ImageView>(R.id.head_logo).apply {
            Logos.into(this, ch)
            visibility = View.VISIBLE
        }
        row.findViewById<TextView>(R.id.head_title).text = title
        row.findViewById<TextView>(R.id.head_subtitle).apply { text = subtitle; U.show(this, subtitle.isNotEmpty()) }
        return row.findViewById<TextView>(R.id.head_side).apply { text = side }
    }

    /** Ряд кнопок внизу окна (popup_buttons.xml); фокус — [select] */
    protected fun buttons(labels: List<String>, onClick: (Int) -> Unit): List<TextView> {
        val bar = add<LinearLayout>(R.layout.popup_buttons)
        return labels.mapIndexed { i, label ->
            (inflater.inflate(R.layout.popup_button, bar, false) as TextView).also {
                it.text = label
                it.setOnClickListener { onClick(i) }
                bar.addView(it)
            }
        }
    }

    protected fun select(views: List<View>, index: Int) = views.forEachIndexed { i, v -> v.isSelected = i == index }
}

/**
 * Список-окно (недавние каналы, меню канала): ↑/↓ — пункт (удержание — быстро), OK — выбрать,
 * удерж. OK — onLong, назад — закрыть.
 */
class ListLayer(
    context: Context,
    title: String,
    private val items: List<Item>,
    private val onSelect: (Item) -> Unit,
    private val onLong: ((Item) -> Unit)? = null,
    private val onBack: () -> Unit,
    empty: String = str(R.string.empty),
    start: Int = 0
) : PopupLayer(context, 34f) {

    class Item(
        val title: String,
        val sub: String = "",
        val channel: Channel? = null,
        val progress: Float = -1f,
        val action: String = "",
        val enabled: Boolean = true
    )

    private val rows = ArrayList<View>()
    private val scroll: ScrollView
    private var index = start.coerceIn(0, maxOf(0, items.size - 1))

    init {
        head(null, title)
        scroll = add(R.layout.list_body)
        val list = scroll.findViewById<LinearLayout>(R.id.list)
        if (items.isEmpty()) list.addView((inflater.inflate(R.layout.list_empty, list, false) as TextView).apply { text = empty })
        items.forEachIndexed { i, it -> list.addView(row(list, it, i)) }
        val maxH = Math.round(context.resources.displayMetrics.heightPixels * 0.7f)
        scroll.viewTreeObserver.addOnGlobalLayoutListener { if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH } }
        applyFocus()
    }

    private fun row(list: LinearLayout, it: Item, i: Int): View {
        val r = inflater.inflate(R.layout.list_item, list, false)
        r.setOnClickListener { index = i; applyFocus(); enter() }
        if (it.channel != null) r.findViewById<ImageView>(R.id.item_logo).apply { Logos.into(this, it.channel); visibility = View.VISIBLE }
        r.findViewById<TextView>(R.id.item_title).apply { text = it.title; if (!it.enabled) alpha = 0.5f }
        r.findViewById<TextView>(R.id.item_sub).apply { text = it.sub; U.show(this, it.sub.isNotEmpty()) }
        if (it.progress >= 0) r.findViewById<ProgressLine>(R.id.item_progress).apply { value = it.progress; visibility = View.VISIBLE }
        rows.add(r)
        return r
    }

    private fun applyFocus() {
        select(rows, index)
        rows.getOrNull(index)?.let { r -> r.post { scroll.smoothScrollTo(0, maxOf(0, r.top - scroll.height / 2 + r.height / 2)) } }
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        val n = items.size
        if (n == 0) return
        when (name) {
            "up" -> index = (index - 1 + n) % n
            "down" -> index = (index + 1) % n
            "pgup" -> index = maxOf(0, index - 8)
            "pgdown" -> index = minOf(n - 1, index + 8)
            else -> return
        }
        applyFocus()
    }

    override fun enter() {
        items.getOrNull(index)?.let { if (it.enabled) onSelect(it) }
    }

    override fun long() {
        items.getOrNull(index)?.let { onLong?.invoke(it) }
    }

    override fun back() = onBack()
}

/** Шкала просмотра в строке списка (list_item.xml); в выбранной (белой) строке — тёмная */
class ProgressLine(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var value = 0f
        set(v) { field = v; invalidate() }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val dark = android.R.attr.state_selected in drawableState
        U.roundRect(c, 0f, 0f, width.toFloat(), h, h / 2, if (dark) 0x33000000 else U.FILL_MARKED)
        U.roundRect(c, 0f, 0f, width * value, h, h / 2, if (dark) U.BLACK else U.WHITE)
    }
}

/**
 * Карточка передачи (ui/details.js): канал, пометка, время, название, описание с прокруткой ↑/↓,
 * кнопки ←/→, OK — действие кнопки.
 */
class DetailsLayer(
    context: Context,
    ch: Channel,
    kicker: String,
    title: String,
    time: String,
    desc: String,
    private val buttons: List<Pair<String, String>>,
    private val onAction: (String) -> Unit,
    private val onBack: () -> Unit
) : PopupLayer(context, 46f) {

    private var index = 0
    private val scroll: ScrollView
    private val btnViews: List<TextView>

    init {
        head(ch, ch.name, kicker)
        val body = add<View>(R.layout.details_body)
        body.findViewById<TextView>(R.id.details_time).text = time
        body.findViewById<TextView>(R.id.details_title).text = title
        scroll = body.findViewById(R.id.details_scroll)
        if (desc.isNotEmpty()) {
            body.findViewById<TextView>(R.id.details_desc).text = desc
            scroll.visibility = View.VISIBLE
            val maxH = Math.round(context.resources.displayMetrics.heightPixels * 0.4f)
            scroll.viewTreeObserver.addOnGlobalLayoutListener { if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH } }
        }
        btnViews = if (buttons.isNotEmpty()) buttons(buttons.map { it.first }) { index = it; enter() }
        else { box.setPadding(0, 0, 0, U.pxi(1.4f)); emptyList() }
        applyFocus()
    }

    private fun applyFocus() = select(btnViews, index)

    override fun key(name: String, long: Boolean, digit: Int) {
        when (name) {
            "left" -> if (index > 0) index--
            "right" -> if (index < buttons.size - 1) index++
            "up" -> scroll.smoothScrollBy(0, -U.pxi(4f))
            "down" -> scroll.smoothScrollBy(0, U.pxi(4f))
        }
        applyFocus()
    }

    override fun enter() {
        buttons.getOrNull(index)?.let { onAction(it.second) } ?: onBack()
    }

    override fun back() = onBack()
}

/** Простой тост вверху экрана (toast.xml в activity_tv.xml) */
class Toast(private val v: TextView) {
    private val hide = Runnable { Anim.hide(v) }

    fun show(text: String) {
        v.text = text
        v.bringToFront()
        if (v.visibility != View.VISIBLE) Anim.show(v)
        v.removeCallbacks(hide)
        v.postDelayed(hide, 2500)
    }
}
