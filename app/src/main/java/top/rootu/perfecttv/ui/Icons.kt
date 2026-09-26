package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import top.rootu.perfecttv.R

/** Значки интерфейса (Material Icons) для рисования на Canvas */
object Icons {
    private lateinit var archive: Drawable
    private lateinit var remind: Drawable
    private lateinit var fav: Drawable
    private lateinit var lock: Drawable
    private lateinit var play: Drawable
    // по своему экземпляру на пару «значок + цвет»: векторный Drawable при аппаратной отрисовке
    // запоминает фильтр в самом объекте, и один общий экземпляр красил бы все значки кадра
    // в цвет последнего (в фокусе — чёрный, и значок пропадал на выделении)
    private val tinted = HashMap<Long, Drawable>()

    enum class Kind { ARCHIVE, REMIND, FAV, LOCK, PLAY }

    fun init(context: Context) {
        archive = context.getDrawable(R.drawable.ic_archive)!!.mutate()
        remind = context.getDrawable(R.drawable.ic_remind)!!.mutate()
        fav = context.getDrawable(R.drawable.ic_fav)!!.mutate()
        lock = context.getDrawable(R.drawable.ic_lock)!!.mutate()
        play = context.getDrawable(R.drawable.ic_play)!!.mutate()
    }

    fun drawable(k: Kind) = when (k) {
        Kind.ARCHIVE -> archive
        Kind.REMIND -> remind
        Kind.FAV -> fav
        Kind.LOCK -> lock
        Kind.PLAY -> play
    }

    /** Квадратный значок со стороной [size] слева-сверху в (x, y) */
    fun draw(c: Canvas, k: Kind, x: Float, y: Float, size: Float, color: Int) {
        val d = tinted.getOrPut(k.ordinal.toLong() shl 32 or (color.toLong() and 0xFFFFFFFFL)) {
            (drawable(k).constantState?.newDrawable()?.mutate() ?: drawable(k)).apply {
                colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
        }
        d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        d.draw(c)
    }
}
