package top.rootu.perfecttv.data

import android.os.Handler
import android.os.Looper

/** События данных для интерфейса; слушатели вызываются в главном потоке */
sealed class DataEvent {
    /** Плейлист грузится: [channels] уже записано; done — закончили (error — причина) */
    data class PlaylistProgress(val playlistId: Long, val channels: Int, val done: Boolean, val error: String? = null) : DataEvent()

    /** Программа источника пополнилась (пачка записана) или загрузка закончилась */
    data class EpgProgress(val sourceId: Long, val url: String, val programmes: Int, val percent: Int, val done: Boolean, val error: String? = null) : DataEvent()

    /** Программа каналов прочитана из БД в фоне (пока идёт загрузка программы) и лежит в кэше — перерисовать */
    object EpgRead : DataEvent()
}

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(DataEvent) -> Unit>()

    fun on(l: (DataEvent) -> Unit) {
        listeners.add(l)
    }

    fun off(l: (DataEvent) -> Unit) {
        listeners.remove(l)
    }

    fun post(e: DataEvent) {
        main.post { listeners.toList().forEach { it(e) } }
    }
}
