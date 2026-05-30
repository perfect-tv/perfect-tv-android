package top.rootu.perfecttv.core

import java.util.Locale

/**
 * Язык интерфейса: русский или английский. Строки в коде — по-русски, обёрнуты в [t];
 * английский — словарь [LangEn] (ключ — русская строка). Шаблоны с подстановками — [f]
 * с «%s». Язык: настройка «Язык» или системный (ru/uk/be/kk — русский, иначе английский).
 * Меняется после перезапуска экрана.
 */
object L {
    var en = false
        private set

    fun init() {
        en = when (Prefs.language) {
            "ru" -> false
            "en" -> true
            else -> Locale.getDefault().language !in listOf("ru", "uk", "be", "kk")
        }
    }

    fun t(ru: String): String = if (en) LangEn.MAP[ru] ?: ru else ru

    fun f(ru: String, vararg args: Any?): String = String.format(Locale.US, t(ru), *args)
}
