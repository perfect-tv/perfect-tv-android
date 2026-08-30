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
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Channel

/**
 * Логотипы каналов: загрузка через Coil (дисковый кэш), в памяти — LRU битмапов для рисования
 * на Canvas (гид, списки). Нет логотипа или не загрузился — цветная плашка с монограммой
 * названия (`Names.monogram`); бейдж с номером — по настройке «Номер канала».
 */
object Logos {
    private lateinit var loader: ImageLoader
    private val bitmaps = LruCache<String, Bitmap>(400)
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val waiters = HashMap<String, MutableList<() -> Unit>>()

    /**
     * Загрузчик с собственным дисковым кэшем логотипов (picon): хранится, сколько бы ни разрешал
     * сервер (respectCacheHeaders = false) — логотипы почти не меняются, а новый логотип
     * в плейлисте — это новый адрес. При перезапуске логотипы берутся с диска, без сети.
     * Вызывается один раз на процесс (App.onCreate).
     */
    fun init(context: Context) {
        if (::loader.isInitialized) return
        val app = context.applicationContext
        // прежний общий кэш Coil (до своего кэша логотипов) — убрать
        java.io.File(app.cacheDir, "image_cache").deleteRecursively()
        loader = ImageLoader.Builder(app)
            .crossfade(false)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(java.io.File(app.cacheDir, "picons"))
                    .maxSizeBytes(CACHE_BYTES)
                    .build()
            }
            .build()
    }

    /**
     * Картинка целиком в битмап для фонового потока (баннеры главного экрана): через тот же
     * загрузчик и дисковый кэш. null — нет адреса, не загрузилась или загрузчик не готов.
     */
    fun fetchBlocking(context: Context, url: String, w: Int, h: Int): Bitmap? {
        if (!::loader.isInitialized || url.isEmpty()) return null
        return try {
            kotlinx.coroutines.runBlocking {
                val r = loader.execute(ImageRequest.Builder(context).data(url).size(w, h).allowHardware(false).build())
                (r.drawable as? BitmapDrawable)?.bitmap
            }
        } catch (e: Exception) { null }
    }

    /** Размер кэша логотипов на диске, байт */
    fun cacheSize(): Long = if (::loader.isInitialized) loader.diskCache?.size ?: 0L else 0L

    /** Очистить кэш логотипов (диск и память): логотипы скачаются заново */
    fun clearCache() {
        if (!::loader.isInitialized) return
        loader.diskCache?.clear()
        loader.memoryCache?.clear()
        bitmaps.evictAll()
        failed.clear()
    }

    private const val CACHE_BYTES = 64L * 1024 * 1024

    /** Битмап логотипа или null (ещё грузится / нет). [ready] — вызвать, когда загрузится */
    fun bitmap(context: Context, ch: Channel, ready: () -> Unit): Bitmap? = bitmapUrl(context, ch.logoUrl(), 256, 160, ready)

    /** То же по адресу картинки (панель передачи в гиде): размер загрузки — [w] × [h] */
    fun bitmapUrl(context: Context, url: String, w: Int, h: Int, ready: () -> Unit): Bitmap? {
        if (url.isEmpty() || url in failed) return null
        bitmaps.get(url)?.let { return it }
        waiters.getOrPut(url) { ArrayList() }.add(ready)
        if (pending.add(url)) {
            loader.enqueue(ImageRequest.Builder(context).data(url).size(w, h)
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

    /**
     * Логотип в ImageView (инфопанель, карточки, окна): рисует [LogoDrawable] — логотип или
     * номер на плашке, по настройке — с бейджем номера.
     */
    fun into(view: ImageView, ch: Channel, badge: Boolean = true) {
        view.tag = ch
        view.setImageDrawable(LogoDrawable(view, ch, badge))
    }

    /**
     * Картинка передачи (EPG, TMDB) в ImageView: без кэша битмапов для Canvas, только дисковый
     * кэш Coil. Не загрузилась — [onError] (картинки в EPG часто битые).
     */
    fun banner(view: ImageView, url: String, onError: () -> Unit = {}) {
        view.setImageDrawable(null)
        loader.enqueue(ImageRequest.Builder(view.context).data(url).target(view).listener(onError = { _, _ -> onError() }).build())
    }

    private val dst = RectF()

    /** Тёмная подложка логотипа на белом (строка в фокусе): белые логотипы иначе не видно */
    val PLATE = Color.rgb(42, 44, 47)

    /**
     * Логотип канала в прямоугольнике [l, t, r, b]: серая подложка (как под логотипами
     * в шапке гида и инфопанели), логотип внутри с отступом, по настройке «Номер канала» —
     * бейдж с номером в левом верхнем углу. Логотипа нет или не загрузился — цветная плашка
     * с монограммой (цвет — от ключа канала, у одного канала всегда один); пока логотип
     * грузится — пустая подложка, чтобы монограмма не мелькала.
     * @param plate тёмная подложка (строка в фокусе с белым фоном: белые логотипы иначе не видно)
     * @param badge рисовать бейдж (если включён в настройках)
     */
    fun draw(c: Canvas, context: Context, ch: Channel, l: Float, t: Float, r: Float, b: Float, plate: Boolean = false, badge: Boolean = true, invalidate: () -> Unit) {
        val bm = bitmap(context, ch, invalidate)
        val h = b - t
        val radius = minOf(U.px(U.RADIUS_S), h * 0.2f)
        U.roundRect(c, l, t, r, b, radius, if (plate) PLATE else U.FILL_SOFT)
        val showBadge = badge && Prefs.numberMode == Prefs.NUMBER_BADGE && ch.num > 0
        if (bm == null) {
            val url = ch.logoUrl()
            if (url.isEmpty() || url in failed) {
                drawMonogram(c, ch, l, t, r, b, radius)
                if (showBadge) drawBadge(c, ch.num, l, t, h, radius)
            }
            return
        }
        val pad = h * 0.14f
        val scale = minOf((r - l - 2 * pad) / bm.width, (h - 2 * pad) / bm.height)
        val w = bm.width * scale
        val bh = bm.height * scale
        dst.set((l + r - w) / 2, (t + b - bh) / 2, (l + r + w) / 2, (t + b + bh) / 2)
        c.drawBitmap(bm, null, dst, U.fill.apply { color = Color.WHITE; isFilterBitmap = true })
        if (showBadge) drawBadge(c, ch.num, l, t, h, radius)
    }

    private val numPaint = U.paint(1f, bold = true)

    /** Монограмма и цвет плашки канала без логотипа (считаются один раз на название) */
    private class Mono(val text: String, val color: Int)

    private val monos = LruCache<String, Mono>(600)
    private val hsv = FloatArray(3)

    private fun mono(ch: Channel): Mono {
        monos.get(ch.name)?.let { return it }
        // приглушённый тон: плашка не спорит с настоящими логотипами и с белым фокусом
        hsv[0] = Math.floorMod(top.rootu.perfecttv.data.Names.key(ch.name).hashCode() * 47, 360).toFloat()
        hsv[1] = 0.42f
        hsv[2] = 0.44f
        return Mono(top.rootu.perfecttv.data.Names.monogram(ch.name), Color.HSVToColor(hsv)).also { monos.put(ch.name, it) }
    }

    /** Цветная плашка с монограммой названия по центру */
    fun drawMonogram(c: Canvas, ch: Channel, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        val m = mono(ch)
        U.roundRect(c, l, t, r, b, radius, m.color)
        val h = b - t
        numPaint.color = U.wa(0.92f)
        numPaint.textSize = minOf(h * 0.44f, (r - l) * 0.8f / maxOf(1, m.text.length) * 1.6f)
        val tw = numPaint.measureText(m.text)
        c.drawText(m.text, (l + r - tw) / 2, (t + b) / 2 - (numPaint.descent() + numPaint.ascent()) / 2, numPaint)
    }

    /** Бейдж номера в левом верхнем углу подложки высотой [h] (угол — как у подложки) */
    // полупрозрачный, в цвет панелей интерфейса: на сером не «дыра», а пометка
    private val badgeBg = Color.argb(195, 26, 27, 28)
    private val badgePaint = U.paint(1f, U.wa(0.92f), bold = true)

    private fun drawBadge(c: Canvas, num: Int, l: Float, t: Float, h: Float, radius: Float) {
        val text = num.toString()
        badgePaint.textSize = maxOf(U.px(0.9f), h * 0.26f)
        val bh = badgePaint.textSize * 1.35f
        val bw = maxOf(bh * 1.15f, badgePaint.measureText(text) + badgePaint.textSize * 0.8f)
        U.roundRect(c, l, t, l + bw, t + bh, radius, badgeBg)
        c.drawText(text, l + (bw - badgePaint.measureText(text)) / 2, t + bh / 2 - (badgePaint.descent() + badgePaint.ascent()) / 2, badgePaint)
    }

    /** Логотип для ImageView: перерисовывается, когда битмап загрузится */
    private class LogoDrawable(private val view: ImageView, private val ch: Channel, private val badge: Boolean) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) {
            val r = bounds
            draw(canvas, view.context, ch, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), badge = badge) {
                if (view.tag === ch) invalidateSelf()
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
