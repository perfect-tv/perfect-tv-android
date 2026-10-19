package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.teletext.TTX_COLORS
import top.rootu.perfecttv.teletext.Ttx
import top.rootu.perfecttv.teletext.TtxDecoder
import top.rootu.perfecttv.teletext.TtxPage

/**
 * Телетекст на весь экран: страницы из [TtxDecoder] (их копит плеер, пока идёт канал); справа —
 * экранная клавиатура (цифры, цветные кнопки, стереть, индекс, подстраницы) как PIN «Google».
 *
 * Клавиатура (по OK, «Назад» скрывает): ↑/↓ — ряд (по кругу), ← / OK / → — левая, средняя, правая кнопка
 * ряда; над клавиатурой круг d-pad показывает кнопки текущего ряда, под ней — диапазоны принятых страниц.
 * Без клавиатуры: ←/→ — предыдущая / следующая из принятых страниц (как каналы), ↑/↓ — подстраницы.
 * Цифры и цветные кнопки пульта работают всегда; PG± — подстраницы; удерж. OK или MENU —
 * меню телетекста ([onMenu]); «Назад» стирает набранное, потом скрывает клавиатуру, потом закрывает.
 * Страница субтитров показывается по времени кадра ([TtxDecoder.subtitleAt]), а не по последней принятой. Страницы, которых ещё не
 * было в эфире, ждём: в потоке они идут по кругу, обычно это секунды.
 */
