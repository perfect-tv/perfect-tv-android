package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.graphics.Canvas
import android.widget.LinearLayout
import android.view.View
import top.rootu.perfecttv.data.Channel

/**
 * Окно «Программа и архив» / «Напоминания»: слева вкладки видов
 * (если их больше одного), в центре передачи по дням (виртуальный список на Canvas),
 * справа вкладки дат.
 *
 * Подсветка: серым — играющая передача и её дата, открытый вид; белым — фокус (строка и
 * её дата; вкладка даты и заголовок этого дня; вкладка вида).
 */
class ScheduleLayer(
    context: Context,
    private val views: List<Icons.Kind>,      // значки видов (пусто или один — без колонки)
    private var viewIndex: Int,
    private val makeContent: (Int) -> Content,
    private val onBack: () -> Unit
) : PopupLayer(context, 64f, 0.86f) {

    class Row(
        val separator: Boolean,
        val day: Long,
        val title: String,
        val id: Long = 0,
        val time: String = "",
        val sub: String = "",
        val past: Boolean = false,
        val live: Boolean = false,
        val future: Boolean = false,
        val archive: Boolean = false,
        val playing: Boolean = false,
        val remind: Boolean = false,
        val channel: Channel? = null,     // логотип в строке (список напоминаний)
        val payload: Any? = null
    )

    class Tab(val day: Long, val label: String)

    class Built(val rows: List<Row>, val tabs: List<Tab>, val focusId: Long? = null)

    /** Содержимое вида; [watch] подписывается на обновления и возвращает отписку */
    class Content(
        val channel: Channel?,
        val icon: Icons.Kind?,
        val title: String,
        val subtitle: String,
        val empty: String,
        val build: () -> Built,
        val sub: () -> String,
        val onSelect: (Row) -> Unit,
        val onLong: (Row) -> Unit,
        val watch: (refresh: () -> Unit) -> () -> Unit
    )

    private enum class Zone { LIST, TABS, VIEWS }

    private lateinit var content: Content
    private var rows: List<Row> = emptyList()
    private var tabs: List<Tab> = emptyList()
    private var index = -1
    private var tabIndex = 0
    private var zone = Zone.LIST
    private var unwatch: (() -> Unit)? = null
    private val headBox: LinearLayout = add<View>(R.layout.schedule_head).findViewById(R.id.schedule_head)
    private var subView: android.widget.TextView? = null
    private val list = ListView(context)

    init {
        box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContent(makeContent(viewIndex))
    }

    private fun setContent(c: Content) {
        unwatch?.invoke()
        content = c
        headBox.removeAllViews()
        // head() добавляет строку в box — переносим её в headBox
        val sub = head(c.channel, c.title, c.subtitle, icon = c.icon)
        val headRow = box.getChildAt(box.childCount - 1)
        box.removeView(headRow)
        headBox.addView(headRow)
        subView = sub
        val b = c.build()
        rows = emptyList()
        index = -1
        list.scrollY0 = 0f
        setRows(b, b.focusId)
        unwatch = c.watch { refresh() }
    }

    fun refresh() {
        setRows(content.build(), null)
    }

    /** Обновить список, сохранив фокус на той же передаче (id = начало) */
    private fun setRows(b: Built, focusId: Long?) {
        val keep = focusId ?: rows.getOrNull(index)?.id
        rows = b.rows
        tabs = b.tabs
        var i = -1
        if (keep != null) for (k in rows.indices) if (!rows[k].separator && rows[k].id <= keep) i = k
        if (i < 0) i = next(-1, 1)
        index = i
        subView?.text = content.sub()
        list.scrollToFocus(true)
        list.invalidate()
    }

    private fun next(from: Int, dir: Int): Int {
        var i = from + dir
        while (i >= 0 && i < rows.size) {
            if (!rows[i].separator) return i
            i += dir
        }
        return -1
    }

    private fun move(dir: Int) {
        val i = next(index, dir)
        if (i >= 0) {
            index = i
            list.scrollToFocus(false)
            list.invalidate()
        }
    }

    private fun playingDay(): Long? = rows.firstOrNull { it.playing }?.day

    /** Первая передача дня, а если в этом дне есть играющая — она */
    private fun dayTarget(day: Long): Int {
        var first = -1
        for (i in rows.indices) {
            val r = rows[i]
            if (r.separator || r.day != day) continue
            if (r.playing) return i
            if (first < 0) first = i
        }
        return first
    }

    private fun toTabs() {
        if (tabs.isEmpty()) return
        val d = rows.getOrNull(index)?.day
        tabIndex = tabs.indexOfFirst { it.day == d }.coerceAtLeast(0)
        zone = Zone.TABS
        list.invalidate()
    }

    private fun toList() {
        zone = Zone.LIST
        tabs.getOrNull(tabIndex)?.let { t -> dayTarget(t.day).takeIf { it >= 0 }?.let { index = it } }
        list.scrollToFocus(false)
        list.invalidate()
    }

    private fun moveTab(dir: Int) {
        val k = (tabIndex + dir).coerceIn(0, tabs.size - 1)
        if (k == tabIndex) return
        tabIndex = k
        // прокрутить к заголовку дня (он подсвечен), чтобы был виден и сам день
        val sep = rows.indexOfFirst { it.separator && it.day == tabs[k].day }
        if (sep >= 0) list.scrollToRow(sep, false)
        list.invalidate()
    }

    private fun switchView(k: Int) {
        if (k < 0 || k >= views.size || k == viewIndex) return
        viewIndex = k
        setContent(makeContent(k))
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        when (zone) {
            Zone.VIEWS -> when (name) {
                "up" -> switchView(viewIndex - 1)
                "down" -> switchView(viewIndex + 1)
                "right" -> { zone = Zone.LIST; list.invalidate() }
            }
            Zone.TABS -> when (name) {
                "up" -> moveTab(-1)
                "down" -> moveTab(1)
                "left" -> toList()
            }
            Zone.LIST -> when (name) {
                "up" -> move(-1)
                "down" -> move(1)
                "pgup" -> repeat(8) { move(-1) }
                "pgdown" -> repeat(8) { move(1) }
                "right" -> toTabs()
                "left" -> if (views.size > 1) { zone = Zone.VIEWS; list.invalidate() }
            }
        }
    }

    override fun enter() {
        when (zone) {
            Zone.VIEWS -> { zone = Zone.LIST; list.invalidate() }
            Zone.TABS -> toList()
            Zone.LIST -> rows.getOrNull(index)?.let { if (!it.separator) content.onSelect(it) }
        }
    }

    override fun long() {
        if (zone == Zone.LIST) rows.getOrNull(index)?.let { if (!it.separator) content.onLong(it) }
    }

    override fun back() {
        when (zone) {
            Zone.VIEWS -> { zone = Zone.LIST; list.invalidate() }
            Zone.TABS -> toList()
            Zone.LIST -> onBack()
        }
    }

    override fun destroy() {
        unwatch?.invoke()
        unwatch = null
    }

    // ================================================================== отрисовка

    @SuppressLint("ViewConstructor")
    private inner class ListView(context: Context) : View(context) {
        private val em = U.em
        private val rowH = 2.9f * em
        private val richH = 3.8f * em
        private val sepH = 2.6f * em
        private val viewsW get() = if (views.size > 1) 7.5f * em else 0f
        private val tabsW = 7.5f * em
        private val pTime = U.paint(1.05f, U.wa(0.8f))
        private val pTitle = U.paint(1.1f)
        private val pTitleBold = U.paint(1.1f, bold = true)
        private val pSub = U.paint(0.9f, U.wa(0.6f))
        private val pSep = U.paint(1f, U.wa(0.55f), bold = true)
        private val pTab = U.paint(0.95f, U.wa(0.7f))
        private val pTabBold = U.paint(0.95f, bold = true)
        private val pIcon = U.paint(0.95f, U.wa(0.8f))
        private val pLive = U.paint(0.75f, bold = true)
        private val pEmpty = U.paint(1.1f, U.wa(0.6f))
        var scrollY0 = 0f
        private var target = 0f

        private fun h(r: Row) = if (r.separator) sepH else if (r.channel != null || r.sub.isNotEmpty()) richH else rowH

        private fun top(i: Int): Float {
            var y = 0f
            for (k in 0 until i) y += h(rows[k])
            return y
        }

        private fun total(): Float {
            var y = 0f
            for (r in rows) y += h(r)
            return y
        }

        /** Строка [i] — в середину окна */
        fun scrollToRow(i: Int, jump: Boolean) {
            if (i < 0 || height == 0) return
            val t = (top(i) - height / 2f + h(rows[i]) / 2).coerceIn(0f, maxOf(0f, total() - height))
            target = t
            if (jump || !Anim.on) scrollY0 = t
            invalidate()
        }

        fun scrollToFocus(jump: Boolean) {
            if (height == 0) post { scrollToRow(index, true) } else scrollToRow(index, jump)
        }

        override fun onDraw(c: Canvas) {
            // плавная прокрутка к цели
            if (Math.abs(target - scrollY0) > 0.5f) {
                scrollY0 += (target - scrollY0) * 0.35f
                postInvalidateOnAnimation()
            } else scrollY0 = target
            val w = width.toFloat()
            val listL = viewsW
            val listR = w - tabsW
            drawViews(c)
            if (rows.isEmpty()) {
                U.drawText(c, content.empty, listL + 2 * em, 2.5f * em, listR - listL - 4 * em, pEmpty)
            } else {
                val save = Fade.begin(c, listL, 0f, listR, height.toFloat())
                var y = -scrollY0
                val focusDay = if (zone == Zone.TABS) tabs.getOrNull(tabIndex)?.day else null
                for (i in rows.indices) {
                    val r = rows[i]
                    val rh = h(r)
                    if (y + rh >= 0 && y <= height) drawRow(c, r, i, listL + 0.7f * em, listR - 0.7f * em, y, rh, focusDay)
                    y += rh
                    if (y > height) break
                }
                // затемнение: сверху — если прокручено, снизу — если ниже ещё есть строки
                val fl = Fade.length
                Fade.end(c, save, listL, 0f, listR, height.toFloat(),
                    scrollY0 / fl, (total() - height - scrollY0) / fl)
            }
            drawTabs(c, w)
        }

        private fun drawRow(c: Canvas, r: Row, i: Int, l: Float, rr: Float, y: Float, rh: Float, focusDay: Long?) {
            if (r.separator) {
                val f = r.day == focusDay
                if (f) U.roundRect(c, l, y + 0.6f * em, rr, y + rh, 0.5f * em, U.WHITE)
                pSep.color = if (f) U.BLACK else U.wa(0.55f)
                U.drawText(c, r.title, l + 0.8f * em, y + rh - 0.95f * em, rr - l, pSep)
                return
            }
            val focus = i == index && zone == Zone.LIST
            val bg = when {
                focus -> U.WHITE
                r.playing -> U.FILL_MARKED
                else -> 0
            }
            if (bg != 0) U.roundRect(c, l, y, rr, y + rh, 0.5f * em, bg)
            val fg = if (focus) U.BLACK else U.WHITE
            val cy = y + rh / 2
            var x = l + 0.8f * em
            if (r.channel != null) {
                Logos.draw(c, context, r.channel, x, y + 0.6f * em, x + 3.4f * em, y + rh - 0.6f * em, plate = focus) { invalidate() }
                x += 4.2f * em
            }
            pTime.color = if (focus) U.BLACK else U.wa(0.8f)
            c.drawText(r.time, x, cy - (pTime.descent() + pTime.ascent()) / 2, pTime)
            x += 3.6f * em
            if (r.channel == null) {
                pSub.color = if (focus) U.BLACK else U.wa(0.5f)
                c.drawText("›", x, cy - (pSub.descent() + pSub.ascent()) / 2, pSub)
                x += 1.1f * em
            }
            val isz = 1.1f * em
            val nIcons = (if (r.playing) 1 else 0) + (if (r.remind) 1 else 0) + (if (r.archive && !r.live) 1 else 0)
            val liveW = if (r.live) pLive.measureText("LIVE") + 1.2f * em else 0f
            val iw = nIcons * (isz + 0.4f * em) + liveW
            val p = if (r.live || r.playing) pTitleBold else pTitle
            p.color = if (focus) U.BLACK else if (r.past) U.wa(0.75f) else if (r.future) U.wa(0.85f) else U.WHITE
            if (r.sub.isNotEmpty()) {
                U.drawText(c, r.title, x, cy - 0.6f * em, rr - x - iw - 0.8f * em, p)
                pSub.color = if (focus) U.BLACK else U.wa(0.6f)
                U.drawText(c, r.sub, x, cy + 0.75f * em, rr - x - iw - 0.8f * em, pSub)
            } else U.drawText(c, r.title, x, cy, rr - x - iw - 0.8f * em, p)
            var ix = rr - 0.8f * em
            if (r.live) {
                pLive.color = fg
                val lw = pLive.measureText("LIVE")
                ix -= lw + 0.5f * em
                U.fill.style = android.graphics.Paint.Style.STROKE
                U.fill.strokeWidth = 0.12f * em
                U.fill.color = fg
                c.drawRoundRect(ix - 0.1f * em, cy - 0.65f * em, ix + lw + 0.4f * em, cy + 0.65f * em, 0.25f * em, 0.25f * em, U.fill)
                U.fill.style = android.graphics.Paint.Style.FILL
                c.drawText("LIVE", ix + 0.15f * em, cy - (pLive.descent() + pLive.ascent()) / 2, pLive)
                ix -= 0.6f * em
            }
            val ic = if (focus) U.BLACK else U.wa(0.8f)
            if (r.archive && !r.live) { ix -= isz; Icons.draw(c, Icons.Kind.ARCHIVE, ix, cy - isz / 2, isz, ic); ix -= 0.4f * em }
            if (r.remind) { ix -= isz; Icons.draw(c, Icons.Kind.REMIND, ix, cy - isz / 2, isz, ic); ix -= 0.4f * em }
            if (r.playing) {
                pIcon.color = ic
                c.drawText("▶", ix - pIcon.measureText("▶"), cy - (pIcon.descent() + pIcon.ascent()) / 2, pIcon)
            }
        }

        private fun drawTabs(c: Canvas, w: Float) {
            val l = w - tabsW
            U.fill.color = U.LINE
            c.drawRect(l, 0f, l + 0.1f * em, height.toFloat(), U.fill)
            if (tabs.isEmpty()) return
            val th = 3.6f * em
            val playing = playingDay()
            val activeDay = if (zone == Zone.LIST) rows.getOrNull(index)?.day else null
            // вкладка в фокусе (или активная) — ближе к середине колонки
            val center = if (zone == Zone.TABS) tabIndex else tabs.indexOfFirst { it.day == activeDay }.coerceAtLeast(0)
            val totalH = tabs.size * (th + 0.3f * em) + 0.8f * em
            val off = (center * (th + 0.3f * em) - height / 2f + th / 2).coerceIn(0f, maxOf(0f, totalH - height))
            val save = Fade.begin(c, l, 0f, w, height.toFloat())
            for ((k, t) in tabs.withIndex()) {
                val y = 0.4f * em + k * (th + 0.3f * em) - off
                if (y + th < 0 || y > height) continue
                val focus = zone == Zone.TABS && k == tabIndex
                val active = t.day == activeDay
                val bg = when {
                    focus || active -> U.WHITE
                    t.day == playing -> U.FILL_MARKED
                    else -> 0
                }
                if (bg != 0) U.roundRect(c, l + 0.5f * em, y, w - 0.5f * em, y + th, U.px(U.RADIUS_S), bg)
                val p = if (focus || active) pTabBold else pTab
                p.color = if (focus || active) U.BLACK else if (t.day == playing) U.WHITE else U.wa(0.7f)
                val lines = t.label.split('\n')
                lines.forEachIndexed { n, s ->
                    val tw = p.measureText(s)
                    val ly = y + th / 2 + (n - (lines.size - 1) / 2f) * 1.25f * em
                    c.drawText(s, l + (tabsW - tw) / 2, ly - (p.descent() + p.ascent()) / 2, p)
                }
            }
            Fade.end(c, save, l, 0f, w, height.toFloat(), off / Fade.length, (totalH - height - off) / Fade.length)
        }

        private fun drawViews(c: Canvas) {
            if (views.size < 2) return
            U.fill.color = U.LINE
            c.drawRect(viewsW - 0.1f * em, 0f, viewsW, height.toFloat(), U.fill)
            val th = 3.6f * em
            for ((k, icon) in views.withIndex()) {
                val y = 0.4f * em + k * (th + 0.3f * em)
                val open = k == viewIndex
                val focus = open && zone == Zone.VIEWS
                if (open) U.roundRect(c, 0.5f * em, y, viewsW - 0.6f * em, y + th, U.px(U.RADIUS_S), if (focus) U.WHITE else U.FILL_MARKED)
                val sz = 2.8f * em
                Icons.draw(c, icon, (viewsW - 0.1f * em - sz) / 2, y + (th - sz) / 2, sz, if (focus) U.BLACK else if (open) U.WHITE else U.wa(0.7f))
            }
        }

        private fun tap(ex: Float, ey: Float) {
            if (ex < viewsW) {
                val k = ((ey - 0.4f * em) / (3.9f * em)).toInt()
                switchView(k)
                return
            }
            if (ex > width - tabsW) return
            var y = -scrollY0
            for (i in rows.indices) {
                val rh = h(rows[i])
                if (ey >= y && ey < y + rh) {
                    if (!rows[i].separator) {
                        index = i
                        zone = Zone.LIST
                        invalidate()
                        enter()
                    }
                    break
                }
                y += rh
            }
        }

        // тап — выбрать, перетаскивание и колесо — прокрутка списка (фокус не двигается)
        private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                scrollBy(dy)
                return true
            }
        })

        private fun scrollBy(dy: Float) {
            target = (target + dy).coerceIn(0f, maxOf(0f, total() - height))
            scrollY0 = target
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(e)
            scrollBy(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * rowH * 2)
            return true
        }
    }
}
