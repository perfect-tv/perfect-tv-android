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
import top.rootu.perfecttv.ui.Logos
import java.io.File
import java.io.FileOutputStream

/**
 * Баннеры карточек главного экрана (16:9, [W] × [H]): логотипы бывают квадратными, круглыми
 * и вытянутыми, поэтому карточка рисуется самими — не отдаётся как есть.
 *
 *  - есть картинка передачи (TMDB или EPG): она на весь баннер, снизу затемнение, логотип канала —
 *    на тёмной плашке в левом нижнем углу;
 *  - картинки нет: фон в цвет канала (тот же тон, что у монограммы в приложении), логотип — крупно
 *    по центру на плашке.
 * Плашка подстраивается под логотип: квадратный или круглый — квадратная, широкий — шире (до 2,4 : 1).
 * Логотипа нет — монограмма названия. Файлы лежат в cacheDir/posters и отдаются лаунчеру
 * через [Provider]; имя файла зависит от канала и передачи, готовый файл не рисуется заново.
 */
object Posters {
    const val AUTHORITY = "top.rootu.perfecttv.posters"
    const val W = 800
    const val H = 450
    private const val MAX_ASPECT = 2.4f

    fun dir(context: Context) = File(context.cacheDir, "posters").also { it.mkdirs() }

    fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    /** Имя файла баннера: канал + начало передачи; «a» — с картинкой передачи, «n» — без неё */
    private fun fileName(ch: Channel, p: Programme?, art: Boolean) =
        "${ch.playlistId}_${Math.abs(ch.key.hashCode())}_${p?.start ?: 0}${if (art) "a" else "n"}.jpg"

    /** Готовый баннер: адрес для лаунчера и имя файла (для [cleanup]) */
    class Made(val uri: Uri, val name: String)

    /** Подряд не загрузившихся картинок передач в этом обновлении: после трёх остальные не пробуем */
    @Volatile var artFailures = 0

    /** Удалить баннеры, которых нет в [keep] (ушли каналы и прошедшие передачи) */
    fun cleanup(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    /**
     * Баннер для карточки или null, если нарисовать не вышло. Звать из фонового потока.
     * [imageUrl] — картинка передачи (может быть пустой или не загрузиться: тогда баннер без неё;
     * позже, когда картинка появится, он будет нарисован заново — файлы «с картинкой» и «без» разные).
     */
    fun make(context: Context, ch: Channel, p: Programme?, imageUrl: String): Made? {
        val withArt = File(dir(context), fileName(ch, p, true))
        if (withArt.length() > 0) return Made(uri(withArt.name), withArt.name)
        return try {
            var art: Bitmap? = null
            if (imageUrl.isNotEmpty() && artFailures < 3) {
                art = Logos.fetchBlocking(context, imageUrl, W, H)
                if (art == null) artFailures++ else artFailures = 0
            }
            val file = if (art != null) withArt else File(dir(context), fileName(ch, p, false))
            if (art == null && file.length() > 0) return Made(uri(file.name), file.name)
            val logo = if (ch.logoUrl().isNotEmpty()) Logos.fetchBlocking(context, ch.logoUrl(), 400, 250) else null
            val bm = render(ch, art, logo)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { bm.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bm.recycle()
            if (!tmp.renameTo(file)) return null
            Made(uri(file.name), file.name)
        } catch (e: Exception) {
            android.util.Log.w("IPTV", "poster ${ch.name}: $e")
            null
        }
    }

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hsv = FloatArray(3)

    /** Баннер целиком (отдельная функция — чтобы рисование не зависело от сети) */
    fun render(ch: Channel, art: Bitmap?, logo: Bitmap?): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val hasArt = art != null
        if (art != null) {
            // картинка — по центру, с обрезкой под 16:9
            val s = maxOf(W.toFloat() / art.width, H.toFloat() / art.height)
            val w = art.width * s
            val h = art.height * s
            c.drawBitmap(art, null, RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), pic)
            plate.shader = LinearGradient(0f, H * 0.4f, 0f, H.toFloat(), 0x00000000, 0xD90B0C0E.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), plate)
            plate.shader = null
        } else {
            // фон — тон канала, темнее к низу
            Color.colorToHSV(Logos.monoColor(ch), hsv)
            val top = Color.HSVToColor(floatArrayOf(hsv[0], 0.45f, 0.36f))
            val bottom = Color.HSVToColor(floatArrayOf(hsv[0], 0.40f, 0.17f))
            plate.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), plate)
            plate.shader = null
        }
        // плашка логотипа
        val ph = if (hasArt) H * 0.25f else H * 0.56f
        val aspect = if (logo != null) (logo.width.toFloat() / logo.height).coerceIn(1f, MAX_ASPECT) else 1.6f
        val pw = ph * aspect
        val margin = H * 0.06f
        val l = if (hasArt) margin else (W - pw) / 2
        val t = if (hasArt) H - margin - ph else (H - ph) / 2
        val radius = ph * 0.2f
        if (logo == null) {
            Logos.drawMonogram(c, ch, l, t, l + pw, t + ph, radius)
            return bm
        }
        plate.color = Color.argb(if (hasArt) 235 else 215, 30, 32, 34)
        c.drawRoundRect(l, t, l + pw, t + ph, radius, radius, plate)
        // тонкая светлая кайма: на тёмной картинке плашка не сливается с ней
        plate.style = Paint.Style.STROKE
        plate.strokeWidth = 2f
        plate.color = Color.argb(46, 255, 255, 255)
        c.drawRoundRect(l, t, l + pw, t + ph, radius, radius, plate)
        plate.style = Paint.Style.FILL
        val pad = ph * 0.13f
        val s = minOf((pw - 2 * pad) / logo.width, (ph - 2 * pad) / logo.height)
        val w = logo.width * s
        val h = logo.height * s
        c.drawBitmap(logo, null, RectF(l + (pw - w) / 2, t + (ph - h) / 2, l + (pw + w) / 2, t + (ph + h) / 2), pic)
        return bm
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
