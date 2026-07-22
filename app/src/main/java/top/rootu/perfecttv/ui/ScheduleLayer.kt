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
 * Окно «Программа и архив» / «Напоминания» (виртуальный список на Canvas). Слева — вкладки дат
 * (←), в центре передачи по дням, справа — панель передачи в фокусе (время, название, описание,
 * что сделает OK). Вкладки видов «Программа» / «Напоминания · N» — кнопками в шапке окна справа
 * (view_pill.xml), фокус на них — → из списка; если вид один — без кнопок.
 *
 * Подсветка: белая заливка — только фокус (строка, вкладка даты или вида); рамкой — то, куда
 * вернётся фокус: дата строки, пока фокус в списке, и передача дня, пока фокус на датах; серым —
 * играющая передача и её дата, открытый вид.
 */
class ScheduleLayer(
    context: Context,
    private val views: List<String>,          // подписи вкладок видов (пусто или одна — без кнопок)
    private var viewIndex: Int,
    private val viewLabel: (Int) -> String,   // подпись со счётчиком («Напоминания · 2»), обновляется с содержимым
    private val makeContent: (Int) -> Content,
    private val onBack: () -> Unit
) : PopupLayer(context, 88f, 0.86f) {

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
        val payload: Any? = null,
        val desc: String = "",            // панель справа: описание,
        val range: String = "",           // «Сегодня, 00:05 – 00:30»
        val action: String = "",          // и что сделает OK
        val progress: Float = -1f         // идущая передача: доля 0..1
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
    private val pills = ArrayList<android.widget.TextView>()
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
        // вкладки видов — кнопками в шапке справа
        pills.clear()
        if (views.size > 1) for (k in views.indices) {
            val pill = inflater.inflate(R.layout.view_pill, headRow as LinearLayout, false) as android.widget.TextView
            pill.setOnClickListener { zone = Zone.LIST; switchView(k); applyPills() }
            headRow.addView(pill)
            pills.add(pill)
        }
        applyPills()
        val b = c.build()
        rows = emptyList()
        index = -1
        list.scrollY0 = 0f
        setRows(b, b.focusId)
        unwatch = c.watch { refresh() }
    }

    fun refresh() {
        setRows(content.build(), null)
        applyPills()
    }

    /** Кнопки видов: открытая — серая, в фокусе (зона VIEWS) — белая; подписи со счётчиками */
    private fun applyPills() {
        pills.forEachIndexed { k, p ->
            p.text = viewLabel(k)
            p.isActivated = k == viewIndex
            p.isSelected = k == viewIndex && zone == Zone.VIEWS
        }
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
        // у напоминаний счётчик уже стоит на кнопке вида — справа в шапке его не повторять
        subView?.text = if (pills.isNotEmpty() && content.channel == null) "" else content.sub()
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
        // прокрутить к передаче, на которую перейдёт фокус (она в рамке); если это первая
        // передача дня — к заголовку дня, чтобы был виден и он
        val t = dayTarget(tabs[k].day)
        val sep = rows.indexOfFirst { it.separator && it.day == tabs[k].day }
        if (t >= 0 && rows[t].playing) list.scrollToRow(t, false) else if (sep >= 0) list.scrollToRow(sep, false)
        list.invalidate()
    }

    private fun switchView(k: Int) {
        if (k < 0 || k >= views.size || k == viewIndex) return
        viewIndex = k
        setContent(makeContent(k))
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        when (zone) {
            // кнопки видов в шапке: ←/→ — соседняя (вид меняется сразу), с первой ← и ↓ — в список
            Zone.VIEWS -> when (name) {
                "right" -> switchView(viewIndex + 1)
                "left" -> if (viewIndex > 0) switchView(viewIndex - 1) else { zone = Zone.LIST; list.invalidate() }
                "down" -> { zone = Zone.LIST; list.invalidate() }
            }
            // даты — слева: → обратно в список
            Zone.TABS -> when (name) {
                "up" -> moveTab(-1)
                "down" -> moveTab(1)
                "right" -> toList()
            }
            Zone.LIST -> when (name) {
                "up" -> move(-1)
                "down" -> move(1)
                "pgup" -> repeat(8) { move(-1) }
                "pgdown" -> repeat(8) { move(1) }
                "left" -> toTabs()
                "right" -> if (views.size > 1) { zone = Zone.VIEWS; list.invalidate() }
            }
        }
        applyPills()
    }

    override fun enter() {
        when (zone) {
            Zone.VIEWS -> { zone = Zone.LIST; list.invalidate(); applyPills() }
            Zone.TABS -> toList()
            Zone.LIST -> rows.getOrNull(index)?.let { if (!it.separator) content.onSelect(it) }
        }
    }

    override fun long() {
        if (zone == Zone.LIST) rows.getOrNull(index)?.let { if (!it.separator) content.onLong(it) }
    }

    override fun back() {
        when (zone) {
            Zone.VIEWS -> { zone = Zone.LIST; list.invalidate(); applyPills() }
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
        private val rowH = 3.2f * em
        private val richH = 4.6f * em
        private val sepH = 2.9f * em
        private val viewsW get() = 0f   // вкладки видов — в шапке окна, колонки справа нет
        private val tabsW = 8.5f * em
        private val detailW get() = minOf(30f * em, width * 0.36f)
        private val pDetailTitle = android.text.TextPaint(U.paint(1.6f, bold = true))
        private val pDetailText = android.text.TextPaint(U.paint(1.25f, U.wa(0.8f)))

        // время, название и значки строки — одного роста и одной яркости
        private val pTime = U.paint(1.45f)
        private val pTitle = U.paint(1.45f)
        private val pTitleBold = U.paint(1.45f, bold = true)
        private val pSub = U.paint(1.2f, U.wa(0.6f))
        private val pSep = U.paint(1.15f, U.wa(0.55f), bold = true)
        private val pTab = U.paint(1.25f, U.wa(0.7f))
        private val pTabBold = U.paint(1.25f, bold = true)
        private val pRange = U.paint(1.4f, bold = true)
        private val pLive = U.paint(1.2f, bold = true)
        private val pEmpty = U.paint(1.3f, U.wa(0.6f))
        var scrollY0 = 0f
        private var target = 0f
        // рамка фокуса едет вместе с прокруткой (тем же шагом), а не прыгает на строку вперёд:
        // верх и высота в координатах списка
        private var focusY = 0f
        private var focusH = 0f
        private var focusSet = false

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
            if (jump || !Anim.on) focusSet = false
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
            // рамка фокуса — к своей строке тем же шагом, что и прокрутка
            val fi = if (zone == Zone.LIST && index in rows.indices && !rows[index].separator) index else -1
            if (fi >= 0) {
                val ty = top(fi)
                val th = h(rows[fi])
                if (!focusSet || !Anim.on || (Math.abs(ty - focusY) < 0.5f && Math.abs(th - focusH) < 0.5f)) {
                    focusY = ty; focusH = th; focusSet = true
                } else {
                    focusY += (ty - focusY) * 0.35f
                    focusH += (th - focusH) * 0.35f
                    postInvalidateOnAnimation()
                }
            } else focusSet = false
            val w = width.toFloat()
            val listL = tabsW
            val listR = w - viewsW - detailW
            if (rows.isEmpty()) {
                // пусто — сообщение на всю ширину, без панели передачи
                U.drawText(c, content.empty, listL + 2 * em, 2.5f * em, w - viewsW - listL - 4 * em, pEmpty)
            } else {
                val save = Fade.begin(c, listL, 0f, listR, height.toFloat())
                var y = -scrollY0
                val focusDay: Long? = null
                // фокус на датах: рамкой — передача, на которую он перейдёт по →
                val frame = if (zone == Zone.TABS) tabs.getOrNull(tabIndex)?.let { dayTarget(it.day) } ?: -1 else -1
                val rl = listL + 0.7f * em
                val rr = listR - 0.7f * em
                for (i in rows.indices) {
                    val r = rows[i]
                    val rh = h(r)
                    if (y + rh >= 0 && y <= height) drawRow(c, r, rl, rr, y, rh, focusDay, false)
                    if (i == frame) {
                        val sw = 0.15f * em
                        U.fill.style = android.graphics.Paint.Style.STROKE
                        U.fill.strokeWidth = sw
                        U.fill.color = U.wa(0.8f)
                        c.drawRoundRect(rl + sw / 2, y + sw / 2, rr - sw / 2, y + rh - sw / 2, 0.5f * em, 0.5f * em, U.fill)
                        U.fill.style = android.graphics.Paint.Style.FILL
                    }
                    y += rh
                    if (y > height) break
                }
                // рамка фокуса и поверх неё — те же строки чёрным, обрезанные по рамке
                if (fi >= 0) {
                    val ft = focusY - scrollY0
                    U.roundRect(c, rl, ft, rr, ft + focusH, 0.5f * em, U.WHITE)
                    val clip = c.save()
                    c.clipRect(rl, ft, rr, ft + focusH)
                    y = -scrollY0
                    for (i in rows.indices) {
                        val r = rows[i]
                        val rh = h(r)
                        if (!r.separator && y + rh > ft && y < ft + focusH) drawRow(c, r, rl, rr, y, rh, focusDay, true)
                        y += rh
                        if (y > ft + focusH) break
                    }
                    c.restoreToCount(clip)
                }
                // затемнение: сверху — если прокручено, снизу — если ниже ещё есть строки
                val fl = Fade.length
                Fade.end(c, save, listL, 0f, listR, height.toFloat(),
                    scrollY0 / fl, (total() - height - scrollY0) / fl)
            }
            if (rows.isNotEmpty()) drawDetail(c, listR, w - viewsW)
            drawTabs(c)
        }

        // панель передачи в фокусе: раскладка текста пересобирается только при смене строки
        private var detailRow: Row? = null
        private var detailTitle: android.text.StaticLayout? = null
        private var detailText: android.text.StaticLayout? = null

        private fun layout(s: String, p: android.text.TextPaint, w: Int, lines: Int) =
            android.text.StaticLayout.Builder.obtain(s, 0, s.length, p, w).setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, 1.15f).build()

        /** Справа от списка: время, название, описание и подсказка «OK — …» для строки в фокусе */
        private fun drawDetail(c: Canvas, l: Float, r: Float) {
            U.fill.color = U.LINE
            c.drawRect(l, 0f, l + 0.1f * em, height.toFloat(), U.fill)
            // фокус на датах — в списке подсвечен заголовок дня, а не передача: панель пустая
            if (zone == Zone.TABS) return
            val row = rows.getOrNull(index)?.takeIf { !it.separator } ?: return
            val x = l + 1.6f * em
            val w = (r - x - 1.4f * em).toInt()
            if (w <= 0) return
            if (row !== detailRow) {
                detailRow = row
                // без программы название строки — само время: в панели его не повторять
                detailTitle = if (row.range.endsWith(row.title)) null else layout(row.title, pDetailTitle, w, 3)
                val maxLines = maxOf(2, ((height - 12f * em) / (pDetailText.textSize * 1.3f)).toInt())
                detailText = if (row.desc.isEmpty()) null else layout(row.desc, pDetailText, w, maxLines)
            }
            var y = 1.4f * em
            if (row.range.isNotEmpty()) {
                pRange.color = if (row.archive && !row.live) U.ARCHIVE else if (row.live) U.LIVE else U.wa(0.75f)
                U.drawText(c, row.range, x, y + 0.7f * em, w.toFloat(), pRange)
                y += 2.3f * em
            }
            detailTitle?.let { c.save(); c.translate(x, y); it.draw(c); c.restore(); y += it.height + 0.9f * em }
            detailText?.let { c.save(); c.translate(x, y); it.draw(c); c.restore() }
            if (row.action.isNotEmpty()) {
                // клавиша в рамке и действие — как в строке подсказок
                val cy = height - 1.7f * em
                val kw = pTab.measureText("OK") + em
                U.fill.style = android.graphics.Paint.Style.STROKE
                U.fill.strokeWidth = 0.1f * em
                U.fill.color = U.wa(0.4f)
                c.drawRoundRect(x, cy - 0.85f * em, x + kw, cy + 0.85f * em, 0.4f * em, 0.4f * em, U.fill)
                U.fill.style = android.graphics.Paint.Style.FILL
                pTab.color = U.wa(0.8f)
                c.drawText("OK", x + 0.5f * em, cy - (pTab.descent() + pTab.ascent()) / 2, pTab)
                pSub.color = U.wa(0.6f)
                c.drawText(row.action, x + kw + 0.6f * em, cy - (pSub.descent() + pSub.ascent()) / 2, pSub)
            }
        }

        /** [focus] — строка под белой рамкой фокуса (чёрным, без своего фона) */
        private fun drawRow(c: Canvas, r: Row, l: Float, rr: Float, y: Float, rh: Float, focusDay: Long?, focus: Boolean) {
            if (r.separator) {
                val f = r.day == focusDay
                if (f) U.roundRect(c, l, y + 0.6f * em, rr, y + rh, 0.5f * em, U.FILL_MARKED)
                pSep.color = if (f) U.WHITE else U.wa(0.55f)
                U.drawText(c, r.title, l + 0.8f * em, y + rh - 0.95f * em, rr - l, pSep)
                return
            }
            if (!focus && r.playing) U.roundRect(c, l, y, rr, y + rh, 0.5f * em, U.FILL_MARKED)
            val fg = if (focus) U.BLACK else U.WHITE
            val cy = y + rh / 2
            var x = l + 0.8f * em
            if (r.channel != null) {
                Logos.draw(c, context, r.channel, x, y + 0.6f * em, x + 3.4f * em, y + rh - 0.6f * em, plate = focus) { invalidate() }
                x += 4.2f * em
            }
            val color = if (focus) U.BLACK else if (r.past) U.wa(0.75f) else if (r.future) U.wa(0.85f) else U.WHITE
            pTime.color = color
            c.drawText(r.time, x, cy - (pTime.descent() + pTime.ascent()) / 2, pTime)
            x += 4.9f * em
            if (r.channel == null) {
                pSub.color = if (focus) U.BLACK else U.wa(0.5f)
                c.drawText("›", x, cy - (pSub.descent() + pSub.ascent()) / 2, pSub)
                x += 1.1f * em
            }
            val isz = 1.7f * em
            val nIcons = (if (r.playing) 1 else 0) + (if (r.remind) 1 else 0) + (if (r.archive && !r.live) 1 else 0)
            val liveW = if (r.live) pLive.measureText("LIVE") + 1.4f * em else 0f
            val iw = nIcons * (isz + 0.5f * em) + liveW + (if (r.playing && r.live) 0.3f * em else 0f)
            val p = if (r.live || r.playing) pTitleBold else pTitle
            p.color = color
            if (r.sub.isNotEmpty()) {
                U.drawText(c, r.title, x, cy - 0.85f * em, rr - x - iw - 0.8f * em, p)
                pSub.color = if (focus) U.BLACK else U.wa(0.6f)
                U.drawText(c, r.sub, x, cy + 1f * em, rr - x - iw - 0.8f * em, pSub)
            } else U.drawText(c, r.title, x, cy, rr - x - iw - 0.8f * em, p)
            // идущая передача: тонкая шкала под названием (в эфире — красная)
            if (r.progress >= 0f) {
                val bw = rr - x - iw - 0.8f * em
                val by = y + rh - 0.45f * em
                U.roundRect(c, x, by, x + bw, by + 0.2f * em, 0.1f * em, if (focus) 0x33000000 else U.FILL_MARKED)
                U.roundRect(c, x, by, x + bw * r.progress, by + 0.2f * em, 0.1f * em, if (focus) U.BLACK else U.LIVE)
            }
            var ix = rr - 0.8f * em
            if (r.live) {
                pLive.color = fg
                val lw = pLive.measureText("LIVE")
                ix -= lw + 0.5f * em
                U.fill.style = android.graphics.Paint.Style.STROKE
                U.fill.strokeWidth = 0.12f * em
                U.fill.color = fg
                c.drawRoundRect(ix - 0.15f * em, cy - 0.7f * em, ix + lw + 0.45f * em, cy + 0.7f * em, 0.25f * em, 0.25f * em, U.fill)
                U.fill.style = android.graphics.Paint.Style.FILL
                c.drawText("LIVE", ix + 0.15f * em, cy - (pLive.descent() + pLive.ascent()) / 2, pLive)
                ix -= 0.6f * em
            }
            val ic = color
            // архив и напоминание — янтарные (цвет «не эфир»), в белой строке фокуса — чёрные
            val ia = if (focus) U.BLACK else U.ARCHIVE
            if (r.archive && !r.live) { ix -= isz; Icons.draw(c, Icons.Kind.ARCHIVE, ix, cy - isz / 2, isz, ia); ix -= 0.5f * em }
            if (r.remind) { ix -= isz; Icons.draw(c, Icons.Kind.REMIND, ix, cy - isz / 2, isz, ia); ix -= 0.5f * em }
            if (r.playing) {
                // треугольник занимает 14/24 высоты значка: рисуем крупнее, чтобы он был ростом с рамку LIVE
                val ps = 2.4f * em
                Icons.draw(c, Icons.Kind.PLAY, ix - ps * 19 / 24, cy - ps / 2, ps, ic)
            }
        }

        /** Вкладки дат — колонка слева */
        private fun drawTabs(c: Canvas) {
            val l = 0f
            val w = tabsW
            U.fill.color = U.LINE
            c.drawRect(w - 0.1f * em, 0f, w, height.toFloat(), U.fill)
            if (tabs.isEmpty()) return
            val th = 3.9f * em
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
                // фокус — белая заливка; день строки в фокусе списка — только рамка (фокус не здесь)
                val bg = when {
                    focus -> U.WHITE
                    t.day == playing -> U.FILL_MARKED
                    else -> 0
                }
                if (bg != 0) U.roundRect(c, l + 0.5f * em, y, w - 0.6f * em, y + th, U.px(U.RADIUS_S), bg)
                if (active) {
                    val sw = 0.15f * em
                    U.fill.style = android.graphics.Paint.Style.STROKE
                    U.fill.strokeWidth = sw
                    U.fill.color = U.wa(0.8f)
                    c.drawRoundRect(l + 0.5f * em + sw / 2, y + sw / 2, w - 0.6f * em - sw / 2, y + th - sw / 2, U.px(U.RADIUS_S), U.px(U.RADIUS_S), U.fill)
                    U.fill.style = android.graphics.Paint.Style.FILL
                }
                val p = if (focus || active) pTabBold else pTab
                p.color = if (focus) U.BLACK else if (active || t.day == playing) U.WHITE else U.wa(0.7f)
                val lines = t.label.split('\n')
                lines.forEachIndexed { n, s ->
                    val tw = p.measureText(s)
                    val ly = y + th / 2 + (n - (lines.size - 1) / 2f) * 1.55f * em
                    c.drawText(s, l + (tabsW - tw) / 2, ly - (p.descent() + p.ascent()) / 2, p)
                }
            }
            Fade.end(c, save, l, 0f, w, height.toFloat(), off / Fade.length, (totalH - height - off) / Fade.length)
        }

        private fun tap(ex: Float, ey: Float) {
            if (ex < tabsW || ex > width - viewsW - detailW) return
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
