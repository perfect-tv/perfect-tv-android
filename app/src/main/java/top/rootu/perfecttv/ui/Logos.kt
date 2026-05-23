package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.ImageView
import coil.ImageLoader
import coil.request.ImageRequest
import top.rootu.perfecttv.data.Channel

/**
 * Логотипы каналов: загрузка через Coil (дисковый кэш), в памяти — LRU битмапов для рисования
 * на Canvas (гид, списки). Нет логотипа или не загрузился — цветная плашка с аббревиатурой
 * (logoStub плагина).
 */
object Logos {
    private lateinit var loader: ImageLoader
    private val bitmaps = LruCache<String, Bitmap>(400)
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, MutableList<() -> Unit>>()

    fun init(context: Context) {
        loader = ImageLoader.Builder(context)
            .crossfade(false)
            .allowRgb565(true)
            .build()
    }

    /** Битмап логотипа или null (ещё грузится / нет). [ready] — вызвать, когда загрузится */
    fun bitmap(context: Context, ch: Channel, ready: () -> Unit): Bitmap? {
        val url = ch.logoUrl()
        if (url.isEmpty() || url in failed) return null
        bitmaps.get(url)?.let { return it }
        waiters.getOrPut(url) { ArrayList() }.add(ready)
        if (pending.add(url)) {
            loader.enqueue(ImageRequest.Builder(context).data(url).size(256, 160)
                .allowHardware(false)
                .listener(onError = { _, r -> android.util.Log.w("IPTV", "logo $url: ${r.throwable}") })
                .target(
                    onSuccess = { d ->
                        pending.remove(url)
                        val bm = (d as? BitmapDrawable)?.bitmap
                        if (bm != null) bitmaps.put(url, bm) else failed.add(url)
                        waiters.remove(url)?.forEach { it() }
                    },
                    onError = {
                        pending.remove(url)
                        failed.add(url)
                        waiters.remove(url)?.forEach { it() }
                    }
                ).build())
        }
        return null
    }

    /** Логотип в ImageView (инфопанель, карточки); заглушка рисуется сразу */
    fun into(view: ImageView, ch: Channel) {
        val stub = Stub.of(ch.name)
        view.setImageDrawable(StubDrawable(stub))
        val bm = bitmap(view.context, ch) { bitmaps.get(ch.logoUrl())?.let { if (view.tag === ch) view.setImageBitmap(it) } }
        view.tag = ch
        if (bm != null) view.setImageBitmap(bm)
    }

    private val dst = RectF()

    /** Тёмная подложка логотипа на белом (строка в фокусе): белые логотипы иначе не видно */
    val PLATE = Color.rgb(42, 44, 47)

    /**
     * Нарисовать логотип канала (вписать по центру) или заглушку в прямоугольник.
     * @param plate подложить тёмную плашку (строка в фокусе с белым фоном)
     */
    fun draw(c: Canvas, context: Context, ch: Channel, l: Float, t: Float, r: Float, b: Float, plate: Boolean = false, invalidate: () -> Unit) {
        val bm = bitmap(context, ch, invalidate)
        if (plate && bm != null) {
            val pad = (b - t) * 0.12f
            U.roundRect(c, l - pad, t - pad, r + pad, b + pad, U.px(U.RADIUS_S), PLATE)
        }
        if (bm == null) {
            Stub.of(ch.name).draw(c, l, t, r, b)
            return
        }
        val scale = minOf((r - l) / bm.width, (b - t) / bm.height)
        val w = bm.width * scale
        val h = bm.height * scale
        dst.set((l + r - w) / 2, (t + b - h) / 2, (l + r + w) / 2, (t + b + h) / 2)
        c.drawBitmap(bm, null, dst, U.fill.apply { color = Color.WHITE; isFilterBitmap = true })
    }

    /** Плашка-заглушка: аббревиатура названия на цвете от хеша */
    class Stub(val text: String, val bg: Int, val fg: Int) {
        fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
            val h = b - t
            val w = minOf(r - l, h * 1.45f)
            val x = (l + r - w) / 2
            U.roundRect(c, x, t, x + w, b, U.px(U.RADIUS_S), bg)
            paint.color = fg
            paint.textSize = h * (if (text.length > 4) 0.28f else 0.36f)
            val tw = paint.measureText(text)
            c.drawText(text, (l + r - tw) / 2, (t + b) / 2 - (paint.descent() + paint.ascent()) / 2, paint)
        }

        companion object {
            private val paint = U.paint(1f, bold = true)
            private val cache = HashMap<String, Stub>()
            private val SPACES = Regex("\\s+")
            private val SHORT = Regex("^(\\+?\\d+|[UF]?HD|4K)$", RegexOption.IGNORE_CASE)

            fun of(title: String): Stub = cache.getOrPut(title) {
                val name = title.replace(Regex("\\s+\\(([+-]?\\d+)\\)"), " $1")
                    .replace(Regex("[-.()\\s]+"), " ")
                    .replace(Regex("(^|\\s+)(TV|ТВ)(\\s+|$)", RegexOption.IGNORE_CASE), "$3")
                    .trim()
                val text = if (name.replace(SPACES, "").length > 5)
                    name.split(SPACES).joinToString("") { if (SHORT.matches(it)) it else it.take(1).uppercase() }.take(6)
                else name.replace(SPACES, "")
                var h = 0
                for (ch in title) h = (h shl 5) - h + ch.code
                val rgb = (h and 0xFFFFFF)
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF
                val fg = if (r * 0.299 + g * 0.587 + b * 0.114 > 186) Color.BLACK else Color.WHITE
                Stub(text.ifEmpty { "?" }, Color.rgb(r, g, b), fg)
            }
        }
    }

    private class StubDrawable(val stub: Stub) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) {
            val r = bounds
            stub.draw(canvas, r.left.toFloat(), r.top.toFloat() + r.height() * 0.1f, r.right.toFloat(), r.bottom.toFloat() - r.height() * 0.1f)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
