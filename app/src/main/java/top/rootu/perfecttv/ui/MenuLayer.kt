package top.rootu.perfecttv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.rootu.perfecttv.R
import top.rootu.perfecttv.data.Channel

/**
 * Меню канала — шторка справа (layer_menu.xml): видео и инфопанель остаются видны.
 * Страница: шапка, ряд плиток частых действий (←/→), ниже — группы пунктов (↑/↓, удержание —
 * быстро). Пункт с [Item.sub] открывает страницу второго уровня (OK или →); «Назад» или ← —
 * обратно, фокус возвращается на тот же пункт. Пункт с [Item.keep] меняет значение на месте
 * (формат кадра, таймер сна): меню не закрывается, страница строится заново.
 */
class MenuLayer(context: Context, private val first: () -> Page, private val onClose: () -> Unit) : Layer {

    class Tile(val icon: Int, val title: String, val run: () -> Unit)

    /** [run] — действие (после него меню закрывается, если не [keep]); [sub] — страница второго уровня */
    class Item(
        val title: String,
        val value: String = "",
        val enabled: Boolean = true,
        val keep: Boolean = false,
        val sub: (() -> Page)? = null,
        val run: () -> Unit = {}
    )

    class Section(val title: String, val items: List<Item>)

    class Page(val title: String, val subtitle: String = "", val channel: Channel? = null, val tiles: List<Tile> = emptyList(), val sections: List<Section>)

    private class Level(val build: () -> Page, var tiles: Boolean, var index: Int)

    private val inflater = LayoutInflater.from(context)
    @SuppressLint("InflateParams")
    private val root = inflater.inflate(R.layout.layer_menu, null)
    override val view: View get() = root

    private val tilesRow: LinearLayout = root.findViewById(R.id.menu_tiles)
    private val scroll: ScrollView = root.findViewById(R.id.menu_scroll)
    private val list: LinearLayout = root.findViewById(R.id.menu_list)

    private val stack = ArrayList<Level>()
    private var page: Page = first()
    private var items: List<Item> = emptyList()
    private val tileViews = ArrayList<View>()
    private val rowViews = ArrayList<View>()

    init {
        root.setOnClickListener { onClose() }
        stack.add(Level(first, page.tiles.isNotEmpty(), 0))
        render()
    }

    private val level get() = stack.last()

    /** Построить текущую страницу заново (значения пунктов изменились) */
    fun refresh() {
        page = level.build()
        render()
    }

    private fun render() {
        val p = page
        root.findViewById<TextView>(R.id.head_title).text = p.title
        root.findViewById<TextView>(R.id.head_subtitle).apply { text = p.subtitle; U.show(this, p.subtitle.isNotEmpty()) }
        root.findViewById<ImageView>(R.id.head_logo).apply {
            if (p.channel != null) Logos.into(this, p.channel)
            U.show(this, p.channel != null)
        }
        tilesRow.removeAllViews()
        tileViews.clear()
        p.tiles.forEachIndexed { i, t ->
            val v = inflater.inflate(R.layout.menu_tile, tilesRow, false)
            v.findViewById<ImageView>(R.id.tile_icon).setImageResource(t.icon)
            v.findViewById<TextView>(R.id.tile_title).text = t.title
            v.setOnClickListener { level.tiles = true; level.index = i; enter() }
            tilesRow.addView(v)
            tileViews.add(v)
        }
        U.show(tilesRow, p.tiles.isNotEmpty())
        list.removeAllViews()
        rowViews.clear()
        val all = ArrayList<Item>()
        for (s in p.sections) {
            if (s.items.isEmpty()) continue
            if (s.title.isNotEmpty()) list.addView((inflater.inflate(R.layout.menu_section, list, false) as TextView).apply { text = s.title })
            for (it in s.items) {
                val i = all.size
                val r = inflater.inflate(R.layout.menu_item, list, false)
                r.findViewById<TextView>(R.id.item_title).apply { text = it.title; if (!it.enabled) alpha = 0.5f }
                r.findViewById<TextView>(R.id.item_value).text = if (it.sub != null) (if (it.value.isEmpty()) "›" else it.value + "  ›") else it.value
                r.setOnClickListener { level.tiles = false; level.index = i; enter() }
                list.addView(r)
                rowViews.add(r)
                all.add(it)
            }
        }
        items = all
        if (level.tiles && p.tiles.isEmpty()) level.tiles = false
        level.index = level.index.coerceIn(0, maxOf(0, (if (level.tiles) p.tiles.size else all.size) - 1))
        applyFocus()
    }

    private fun applyFocus() {
        val l = level
        tileViews.forEachIndexed { i, v -> v.isSelected = l.tiles && i == l.index }
        rowViews.forEachIndexed { i, v -> v.isSelected = !l.tiles && i == l.index }
        if (l.tiles) scroll.post { scroll.smoothScrollTo(0, 0) }
        else rowViews.getOrNull(l.index)?.let { r -> r.post { scroll.smoothScrollTo(0, maxOf(0, r.top - scroll.height / 2 + r.height / 2)) } }
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        val l = level
        val n = items.size
        val t = page.tiles.size
        when (name) {
            "up" -> when {
                l.tiles -> if (n > 0) { l.tiles = false; l.index = n - 1 }
                l.index > 0 -> l.index--
                t > 0 -> { l.tiles = true; l.index = 0 }
                n > 0 -> l.index = n - 1
            }
            "down" -> when {
                l.tiles -> if (n > 0) { l.tiles = false; l.index = 0 }
                l.index < n - 1 -> l.index++
                t > 0 -> { l.tiles = true; l.index = 0 }
                else -> l.index = 0
            }
            "left" -> if (l.tiles) { if (l.index > 0) l.index-- } else if (stack.size > 1) return back()
            "right" -> if (l.tiles) { if (l.index < t - 1) l.index++ } else if (items.getOrNull(l.index)?.sub != null) return enter()
            "pgup" -> if (!l.tiles) l.index = maxOf(0, l.index - 8)
            "pgdown" -> if (!l.tiles) l.index = minOf(n - 1, l.index + 8)
            else -> return
        }
        applyFocus()
    }

    override fun enter() {
        val l = level
        if (l.tiles) {
            val t = page.tiles.getOrNull(l.index) ?: return
            onClose()
            t.run()
            return
        }
        val it = items.getOrNull(l.index) ?: return
        if (!it.enabled) return
        if (it.sub != null) {
            stack.add(Level(it.sub, false, 0))
            refresh()
            return
        }
        if (it.keep) {
            it.run()
            refresh()
        } else {
            onClose()
            it.run()
        }
    }

    override fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            refresh()
        } else onClose()
    }

    override fun media(name: String) {
        if (name == "menu") onClose()
    }
}
