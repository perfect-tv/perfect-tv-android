package top.rootu.perfecttv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Catalog
import top.rootu.perfecttv.data.Channel

/**
 * ТВ-гид (порт guide/guide.js): категории ‹ ›, список каналов и сетка программы
 * (вид «ТВ-гид») или текущая передача и «далее» (вид «Список»).
 *
 * Всё рисуется одним View без выделения памяти на кадр. Окно сетки — [winStart] и 2 часа,
 * шаг 30 минут. Пока фокус на списке каналов, окно «живое»: winStart = сейчас − 30 мин
 * (линия «сейчас» на 25%), пересчитывается раз в 10 с. В сетке окно замирает и движется фокус.
 */
class GuideLayer(private val tv: Tv) : Layer {

    private val ctx: Context = tv.activity
    private val main = Handler(Looper.getMainLooper())
    private val gv = GuideView(ctx)
    override val view: View get() = gv

    private var groups: List<Catalog.Group> = emptyList()
    private var groupIndex = 0
    private var index = 0
    private var zone = Zone.CHANNELS
    private var grid = Prefs.guideGrid
    private var winStart = 0L
    private var focusTime = 0L
    private var live = true

    private enum class Zone { CHANNELS, GRID }

    private val group get() = groups.getOrNull(groupIndex)
    private val channels get() = group?.channels ?: emptyList()
    private val focused get() = channels.getOrNull(index)

    private val tick = object : Runnable {
        override fun run() {
            if (live) winStart = liveStart()
            gv.invalidate()
            main.postDelayed(this, 10_000)
        }
    }

    private fun liveStart() = Clock.now() - Cells.SLOT

