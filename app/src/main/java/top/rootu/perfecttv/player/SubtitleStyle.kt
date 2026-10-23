package top.rootu.perfecttv.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import top.rootu.perfecttv.core.Prefs

/**
 * Оформление субтитров (Настройки → Просмотр): размер, цвет текста, фон, обводка, отступ снизу.
 * «Как в потоке» (по умолчанию) ничего не переопределяет: у субтитров телетекста цвет текста и фона
 * несёт сам поток (белый — речь, жёлтый — песня…), он остаётся как есть; заданный цвет или фон
 * заменяет цвета потока (для телетекста — при создании реплики, см. `TeletextTs.cue`).
 */
object SubtitleStyle {
    val SIZES = listOf(75, 100, 125, 150, 200)
    val BOTTOMS = listOf(4, 8, 12, 18)
    /** Цвет текста: 0 — как в потоке, дальше ARGB */
    val COLORS = listOf(0, 0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF69F0AE.toInt(), 0xFF80D8FF.toInt())
    /** Фон: 0 — как в потоке, 1 — нет, 2 — полупрозрачный, 3 — чёрный */
    val BG_COLORS = listOf(0, Color.TRANSPARENT, 0x99000000.toInt(), 0xFF000000.toInt())
    /** Обводка: 0 — нет, 1 — контур, 2 — тень */
    val EDGES = listOf(CaptionStyleCompat.EDGE_TYPE_NONE, CaptionStyleCompat.EDGE_TYPE_OUTLINE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW)

    /** Цвет текста, заданный пользователем (0 — оставить цвет потока) */
    fun textColor() = COLORS.getOrElse(Prefs.subColor) { 0 }

    /** Цвет фона, заданный пользователем; null — оставить фон потока */
    fun background(): Int? = if (Prefs.subBg == 0) null else BG_COLORS.getOrElse(Prefs.subBg) { 0 }

    fun apply(v: SubtitleView) {
        val bg = background() ?: 0x99000000.toInt()  // «как в потоке»: реплики без своего фона на лёгкой подложке
        val fg = textColor().takeIf { it != 0 } ?: Color.WHITE
        v.setStyle(CaptionStyleCompat(fg, Color.TRANSPARENT, bg, EDGES.getOrElse(Prefs.subEdge) { 0 },
            Color.BLACK, Typeface.DEFAULT))
        v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Prefs.subSize / 100f)
        v.setBottomPaddingFraction(Prefs.subBottom / 100f)
    }
}
