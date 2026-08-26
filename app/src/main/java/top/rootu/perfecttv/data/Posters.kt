package top.rootu.perfecttv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import top.rootu.perfecttv.R
import top.rootu.perfecttv.ui.Logos
import java.io.File
import java.io.FileOutputStream

/**
 * Баннеры карточек главного экрана (16:9, [W] × [H]). Логотипы бывают квадратными, круглыми
 * и вытянутыми, поэтому карточка рисуется самими — не отдаётся как есть:
 *  - есть картинка передачи (TMDB или EPG): она на весь баннер, снизу затемнение, логотип канала —
 *    в прямоугольнике (как логотипы в программе) в левом нижнем углу;
 *  - картинки нет: логотип канала на сером фоне (как баннер «О передаче» в плеере);
 *  - логотипа у канала нет (или он не загрузился): на картинке передачи — значок приложения вместо
 *    логотипа (в той же рамке), без картинки — баннер самого приложения.
 * Файлы лежат в cacheDir/posters и отдаются лаунчеру через [Provider]; имя файла зависит от канала
 * и передачи, готовый файл не рисуется заново.
 */
object Posters {
    const val AUTHORITY = "top.rootu.perfecttv.posters"
    const val W = 800
    const val H = 450
    /** Прямоугольник логотипа, ширина к высоте (как подложка логотипа в инфопанели: 96 × 66) */
    private const val LOGO_ASPECT = 1.45f
    private val GRAY = Color.rgb(42, 44, 47)

    fun dir(context: Context) = File(context.cacheDir, "posters").also { it.mkdirs() }

    fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    /** Имя файла баннера: канал + начало передачи; «a» — с картинкой передачи, «n» — без неё */
    private fun fileName(ch: Channel, p: Programme?, art: Boolean) =
        "${ch.playlistId}_${Math.abs(ch.key.hashCode())}_${p?.start ?: 0}${if (art) "a" else "n"}.jpg"

    /** Сколько живёт баннер после последней публикации: карточки обновляются при каждом уходе из приложения, дольше они лаунчеру не нужны */
    private const val TTL_MS = 24 * 3600_000L