    /** Открыть на канале и категории (zone "grid" — сразу в сетку) */
    fun open(ch: Channel?, groupKey: String?, toGrid: Boolean = false) {
        val cat = tv.catalog ?: return
        groups = cat.groups()
        val g = ch?.let { cat.groupOf(it, groupKey) } ?: cat.group(groupKey) ?: groups.firstOrNull()
        groupIndex = groups.indexOfFirst { it.key == g?.key }.coerceAtLeast(0)
        index = ch?.let { channels.indexOf(it) }?.coerceAtLeast(0) ?: 0
        zone = Zone.CHANNELS
        live = true
        winStart = liveStart()
        grid = Prefs.guideGrid
        if (toGrid && grid) toGrid()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 10_000)
        gv.invalidate()
    }

    /** Список категорий изменился (скрытие, избранное) — оставить фокус на том же канале */
    fun reload() {
        open(focused, group?.key)
    }

    fun setView(gridView: Boolean) {
        grid = gridView
        zone = Zone.CHANNELS
        live = true
        winStart = liveStart()
        gv.invalidate()
    }

    fun refresh() = gv.invalidate()

    fun focusNumber(num: Int) {
        val i = channels.indexOfFirst { it.num == num }
        if (i >= 0) {
            index = i
            gv.invalidate()
            return
        }
        // нет в категории — ищем в остальных
        for ((gi, g) in groups.withIndex()) {
            val j = g.channels.indexOfFirst { it.num == num }
            if (j >= 0) {
                groupIndex = gi
                index = j
                gv.invalidate()
                return
            }
        }
    }

    // ------------------------------------------------------------------ клавиши

    override fun hasLong(name: String) = zone == Zone.CHANNELS && (name == "left" || name == "right")

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return tv.digit(digit)
        if (zone == Zone.GRID) return gridKey(name)
        when (name) {
            "up" -> move(-1)
            "down" -> move(1)
            "pgup" -> move(-pageSize())
            "pgdown" -> move(pageSize())
            "left" -> if (long) focused?.let { tv.openSchedule(it, 0) } else changeGroup(-1)
            "right" -> if (long) { if (grid) toGrid() } else changeGroup(1)
        }
    }

    private fun move(d: Int) {
        val n = channels.size
        if (n == 0) return
        // по одному — по кругу, страницами — до края
        index = if (Math.abs(d) == 1) (index + d + n) % n else (index + d).coerceIn(0, n - 1)
        gv.invalidate()
    }

    private fun changeGroup(d: Int) {
        if (groups.size < 2) return
        val ch = focused
        groupIndex = (groupIndex + d + groups.size) % groups.size
        // тот же канал в новой категории, иначе — начало
        index = ch?.let { channels.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        gv.invalidate()
    }

    private fun pageSize() = maxOf(1, gv.visibleRows() - 1)

    private fun toGrid() {
        zone = Zone.GRID
        live = false
        focusTime = Clock.now()
        gv.invalidate()
    }

    private fun cellsOf(ch: Channel, from: Long, to: Long) = Cells.cells(tv.repo.range(ch, from, to), from, to)

    private fun focusCell(): Cell? = focused?.let { Cells.at(tv.repo.programmes(it), focusTime) }

    private fun gridKey(name: String) {
        val ch = focused ?: return
        val cell = Cells.at(tv.repo.programmes(ch), focusTime)
        when (name) {
            "left" -> {
                // предыдущая передача (или слот); окно сдвигается, если она левее
                focusTime = maxOf(Cells.at(tv.repo.programmes(ch), cell.start - 1).start, Clock.now() - 14 * 86400)
                if (focusTime < winStart) winStart = Math.floorDiv(focusTime, Cells.SLOT) * Cells.SLOT
            }
            "right" -> {
                focusTime = cell.end
                if (focusTime >= winStart + SPAN - Cells.SLOT / 2) winStart = Math.floorDiv(focusTime, Cells.SLOT) * Cells.SLOT - Cells.SLOT
            }
            // канал выше/ниже: время фокуса сохраняется
            "up" -> move(-1)
            "down" -> move(1)
            "pgup" -> move(-pageSize())
            "pgdown" -> move(pageSize())
        }
        gv.invalidate()
    }

    override fun enter() {
        if (tv.numberPending()) return tv.applyNumber()
        val ch = focused ?: return
        if (zone == Zone.GRID) {
            focusCell()?.let { tv.openDetails(ch, it) }
            return
        }
        tv.playChannel(ch, group?.key)
    }

    override fun long() {
        focused?.let { tv.openMenu(it, guide = true) }
    }

    override fun back() {
        if (tv.numberPending()) return tv.cancelNumber()
        if (zone == Zone.GRID) {
            zone = Zone.CHANNELS
            live = true
            winStart = liveStart()
            gv.invalidate()
            return
        }
        tv.closeGuide()
    }

    override fun media(name: String) {
        if (name == "guide") tv.closeGuide() else if (name == "menu") long()
    }

    override fun destroy() {
        main.removeCallbacks(tick)
    }

    /** Канал в фокусе и категория (для меню и возврата) */
    fun focusedChannel() = focused
    fun groupKey() = group?.key

    // ================================================================== отрисовка

    @SuppressLint("ViewConstructor")
    private inner class GuideView(context: Context) : View(context) {
        private val em = U.em
        private val pTitle = U.paint(1.7f, bold = true)
        private val pChannel = U.paint(1.05f, U.wa(0.7f))
        private val pTime = U.paint(1f, U.wa(0.8f))
        private val pDesc = TextPaint(U.paint(1f, U.wa(0.6f)))
        private val pClock = U.paint(1.6f, U.wa(0.9f))
        private val pCat = U.paint(1.25f, bold = true)
        private val pCatArrow = U.paint(1.3f, U.wa(0.5f))
        private val pCount = U.paint(0.8f, U.wa(0.5f))
        private val pTick = U.paint(0.95f, U.wa(0.7f))
        private val pNum = U.paint(0.95f, U.wa(0.5f))
        private val pName = U.paint(1.15f)
        private val pNameBold = U.paint(1.15f, bold = true)
        private val pCell = U.paint(1.05f)
        private val pCellTime = U.paint(0.85f, U.wa(0.55f))
        private val pEmpty = U.paint(1.2f, U.wa(0.6f))
        private val line = Paint().apply { color = U.wa(0.25f); strokeWidth = 0.1f * em }
        private val nowLine = Paint().apply { color = U.wa(0.7f); strokeWidth = 0.12f * em }
        private var gradient: LinearGradient? = null
        private val bgPaint = Paint()
        private var descLayout: StaticLayout? = null
        private var descKey = ""

        private val padX get() = 2f * em
        private val padTop get() = 1.5f * em
        private val topH get() = 7.6f * em
        private val headH get() = 2.6f * em
        private val rowH get() = 3.2f * em
        private val gap get() = 0.25f * em
        private val bodyTop get() = padTop + topH + em + headH
        private val chW get() = (width - 2 * padX) * (if (grid) 0.30f else 0.45f)

        fun visibleRows() = maxOf(1, ((height - bodyTop - em) / (rowH + gap)).toInt())

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            gradient = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(U.GUIDE_TOP, android.graphics.Color.argb(220, 12, 13, 14), U.GUIDE_BOTTOM),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            bgPaint.shader = gradient
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            val now = Clock.now()
            if (live) winStart = liveStart()
            drawTop(c, now)
            drawHead(c, now)
            val list = channels
            if (list.isEmpty()) {
                U.drawText(c, if (tv.catalog?.channels?.isEmpty() != false) "Каналы загружаются…" else "Нет каналов", padX + em, bodyTop + 2 * em, width - 2 * padX, pEmpty)
                return
            }
            val rows = visibleRows()
            // фокус — по центру списка, у краёв — без пустоты
            var first = index - rows / 2
            first = first.coerceIn(0, maxOf(0, list.size - rows))
            val gridL = padX + chW + gap
            val gridR = width - padX
            for (r in 0 until rows) {
                val i = first + r
                if (i >= list.size) break
                val y = bodyTop + r * (rowH + gap)
                drawRow(c, list[i], i == index, y, gridL, gridR, now)
            }
            if (grid) {
                val x = gridL + (now - winStart).toFloat() / SPAN * (gridR - gridL)
                if (x in gridL..gridR) c.drawLine(x, bodyTop - 0.3f * em, x, bodyTop + rows * (rowH + gap), nowLine)
            } else drawSide(c, now)
        }

        private fun drawTop(c: Canvas, now: Long) {
            val ch = focused ?: return
            val t = padTop
            val logoW = 9f * em
            U.roundRect(c, padX, t, padX + logoW, t + 6.2f * em, U.px(U.RADIUS), U.FILL_SOFT)
            Logos.draw(c, context, ch, padX + 0.6f * em, t + 0.6f * em, padX + logoW - 0.6f * em, t + 5.6f * em) { invalidate() }
            val x = padX + logoW + 1.2f * em
            val clockW = pClock.measureText("00:00") + em
            val w = width - padX - x - clockW
            val ref = if (zone == Zone.GRID) focusTime else now
            val cell = Cells.at(tv.repo.programmes(ch), ref)
            val p = cell.program
            U.drawText(c, "${ch.num}. ${ch.name}", x, t + 0.7f * em, w, pChannel)
            U.drawText(c, p?.title ?: "Нет программы", x, t + 2.3f * em, w, pTitle)
            U.drawText(c, Fmt.dayTimeRange(cell.start, cell.end), x, t + 3.9f * em, w, pTime)
            val desc = p?.desc ?: ""
            if (desc.isNotEmpty()) {
                val key = desc + w.toInt()
                if (key != descKey) {
                    descKey = key
                    descLayout = StaticLayout.Builder.obtain(desc, 0, desc.length, pDesc, w.toInt())
                        .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                }
                c.save()
                c.translate(x, t + 4.6f * em)
                descLayout?.draw(c)
                c.restore()
            }
            val clock = Fmt.hhmm(now)
            c.drawText(clock, width - padX - pClock.measureText(clock), t + 1.4f * em, pClock)
            if (tv.repo.epgLoading()) {
                val s = "Программа загружается…"
                c.drawText(s, width - padX - pCount.measureText(s), t + 2.8f * em, pCount)
            }
        }

        private fun drawHead(c: Canvas, now: Long) {
            val y = padTop + topH + em
            val cy = y + headH / 2
            val g = group
            val arrowW = 1.6f * em
            val l = padX
            val r = padX + chW - em
            if (groups.size > 1) {
                c.drawText("‹", l, cy - (pCatArrow.descent() + pCatArrow.ascent()) / 2, pCatArrow)
                c.drawText("›", r - pCatArrow.measureText("›"), cy - (pCatArrow.descent() + pCatArrow.ascent()) / 2, pCatArrow)
            }
            val title = g?.title?.ifEmpty { "—" } ?: "—"
            val count = " " + (g?.channels?.size ?: 0)
            val cw = pCount.measureText(count)
            val tw = minOf(pCat.measureText(title), r - l - 2 * arrowW - cw)
            val tx = (l + r - tw - cw) / 2
            U.drawText(c, if (g?.favorites == true) "♥ $title" else title, tx, cy, tw, pCat)
            c.drawText(count, tx + tw, cy - (pCount.descent() + pCount.ascent()) / 2, pCount)
            if (!grid) return
            val gl = padX + chW + gap
            val gr = width - padX
            var t = Math.floorDiv(winStart, Cells.SLOT) * Cells.SLOT
            while (t < winStart + SPAN) {
                if (t >= winStart) {
                    val x = gl + (t - winStart).toFloat() / SPAN * (gr - gl)
                    c.drawLine(x, y + 0.6f * em, x, y + headH, line)
                    c.drawText(Fmt.hhmm(t), x + 0.4f * em, y + 1.6f * em, pTick)
                }
                t += Cells.SLOT
            }
            val day = Fmt.dayLabel(maxOf(winStart, if (zone == Zone.GRID) focusTime else now))
            c.drawText(day, gr - pCount.measureText(day), y + 0.2f * em, pCount)
        }

        private fun drawRow(c: Canvas, ch: Channel, focus: Boolean, y: Float, gl: Float, gr: Float, now: Long) {
            val l = padX
            val r = padX + chW
            val chFocus = focus && zone == Zone.CHANNELS
            val bg = if (chFocus) U.WHITE else if (focus) U.FILL_ACTIVE else U.FILL
            U.roundRect(c, l, y, r, y + rowH, U.px(U.RADIUS_S), bg)
            val fg = if (chFocus) U.BLACK else U.WHITE
            val cy = y + rowH / 2
            var x = l + 0.8f * em
            Logos.draw(c, context, ch, x, y + 0.4f * em, x + 3.4f * em, y + rowH - 0.4f * em, plate = chFocus) { invalidate() }
            x += 3.4f * em + 0.7f * em
            pNum.color = if (chFocus) U.BLACK else U.wa(0.5f)
            c.drawText(ch.num.toString(), x, cy - (pNum.descent() + pNum.ascent()) / 2, pNum)
            x += 2.4f * em
            val current = ch === tv.state.channel
            val pn = if (current) pNameBold else pName
            pn.color = fg
            val fav = tv.catalog?.isFavorite(ch) == true
            val locked = tv.catalog?.isLocked(ch) == true
            val isz = 1f * em
            val iw = ((if (fav) 1 else 0) + (if (ch.hasArchive) 1 else 0) + (if (locked) 1 else 0)) * (isz + 0.35f * em)
            if (grid) {
                U.drawText(c, ch.name, x, cy, r - x - 0.8f * em - iw, pn)
            } else {
                // вид «Список»: название и текущая передача с прогрессом
                U.drawText(c, ch.name, x, y + 1.05f * em, r - x - 0.8f * em - iw, pn)
                val p = tv.repo.at(ch, now)
                pCellTime.color = if (chFocus) U.BLACK else U.wa(0.6f)
                U.drawText(c, p?.title ?: "Нет программы", x, y + 2.05f * em, r - x - 0.8f * em, pCellTime)
                if (p != null) {
                    val bw = r - x - 0.8f * em
                    U.roundRect(c, x, y + 2.6f * em, x + bw, y + 2.8f * em, 0.1f * em, if (chFocus) 0x33000000 else U.FILL_MARKED)
                    U.roundRect(c, x, y + 2.6f * em, x + bw * Fmt.progress(p.start, p.stop, now), y + 2.8f * em, 0.1f * em, if (chFocus) U.BLACK else U.WHITE)
                }
            }
            val ic = if (chFocus) U.BLACK else U.wa(0.7f)
            var ix = r - 0.8f * em
            if (ch.hasArchive) { ix -= isz; Icons.draw(c, Icons.Kind.ARCHIVE, ix, cy - isz / 2, isz, ic); ix -= 0.35f * em }
            if (fav) { ix -= isz; Icons.draw(c, Icons.Kind.FAV, ix, cy - isz / 2, isz, ic); ix -= 0.35f * em }
            if (locked) { ix -= isz; Icons.draw(c, Icons.Kind.LOCK, ix, cy - isz / 2, isz, ic) }
            if (!grid) return
            // сетка: ячейки окна [winStart, winStart + SPAN)
            val from = winStart
            val to = winStart + SPAN
            val k = (gr - gl) / SPAN
            val fc = if (focus && zone == Zone.GRID) Cells.at(tv.repo.programmes(ch), focusTime) else null
            for (cell in cellsOf(ch, from, to)) {
                val cl = gl + (maxOf(cell.start, from) - from) * k
                val cr = gl + (minOf(cell.end, to) - from) * k - gap
                if (cr - cl < 2) continue
                val isFocus = fc != null && fc.start == cell.start
                val isNow = cell.start <= now && now < cell.end
                val color = when {
                    isFocus -> U.WHITE
                    cell.program == null -> U.wa(0.04f)
                    isNow -> U.FILL_NOW
                    else -> U.FILL_CELL
                }
                U.roundRect(c, cl, y, cr, y + rowH, U.px(U.RADIUS_S), color)
                val tl = cl + 0.7f * em
                var tw = cr - tl - 0.5f * em
                // запланированное напоминание — значок часов в ячейке
                if (cell.program != null && cell.start > now && tv.reminders.has(ch, cell.start) && tw > 2 * em) {
                    val isz = 0.9f * em
                    Icons.draw(c, Icons.Kind.REMIND, cr - 0.5f * em - isz, cy - isz / 2, isz, if (isFocus) U.BLACK else U.wa(0.85f))
                    tw -= isz + 0.4f * em
                }
                if (tw > em) {
                    pCell.color = if (isFocus) U.BLACK else if (cell.end <= now) U.wa(0.6f) else U.WHITE
                    U.drawText(c, cell.title.ifEmpty { if (isFocus) Fmt.timeRange(cell.start, cell.end) else "" }, tl, cy, tw, pCell)
                }
            }
        }

        /** Вид «Список»: справа — программа канала в фокусе, что дальше */
        private fun drawSide(c: Canvas, now: Long) {
            val ch = focused ?: return
            val l = padX + chW + 1.5f * em
            val r = width - padX
            var y = bodyTop
            val list = tv.repo.programmes(ch).filter { it.stop > now }.take(visibleRows() + 2)
            if (list.isEmpty()) {
                U.drawText(c, "Нет программы", l, y + rowH / 2, r - l, pEmpty)
                return
            }
            for (p in list) {
                if (y + 1.8f * em > height) break
                val isNow = p.start <= now
                pCellTime.color = U.wa(0.6f)
                c.drawText(Fmt.hhmm(p.start), l, y + 1.2f * em, pCellTime)
                val pn = if (isNow) pNameBold else pName
                pn.color = U.WHITE
                U.drawText(c, p.title, l + 4f * em, y + 0.9f * em, r - l - 4f * em, pn)
                y += 2.2f * em
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            val y = e.y
            if (y < bodyTop) return true
            val rows = visibleRows()
            var first = index - rows / 2
            first = first.coerceIn(0, maxOf(0, channels.size - rows))
            val i = first + ((y - bodyTop) / (rowH + gap)).toInt()
            if (i !in channels.indices) return true
            index = i
            if (e.x < padX + chW || !grid) {
                zone = Zone.CHANNELS
                enter()
            } else {
                zone = Zone.GRID
                live = false
                val gl = padX + chW + gap
                focusTime = winStart + ((e.x - gl) / (width - padX - gl) * SPAN).toLong()
                enter()
            }
            invalidate()
            return true
        }
    }

    companion object {
        /** Ширина окна сетки, с */
        const val SPAN = 7200L
    }
}
