package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.Programme

/**
 * Инфопанель внизу экрана (info_panel.xml): затемнение во всю ширину, логотип, канал, бейджи,
 * часы, передача, шкала с буфером и маркером перемотки, «ещё N мин» (в архиве — время кадра),
 * «Далее» (при перемотке на её месте — пузырь с целевым временем под маркером).
 * show(сек) — с автоскрытием, show(0) — закреплена (↓): внизу — подсказки клавиш.
 * setMore() — раскрытое описание под основной строкой.
 */
class InfoPanel(val view: View) {

    class Model(
        val channel: Channel,
        val program: Programme?,
        val next: Programme?,
        val next2: Programme?,
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
    private val remain: TextView = view.findViewById(R.id.info_remain)
    private val next: TextView = view.findViewById(R.id.info_next)
    private val seekBubble: TextView = view.findViewById(R.id.info_seek)
    private val moreBox: View = view.findViewById(R.id.info_more)
    private val banner: ImageView = view.findViewById(R.id.info_banner)
    private val upnext: LinearLayout = view.findViewById(R.id.info_upnext)
    private val hints: LinearLayout = view.findViewById(R.id.info_hints)
    private val moreTags: TextView = view.findViewById(R.id.info_more_tags)
    private val moreText: TextView = view.findViewById(R.id.info_more_text)

    init {
        view.background = Scrim(view.resources.getDimension(R.dimen.info_fade), view.resources.getColor(R.color.scrim, null))
        banner.clipToOutline = true
        // колонки «О передаче» — долями ширины панели (ширина экрана в dp зависит от размера
        // интерфейса): картинка 25% (16:9), «Далее на канале» 27%, остальное — описание
        val dm = view.resources.displayMetrics
        val cw = dm.widthPixels - 2 * view.resources.getDimensionPixelSize(R.dimen.screen_pad)
        banner.layoutParams.width = Math.round(cw * 0.25f)
        banner.layoutParams.height = Math.round(cw * 0.25f * 9 / 16)
        upnext.layoutParams.width = Math.round(cw * 0.27f)
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
        // справа от шкалы: эфир — сколько осталось, архив — время кадра (при перемотке оно в пузыре)
        set(remain, when {
            m.seek != null -> ""
            m.archive -> hhmmss(m.position)
            p != null -> remainText(p.stop - m.position)
            else -> ""
        })
        if (m.seek != null) {
            val diff = m.seek - Clock.now()
            set(seekBubble, hhmmss(m.seek) + "   " + (if (diff < 0) "−" else "+") + Fmt.duration(Math.abs(diff)))
            seekAt = f(m.seek)
            placeSeek()
            next.visibility = View.INVISIBLE
            seekBubble.visibility = View.VISIBLE
        } else {
            seekBubble.visibility = View.INVISIBLE
            next.visibility = View.VISIBLE
            set(next, nextText(m))
        }
        hintArchive = m.archive
        hintPaused = m.paused
        updateHints()
    }

    /** «ещё 22 мин» / «ещё 1 ч 05 мин»; передача кончилась — пусто */
    private fun remainText(sec: Long): String {
        if (sec <= 0) return ""
        val min = (sec + 59) / 60
        return if (min < 60) str(R.string.x_min_left, min) else str(R.string.x_h_x_min_left, min / 60, (min % 60).let { if (it < 10) "0$it" else it.toString() })
    }

    /** «Далее: 00:55 Новости · 01:10 Все на Матч!» — подпись приглушена */
    private fun nextText(m: Model): CharSequence {
        val sb = SpannableStringBuilder(str(R.string.next))
        sb.setSpan(ForegroundColorSpan(U.wa(0.5f)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val n = m.next ?: return sb.append(str(R.string.no_programme_2))
        sb.append(Fmt.hhmm(n.start)).append(' ').append(n.title)
        m.next2?.let { sb.append("   ·   ").append(Fmt.hhmm(it.start)).append(' ').append(it.title) }
        return sb
    }

    private var seekAt = 0f

    /** Пузырь перемотки — под маркером шкалы (шкала и строка пузыря начинаются с одного края) */
    private fun placeSeek() {
        if (bar.width == 0) {
            seekBubble.post { if (bar.width != 0) placeSeek() }
            return
        }
        seekBubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = seekBubble.measuredWidth
        val max = ((seekBubble.parent as View).width - w).toFloat()
        seekBubble.translationX = (bar.left + bar.width * seekAt.coerceIn(0f, 1f) - w / 2f).coerceIn(0f, maxOf(0f, max))
    }

    // ---------------------------------------------------------- подсказки клавиш

    private var hintArchive = false
    private var hintPaused = false
    private var hintsKey = ""

    /** Строка подсказок у закреплённой панели (настройка «Подсказки клавиш»; на сенсорных — кнопки) */
    private fun updateHints() {
        val show = visible && sticky && Prefs.keyHints && !U.touch
        U.show(hints, show)
        if (!show) return
        val key = "$hintArchive$hintPaused$more"
        if (key == hintsKey && hints.childCount > 0) return
        hintsKey = key
        hints.removeAllViews()
        val inflater = android.view.LayoutInflater.from(view.context)
        fun hint(k: String, action: String) {
            val v = inflater.inflate(R.layout.hint_item, hints, false)
            v.findViewById<TextView>(R.id.hint_key).text = k
            v.findViewById<TextView>(R.id.hint_action).text = action
            hints.addView(v)
        }
        hint("OK", str(if (hintPaused) R.string.hint_resume else R.string.hint_pause))
        if (more) hint("↑", str(R.string.hint_collapse)) else {
            hint("↓", str(R.string.about))
            hint("↑", str(R.string.close))
        }
        hint("←  →", str(if (hintArchive) R.string.hint_seek else R.string.hint_channel))
        hint(str(R.string.key_hold_ok), str(R.string.menu))
    }

    private fun badgesOf(m: Model): CharSequence {
        val sb = SpannableStringBuilder()
        fun badge(text: String, bg: Int, fg: Int = U.WHITE) {
            if (sb.isNotEmpty()) sb.append(" ")
            val s = sb.length
            sb.append(text)
            sb.setSpan(BadgeSpan(bg, fg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (m.error) badge(str(R.string.error), U.LIVE)
        if (m.paused) badge("❚❚", U.FILL_ACTIVE)
        if (m.archive) {
            // отставание от эфира — в бейдже: «Архив · −21:40»
            val lag = Clock.now() - m.position
            badge(str(R.string.archive_2) + (if (lag > 0) " · −" + Fmt.duration(lag) else ""), U.ARCHIVE, U.ON_ARCHIVE)
        } else badge(str(R.string.live), U.LIVE)
        if (m.height > 0) badge(if (m.height >= 2000) "4K" else if (m.height >= 1000) "FHD" else if (m.height >= 700) "HD" else "SD", U.FILL_ACTIVE)
        if (m.channel.hasArchive) badge("⟲ " + str(R.string.x_d, m.channel.archiveDays), U.FILL_ACTIVE)
        return sb
    }

    /** Бейдж: скруглённая плашка с полями вокруг текста (по высоте — на всю строку, она задана в разметке) */
    private class BadgeSpan(private val bg: Int, private val fg: Int) : ReplacementSpan() {
        private val padH = U.px(0.6f)
        private val gap = U.px(0.3f)

        override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?) =
            Math.round(paint.measureText(text, start, end) + 2 * padH + gap)

        override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            val w = paint.measureText(text, start, end) + 2 * padH
            // плашка — по росту текста с полями сверху и снизу, а не по строке
            val mid = y + (paint.ascent() + paint.descent()) / 2
            val half = paint.textSize * 0.82f
            val color = paint.color
            paint.color = bg
            c.drawRoundRect(x, mid - half, x + w, mid + half, U.px(0.4f), U.px(0.4f), paint)
            paint.color = fg
            c.drawText(text, start, end, x + padH, y.toFloat(), paint)
            paint.color = color
        }
    }

    private fun hhmmss(sec: Long): String {
        val s = (sec % 60).toInt()
        return Fmt.hhmm(sec) + ":" + (if (s < 10) "0$s" else s.toString())
    }

    private var bannerUrl: String? = null

    /**
     * Раскрытое описание (название — в самой панели): [tags] — время, жанр, год…; [image] —
     * картинка передачи (EPG или TMDB); пусто или не загрузилась — логотип канала [ch]
     * в её области; [upcoming] — «Далее на канале» (блок не пустой и без описания).
     */
    fun setMore(tags: String, text: String, image: String = "", ch: Channel? = null, upcoming: List<Programme> = emptyList()) {
        more = true
        moreTags.text = tags
        moreText.text = text
        U.show(moreText, text.isNotEmpty())
        if (image.isNotEmpty()) {
            if (bannerUrl != image) {
                bannerUrl = image
                banner.scaleType = ImageView.ScaleType.CENTER_CROP
                banner.setBackgroundResource(R.drawable.bg_soft)
                Logos.banner(banner, image) { if (bannerUrl == image && ch != null) logoBanner(ch) }
            }
        } else if (ch != null) logoBanner(ch)
        // «Далее на канале»: первая строка блока — заголовок из разметки
        while (upnext.childCount > 1) upnext.removeViewAt(upnext.childCount - 1)
        val inflater = android.view.LayoutInflater.from(view.context)
        for (p in upcoming) {
            val row = inflater.inflate(R.layout.upnext_row, upnext, false)
            row.findViewById<TextView>(R.id.upnext_time).text = Fmt.hhmm(p.start)
            row.findViewById<TextView>(R.id.upnext_title).text = p.title
            upnext.addView(row)
        }
        U.show(upnext, upcoming.isNotEmpty())
        updateHints()
        animateMore(true)
    }

    /** Логотип канала со своей подложкой на всю область картинки (без бейджа номера) */
    private fun logoBanner(ch: Channel) {
        bannerUrl = null
        banner.scaleType = ImageView.ScaleType.FIT_CENTER
        banner.background = null
        Logos.into(banner, ch, badge = false)
    }

    fun collapse() {
        more = false
        updateHints()
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
        updateHints()
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

/**
 * Затемнение под инфопанелью: сверху плавно проявляется на длине [fade] (она задана в px,
 * поэтому не растягивается, когда панель становится выше — раскрыто описание), ниже — плотное.
 */
private class Scrim(private val fade: Float, private val color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint()

    private fun c(k: Float) = (color and 0xFFFFFF) or (Math.round(android.graphics.Color.alpha(color) * k) shl 24)

    override fun onBoundsChange(b: android.graphics.Rect) {
        val h = b.height().toFloat()
        if (h <= 0f) return
        // к началу содержимого (конец плавной части) — уже 94% плотности: текст читается на светлом кадре
        val f = (fade / h).coerceAtMost(0.95f)
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(c(0f), c(0.55f), c(0.94f), color), floatArrayOf(0f, f * 0.5f, f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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
