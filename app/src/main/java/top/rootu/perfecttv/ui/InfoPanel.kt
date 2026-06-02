package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.Programme

/**
 * Инфопанель внизу экрана (порт ui/info.js): логотип, канал, бейджи, часы, передача,
 * шкала с буфером и маркером перемотки, «Далее». show(сек) — с автоскрытием,
 * show(0) — закреплена (↓). setMore() — раскрытое описание под основной строкой.
 */
class InfoPanel(context: Context) {

    class Model(
        val channel: Channel,
        val program: Programme?,
        val next: Programme?,
        val archive: Boolean,
        val position: Long,
        val ahead: Long,
        val seek: Long?,
        val height: Int,
        val paused: Boolean,
        val error: Boolean
    )

    private val main = Handler(Looper.getMainLooper())
    var visible = false
        private set
    var sticky = false
        private set
    var more = false
        private set
    var onHide: (() -> Unit)? = null
    /** Кнопки панели на сенсорных устройствах: prev, pause, next, more, guide, menu, history */
    var onAction: ((String) -> Unit)? = null

    private var channel: Channel? = null

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = U.bg(U.PANEL, U.RADIUS_L)
        setPadding(U.pxi(1.2f), U.pxi(1f), U.pxi(1.5f), U.pxi(1f))
        visibility = View.GONE
    }
    // подложку, отступ и бейдж номера рисует сам логотип (Logos)
    private val logo = ImageView(context)
    private val chName = U.text(context, 1.1f, 0.75f)
    private val badges = U.text(context, 0.85f)
    private val clock = U.text(context, 1.25f, 0.9f)
    private val title = U.text(context, 1.6f, bold = true)
    private val timeStart = U.text(context, 0.95f, 0.7f)
    private val timeEnd = U.text(context, 0.95f, 0.7f)
    private val bar = Bar(context)
    private val next = U.text(context, 1f, 0.65f)
    private val moreBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val moreBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val banner = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = U.bg(U.FILL_SOFT, U.RADIUS)
        clipToOutline = true
    }
    private val moreTitle = U.text(context, 1.2f, bold = true, lines = 2)
    private val moreTags = U.text(context, 0.95f, 0.6f)
    private val moreText = U.text(context, 1f, 0.8f, lines = 7)

    init {
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(logo, LinearLayout.LayoutParams(U.pxi(8f), U.pxi(5.6f)).apply { rightMargin = U.pxi(1.2f) })
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(chName, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(badges, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = U.pxi(0.6f) })
        line.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = U.pxi(1.2f) })
        body.addView(line)
        body.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.25f) })
        val prog = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prog.addView(timeStart, LinearLayout.LayoutParams(-2, -2))
        prog.addView(bar, LinearLayout.LayoutParams(0, U.pxi(0.35f), 1f).apply { leftMargin = U.pxi(0.7f); rightMargin = U.pxi(0.7f) })
        prog.addView(timeEnd, LinearLayout.LayoutParams(-2, -2))
        body.addView(prog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.5f) })
        body.addView(next, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.45f) })
        if (U.touch) {
            // тач (не ТВ): кнопки под «Далее», как в плагине
            val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, a) in listOf("◀" to "prev", "❚❚" to "pause", "▶" to "next", str(R.string.about) to "more", str(R.string.guide) to "guide", str(R.string.menu) to "menu", str(R.string.recent) to "history")) {
                bar.addView(U.text(context, 1f).apply {
                    text = label
                    background = U.bg(U.FILL_BTN, U.RADIUS_S)
                    setPadding(U.pxi(0.9f), U.pxi(0.45f), U.pxi(0.9f), U.pxi(0.45f))
                    setOnClickListener { onAction?.invoke(a) }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = U.pxi(0.5f) })
            }
            body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.7f) })
        }
        top.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(top)
        view.isClickable = true   // тап по панели не уходит в жесты видео
        // баннер TMDB (или логотип канала) слева, справа — название, теги, описание
        moreBox.addView(banner, LinearLayout.LayoutParams(U.pxi(22f), U.pxi(12.4f)).apply { rightMargin = U.pxi(1.5f) })
        moreBody.addView(moreTitle)
        moreBody.addView(moreTags, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.3f) })
        moreBody.addView(moreText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.5f) })
        moreBox.addView(moreBody, LinearLayout.LayoutParams(0, -2, 1f))
        moreText.gravity = Gravity.TOP
        view.addView(moreBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(1f) })
        U.show(moreBox, false)
    }

    /** Разместить в корне: внизу, с отступами */
    fun attach(root: FrameLayout) {
        root.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = U.pxi(2f); rightMargin = U.pxi(2f); bottomMargin = U.pxi(1.5f)
        })
    }

    private fun set(v: TextView, s: CharSequence) {
        if (v.text.toString() != s.toString() || s is Spanned) v.text = s
    }

    fun update(m: Model) {
        val ch = m.channel
        if (channel !== ch) {
            channel = ch
            Logos.into(logo, ch)
        }
        set(chName, ch.title())
        set(clock, Fmt.hhmm(Clock.now()))
        set(badges, badgesOf(m))
        val p = m.program
        val ref = m.seek ?: m.position
        set(title, p?.title ?: if (m.archive || m.seek != null) str(R.string.archive) + Fmt.hhmm(ref) else str(R.string.no_programme))
        // шкала: передача, а без EPG — получасовой слот вокруг позиции
        val from = p?.start ?: (ref - ref % SLOT)
        val to = p?.stop ?: (from + SLOT)
        set(timeStart, Fmt.hhmm(from))
        set(timeEnd, Fmt.hhmm(to))
        val f = { x: Long -> Fmt.progress(from, to, x) }
        bar.set(f(m.position), if (m.ahead > 0) maxOf(f(m.position + m.ahead), f(m.position) + 0.01f) else f(m.position), m.seek?.let { f(it) })
        val now = Clock.now()
        if (m.seek != null) {
            val diff = m.seek - now
            set(next, hhmmss(m.seek) + " (" + (if (diff < 0) "−" else "+") + Fmt.duration(Math.abs(diff)) + ")")
        } else {
            var s = ""
            if (m.archive) s = hhmmss(m.position) + " (−" + Fmt.duration(now - m.position) + ") · "
            s += str(R.string.next) + (m.next?.let { Fmt.hhmm(it.start) + " " + it.title } ?: str(R.string.no_programme_2))
            set(next, s)
        }
    }

    private fun badgesOf(m: Model): CharSequence {
        val sb = SpannableStringBuilder()
        fun badge(text: String, bg: Int, fg: Int = U.WHITE) {
            if (sb.isNotEmpty()) sb.append("  ")
            val s = sb.length
            sb.append(" ").append(text).append(" ")
            sb.setSpan(BackgroundColorSpan(bg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(fg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (m.error) badge(str(R.string.error), U.LIVE)
        if (m.paused) badge("❚❚", U.FILL_ACTIVE)
        if (m.archive) badge(str(R.string.archive_2), U.WHITE, U.BLACK) else badge(str(R.string.live), U.LIVE)
        if (m.height > 0) badge(if (m.height >= 2000) "4K" else if (m.height >= 1000) "FHD" else if (m.height >= 700) "HD" else "SD", U.FILL_ACTIVE)
        if (m.channel.hasArchive) badge("⟲ " + str(R.string.x_d, m.channel.archiveDays), U.FILL_ACTIVE)
        return sb
    }

    private fun hhmmss(sec: Long): String {
        val s = (sec % 60).toInt()
        return Fmt.hhmm(sec) + ":" + (if (s < 10) "0$s" else s.toString())
    }

    private var bannerUrl: String? = null

    /**
     * Раскрытое описание: null — свернуть. [image] — баннер TMDB; пусто — логотип канала [ch]
     * в области баннера.
     */
    fun setMore(t: String?, tags: String, text: String, image: String = "", ch: Channel? = null) {
        more = t != null
        if (t != null) {
            moreTitle.text = t
            moreTags.text = tags
            moreText.text = text
            U.show(moreText, text.isNotEmpty())
            if (image.isNotEmpty()) {
                if (bannerUrl != image) {
                    bannerUrl = image
                    banner.scaleType = ImageView.ScaleType.CENTER_CROP
                    banner.setPadding(0, 0, 0, 0)
                    banner.background = U.bg(U.FILL_SOFT, U.RADIUS)
                    Logos.banner(banner, image)
                }
            } else if (ch != null) {
                bannerUrl = null
                banner.scaleType = ImageView.ScaleType.FIT_CENTER
                // логотип со своей подложкой на всю область баннера
                banner.setPadding(0, 0, 0, 0)
                banner.background = null
                Logos.into(banner, ch)
            }
        }
        U.show(moreBox, more)
    }

    fun collapse() {
        more = false
        U.show(moreBox, false)
    }

    private val hideRun = Runnable { hide() }

    /** timeout 0 — закрепить; закреплённую повторный show с таймаутом не открепляет */
    fun show(timeout: Int) {
        if (visible && sticky && timeout != 0) return
        sticky = timeout == 0
        if (!visible) {
            visible = true
            view.visibility = View.VISIBLE
            if (Anim.on) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(150).start()
            }
        }
        main.removeCallbacks(hideRun)
        if (timeout != 0) main.postDelayed(hideRun, timeout * 1000L)
    }

    fun hide() {
        main.removeCallbacks(hideRun)
        if (!visible) return
        onHide?.invoke()
        visible = false
        sticky = false
        collapse()
        Anim.hide(view)
    }

    /** Шкала прогресса: подложка, буфер, значение, маркер перемотки */
    private class Bar(context: Context) : View(context) {
        private var value = 0f
        private var buffer = 0f
        private var seek: Float? = null

        fun set(v: Float, b: Float, s: Float?) {
            if (v == value && b == buffer && s == seek) return
            value = v; buffer = b; seek = s
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            U.roundRect(c, 0f, 0f, w, h, r, U.FILL_MARKED)
            U.roundRect(c, 0f, 0f, w * buffer, h, r, U.wa(0.35f))
            U.roundRect(c, 0f, 0f, w * value, h, r, if (seek != null) U.wa(0.6f) else U.WHITE)
            seek?.let {
                val x = w * it
                U.roundRect(c, x - h * 0.6f, -h * 0.8f, x + h * 0.6f, h * 1.8f, h * 0.3f, U.WHITE)
            }
        }
    }

    companion object {
        const val SLOT = 1800L
    }
}

/** Плавные появления/скрытия (настройка «Плавные анимации») */
object Anim {
    var on = true

    fun hide(v: View, done: (() -> Unit)? = null) {
        if (!on) {
            v.visibility = View.GONE
            done?.invoke()
            return
        }
        v.animate().alpha(0f).setDuration(140).withEndAction {
            v.visibility = View.GONE
            v.alpha = 1f
            done?.invoke()
        }.start()
    }

    fun show(v: View) {
        v.visibility = View.VISIBLE
        if (!on) return
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(150).start()
    }
}
