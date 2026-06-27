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
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.Programme

/**
 * Инфопанель внизу экрана: логотип, канал, бейджи, часы, передача,
 * шкала с буфером и маркером перемотки, «Далее». show(сек) — с автоскрытием,
 * show(0) — закреплена (↓). setMore() — раскрытое описание под основной строкой.
 */
class InfoPanel(val view: View) {

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

    // подложку, отступ и бейдж номера рисует сам логотип (Logos)
    private val logo: ImageView = view.findViewById(R.id.info_logo)
    private val chName: TextView = view.findViewById(R.id.info_channel)
    private val badges: TextView = view.findViewById(R.id.info_badges)
    private val clock: TextView = view.findViewById(R.id.info_clock)
    private val title: TextView = view.findViewById(R.id.info_title)
    private val timeStart: TextView = view.findViewById(R.id.info_start)
    private val timeEnd: TextView = view.findViewById(R.id.info_end)
    private val bar: InfoBar = view.findViewById(R.id.info_bar)
    private val next: TextView = view.findViewById(R.id.info_next)
    private val moreBox: View = view.findViewById(R.id.info_more)
    private val banner: ImageView = view.findViewById(R.id.info_banner)
    private val moreTitle: TextView = view.findViewById(R.id.info_more_title)
    private val moreTags: TextView = view.findViewById(R.id.info_more_tags)
    private val moreText: TextView = view.findViewById(R.id.info_more_text)

    init {
        banner.clipToOutline = true
        if (U.touch) {
            // тач (не ТВ): кнопки под «Далее»; tag кнопки — действие
            val touch = view.findViewById<LinearLayout>(R.id.info_touch)
            touch.visibility = View.VISIBLE
            for (i in 0 until touch.childCount) touch.getChildAt(i).let { b -> b.setOnClickListener { onAction?.invoke(b.tag as String) } }
        }
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
        bar.archive = m.archive
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
        if (m.archive) badge(str(R.string.archive_2), U.ARCHIVE, U.ON_ARCHIVE) else badge(str(R.string.live), U.LIVE)
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
                    banner.setBackgroundResource(R.drawable.bg_soft)
                    Logos.banner(banner, image)
                }
            } else if (ch != null) {
                bannerUrl = null
                banner.scaleType = ImageView.ScaleType.FIT_CENTER
                // логотип со своей подложкой на всю область баннера
                banner.setPadding(0, 0, 0, 0)
                banner.background = null
                Logos.into(banner, ch, badge = false)   // в области баннера номер-бейдж не нужен
            }
        }
        animateMore(more)
    }

    fun collapse() {
        more = false
        animateMore(false)
    }

    private var moreAnim: android.animation.ValueAnimator? = null

    /**
     * Описание выдвигается/сворачивается по высоте с проявлением за 0,25 с.
     * Без анимаций (настройка) или когда панель скрыта — сразу.
     */
    private fun animateMore(show: Boolean) {
        moreAnim?.cancel()
        moreAnim = null
        val lp = moreBox.layoutParams
        val shown = moreBox.visibility == View.VISIBLE
        if (!Anim.on || !visible || view.width == 0) {
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            moreBox.alpha = 1f
            U.show(moreBox, show)
            moreBox.requestLayout()
            return
        }
        if (show == shown && lp.height == LinearLayout.LayoutParams.WRAP_CONTENT) return
        val from = if (shown) moreBox.height else 0
        val to = if (show) {
            val w = view.width - view.paddingLeft - view.paddingRight
            moreBox.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            moreBox.measuredHeight
        } else 0
        val a0 = if (shown) moreBox.alpha else 0f
        lp.height = from
        moreBox.visibility = View.VISIBLE
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(250)
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            val f = it.animatedValue as Float
            lp.height = Math.round(from + (to - from) * f)
            moreBox.alpha = a0 + ((if (show) 1f else 0f) - a0) * minOf(1f, f * 1.25f)
            moreBox.requestLayout()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                moreBox.alpha = 1f
                if (!show) moreBox.visibility = View.GONE
                moreBox.requestLayout()
            }
        })
        moreAnim = anim
        anim.start()
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

    companion object {
        const val SLOT = 1800L
    }
}

/** Шкала прогресса: подложка, буфер, значение, маркер перемотки */
class InfoBar(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var value = 0f
    private var buffer = 0f
    private var seek: Float? = null

    /** Архив или сдвиг: шкала янтарная (эфир — белая) */
    var archive = false
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    fun set(v: Float, b: Float, s: Float?) {
        if (v == value && b == buffer && s == seek) return
        value = v; buffer = b; seek = s
        invalidate()
    }

    /**
     * Полоса толщиной 0,35 em по центру View; маркер перемотки — на всю высоту View (она больше
     * толщины полосы: рисовать за границами View нельзя — Android обрезает).
     */
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = minOf(h, U.px(0.35f))
        val top = (h - t) / 2
        val bottom = top + t
        val r = t / 2
        U.roundRect(c, 0f, top, w, bottom, r, U.FILL_MARKED)
        U.roundRect(c, 0f, top, w * buffer, bottom, r, U.wa(0.35f))
        val on = if (archive) U.ARCHIVE else U.WHITE
        U.roundRect(c, 0f, top, w * value, bottom, r, if (seek != null) (on and 0xFFFFFF) or (0x99 shl 24) else on)
        seek?.let {
            // маркер: белый с тёмной обводкой — виден и на белой части полосы
            val half = U.px(0.25f)
            val x = (w * it).coerceIn(half, w - half)
            val edge = U.px(0.1f)
            U.roundRect(c, x - half - edge, 0f, x + half + edge, h, half, U.PANEL)
            U.roundRect(c, x - half, edge, x + half, h - edge, half, U.WHITE)
        }
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
