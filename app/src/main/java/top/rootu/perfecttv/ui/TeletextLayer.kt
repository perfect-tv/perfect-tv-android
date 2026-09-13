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
 * экранная клавиатура (цифры, цветные кнопки, стереть, индекс, стоп подстраниц, фон).
 *
 * Фокус управления — в одной из двух зон. Клавиатура: стрелки по кнопкам, OK — нажать, ← из левого
 * столбца — на страницу. Страница (вокруг неё рамка): ↑/↓ — страница, ←/→ — подстраницы, OK — к клавиатуре.
 * Цифры и цветные кнопки пульта работают всегда; PG± — на десять страниц; удерж. OK или MENU —
 * меню телетекста ([onMenu]); «Назад» стирает набранное, потом закрывает. Страницы, которых ещё не
 * было в эфире, ждём: в потоке они идут по кругу, обычно это секунды.
 */
class TeletextLayer(
    private val context: Context,
    private val decoder: TtxDecoder,
    startPage: Int,
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

    // клавиатура: 6 рядов по 3 кнопки
    private val keypad: View = LayoutInflater.from(context).inflate(R.layout.teletext_keypad, root, false)
    private val padViews = ArrayList<TextView>()
    private val padKeys = ArrayList<String>()
    private var padIndex = 4  // «5»
    /** 0 — клавиатура, 1 — страница */
    private var zone = 0

    var padVisible = Prefs.ttxKeypad
        private set
    val mix get() = tv.mix
    val reveal get() = tv.reveal
    /** Автолистание подстраниц остановлено */
    var hold = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            phase = !phase
            tv.flashOn = phase
            // подстраницы листаются сами, как на телевизоре
            val n = decoder.subpages(page).size
            if (n > 1 && !hold && System.currentTimeMillis() - lastSwitch > SUBPAGE_MS) { sub = (sub + 1) % n; lastSwitch = System.currentTimeMillis(); seen = -1 }
            if (decoder.version != seen) refresh()
            handler.postDelayed(this, 500)
        }
    }

    init {
        root.addView(tv, FrameLayout.LayoutParams(-1, -1))
        root.addView(keypad, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = U.pxi(1.2f) })
        val rows = (keypad as LinearLayout)
        for (r in 0 until rows.childCount) {
            val line = rows.getChildAt(r) as LinearLayout
            for (c in 0 until line.childCount) {
                val v = line.getChildAt(c) as TextView
                val i = padViews.size
                padViews.add(v)
                padKeys.add(v.tag as String)
                v.setOnClickListener { padIndex = i; zone = 0; press(padKeys[i]); render() }
            }
        }
        // цветные кнопки — цветом, в фокусе — с белой рамкой
        for ((k, c) in listOf("r" to 1, "g" to 2, "y" to 3, "b" to 4)) padViews[padKeys.indexOf(k)].background = colorKey(TTX_COLORS[c])
        tv.mix = Prefs.ttxMix
        applyPad()
        lastSwitch = System.currentTimeMillis()
        refresh()
        handler.postDelayed(tick, 500)
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
        if (!padVisible) zone = 1
        render()
    }

    private fun render() {
        padViews.forEachIndexed { i, v -> v.isSelected = padVisible && zone == 0 && i == padIndex }
        padViews[padKeys.indexOf("hold")].text = if (hold) "▶" else "‖"
        tv.focused = padVisible && zone == 1
    }

    private fun refresh() {
        seen = decoder.version
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
            "hold" -> toggleHold()
            "mix" -> toggleMix()
            else -> digit(k.toInt())
        }
    }

    // ------------------------------------------------------------ состояние для меню телетекста

    fun toggleKeypad() { padVisible = !padVisible; Prefs.ttxKeypad = padVisible; if (padVisible) zone = 0; applyPad() }
    fun toggleMix() { tv.mix = !tv.mix; Prefs.ttxMix = tv.mix }
    fun toggleReveal() { tv.reveal = !tv.reveal }
    fun toggleHold() { hold = !hold; lastSwitch = System.currentTimeMillis(); render() }
    fun close() = onClose()

    // ------------------------------------------------------------ клавиши

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") return digit(digit)
        when (name) {
            "pgup" -> return go(step(page, 10))
            "pgdown" -> return go(step(page, -10))
        }
        if (zone == 0 && padVisible) {
            val r = padIndex / 3
            val c = padIndex % 3
            when (name) {
                "left" -> if (c > 0) padIndex-- else zone = 1
                "right" -> if (c < 2) padIndex++
                "up" -> padIndex = ((r + 5) % 6) * 3 + c
                "down" -> padIndex = ((r + 1) % 6) * 3 + c
            }
            render()
        } else when (name) {
            "up" -> go(step(page, 1))
            "down" -> go(step(page, -1))
            "left" -> subpage(-1)
            "right" -> subpage(1)
        }
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
        if (zone == 0 && padVisible) press(padKeys[padIndex])
        else if (padVisible) { zone = 0; render() }
        else toggleReveal()
        render()
    }

    override fun long() = onMenu()

    override fun back() {
        if (typed.isNotEmpty()) { typed = ""; seen = -1; refresh() } else onClose()
    }

    override fun destroy() {
        handler.removeCallbacks(tick)
    }

    companion object {
        private const val SUBPAGE_MS = 6000L

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

        private fun next(n: Int): Int {
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
