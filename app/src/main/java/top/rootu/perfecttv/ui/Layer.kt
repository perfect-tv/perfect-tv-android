package top.rootu.perfecttv.ui

import android.view.View

/**
 * Слой интерфейса поверх видео (окно, гид, плеер). Клавиши получает верхний слой стека.
 *   key(name, long, digit)  name: left|right|up|down|pgup|pgdown|digit
 *   hasLong(name)           есть ли долгое действие (тогда короткое — на keyup)
 *   keyUp(name)             клавиша без долгого действия отпущена (конец удержания)
 *   media(name)             playpause|play|pause|stop|rewind|forward|guide|info|menu|history
 */
interface Layer {
    val view: View?
    fun key(name: String, long: Boolean, digit: Int) {}
    fun hasLong(name: String) = false
    fun keyUp(name: String) {}
    fun enter() {}
    fun long() {}
    fun back() {}
    fun media(name: String) {}
    /** Убрать слой (отписки, таймеры) */
    fun destroy() {}
}