    /**
     * Удалить баннеры, которые не публиковались дольше [TTL_MS] — единственное правило очистки
     * (по набору текущих карточек не удаляем: лаунчер может ещё показывать прежние). Зовётся при старте процесса
     * (в том числе когда лаунчер сам поднял провайдер) и при каждом обновлении главного экрана;
     * опубликованный заново баннер «обновляет» файл ([touch]).
     */
    fun expire(context: Context) {
        val limit = System.currentTimeMillis() - TTL_MS
        dir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    /** Все баннеры удалить (настройка главного экрана выключена, очистка кэша): при следующем обновлении нарисуются заново */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Размер баннеров на диске, байт */
    fun size(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** Готовый баннер: адрес для лаунчера и имя файла */
    class Made(val uri: Uri, val name: String)

    /** Подряд не загрузившихся картинок передач в этом обновлении: после трёх остальные не пробуем */
    @Volatile var artFailures = 0

    /**
     * Баннер для карточки или null, если нарисовать не вышло. Звать из фонового потока.
     * [imageUrl] — картинка передачи (может быть пустой или не загрузиться: тогда баннер без неё;
     * позже, когда картинка появится, он будет нарисован заново — файлы «с картинкой» и «без» разные).
     */
    fun make(context: Context, ch: Channel, p: Programme?, imageUrl: String): Made? {
        val withArt = File(dir(context), fileName(ch, p, true))
        if (withArt.length() > 0) { touch(withArt); return Made(uri(withArt.name), withArt.name) }
        return try {
            val logo = if (ch.logoUrl().isNotEmpty()) Logos.fetchBlocking(context, ch.logoUrl(), 400, 250) else null
            if (logo == null) {
                // нет логотипа: на картинке передачи — значок приложения вместо него (в той же рамке),
                // без картинки — баннер приложения
                val withIcon = File(dir(context), fileName(ch, p, true).replace("a.jpg", "i.jpg"))
                if (withIcon.length() > 0) { touch(withIcon); return Made(uri(withIcon.name), withIcon.name) }
                val art = fetchArt(context, imageUrl) ?: return appBanner(context)
                val icon = context.getDrawable(R.drawable.icon)!!.toBitmap(256, 256)
                return save(withIcon, render(art, icon))
            }
            val art = fetchArt(context, imageUrl)
            val file = if (art != null) withArt else File(dir(context), fileName(ch, p, false))
            if (art == null && file.length() > 0) { touch(file); return Made(uri(file.name), file.name) }
            save(file, render(art, logo))
        } catch (e: Exception) {
            android.util.Log.w("IPTV", "poster ${ch.name}: $e")
            null
        }
    }

    /** Картинка передачи или null (адреса нет, не загрузилась или уже три неудачи подряд) */
    private fun fetchArt(context: Context, url: String): Bitmap? {
        if (url.isEmpty() || artFailures >= 3) return null
        val art = Logos.fetchBlocking(context, url, W, H)
        if (art == null) artFailures++ else artFailures = 0
        return art
    }

    private const val APP_BANNER = "app.jpg"

    /** Баннер приложения (banner.xml) в размер карточки — для каналов без логотипа */
    fun appBanner(context: Context): Made? {
        val file = File(dir(context), APP_BANNER)
        if (file.length() > 0) { touch(file); return Made(uri(APP_BANNER), APP_BANNER) }
        val d = context.getDrawable(R.drawable.banner) ?: return null
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, W, H)
        d.draw(Canvas(bm))
        return save(file, bm)
    }

    private fun save(file: File, bm: Bitmap): Made? {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { bm.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bm.recycle()
        return if (tmp.renameTo(file)) Made(uri(file.name), file.name) else null
    }

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Баннер целиком (отдельная функция — чтобы рисование не зависело от сети) */
    fun render(art: Bitmap?, logo: Bitmap): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        if (art == null) {
            // серый фон и логотип на нём — как «О передаче» в плеере
            c.drawColor(GRAY)
            drawLogo(c, logo, 0f, 0f, W.toFloat(), H.toFloat(), H * 0.14f)
            return bm
        }
        // картинка — по центру, с обрезкой под 16:9
        val s = maxOf(W.toFloat() / art.width, H.toFloat() / art.height)
        val w = art.width * s
        val h = art.height * s
        c.drawBitmap(art, null, RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), pic)
        plate.shader = LinearGradient(0f, H * 0.4f, 0f, H.toFloat(), 0x00000000, 0xD90B0C0E.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), plate)
        plate.shader = null
        // логотип — в прямоугольнике, как в программе
        val ph = H * 0.26f
        val pw = ph * LOGO_ASPECT
        val margin = H * 0.06f
        val l = margin
        val t = H - margin - ph
        val radius = ph * 0.14f
        plate.color = Color.argb(235, 30, 32, 34)
        c.drawRoundRect(l, t, l + pw, t + ph, radius, radius, plate)
        // тонкая светлая кайма: на тёмной картинке плашка не сливается с ней
        plate.style = Paint.Style.STROKE
        plate.strokeWidth = 2f
        plate.color = Color.argb(46, 255, 255, 255)
        c.drawRoundRect(l, t, l + pw, t + ph, radius, radius, plate)
        plate.style = Paint.Style.FILL
        drawLogo(c, logo, l, t, l + pw, t + ph, ph * 0.14f)
        return bm
    }

    /** Логотип по центру прямоугольника, вписанный с отступом [pad] */
    private fun drawLogo(c: Canvas, logo: Bitmap, l: Float, t: Float, r: Float, b: Float, pad: Float) {
        val s = minOf((r - l - 2 * pad) / logo.width, (b - t - 2 * pad) / logo.height)
        val w = logo.width * s
        val h = logo.height * s
        c.drawBitmap(logo, null, RectF((l + r - w) / 2, (t + b - h) / 2, (l + r + w) / 2, (t + b + h) / 2), pic)
    }

    /** Отдаёт лаунчеру файлы баннеров (только чтение, только свой каталог) */
    class Provider : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = uri.lastPathSegment ?: throw java.io.FileNotFoundException(uri.toString())
            if (name.contains('/') || name.contains("..") || mode != "r") throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(File(dir(context!!), name), ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
