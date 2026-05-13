package top.rootu.perfecttv.core

import java.util.Date

/**
 * Время приложения: unix-секунды с поправкой по серверу. Часы дешёвых приставок уплывают,
 * а архив и прогресс считаются от реального времени (аналог core/time.js плагина).
 * Поправка берётся из заголовка Date ответов (плейлист, EPG), если расхождение больше 30 с.
 */
object Clock {
    @Volatile private var offsetMs = 0L

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun now(): Long = nowMs() / 1000

    fun syncWith(serverDate: Date?) {
        if (serverDate == null) return
        val diff = serverDate.time - System.currentTimeMillis()
        // Date с точностью до секунды и задержкой сети: мелочь не трогаем
        offsetMs = if (Math.abs(diff) > 30_000) diff else 0
    }
}
