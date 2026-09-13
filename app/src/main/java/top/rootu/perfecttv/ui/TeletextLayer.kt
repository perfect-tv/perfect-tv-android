package top.rootu.perfecttv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.teletext.Ttx
import top.rootu.perfecttv.teletext.TtxDecoder

/**
 * Телетекст на весь экран: страницы из [TtxDecoder] (их копит плеер, пока идёт канал).
 * 0–9 — номер страницы (три цифры), ↑/↓ — следующая/предыдущая, PG± — на десять,
 * ←/→ — подстраницы, цветные кнопки — быстрые ссылки страницы, OK — показать скрытый текст,
 * «Назад» или клавиша телетекста — выход. Страницы, которых ещё не было в эфире, ждём:
 * в потоке они идут по кругу, обычно это секунды.
 */
class TeletextLayer(
    context: Context,
    private val decoder: TtxDecoder,
    startPage: Int,
    private val onClose: () -> Unit
) : Layer {

    private val tv = TeletextView(context)
    override val view: View get() = tv
    override val holdRepeat get() = false

    private val handler = Handler(Looper.getMainLooper())
    private var page = startPage
    private var sub = 0
    private var typed = ""
    private var seen = -1
    private var phase = true
    private var lastSwitch = 0L

    private val tick = object : Runnable {
        override fun run() {
            phase = !phase
            tv.flashOn = phase
            // подстраницы листаются сами, как на телевизоре
            val n = decoder.subpages(page).size
            if (n > 1 && System.currentTimeMillis() - lastSwitch > SUBPAGE_MS) { sub = (sub + 1) % n; lastSwitch = System.currentTimeMillis(); seen = -1 }
            if (decoder.version != seen) refresh()
            handler.postDelayed(this, 500)
        }
    }

    init {
        lastSwitch = System.currentTimeMillis()
        refresh()
        handler.postDelayed(tick, 500)
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

    private fun blank() = top.rootu.perfecttv.teletext.TtxPage(page, 0, 0, 0, arrayOfNulls(25), null)

    private fun go(n: Int) {
        page = n
        sub = 0
        typed = ""
        lastSwitch = System.currentTimeMillis()
        seen = -1
        refresh()
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        when (name) {
            "digit" -> {
                // первая цифра 1–8 (страницы 100–8FF); набрано три — переходим
                if (typed.isEmpty() && digit !in 1..8) return
                typed += digit
                if (typed.length == 3) go(typed.toInt(16)) else { seen = -1; refresh() }
            }
            "up" -> go(step(page, 1))
            "down" -> go(step(page, -1))
            "pgup" -> go(step(page, 10))
            "pgdown" -> go(step(page, -10))
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

    // цветные кнопки: ссылки Fastext текущей страницы
    override fun media(name: String) {
        val idx = when (name) { "red" -> 0; "green" -> 1; "yellow" -> 2; "blue" -> 3; else -> -1 }
        if (idx >= 0) {
            val subs = decoder.subpages(page)
            val link = subs.getOrNull(sub)?.links?.getOrNull(idx) ?: 0
            if (link != 0) go(link)
            return
        }
        if (name == "teletext") onClose()
    }

    override fun enter() {
        tv.reveal = !tv.reveal
    }

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
