package top.rootu.perfecttv.ui

import top.rootu.perfecttv.core.L
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.rootu.perfecttv.data.Channel

/**
 * Окно поверх плеера: затемнение и коробка по центру (PopupLayer плагина).
 * Тап вне коробки — back().
 */
abstract class PopupLayer(context: Context, widthEm: Float, heightFraction: Float = 0f) : Layer {
    protected val root = FrameLayout(context).apply { setBackgroundColor(U.OVERLAY) }
    protected val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = U.bg(U.POPUP, U.RADIUS_L)
        isClickable = true
    }
    override val view: View get() = root

    init {
        val h = if (heightFraction > 0) Math.round(context.resources.displayMetrics.heightPixels * heightFraction) else -2
        root.addView(box, FrameLayout.LayoutParams(minOf(U.pxi(widthEm), context.resources.displayMetrics.widthPixels - U.pxi(2f)), h, Gravity.CENTER))
        root.setOnClickListener { back() }
    }

    /** Шапка окна: логотип канала (или null), заголовок, подзаголовок, справа — подпись */
    protected fun head(ch: Channel?, title: String, subtitle: String = "", side: String = "", icon: Icons.Kind? = null): TextView {
        val ctx = root.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(U.pxi(1.5f), U.pxi(1.2f), U.pxi(1.5f), U.pxi(0.8f))
        }
        if (icon != null) {
            val v = ImageView(ctx).apply { setImageDrawable(Icons.drawable(icon).constantState?.newDrawable()?.mutate()); setColorFilter(U.WHITE) }
            row.addView(v, LinearLayout.LayoutParams(U.pxi(2.4f), U.pxi(2.4f)).apply { rightMargin = U.pxi(1f) })
        } else if (ch != null) {
            val logo = ImageView(ctx)
            Logos.into(logo, ch)
            row.addView(logo, LinearLayout.LayoutParams(U.pxi(3.4f), U.pxi(2.4f)).apply { rightMargin = U.pxi(1f) })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(U.text(ctx, 1.4f, bold = true).apply { text = title })
        if (subtitle.isNotEmpty()) col.addView(U.text(ctx, 0.95f, 0.6f).apply { text = subtitle }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = U.pxi(0.15f) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sub = U.text(ctx, 1f, 0.6f).apply { text = side }
        row.addView(sub)
        box.addView(row)
        return sub
    }
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
    empty: String = L.t("Пусто"),
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
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; Fade.scroll(this) }
    private var index = start.coerceIn(0, maxOf(0, items.size - 1))

    init {
        head(null, title)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(U.pxi(0.7f), 0, U.pxi(0.7f), U.pxi(0.8f)) }
        if (items.isEmpty()) list.addView(U.text(context, 1.1f, 0.6f).apply { text = empty; setPadding(U.pxi(0.8f), U.pxi(0.8f), 0, U.pxi(1f)) })
        items.forEachIndexed { i, it -> list.addView(row(context, it, i)); }
        scroll.addView(list)
        val maxH = Math.round(context.resources.displayMetrics.heightPixels * 0.7f)
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        scroll.layoutParams.height = -2
        scroll.viewTreeObserver.addOnGlobalLayoutListener { if (scroll.height > maxH) scroll.layoutParams = LinearLayout.LayoutParams(-1, maxH) }
        applyFocus()
    }

    private fun row(ctx: Context, it: Item, i: Int): View {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(U.pxi(0.8f), U.pxi(0.55f), U.pxi(0.8f), U.pxi(0.55f))
            setOnClickListener { index = i; applyFocus(); enter() }
        }
        if (it.channel != null) {
            val logo = ImageView(ctx)
            Logos.into(logo, it.channel)
            r.addView(logo, LinearLayout.LayoutParams(U.pxi(3f), U.pxi(2.1f)).apply { rightMargin = U.pxi(0.8f) })
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(U.text(ctx, 1.1f, if (it.enabled) 1f else 0.5f).apply { text = it.title })
        if (it.sub.isNotEmpty()) col.addView(U.text(ctx, 0.9f, 0.6f).apply { text = it.sub }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.15f) })
        if (it.progress >= 0) col.addView(ProgressLine(ctx, it.progress), LinearLayout.LayoutParams(-1, U.pxi(0.2f)).apply { topMargin = U.pxi(0.35f) })
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        rows.add(r)
        return r
    }

    private fun applyFocus() {
        rows.forEachIndexed { i, r ->
            val f = i == index
            r.background = if (f) U.bg(U.WHITE, 0.5f) else null
            tint(r, if (f) U.BLACK else U.WHITE)
            // белые логотипы на белой строке — на тёмной плашке
            (r as? LinearLayout)?.getChildAt(0)?.let { if (it is ImageView) it.background = if (f) U.bg(Logos.PLATE, U.RADIUS_S) else null }
        }
        rows.getOrNull(index)?.let { r -> r.post { scroll.smoothScrollTo(0, maxOf(0, r.top - scroll.height / 2 + r.height / 2)) } }
    }

    private fun tint(v: View, color: Int) {
        if (v is TextView) v.setTextColor((v.currentTextColor and 0xFF000000.toInt()) or (color and 0xFFFFFF))
        if (v is ProgressLine) v.dark = color == U.BLACK
        if (v is LinearLayout) for (k in 0 until v.childCount) tint(v.getChildAt(k), color)
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

    private class ProgressLine(ctx: Context, val value: Float) : View(ctx) {
        var dark = false
            set(v) { field = v; invalidate() }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            U.roundRect(c, 0f, 0f, width.toFloat(), h, h / 2, if (dark) 0x33000000 else U.FILL_MARKED)
            U.roundRect(c, 0f, 0f, width * value, h, h / 2, if (dark) U.BLACK else U.WHITE)
        }
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

    private val btnViews = ArrayList<TextView>()
    private var index = 0
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; Fade.scroll(this) }

    init {
        head(ch, ch.name, kicker)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(U.pxi(1.5f), 0, U.pxi(1.5f), 0) }
        body.addView(U.text(context, 1f, 0.7f).apply { text = time })
        body.addView(U.text(context, 1.5f, bold = true, lines = 3).apply { text = title }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.3f) })
        if (desc.isNotEmpty()) {
            val d = U.text(context, 1.05f, 0.8f, lines = 0).apply { text = desc; gravity = Gravity.TOP; setLineSpacing(0f, 1.15f) }
            scroll.addView(d)
            body.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.7f) })
            val maxH = Math.round(context.resources.displayMetrics.heightPixels * 0.4f)
            scroll.viewTreeObserver.addOnGlobalLayoutListener { if (scroll.height > maxH) scroll.layoutParams = LinearLayout.LayoutParams(-1, maxH).apply { topMargin = U.pxi(0.7f) } }
        }
        box.addView(body)
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(U.pxi(1.5f), U.pxi(1.2f), U.pxi(1.5f), U.pxi(1.4f)) }
        buttons.forEachIndexed { i, b ->
            val v = U.text(context, 1.05f).apply {
                text = b.first
                setPadding(U.pxi(1.1f), U.pxi(0.55f), U.pxi(1.1f), U.pxi(0.55f))
                setOnClickListener { index = i; enter() }
            }
            btnViews.add(v)
            bar.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = U.pxi(0.6f) })
        }
        if (buttons.isNotEmpty()) box.addView(bar)
        else box.addView(View(context), LinearLayout.LayoutParams(-1, U.pxi(1.4f)))
        applyFocus()
    }

    private fun applyFocus() {
        btnViews.forEachIndexed { i, v ->
            v.background = U.bg(if (i == index) U.WHITE else U.FILL_BTN, U.RADIUS_S)
            v.setTextColor(if (i == index) U.BLACK else U.WHITE)
        }
    }

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

/** Простой тост внизу экрана */
class Toast(private val root: FrameLayout) {
    private val v = U.text(root.context, 1.1f).apply {
        background = U.bg(U.PANEL, U.RADIUS)
        setPadding(U.pxi(1.2f), U.pxi(0.7f), U.pxi(1.2f), U.pxi(0.7f))
        visibility = View.GONE
    }
    private val hide = Runnable { Anim.hide(v) }

    init {
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = U.pxi(2f) })
    }

    fun show(text: String) {
        v.text = text
        v.bringToFront()
        if (v.visibility != View.VISIBLE) Anim.show(v)
        v.removeCallbacks(hide)
        v.postDelayed(hide, 2500)
    }
}
