package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
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
import top.rootu.perfecttv.data.Playlist
import top.rootu.perfecttv.data.Programme

/**
 * ТВ-гид: список каналов и сетка программы (вид «ТВ-гид») или текущая передача и «далее»
 * (вид «Список»). ← из списка каналов — колонка категорий: выезжает слева и сдвигает список
 * и сетку вправо, за край экрана (шапка с передачей и часами стоит); → — фокус в сетку.
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

    private enum class Zone { PLAYLISTS, CATS, CHANNELS, GRID, PROGRAMS, DATES }

    // колонка категорий: куда вернуться по «Назад» (отмена выбора)
    private var catsFromGroup = 0
    private var catsFromChannel: Channel? = null

    private val group get() = groups.getOrNull(groupIndex)
    private val channels get() = group?.channels ?: emptyList()
    private val focused get() = channels.getOrNull(index)

    /** Описание в гиде (настройка): только в виде «Список» */
    private val descOn get() = Prefs.guideDesc && !grid

    // ---------------------------------------------------------- вид «Список»: программа канала в фокусе
    // три колонки: каналы · программа (идущая — со шкалой) · компактные даты; фокус ходит по ←/→

    private var plCh: Channel? = null          // канал, для которого собраны передачи
    private var plLast: Channel? = null        // тот же канал до обновления программы (сохранить фокус)
    private var plRows: List<Programme> = emptyList()
    private var plIndex = 0
    private var plKeep = -1L                   // начало передачи в фокусе на момент обновления программы
    private var plJump = true                  // канал сменился — прокрутка без анимации
    private var plDays: List<Long> = emptyList()
    private var plDayLabels: List<Pair<String, String>> = emptyList()
    private var dateIndex = 0

    private fun dayOf(i: Int) = Fmt.dayIndex(plRows[i].start)

    /** Индекс идущей передачи (нет — ближайшая следующая, нет — последняя) */
    private fun liveIndex(): Int {
        val now = Clock.now()
        var next = -1
        for (i in plRows.indices) {
            val p = plRows[i]
            if (p.start <= now && now < p.stop) return i
            if (next < 0 && p.start > now) next = i
        }
        return if (next >= 0) next else maxOf(0, plRows.size - 1)
    }

    /**
     * Передачи канала в фокусе — от начала архива до недели вперёд, как в окне «Программа». Пересобираются
     * при смене канала и после обновления программы (фокус остаётся на той же передаче).
     */
    private fun ensureRows() {
        val ch = focused
        if (ch == null) {
            plCh = null; plLast = null
            plRows = emptyList(); plDays = emptyList(); plDayLabels = emptyList()
            return
        }
        if (ch === plCh) return
        val keep = if (ch === plLast) plKeep else -1L
        plKeep = -1L
        val now = Clock.now()
        val from = Fmt.dayStart(now - maxOf(ch.archiveDays, 1) * 86400L)
        val to = Fmt.dayStart(now) + 8 * 86400L
        plRows = tv.repo.range(ch, from, to).filter { it.start >= from }
        plCh = ch
        plLast = ch
        val days = ArrayList<Long>()
        val labels = ArrayList<Pair<String, String>>()
        for (p in plRows) {
            val d = Fmt.dayIndex(p.start)
            if (days.isEmpty() || days.last() != d) {
                days.add(d)
                val t = Fmt.dayTabShort(p.start).split('\n')
                labels.add(t[0] to t.getOrElse(1) { "" })
            }
        }
        plDays = days
        plDayLabels = labels
        if (keep >= 0) {
            var i = 0
            for (k in plRows.indices) if (plRows[k].start <= keep) i = k
            plIndex = i
        } else {
            plIndex = liveIndex()
            plJump = true
        }
        dateIndex = plDays.indexOf(if (plRows.isEmpty()) 0L else dayOf(plIndex.coerceIn(0, plRows.size - 1))).coerceAtLeast(0)
    }

    private fun toPrograms() {
        ensureRows()
        if (plRows.isEmpty()) return
        plIndex = liveIndex()
        zone = Zone.PROGRAMS
        gv.invalidate()
    }

    private fun programKey(name: String) {
        val n = plRows.size
        if (n == 0) { zone = Zone.CHANNELS; return gv.invalidate() }
        when (name) {
            "up" -> plIndex = (plIndex - 1).coerceAtLeast(0)
            "down" -> plIndex = (plIndex + 1).coerceAtMost(n - 1)
            "pgup" -> plIndex = (plIndex - 8).coerceAtLeast(0)
            "pgdown" -> plIndex = (plIndex + 8).coerceAtMost(n - 1)
            // ← — назад к каналам (у включённого описания список каналов разворачивается, панель уезжает)
            "left" -> zone = Zone.CHANNELS
            "right" -> {
                dateIndex = plDays.indexOf(dayOf(plIndex)).coerceAtLeast(0)
                zone = Zone.DATES
            }
        }
        gv.invalidate()
    }

    private fun datesKey(name: String) {
        when (name) {
            "up" -> moveDate(-1)
            "down" -> moveDate(1)
            "left" -> zone = Zone.PROGRAMS
        }
        gv.invalidate()
    }

    private fun moveDate(d: Int) {
        val k = (dateIndex + d).coerceIn(0, maxOf(0, plDays.size - 1))
        if (k == dateIndex) return
        dateIndex = k
        plIndex = dayTarget(plDays[k]).coerceAtLeast(0)
    }

    /** Передача дня, на которую встаёт фокус: идущая, иначе первая */
    private fun dayTarget(day: Long): Int {
        val now = Clock.now()
        var first = -1
        for (i in plRows.indices) {
            if (dayOf(i) != day) continue
            val p = plRows[i]
            if (p.start <= now && now < p.stop) return i
            if (first < 0) first = i
        }
        return first
    }

    /** Что делает OK на передаче — как в окне «Программа»: эфир, архив или карточка */
    private fun programAction(p: Programme) {
        val ch = focused ?: return
        val now = Clock.now()
        when {
            p.start <= now && now < p.stop -> tv.playChannel(ch, group?.key)
            p.stop <= now && ch.hasArchive && p.start >= now - ch.archiveDays * 86400L - Cells.SLOT -> tv.playArchive(ch, p.start, p.stop)
            else -> tv.openDetails(ch, Cell(p.start, p.stop, p))
        }
    }

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
        plCh = null; plLast = null; plKeep = -1L
        gv.resetShift()
        if (toGrid && grid) toGrid()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 10_000)
        gv.invalidate()
    }

    /** Список категорий изменился (скрытие, избранное) — оставить фокус на том же канале */
    fun reload() {
        open(focused, group?.key)
    }

    /**
     * Скрыть канал или категорию и встать рядом, а не на первую категорию: [hide] скрывает
     * (меняет каталог), [gone] — канал после этого не виден, [groupGone] — скрыта эта категория.
     * Канал в фокусе пропал — соседний в той же категории (предыдущий, нет — следующий);
     * пропала сама категория — соседняя (предыдущая, нет — следующая).
     */
    fun reloadAfterHide(gone: (Channel) -> Boolean, groupGone: String?, hide: () -> Unit) {
        val list = channels
        var gk = group?.key
        var ch: Channel? = focused
        if (gk != null && gk == groupGone) {
            gk = (groups.getOrNull(groupIndex - 1) ?: groups.getOrNull(groupIndex + 1))?.key
            ch = null
        } else if (ch != null && gone(ch)) {
            ch = list.subList(0, index).lastOrNull { !gone(it) } ?: list.drop(index + 1).firstOrNull { !gone(it) }
            if (ch == null) {
                gk = (groups.getOrNull(groupIndex - 1) ?: groups.getOrNull(groupIndex + 1))?.key
            }
        }
        hide()
        open(ch, gk)
    }

    fun setView(gridView: Boolean) {
        grid = gridView
        if (zone == Zone.GRID || zone == Zone.PROGRAMS || zone == Zone.DATES) zone = Zone.CHANNELS
        plCh = null
        live = true
        winStart = liveStart()
        gv.invalidate()
    }

    fun refresh() {
        // программа обновилась: передачи пересоберутся, фокус останется на той же передаче
        plKeep = plRows.getOrNull(plIndex)?.start ?: -1L
        plCh = null
        gv.invalidate()
    }

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

    // удержание ← — «Программа и архив» канала; в виде «Список» программа и так справа — там это не нужно
    override fun hasLong(name: String) = zone == Zone.CHANNELS && name == "left" && grid

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return tv.digit(digit)
        if (zone == Zone.GRID) return gridKey(name)
        if (zone == Zone.PLAYLISTS) return playlistsKey(name)
        if (zone == Zone.CATS) return catsKey(name)
        if (zone == Zone.PROGRAMS) return programKey(name)
        if (zone == Zone.DATES) return datesKey(name)
        when (name) {
            "up" -> move(-1)
            "down" -> move(1)
            "pgup" -> move(-pageSize())
            "pgdown" -> move(pageSize())
            "left" -> if (long) focused?.let { tv.openSchedule(it, 0) } else openCats()
            // → в сетку программы; в виде «Список» — в список программ канала
            "right" -> if (grid) toGrid() else toPrograms()
        }
    }

    // ---------------------------------------------------------- колонка категорий

    /** Фокус на строке «Поиск» над категориями */
    private var catSearch = false

    // ---------------------------------------------------------- колонка плейлистов (← в колонке категорий)

    private var pls: List<Playlist> = emptyList()
    private var plsIndex = 0

    /** ← в колонке категорий: левее выезжает список плейлистов (нужны хотя бы два) */
    private fun openPlaylists() {
        pls = tv.repo.playlists()
        if (pls.size < 2) return
        plsIndex = pls.indexOfFirst { it.id == tv.playlist?.id }.coerceAtLeast(0)
        zone = Zone.PLAYLISTS
        gv.invalidate()
    }

    /** ↑/↓ — плейлист, OK — открыть его, → — обратно к категориям */
    private fun playlistsKey(name: String) {
        val n = pls.size
        when (name) {
            "up" -> plsIndex = (plsIndex - 1 + n) % n
            "down" -> plsIndex = (plsIndex + 1) % n
            "pgup" -> plsIndex = maxOf(0, plsIndex - gv.catRows() + 1)
            "pgdown" -> plsIndex = minOf(n - 1, plsIndex + gv.catRows() - 1)
            "right" -> zone = Zone.CATS
        }
        gv.invalidate()
    }

    private fun choosePlaylist() {
        val p = pls.getOrNull(plsIndex) ?: return
        if (p.id == tv.playlist?.id) { zone = Zone.CATS; gv.invalidate() } else tv.switchPlaylist(p)
    }

    private fun openCats() {
        if (groups.size < 2) return
        catsFromGroup = groupIndex
        catsFromChannel = focused
        catSearch = false
        zone = Zone.CATS
        gv.invalidate()
    }

    /** ↑/↓ — категория (список каналов справа меняется сразу), → — к её каналам */
    private fun catsKey(name: String) {
        val n = groups.size
        // над категориями — строка «Поиск»: ↑ с первой категории — на неё, с неё ↑ — к последней
        if (catSearch) {
            when (name) {
                "up" -> { catSearch = false; setGroup(n - 1) }
                "down" -> { catSearch = false; setGroup(0) }
                "left" -> openPlaylists()
                "right" -> closeCats()
            }
            gv.invalidate()
            return
        }
        when (name) {
            "up" -> if (groupIndex == 0) { catSearch = true; gv.invalidate() } else setGroup(groupIndex - 1)
            "down" -> setGroup((groupIndex + 1) % n)
            "pgup" -> setGroup(maxOf(0, groupIndex - gv.catRows() + 1))
            "pgdown" -> setGroup(minOf(n - 1, groupIndex + gv.catRows() - 1))
            "left" -> openPlaylists()
            "right" -> closeCats()
        }
    }

    private fun setGroup(i: Int) {
        if (i == groupIndex || i !in groups.indices) return
        groupIndex = i
        // канал, с которого открыли колонку, — если он есть в категории; иначе начало
        index = catsFromChannel?.let { channels.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        gv.invalidate()
    }

    private fun closeCats() {
        catSearch = false
        zone = Zone.CHANNELS
        gv.invalidate()
    }

    private fun move(d: Int) {
        val n = channels.size
        if (n == 0) return
        // по одному — по кругу, страницами — до края
        index = if (Math.abs(d) == 1) (index + d + n) % n else (index + d).coerceIn(0, n - 1)
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
        if (zone == Zone.PLAYLISTS) return choosePlaylist()
        if (zone == Zone.CATS && catSearch) return tv.openSearch()
        if (zone == Zone.CATS) return closeCats()
        val ch = focused ?: return
        if (zone == Zone.GRID) {
            focusCell()?.let { tv.openDetails(ch, it) }
            return
        }
        if (zone == Zone.PROGRAMS) return plRows.getOrNull(plIndex)?.let { programAction(it) } ?: Unit
        if (zone == Zone.DATES) { zone = Zone.PROGRAMS; gv.invalidate(); return }
        tv.playChannel(ch, group?.key)
    }

    override fun long() {
        // в списке программ удержание — карточка передачи, на каналах и датах — меню канала
        if (zone == Zone.PROGRAMS) {
            val ch = focused ?: return
            plRows.getOrNull(plIndex)?.let { tv.openDetails(ch, Cell(it.start, it.stop, it)) }
            return
        }
        focused?.let { tv.openMenu(it, guide = true) }
    }

    override fun back() {
        if (tv.numberPending()) return tv.cancelNumber()
        if (zone == Zone.PLAYLISTS) {
            zone = Zone.CATS
            gv.invalidate()
            return
        }
        if (zone == Zone.CATS) {
            // отмена: категория и канал — как до открытия колонки
            groupIndex = catsFromGroup.coerceIn(0, maxOf(0, groups.size - 1))
            index = catsFromChannel?.let { channels.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            return closeCats()
        }
        if (zone == Zone.GRID) {
            zone = Zone.CHANNELS
            live = true
            winStart = liveStart()
            gv.invalidate()
            return
        }
        if (zone == Zone.PROGRAMS || zone == Zone.DATES) {
            zone = Zone.CHANNELS
            gv.invalidate()
            return
        }
        tv.closeGuide()
    }

    override fun media(name: String) {
        if (name == "guide") tv.closeGuide() else if (name == "menu") long() else if (name == "search") tv.openSearch()
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
        // текст — не мельче 1,1 em (11 sp): «Масштаб»
        private val pTitle = U.paint(1.8f, bold = true)
        private val pChannel = U.paint(1.2f, U.wa(0.7f))
        private val pTime = U.paint(1.2f, U.wa(0.8f))
        private val pDesc = TextPaint(U.paint(1.15f, U.wa(0.65f)))
        private val pClock = U.paint(2.2f, U.wa(0.9f))
        private val pCat = U.paint(1.4f, bold = true)
        private val pCatArrow = U.paint(1.4f, U.wa(0.5f))
        private val pCount = U.paint(1.1f, U.wa(0.5f))
        private val pTick = U.paint(1.1f, U.wa(0.7f))
        private val pNowChip = U.paint(1.1f, bold = true)
        private val pNum = U.paint(1.15f, U.wa(0.5f))
        private val pName = U.paint(1.35f)
        private val pNameBold = U.paint(1.35f, bold = true)
        private val pCell = U.paint(1.25f)
        private val pCellTime = U.paint(1.1f, U.wa(0.55f))
        private val pEmpty = U.paint(1.35f, U.wa(0.6f))
        private val line = Paint().apply { color = U.wa(0.25f); strokeWidth = 0.1f * em }
        private val nowLine = Paint().apply { color = U.LIVE; strokeWidth = 0.2f * em }
        private var gradient: LinearGradient? = null
        private val bgPaint = Paint()
        private var descLayout: StaticLayout? = null
        private var descKey = ""

        // безопасная зона: 4 em по бокам, 2,4 em сверху и снизу
        private val pHint = U.paint(1.15f, U.wa(0.6f))
        private val pHintKey = U.paint(1.1f, U.wa(0.8f))
        private val pCatItem = U.paint(1.3f)
        private val pCatItemBold = U.paint(1.3f, bold = true)

        // колонка категорий: 0 — убрана, 1 — выехала; список и сетка сдвинуты на shift * (catW + зазор)
        private var shift = 0f
        private var shift2 = 0f          // колонка плейлистов левее категорий
        private val catW get() = 19f * em
        private val catRowH get() = 3.2f * em

        fun resetShift() { shift = 0f }

        fun catRows() = maxOf(1, ((height - bodyTop - padBottom - hintsH + gap) / (catRowH + gap)).toInt())

        // строка подсказок под списком (настройка «Подсказки клавиш»)
        private val hintsH get() = if (Prefs.keyHints && !U.touch) 1.9f * em else 0f

        private val padX get() = 4f * em
        private val padTop get() = 2.4f * em
        private val padBottom get() = 2.4f * em
        private val topH get() = 7.6f * em
        private val top1H get() = 4.8f * em
        private val headH get() = 2.6f * em
        private val rowH get() = 3.4f * em
        private val gap get() = 0.25f * em

        // шапка гида (настройка): 0 — канал и передача, 1 — только канал, 2 — скрыть. При выезде панели описания
        // шапка «канал и передача» сжимается до «только канала»
        private fun topBlock(mode: Int) = when (mode) { 2 -> 0f; 1 -> top1H + em; else -> topH + em }
        private val topBlockNow: Float get() {
            val m = Prefs.guideHeader
            val a = topBlock(m)
            return a + (topBlock(maxOf(m, 1)) - a) * rail
        }
        private val bodyTop get() = padTop + topBlockNow + headH

        // вид «Список»: rail 0 — каналы целиком, 1 — колонка логотипов и панель передачи справа
        private var rail = 0f
        private val contentW get() = width - 2 * padX
        private val railW get() = 5.6f * em
        private val datesW get() = 5.4f * em
        private val panelFullW get() = contentW * 0.30f
        private val chW get() = (contentW * 0.30f).let { full -> if (grid) full else full + (railW - full) * rail }
        private val datesR get() = width - padX - (if (rail > 0f) panelFullW * rail + em else 0f)
        private val datesL get() = datesR - datesW
        private val progL get() = padX + chW + 1.2f * em
        private val progR get() = datesL - em

        fun visibleRows() = maxOf(1, ((height - bodyTop - padBottom - hintsH + gap) / (rowH + gap)).toInt())

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            gradient = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(U.GUIDE_TOP, android.graphics.Color.argb(220, 12, 13, 14), U.GUIDE_BOTTOM),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            bgPaint.shader = gradient
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            val now = Clock.now()
            if (live) winStart = liveStart()
            if (now / 60 != nowMinute) {
                nowMinute = now / 60
                nowText = Fmt.hhmm(now)
            }
            // панель описания: каналы сжимаются до логотипов, справа выезжает панель
            val wantRail = if (descOn && (zone == Zone.PROGRAMS || zone == Zone.DATES)) 1f else 0f
            if (!Anim.on || Math.abs(wantRail - rail) < 0.01f) rail = wantRail
            else {
                rail += (wantRail - rail) * 0.3f
                postInvalidateOnAnimation()
            }
            drawTop(c, now)
            // колонка категорий выезжает слева, список и сетка уезжают вправо за край экрана
            val target = if (zone == Zone.CATS || zone == Zone.PLAYLISTS) 1f else 0f
            if (!Anim.on || Math.abs(target - shift) < 0.01f) shift = target
            else {
                shift += (target - shift) * 0.3f
                postInvalidateOnAnimation()
            }
            val target2 = if (zone == Zone.PLAYLISTS) 1f else 0f
            if (!Anim.on || Math.abs(target2 - shift2) < 0.01f) shift2 = target2
            else {
                shift2 += (target2 - shift2) * 0.3f
                postInvalidateOnAnimation()
            }
            drawHints(c)
            if (shift2 > 0f) drawPlaylists(c)
            if (shift > 0f) drawCats(c)
            val moved = shift > 0f
            if (moved) {
                c.save()
                val dx = (shift + shift2) * (catW + em)
                c.clipRect(padX + dx - gap, 0f, width.toFloat(), height.toFloat())
                c.translate(dx, 0f)
            }
            drawBody(c, now)
            if (moved) c.restore()
        }

        private fun drawBody(c: Canvas, now: Long) {
            drawHead(c, now)
            val list = channels
            if (list.isEmpty()) {
                U.drawText(c, if (tv.catalog?.channels?.isEmpty() != false) str(R.string.loading_channels) else str(R.string.no_channels), padX + em, bodyTop + 2 * em, width - 2 * padX, pEmpty)
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
                if (x in gridL..gridR) {
                    // красная линия «сейчас» и время над ней (подпись деления под плашкой не рисуется)
                    val shown = minOf(rows, list.size - first)
                    c.drawLine(x, bodyTop - 0.5f * em, x, bodyTop + shown * (rowH + gap) - gap, nowLine)
                    val hw = nowChipHalf()
                    val cx = x.coerceIn(gridL + hw, gridR - hw)
                    val cy = bodyTop - 1.35f * em
                    U.roundRect(c, cx - hw, cy - 0.85f * em, cx + hw, cy + 0.85f * em, 0.35f * em, U.LIVE)
                    c.drawText(nowText, cx - pNowChip.measureText(nowText) / 2, cy - (pNowChip.descent() + pNowChip.ascent()) / 2, pNowChip)
                }
            } else {
                drawPrograms(c, now)
                drawDates(c)
                drawPanel(c, now)
            }
        }

        /** Колонка плейлистов: название и число каналов; открытый сейчас — выделен, в фокусе — белая */
        private fun drawPlaylists(c: Canvas) {
            val l = padX - (1f - shift2) * (padX + catW)
            val r = l + catW
            val headY = padTop + topBlockNow
            pCat.color = U.wa(0.85f)
            U.drawText(c, str(R.string.playlists_title), l + em, headY + headH / 2 - 0.3f * em, catW - 2 * em, pCat)
            val rows = catRows()
            val n = pls.size
            val first = (plsIndex - rows / 2).coerceIn(0, maxOf(0, n - rows))
            for (k in 0 until rows) {
                val i = first + k
                if (i >= n) break
                val p = pls[i]
                val y = bodyTop + k * (catRowH + gap)
                val focus = i == plsIndex && zone == Zone.PLAYLISTS
                val open = p.id == tv.playlist?.id
                val bg = if (focus) U.WHITE else if (open) U.FILL_ACTIVE else U.FILL
                U.roundRect(c, l, y, r, y + catRowH, U.px(U.RADIUS_S), bg)
                val cy = y + catRowH / 2
                val count = if (p.channelsCount > 0) p.channelsCount.toString() else ""
                pCount.color = if (focus) U.BLACK else U.wa(0.5f)
                val cw = pCount.measureText(count)
                c.drawText(count, r - em - cw, cy - (pCount.descent() + pCount.ascent()) / 2, pCount)
                val pt = if (open) pCatItemBold else pCatItem
                pt.color = if (focus) U.BLACK else U.WHITE
                U.drawText(c, p.name.ifEmpty { "—" }, l + em, cy, r - em - cw - 2 * em, pt)
            }
            pCount.color = U.wa(0.5f)
        }

        /** Колонка категорий: название, число каналов, замок; в фокусе — белая */
        private fun drawCats(c: Canvas) {
            val l = padX - (1f - shift) * (padX + catW) + shift2 * (catW + em)
            val r = l + catW
            val headY = padTop + topBlockNow
            // вместо заголовка — строка «Поиск» (↑ с первой категории)
            val sf = catSearch && zone == Zone.CATS
            U.roundRect(c, l, headY - 0.3f * em, r, headY + headH - 0.3f * em, U.px(U.RADIUS_S), if (sf) U.WHITE else U.FILL)
            val scy = headY + headH / 2 - 0.3f * em
            val sc = if (sf) U.BLACK else U.wa(0.7f)
            U.fill.style = Paint.Style.STROKE
            U.fill.strokeWidth = 0.15f * em
            U.fill.color = sc
            c.drawCircle(l + 1.6f * em, scy - 0.1f * em, 0.5f * em, U.fill)
            c.drawLine(l + 1.95f * em, scy + 0.25f * em, l + 2.4f * em, scy + 0.7f * em, U.fill)
            U.fill.style = Paint.Style.FILL
            pCatItem.color = if (sf) U.BLACK else U.wa(0.85f)
            U.drawText(c, catsTitle, l + 3.2f * em, scy, catW - 4.2f * em, pCatItem)
            val rows = catRows()
            val n = groups.size
            val first = (groupIndex - rows / 2).coerceIn(0, maxOf(0, n - rows))
            val cat = tv.catalog
            val pin = Prefs.pin.isNotEmpty()
            for (k in 0 until rows) {
                val i = first + k
                if (i >= n) break
                val g = groups[i]
                val y = bodyTop + k * (catRowH + gap)
                val focus = i == groupIndex
                val white = focus && zone == Zone.CATS && !catSearch
                if (focus) U.roundRect(c, l, y, r, y + catRowH, U.px(U.RADIUS_S), if (white) U.WHITE else U.FILL_ACTIVE)
                val cy = y + catRowH / 2
                val count = counts.getOrPut(g.key + g.channels.size) { g.channels.size.toString() }
                pCount.color = if (white) U.BLACK else U.wa(0.5f)
                val cw = pCount.measureText(count)
                c.drawText(count, r - em - cw, cy - (pCount.descent() + pCount.ascent()) / 2, pCount)
                var tx = l + em
                val isz = 1.5f * em
                val ic = if (white) U.BLACK else U.wa(0.7f)
                if (g.favorites) { Icons.draw(c, Icons.Kind.FAV, tx, cy - isz / 2, isz, ic); tx += isz + 0.5f * em }
                else if (pin && cat?.isGroupLocked(g.key) == true) { Icons.draw(c, Icons.Kind.LOCK, tx, cy - isz / 2, isz, ic); tx += isz + 0.5f * em }
                val p = if (focus) pCatItemBold else pCatItem
                p.color = if (white) U.BLACK else if (focus) U.WHITE else U.wa(0.85f)
                U.drawText(c, g.title.ifEmpty { "—" }, tx, cy, r - em - cw - em - tx, p)
            }
            pCount.color = U.wa(0.5f)
        }

        // строки, которые не меняются от кадра к кадру: собираются при открытии и смене зоны
        private val counts = HashMap<String, String>()
        private var catsTitle = ""
        private var hintZone: Zone? = null
        private var hintList: List<Pair<String, String>> = emptyList()

        private fun hintsFor(z: Zone): List<Pair<String, String>> = when (z) {
            Zone.PLAYLISTS -> listOf("↑ ↓" to str(R.string.hint_playlist), "OK" to str(R.string.hint_select), "→" to str(R.string.hint_categories), str(R.string.key_back) to str(R.string.hint_cancel))
            Zone.CATS -> listOf("↑ ↓" to str(R.string.category), "→" to str(R.string.hint_to_channels), str(R.string.key_back) to str(R.string.hint_cancel))
            Zone.PROGRAMS -> listOf("OK" to str(R.string.watch), "←" to str(R.string.hint_to_channels), "→" to str(R.string.hint_dates), "↑ ↓" to str(R.string.hint_programme_pick))
            Zone.DATES -> listOf("↑ ↓" to str(R.string.hint_date), "←" to str(R.string.hint_programmes), "OK" to str(R.string.watch), str(R.string.key_back) to str(R.string.hint_to_channels))
            Zone.GRID -> listOf("OK" to str(R.string.about), "← →" to str(R.string.hint_programmes), str(R.string.key_back) to str(R.string.hint_to_channels))
            Zone.CHANNELS -> listOf("OK" to str(R.string.watch), "←" to str(R.string.hint_categories),
                "→" to str(if (grid) R.string.hint_into_grid else R.string.hint_programme), str(R.string.key_hold_ok) to str(R.string.menu))
        }

        /** Подсказки клавиш под списком: клавиша в рамке и действие */
        private fun drawHints(c: Canvas) {
            if (hintsH == 0f) return
            if (hintZone != zone || catsTitle.isEmpty()) {
                hintZone = zone
                hintList = hintsFor(zone)
                catsTitle = str(R.string.search)
            }
            val cy = height - padBottom - 0.8f * em
            var x = padX
            for ((key, action) in hintList) {
                val kw = maxOf(2f * em, Keycap.width(key, pHintKey) + em)
                U.fill.style = Paint.Style.STROKE
                U.fill.strokeWidth = 0.1f * em
                U.fill.color = U.wa(0.4f)
                c.drawRoundRect(x, cy - 0.85f * em, x + kw, cy + 0.85f * em, 0.4f * em, 0.4f * em, U.fill)
                U.fill.style = Paint.Style.FILL
                Keycap.draw(c, key, x + (kw - Keycap.width(key, pHintKey)) / 2, cy, pHintKey)
                x += kw + 0.6f * em
                c.drawText(action, x, cy - (pHint.descent() + pHint.ascent()) / 2, pHint)
                x += pHint.measureText(action) + 1.8f * em
            }
        }

        // подпись плашки «сейчас»: строка меняется раз в минуту, а не на каждом кадре
        private var nowText = ""
        private var nowMinute = -1L

        private fun nowChipHalf() = pNowChip.measureText(nowText) / 2 + 0.45f * em

        private fun drawTop(c: Canvas, now: Long) {
            val ch = focused ?: return
            val mode = Prefs.guideHeader
            if (mode == 2) return
            if (mode == 0) {
                // «канал и передача» плавно переходит в «только канал», когда выезжает панель описания
                val h = padTop + topH + em
                if (rail < 0.99f) {
                    val s = if (rail > 0f) c.saveLayerAlpha(0f, 0f, width.toFloat(), h, Math.round(255 * (1 - rail))) else -1
                    drawTopFull(c, ch, now)
                    if (s >= 0) c.restoreToCount(s)
                }
                if (rail > 0f) {
                    val s = c.saveLayerAlpha(0f, 0f, width.toFloat(), h, Math.round(255 * rail))
                    drawTopChannel(c, ch)
                    c.restoreToCount(s)
                }
            } else drawTopChannel(c, ch)
            drawClock(c, now)
        }

        /** Шапка «канал + передача»: логотип, канал, название, время, описание (передача — в фокусе гида) */
        private fun drawTopFull(c: Canvas, ch: Channel, now: Long) {
            val t = padTop
            val logoW = 9f * em
            Logos.draw(c, context, ch, padX, t, padX + logoW, t + 6.2f * em) { invalidate() }
            val x = padX + logoW + 1.2f * em
            val clockW = pClock.measureText("00:00") + em
            val w = width - padX - x - clockW
            // в списке программ — передача в фокусе списка, в сетке — под фокусом сетки, иначе — идущая
            val listP = if ((zone == Zone.PROGRAMS || zone == Zone.DATES) && !grid) plRows.getOrNull(plIndex) else null
            val ref = if (zone == Zone.GRID) focusTime else now
            val cell = if (listP != null) Cell(listP.start, listP.stop, listP) else Cells.at(tv.repo.programmes(ch), ref)
            val p = cell.program
            U.drawText(c, ch.title(), x, t + 0.6f * em, w, pChannel)
            U.drawText(c, p?.title ?: str(R.string.no_programme), x, t + 2.35f * em, w, pTitle)
            U.drawText(c, Fmt.dayTimeRange(cell.start, cell.end), x, t + 4.05f * em, w, pTime)
            val desc = p?.desc ?: ""
            if (desc.isNotEmpty()) {
                val key = desc + w.toInt()
                if (key != descKey) {
                    descKey = key
                    descLayout = StaticLayout.Builder.obtain(desc, 0, desc.length, pDesc, w.toInt())
                        .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                }
                c.save()
                c.translate(x, t + 4.9f * em)
                descLayout?.draw(c)
                c.restore()
            }
        }

        /** Шапка «только канал»: логотип, название канала, категория и глубина архива */
        private fun drawTopChannel(c: Canvas, ch: Channel) {
            val t = padTop
            val logoW = 6.8f * em
            Logos.draw(c, context, ch, padX, t + 0.2f * em, padX + logoW, t + 0.2f * em + 4.4f * em) { invalidate() }
            val x = padX + logoW + 1.2f * em
            val clockW = pClock.measureText("00:00") + em
            val w = width - padX - x - clockW
            U.drawText(c, ch.title(), x, t + 1.5f * em, w, pTitle)
            val sub = ch.group + if (ch.hasArchive) " · " + str(R.string.archive_x_d, ch.archiveDays) else ""
            U.drawText(c, sub, x, t + 3.5f * em, w, pChannel)
        }

        private fun drawClock(c: Canvas, now: Long) {
            val t = padTop
            val clock = Fmt.hhmm(now)
            // на сенсорных (не ТВ) — ✕ закрыть, часы левее
            val closeW = if (U.touch) 2.6f * em else 0f
            if (U.touch) c.drawText("✕", width - padX - pClock.measureText("✕"), t + 1.7f * em, pClock)
            c.drawText(clock, width - padX - closeW - pClock.measureText(clock), t + 1.7f * em, pClock)
            if (tv.repo.epgLoading()) {
                val s = str(R.string.programme_is_loading)
                c.drawText(s, width - padX - pCount.measureText(s), t + 3.4f * em, pCount)
            }
        }

        private fun drawHead(c: Canvas, now: Long) {
            if (rail > 0.5f) return   // каналы сжаты до логотипов — заголовка категории нет
            val y = padTop + topBlockNow
            val cy = y + headH / 2
            val g = group
            val arrowW = 1.6f * em
            val l = padX
            val r = padX + chW - em
            // «‹ Эфирные 20»: стрелка напоминает, что категории — слева (←)
            if (groups.size > 1) c.drawText("‹", l, cy - (pCatArrow.descent() + pCatArrow.ascent()) / 2, pCatArrow)
            val title = g?.title?.ifEmpty { "—" } ?: "—"
            val count = g?.let { counts.getOrPut(it.key + it.channels.size) { it.channels.size.toString() } } ?: ""
            val cw = pCount.measureText(count)
            val tw = minOf(pCat.measureText(title), r - l - arrowW - cw - em)
            val tx = l + arrowW
            U.drawText(c, title, tx, cy, tw, pCat)
            c.drawText(count, tx + tw + 0.6f * em, cy - (pCount.descent() + pCount.ascent()) / 2, pCount)
            if (!grid) return
            val gl = padX + chW + gap
            val gr = width - padX
            var t = Math.floorDiv(winStart, Cells.SLOT) * Cells.SLOT
            while (t < winStart + SPAN) {
                if (t >= winStart) {
                    val x = gl + (t - winStart).toFloat() / SPAN * (gr - gl)
                    c.drawLine(x, y + 0.6f * em, x, y + headH, line)
                    val lx = x + 0.4f * em
                    val nx = gl + (now - winStart).toFloat() / SPAN * (gr - gl)
                    val hw = nowChipHalf() + 0.3f * em
                    if (nx < gl || nx > gr || lx > nx + hw || lx + tickW < nx - hw) c.drawText(tickLabel(t), lx, y + 1.6f * em, pTick)
                }
                t += Cells.SLOT
            }
            val day = Fmt.dayLabel(maxOf(winStart, if (zone == Zone.GRID) focusTime else now))
            c.drawText(day, gr - pCount.measureText(day), y - 0.1f * em, pCount)
        }

        private val tickW get() = pTick.measureText("00:00")

        // подписи делений: четыре строки на окно, без выделений на кадр
        private val tickTimes = LongArray(8)
        private val tickTexts = arrayOfNulls<String>(8)

        private fun tickLabel(t: Long): String {
            val i = (Math.floorMod(t / Cells.SLOT, 8L)).toInt()
            if (tickTimes[i] != t || tickTexts[i] == null) {
                tickTimes[i] = t
                tickTexts[i] = Fmt.hhmm(t)
            }
            return tickTexts[i]!!
        }

        private fun drawRow(c: Canvas, ch: Channel, focus: Boolean, y: Float, gl: Float, gr: Float, now: Long) {
            val l = padX
            val r = padX + chW
            val chFocus = focus && zone == Zone.CHANNELS
            val bg = if (chFocus) U.WHITE else if (focus) U.FILL_ACTIVE else U.FILL
            U.roundRect(c, l, y, r, y + rowH, U.px(U.RADIUS_S), bg)
            // фокус ушёл в программу или на даты — канал остаётся обведённым: сюда он вернётся
            if (focus && !chFocus && !grid && (zone == Zone.PROGRAMS || zone == Zone.DATES)) outline(c, l, y, r, y + rowH)
            val fg = if (chFocus) U.BLACK else U.WHITE
            val cy = y + rowH / 2
            var x = l + 0.35f * em
            Logos.draw(c, context, ch, x, y + 0.25f * em, x + 4.3f * em, y + rowH - 0.25f * em, plate = chFocus) { invalidate() }
            // колонка логотипов (выехало описание): только логотип
            if (!grid && r - l < 10f * em) return
            x += 4.3f * em + 0.7f * em
            // колонка номеров — только в режиме «рядом с названием»
            if (Prefs.numberMode == Prefs.NUMBER_NEAR_NAME) {
                pNum.color = if (chFocus) U.BLACK else U.wa(0.5f)
                c.drawText(ch.num.toString(), x, cy - (pNum.descent() + pNum.ascent()) / 2, pNum)
                x += 2.8f * em
            } else x += 0.5f * em
            val current = ch === tv.state.channel
            val pn = if (current) pNameBold else pName
            pn.color = fg
            val fav = tv.catalog?.isFavorite(ch) == true
            val locked = tv.catalog?.isLocked(ch) == true
            val isz = 1.5f * em
            val iw = ((if (fav) 1 else 0) + (if (ch.hasArchive) 1 else 0) + (if (locked) 1 else 0)) * (isz + 0.35f * em)
            U.drawText(c, ch.name, x, cy, r - x - 0.8f * em - iw, pn)
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
                val isPast = cell.end <= now
                // прошедшее приглушено, идущее сейчас — ярче будущего
                val color = when {
                    isFocus -> U.WHITE
                    cell.program == null -> U.wa(0.04f)
                    isNow -> U.FILL_NOW
                    isPast -> U.FILL
                    else -> U.FILL_CELL
                }
                U.roundRect(c, cl, y, cr, y + rowH, U.px(U.RADIUS_S), color)
                val tl = cl + 0.7f * em
                var tw = cr - tl - 0.5f * em
                // запланированное напоминание — значок часов в ячейке
                if (cell.program != null && cell.start > now && tv.reminders.has(ch, cell.start) && tw > 2 * em) {
                    val isz = 1.5f * em
                    Icons.draw(c, Icons.Kind.REMIND, cr - 0.5f * em - isz, cy - isz / 2, isz, if (isFocus) U.BLACK else U.ARCHIVE)
                    tw -= isz + 0.4f * em
                }
                if (tw > em) {
                    pCell.color = if (isFocus) U.BLACK else if (isPast) U.wa(0.45f) else U.WHITE
                    U.drawText(c, cell.title.ifEmpty { if (isFocus) Fmt.timeRange(cell.start, cell.end) else "" }, tl, cy, tw, pCell)
                }
            }
        }

        private fun outline(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val sw = 0.15f * em
            U.fill.style = Paint.Style.STROKE
            U.fill.strokeWidth = sw
            U.fill.color = U.wa(0.8f)
            c.drawRoundRect(l + sw / 2, t + sw / 2, r - sw / 2, b - sw / 2, U.px(U.RADIUS_S), U.px(U.RADIUS_S), U.fill)
            U.fill.style = Paint.Style.FILL
        }

        private val pProgTime = U.paint(1.3f)
        private val pProgTitle = U.paint(1.35f)
        private val pProgTitleBold = U.paint(1.35f, bold = true)
        private val pLive = U.paint(1.2f, bold = true)
        private val pTab = U.paint(1.25f)
        private val pTabBold = U.paint(1.25f, bold = true)
        private var plScroll = 0f

        private val listBottom get() = height - padBottom - hintsH

        private fun progH(p: Programme, now: Long) = if (p.start <= now && now < p.stop) rowH + 0.9f * em else rowH

        /** Список передач канала в фокусе: идущая — со шкалой, прошедшая — со значком архива */
        private fun drawPrograms(c: Canvas, now: Long) {
            ensureRows()
            val l = progL
            val r = progR
            val top = bodyTop
            val bottom = listBottom
            val rows = plRows
            if (rows.isEmpty()) {
                U.drawText(c, str(R.string.no_programme), l + em, top + rowH / 2, r - l, pEmpty)
                return
            }
            // фокус — по центру, у краёв — без пустоты; на каналах показана идущая передача
            val fi = if (zone == Zone.CHANNELS) liveIndex() else plIndex
            var total = 0f
            var fy = 0f
            var fh = 0f
            for (i in rows.indices) {
                val h = progH(rows[i], now)
                if (i == fi) { fy = total; fh = h }
                total += h + gap
            }
            val viewH = bottom - top
            val target = (fy - viewH / 2 + fh / 2).coerceIn(0f, maxOf(0f, total - viewH))
            if (plJump || !Anim.on) { plScroll = target; plJump = false }
            else if (Math.abs(target - plScroll) > 0.5f) {
                plScroll += (target - plScroll) * 0.35f
                postInvalidateOnAnimation()
            } else plScroll = target
            val save = Fade.begin(c, l, top, r, bottom)
            var y = top - plScroll
            for (i in rows.indices) {
                val h = progH(rows[i], now)
                if (y + h >= top && y <= bottom) drawProgRow(c, rows[i], i, l, r, y, h, now)
                y += h + gap
                if (y > bottom) break
            }
            Fade.end(c, save, l, top, r, bottom, plScroll / Fade.length, (total - viewH - plScroll) / Fade.length)
        }

        private fun drawProgRow(c: Canvas, p: Programme, i: Int, l: Float, r: Float, y: Float, h: Float, now: Long) {
            val ch = focused ?: return
            val live = p.start <= now && now < p.stop
            val past = p.stop <= now
            val pf = zone == Zone.PROGRAMS && i == plIndex
            val ghost = zone == Zone.DATES && i == plIndex
            val bg = when { pf -> U.WHITE; ghost -> U.FILL_ACTIVE; live -> U.FILL_PLAYING; else -> 0 }
            if (bg != 0) U.roundRect(c, l, y, r, y + h, U.px(U.RADIUS_S), bg)
            if (ghost) outline(c, l, y, r, y + h)
            val fg = if (pf) U.BLACK else if (past) U.wa(0.62f) else U.WHITE
            val cy = if (live) y + rowH / 2 - 0.1f * em else y + h / 2
            pProgTime.color = fg
            c.drawText(Fmt.hhmm(p.start), l + 0.8f * em, cy - (pProgTime.descent() + pProgTime.ascent()) / 2, pProgTime)
            // значки справа: LIVE, архив, напоминание — белые (в строке фокуса чёрные)
            val ic = if (pf) U.BLACK else U.wa(0.8f)
            val isz = 1.7f * em
            var ix = r - 0.8f * em
            if (live) {
                pLive.color = ic
                val lw = pLive.measureText("LIVE")
                val pad = 0.8f * em
                ix -= lw + 2 * pad
                U.fill.style = Paint.Style.STROKE
                U.fill.strokeWidth = 0.12f * em
                U.fill.color = ic
                c.drawRoundRect(ix, cy - 0.85f * em, ix + lw + 2 * pad, cy + 0.85f * em, 0.3f * em, 0.3f * em, U.fill)
                U.fill.style = Paint.Style.FILL
                c.drawText("LIVE", ix + pad, cy - (pLive.descent() + pLive.ascent()) / 2, pLive)
                ix -= 0.5f * em
            }
            if (past && ch.hasArchive && p.start >= now - ch.archiveDays * 86400L - Cells.SLOT) { ix -= isz; Icons.draw(c, Icons.Kind.ARCHIVE, ix, cy - isz / 2, isz, ic); ix -= 0.5f * em }
            if (p.start > now && tv.reminders.has(ch, p.start)) { ix -= isz; Icons.draw(c, Icons.Kind.REMIND, ix, cy - isz / 2, isz, ic); ix -= 0.5f * em }
            val tx = l + 5.4f * em
            val pt = if (live) pProgTitleBold else pProgTitle
            pt.color = fg
            U.drawText(c, p.title, tx, cy, ix - tx - 0.3f * em, pt)
            if (live) {
                // шкала прогресса идущей передачи
                val by = y + h - 0.95f * em
                val bw = r - 0.8f * em - tx
                U.roundRect(c, tx, by, tx + bw, by + 0.25f * em, 0.12f * em, if (pf) 0x33000000 else U.wa(0.22f))
                U.roundRect(c, tx, by, tx + bw * Fmt.progress(p.start, p.stop, now), by + 0.25f * em, 0.12f * em, if (pf) U.BLACK else U.WHITE)
            }
        }

        /** Колонка дат: день недели и число; в фокусе — белая, день выбранной передачи — рамкой */
        private fun drawDates(c: Canvas) {
            val days = plDays
            if (days.isEmpty()) return
            val l = datesL
            val r = datesR
            val th = 3.6f * em
            val step = th + 0.3f * em
            val top = bodyTop
            val bottom = listBottom
            val cur = if (zone == Zone.DATES) dateIndex
            else days.indexOf(dayOf(if (zone == Zone.CHANNELS) liveIndex() else plIndex.coerceIn(0, plRows.size - 1))).coerceAtLeast(0)
            val viewH = bottom - top
            val off = (cur * step - viewH / 2 + th / 2).coerceIn(0f, maxOf(0f, days.size * step - viewH))
            val save = Fade.begin(c, l, top, r, bottom)
            for (k in days.indices) {
                val y = top + k * step - off
                if (y + th < top || y > bottom) continue
                val focus = zone == Zone.DATES && k == dateIndex
                if (focus) U.roundRect(c, l, y, r, y + th, U.px(U.RADIUS_S), U.WHITE)
                else if (k == cur) outline(c, l, y, r, y + th)
                val p = if (focus || k == cur) pTabBold else pTab
                p.color = if (focus) U.BLACK else if (k == cur) U.WHITE else U.wa(0.72f)
                val (a, b) = plDayLabels[k]
                c.drawText(a, (l + r - p.measureText(a)) / 2, y + th / 2 - 0.6f * em - (p.descent() + p.ascent()) / 2, p)
                c.drawText(b, (l + r - p.measureText(b)) / 2, y + th / 2 + 0.75f * em - (p.descent() + p.ascent()) / 2, p)
            }
            Fade.end(c, save, l, top, r, bottom, off / Fade.length, (days.size * step - viewH - off) / Fade.length)
        }

        // панель передачи (настройка «Описание в ТВ-гиде»): раскладка текста пересобирается при смене передачи
        private val pPanelTitle = TextPaint(U.paint(1.6f, bold = true))
        private val pPanelDesc = TextPaint(U.paint(1.25f, U.wa(0.8f)))
        private val pPanelSub = U.paint(1.2f, U.wa(0.6f))
        private var panelKey = ""
        private var panelTitle: StaticLayout? = null
        private var panelDesc: StaticLayout? = null
        private val clip = android.graphics.Path()
        private val srcR = android.graphics.Rect()
        private val dstR = android.graphics.RectF()
        private val bmPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private fun layoutOf(s: String, p: TextPaint, w: Int, lines: Int) =
            StaticLayout.Builder.obtain(s, 0, s.length, p, w).setMaxLines(lines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, 1.12f).build()

        /** Справа: картинка передачи (нет — логотип канала), время, название, описание, что сделает OK */
        private fun drawPanel(c: Canvas, now: Long) {
            if (rail <= 0.01f) return
            val ch = focused ?: return
            val p = plRows.getOrNull(plIndex) ?: return
            val pw = panelFullW
            val l = width - padX - pw + (1f - rail) * (pw + padX)
            val top = bodyTop
            val bottom = listBottom
            val s = c.saveLayerAlpha(l, top, l + pw, bottom, Math.round(255 * rail))
            val ih = minOf(pw * 9f / 16f, (bottom - top) * 0.4f)
            val bm = if (p.icon.isNotEmpty()) Logos.bitmapUrl(context, p.icon, 512, 288) { invalidate() } else null
            if (bm != null) {
                // кадр передачи — по центру, обрезка под рамку
                val k = maxOf(pw / bm.width, ih / bm.height)
                val sw = pw / k
                val sh = ih / k
                srcR.set(((bm.width - sw) / 2).toInt(), ((bm.height - sh) / 2).toInt(), ((bm.width + sw) / 2).toInt(), ((bm.height + sh) / 2).toInt())
                dstR.set(l, top, l + pw, top + ih)
                c.save()
                clip.rewind()
                clip.addRoundRect(dstR, U.px(U.RADIUS_S), U.px(U.RADIUS_S), android.graphics.Path.Direction.CW)
                c.clipPath(clip)
                c.drawBitmap(bm, srcR, dstR, bmPaint)
                c.restore()
            } else Logos.draw(c, context, ch, l, top, l + pw, top + ih, badge = false) { invalidate() }
            val w = pw.toInt()
            val key = ch.key + ":" + p.start + ":" + w
            if (key != panelKey) {
                panelKey = key
                panelTitle = layoutOf(p.title, pPanelTitle, w, 3)
                panelDesc = if (p.desc.isEmpty()) null else layoutOf(p.desc, pPanelDesc, w, maxOf(2, ((bottom - top - ih - 9f * em) / (pPanelDesc.textSize * 1.4f)).toInt()))
            }
            var y = top + ih + 1.2f * em
            pPanelSub.color = U.wa(0.7f)
            U.drawText(c, Fmt.dayTimeRange(p.start, p.stop), l, y + 0.6f * em, pw, pPanelSub)
            y += 1.9f * em
            panelTitle?.let { c.save(); c.translate(l, y); it.draw(c); c.restore(); y += it.height + 0.7f * em }
            panelDesc?.let { c.save(); c.translate(l, y); it.draw(c); c.restore() }
            // клавиша OK в рамке и действие — как в окне «Программа»
            val live = p.start <= now && now < p.stop
            val action = str(when {
                live -> R.string.watch_live
                p.stop <= now && ch.hasArchive && p.start >= now - ch.archiveDays * 86400L - Cells.SLOT -> R.string.watch_from_archive
                else -> R.string.about
            })
            val cy = bottom - 0.9f * em
            val kw = maxOf(2f * em, pHintKey.measureText("OK") + em)
            U.fill.style = Paint.Style.STROKE
            U.fill.strokeWidth = 0.1f * em
            U.fill.color = U.wa(0.4f)
            c.drawRoundRect(l, cy - 0.85f * em, l + kw, cy + 0.85f * em, 0.4f * em, 0.4f * em, U.fill)
            U.fill.style = Paint.Style.FILL
            c.drawText("OK", l + (kw - pHintKey.measureText("OK")) / 2, cy - (pHintKey.descent() + pHintKey.ascent()) / 2, pHintKey)
            c.drawText(action, l + kw + 0.6f * em, cy - (pHint.descent() + pHint.ascent()) / 2, pHint)
            c.restoreToCount(s)
        }

        // ---------------------------------------------------------- тач и мышь

        private fun rowAt(y: Float): Int {
            val rows = visibleRows()
            val first = (index - rows / 2).coerceIn(0, maxOf(0, channels.size - rows))
            return first + ((y - bodyTop) / (rowH + gap)).toInt()
        }

        private val gridL get() = padX + chW + gap

        /** Тап: ‹ › — категория, ✕ — закрыть, канал — смотреть, передача — карточка */
        private fun tap(x: Float, y: Float) {
            val headY = padTop + topBlockNow
            if (U.touch && x > width - padX - 2.5f * em && y < padTop + 2.5f * em) return tv.closeGuide()
            if (zone == Zone.PLAYLISTS) {
                // тап по плейлисту — открыть; мимо колонки — вернуться к категориям
                if (x < padX + catW && y >= bodyTop) {
                    val rows = catRows()
                    val first = (plsIndex - rows / 2).coerceIn(0, maxOf(0, pls.size - rows))
                    val i = first + ((y - bodyTop) / (catRowH + gap)).toInt()
                    if (i in pls.indices) { plsIndex = i; return choosePlaylist() }
                }
                zone = Zone.CATS
                return invalidate()
            }
            if (zone == Zone.CATS) {
                // тап по категории — открыть её каналы; мимо колонки — закрыть колонку
                if (x < padX + catW && y < bodyTop && y >= headY) return tv.openSearch()
                if (x < padX + catW && y >= bodyTop) {
                    val rows = catRows()
                    val first = (groupIndex - rows / 2).coerceIn(0, maxOf(0, groups.size - rows))
                    setGroup(first + ((y - bodyTop) / (catRowH + gap)).toInt())
                }
                return closeCats()
            }
            if (y in headY..headY + headH && x < padX + chW) return openCats()
            if (y < bodyTop) return
            if (!grid && x > padX + chW) return listTap(x, y)
            val i = rowAt(y)
            if (i !in channels.indices) return
            index = i
            if (x < gridL || !grid) {
                if (!grid) zone = Zone.CHANNELS
                zone = Zone.CHANNELS
                enter()
            } else {
                zone = Zone.GRID
                live = false
                focusTime = winStart + ((x - gridL) / (width - padX - gridL) * SPAN).toLong()
                enter()
            }
            invalidate()
        }

        /** Тап в виде «Список»: по дате — выбрать день, по передаче — то же, что OK */
        private fun listTap(x: Float, y: Float) {
            ensureRows()
            val now = Clock.now()
            if (x >= datesL && x <= datesR) {
                val step = 3.9f * em
                val cur = plDays.indexOf(dayOf(plIndex.coerceIn(0, maxOf(0, plRows.size - 1)))).coerceAtLeast(0)
                val viewH = listBottom - bodyTop
                val off = (cur * step - viewH / 2 + 1.8f * em).coerceIn(0f, maxOf(0f, plDays.size * step - viewH))
                val k = ((y - bodyTop + off) / step).toInt()
                if (k in plDays.indices) { dateIndex = k; plIndex = dayTarget(plDays[k]).coerceAtLeast(0); zone = Zone.PROGRAMS }
                return invalidate()
            }
            if (x < progL || x > progR) return
            var yy = bodyTop - plScroll
            for (i in plRows.indices) {
                val h = progH(plRows[i], now)
                if (y >= yy && y < yy + h) {
                    plIndex = i
                    zone = Zone.PROGRAMS
                    invalidate()
                    programAction(plRows[i])
                    return
                }
                yy += h + gap
            }
        }

        private var dragY = 0f
        private var dragX = 0f

        private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragY = 0f
                dragX = 0f
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (e.y < bodyTop) return
                val i = rowAt(e.y)
                if (i !in channels.indices) return
                index = i
                invalidate()
                long()
            }

            // вертикально — каналы (по строке за высоту строки), горизонтально в сетке — время
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                dragY += dy
                val step = rowH + gap
                while (dragY > step) { dragY -= step; moveClamped(1) }
                while (dragY < -step) { dragY += step; moveClamped(-1) }
                if (grid && (e1?.x ?: 0f) > gridL) {
                    dragX += dx
                    val sec = (dragX / (width - padX - gridL) * SPAN).toLong()
                    if (Math.abs(sec) >= 60) {
                        live = false
                        winStart += sec
                        dragX = 0f
                    }
                }
                invalidate()
                return true
            }
        })

        private fun moveClamped(d: Int) {
            index = (index + d).coerceIn(0, maxOf(0, channels.size - 1))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // правый клик мышью — меню канала
            if (e.action == MotionEvent.ACTION_DOWN && e.buttonState and MotionEvent.BUTTON_SECONDARY != 0 && e.y >= bodyTop) {
                val i = rowAt(e.y)
                if (i in channels.indices) { index = i; invalidate(); long() }
                return true
            }
            return gestures.onTouchEvent(e)
        }

        /** Колесо мыши: каналы; с Shift — время сетки */
        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(e)
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (e.metaState and android.view.KeyEvent.META_SHIFT_ON != 0 || h != 0f) {
                if (grid) {
                    live = false
                    winStart += (if ((if (h != 0f) h else v) > 0) -1 else 1) * Cells.SLOT
                }
            } else if (v != 0f) moveClamped(if (v > 0) -1 else 1)
            invalidate()
            return true
        }
    }

    companion object {
        /** Ширина окна сетки, с */
        const val SPAN = 7200L
    }
}