class TeletextLayer(
    private val context: Context,
    private val decoder: TtxDecoder,
    startPage: Int,
    /** Позиция воспроизведения от начала периода, мкс (-1 — неизвестна): по ней выбирается страница субтитров */
    private val position: () -> Long,
    private val onMenu: () -> Unit,
    private val onClose: () -> Unit
) : Layer {

    private val root = FrameLayout(context)
    private val tv = TeletextView(context)
    override val view: View get() = root
    override val holdRepeat get() = false

    private val handler = Handler(Looper.getMainLooper())
    var page = startPage
        private set
    private var sub = 0
    private var typed = ""
    private var seen = -1
    private var phase = true
    private var lastSwitch = 0L

    // клавиатура: 6 рядов по 3 кнопки, выбран ряд [row]
    private val keypad: View = LayoutInflater.from(context).inflate(R.layout.teletext_keypad, root, false)
    private val padViews = ArrayList<TextView>()
    private val padKeys = ArrayList<String>()
    private var row = 1  // «4 5 6»
    private val wheel: TeletextWheel = keypad.findViewById(R.id.ttx_wheel)
    private val rangesView: TextView = keypad.findViewById(R.id.ttx_ranges)

    var padVisible = Prefs.ttxKeypad
        private set
    val mix get() = tv.mix
    val reveal get() = tv.reveal
    /** Автолистание подстраниц остановлено */
    var hold = false
        private set

    private var beat = 0
    private val tick = object : Runnable {
        override fun run() {
            // шаг 250 мс (страница субтитров следует за кадром), мигание — по-прежнему раз в 500
            if (beat++ % 2 == 0) { phase = !phase }
            tv.flashOn = phase
            if (syncing()) { seen = -1 }
            // подстраницы листаются сами, как на телевизоре
            val n = decoder.subpages(page).size
            if (n > 1 && !hold && System.currentTimeMillis() - lastSwitch > SUBPAGE_MS) { sub = (sub + 1) % n; lastSwitch = System.currentTimeMillis(); seen = -1 }
            if (decoder.version != seen) refresh()
            handler.postDelayed(this, 250)
        }
    }

    init {
        root.addView(tv, FrameLayout.LayoutParams(-1, -1))
        root.addView(keypad, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = U.pxi(1.2f) })
        val rows = (keypad as LinearLayout)
        for (r in 0 until rows.childCount) {
            val line = rows.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until line.childCount) {
                val v = line.getChildAt(c) as TextView
                val i = padViews.size
                v.isFocusable = false  // кнопки — только по тапу, фокус Android не нужен (иначе «1» оставалась белой)
                v.isFocusableInTouchMode = false
                padViews.add(v)
                padKeys.add(v.tag as String)
                v.setOnClickListener { row = i / 3; press(padKeys[i]); render() }
            }
        }
        // цветные кнопки — цветом, в фокусе — с белой рамкой
        for ((k, c) in listOf("r" to 1, "g" to 2, "y" to 3, "b" to 4)) padViews[padKeys.indexOf(k)].background = colorKey(TTX_COLORS[c])
        tv.mix = Prefs.ttxMix
        applyPad()
        lastSwitch = System.currentTimeMillis()
        refresh()
        handler.postDelayed(tick, 250)
    }

    private fun colorKey(color: Int): StateListDrawable {
        fun shape(stroke: Boolean) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = U.px(0.6f)
            if (stroke) setStroke(U.pxi(0.3f), 0xFFFFFFFF.toInt())
            else alpha = 200
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }

    private fun applyPad() {
        keypad.visibility = if (padVisible) View.VISIBLE else View.GONE
        tv.left = padVisible
        render()
    }

    private fun render() {
        padViews.forEachIndexed { i, v -> v.isSelected = padVisible && i / 3 == row }
        val colors = IntArray(3) { c ->
            val i = row * 3 + c
            val n = listOf("r", "g", "y", "b").indexOf(padKeys[i])
            if (n >= 0) TTX_COLORS[n + 1] else 0
        }
        wheel.set(Array(3) { c -> padViews[row * 3 + c].text.toString() }, colors)
        if (padVisible) showRanges()
    }

    private fun showRanges() {
        val r = ranges(decoder.numbers())
        val sb = android.text.SpannableStringBuilder()
        if (r.isEmpty()) sb.append(str(R.string.ttx_no_pages))
        for ((i, e) in r.withIndex()) {
            if (i > 0) sb.append("  ")
            val s0 = sb.length
            sb.append(if (e.first == e.last) "%03X".format(e.first) else "%03X–%03X".format(e.first, e.last))
            if (page in e.first..e.last) {
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()), s0, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s0, sb.length, 0)
            }
        }
        if (rangesView.text.toString() != sb.toString() || page != shownRangePage) rangesView.text = sb
        shownRangePage = page
    }
    private var shownRangePage = -1

    /** Страница субтитров идёт по времени кадра: пока на ней — обновляем по часам, а не по приёму */
    private fun syncing() = page == decoder.subtitleNumber && decoder.hasSubtitleHistory() && position() >= 0

    private fun refresh() {
        seen = decoder.version
        if (padVisible) showRanges()
        if (syncing()) {
            // субтитры: страница, что была на экране в момент текущего кадра (нет такой — пустая)
            val p = decoder.subtitleAt(position()) ?: blank()
            tv.message = ""
            tv.grid = Ttx.layout(p, Ttx.headerRow(page, p.rows[0], typed))
            return
        }
        val subs = decoder.subpages(page)
        if (subs.isEmpty()) {
            // страницы ещё нет: пустой экран с заголовком и ожиданием
            tv.grid = Ttx.layout(blank(), Ttx.headerRow(page, null, typed))
            tv.message = str(R.string.teletext_waiting, "%03X".format(page))
            return
        }
        if (sub >= subs.size) sub = 0
        val p = subs[sub]
        tv.message = ""
        tv.grid = Ttx.layout(p, Ttx.headerRow(page, p.rows[0], typed))
    }

    private fun blank() = TtxPage(page, 0, 0, 0, arrayOfNulls(25), null)

    private fun go(n: Int) {
        page = n
        sub = 0
        typed = ""
        lastSwitch = System.currentTimeMillis()
        seen = -1
        refresh()
    }

    private fun digit(d: Int) {
        // первая цифра 1–8 (страницы 100–8FF); набрано три — переходим
        if (typed.isEmpty() && d !in 1..8) return
        typed += d
        if (typed.length == 3) go(typed.toInt(16)) else { seen = -1; refresh() }
    }

    private fun link(idx: Int) {
        val link = decoder.subpages(page).getOrNull(sub)?.links?.getOrNull(idx) ?: 0
        if (link != 0) go(link)
    }

    // нажатие кнопки клавиатуры
    private fun press(k: String) {
        when (k) {
            "r" -> link(0)
            "g" -> link(1)
            "y" -> link(2)
            "b" -> link(3)
            "del" -> { typed = typed.dropLast(1); seen = -1; refresh() }
            "idx" -> go(0x100)
            "prev" -> go(known(-1))
            "next" -> go(known(1))
            else -> digit(k.toInt())
        }
    }

    // ------------------------------------------------------------ состояние для меню телетекста

    /** Из меню: клавиатура показана — и при следующем открытии телетекста */
    fun toggleKeypad() { padVisible = !padVisible; Prefs.ttxKeypad = padVisible; applyPad() }
    fun toggleMix() { tv.mix = !tv.mix; Prefs.ttxMix = tv.mix }
    fun toggleReveal() { tv.reveal = !tv.reveal }
    fun toggleHold() { hold = !hold; lastSwitch = System.currentTimeMillis(); render() }
    fun close() = onClose()

    // ------------------------------------------------------------ клавиши

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return digit(digit)
        when (name) {
            "pgup" -> return subpage(1)
            "pgdown" -> return subpage(-1)
        }
        if (padVisible) {
            when (name) {
                "left" -> press(padKeys[row * 3])
                "right" -> press(padKeys[row * 3 + 2])
                "up" -> row = (row + 5) % 6
                "down" -> row = (row + 1) % 6
            }
            render()
        } else when (name) {
            "left" -> go(known(-1))
            "right" -> go(known(1))
            "up" -> subpage(-1)
            "down" -> subpage(1)
        }
    }

    /** Соседняя принятая страница ([d] = ±1) по кругу; ничего ещё не принято — следующий номер подряд */
    private fun known(d: Int): Int {
        val nums = decoder.numbers()
        if (nums.isEmpty()) return step(page, d)
        return if (d > 0) nums.firstOrNull { it > page } ?: nums.first() else nums.lastOrNull { it < page } ?: nums.last()
    }

    private fun subpage(d: Int) {
        val n = decoder.subpages(page).size
        if (n < 2) return
        sub = (sub + d + n) % n
        lastSwitch = System.currentTimeMillis() + SUBPAGE_MS  // после ручного листания автоперелистывание не мешает дольше
        seen = -1
        refresh()
    }

    override fun media(name: String) {
        when (name) {
            "red" -> link(0)
            "green" -> link(1)
            "yellow" -> link(2)
            "blue" -> link(3)
            "teletext" -> onClose()
            "menu" -> onMenu()
        }
    }

    override fun enter() {
        if (padVisible) press(padKeys[row * 3 + 1]) else { padVisible = true; applyPad(); return }
        render()
    }

    override fun long() = onMenu()

    override fun back() {
        when {
            typed.isNotEmpty() -> { typed = ""; seen = -1; refresh() }
            padVisible -> { padVisible = false; applyPad() }  // только скрыть; настройка меню не меняется
            else -> onClose()
        }
    }

    override fun destroy() {
        handler.removeCallbacks(tick)
    }

    companion object {
        private const val SUBPAGE_MS = 6000L

        /** Номера страниц (BCD, по возрастанию) → диапазоны подряд идущих: 100,101,102,200 → 100–102, 200 */
        fun ranges(nums: List<Int>): List<IntRange> {
            val out = ArrayList<IntRange>()
            var from = -1
            var prev = -1
            for (n in nums) {
                if (from >= 0 && n == next(prev)) { prev = n; continue }
                if (from >= 0) out.add(from..prev)
                from = n; prev = n
            }
            if (from >= 0) out.add(from..prev)
            return out
        }

        /** Следующая страница на [d] шагов; номера в BCD, шестнадцатеричные «цифры» пропускаются, по кругу 100…899 */
        fun step(from: Int, d: Int): Int {
            var n = from
            var left = Math.abs(d)
            while (left > 0) {
                n = if (d > 0) next(n) else prev(n)
                left--
            }
            return n
        }

        internal fun next(n: Int): Int {
            var v = n + 1
            if (v and 0xF > 9) v = (v and 0xFF0) + 0x10
            if (v and 0xF0 > 0x90) v = (v and 0xF00) + 0x100
            return if (v > 0x899) 0x100 else v
        }

        private fun prev(n: Int): Int {
            var v = n - 1
            if (v and 0xF > 9) v = (v and 0xFF0) + 9
            if (v and 0xF0 > 0x90) v = (v and 0xF00) + 0x99
            return if (v < 0x100) 0x899 else v
        }
    }
}
