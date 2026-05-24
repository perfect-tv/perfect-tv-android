package top.rootu.perfecttv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import top.rootu.perfecttv.R

/** Значки интерфейса (Material Icons, как ui/icons.js плагина) для рисования на Canvas */
object Icons {
    private lateinit var archive: Drawable
    private lateinit var remind: Drawable
    private lateinit var fav: Drawable
    private lateinit var lock: Drawable
    private val filters = HashMap<Int, PorterDuffColorFilter>()

    enum class Kind { ARCHIVE, REMIND, FAV, LOCK }

    fun init(context: Context) {
        archive = context.getDrawable(R.drawable.ic_archive)!!.mutate()
        remind = context.getDrawable(R.drawable.ic_remind)!!.mutate()
        fav = context.getDrawable(R.drawable.ic_fav)!!.mutate()
        lock = context.getDrawable(R.drawable.ic_lock)!!.mutate()
    }

    fun drawable(k: Kind) = when (k) {
        Kind.ARCHIVE -> archive
        Kind.REMIND -> remind
        Kind.FAV -> fav
        Kind.LOCK -> lock
    }

    /** Квадратный значок со стороной [size] слева-сверху в (x, y) */
    fun draw(c: Canvas, k: Kind, x: Float, y: Float, size: Float, color: Int) {
        val d = drawable(k)
        d.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
        d.colorFilter = filters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
        d.draw(c)
    }
}
