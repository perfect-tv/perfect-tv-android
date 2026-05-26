package top.rootu.perfecttv.core

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения и «где остановились» (последний плейлист, канал, категория) */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)

    /** Глубина архива, если в плейлисте не указана, дней */
    var archiveDays: Int
        get() = int("archive_days", 3)
        set(v) = sp.edit().putInt("archive_days", v).apply()

    /** ↑ в плеере: true — программа и архив, false — недавние каналы */
    var upArchive: Boolean
        get() = bool("up_archive", true)
        set(v) = sp.edit().putBoolean("up_archive", v).apply()

    /** За сколько минут напоминать (0 — когда начнётся) */
    var remindBefore: Int
        get() = int("remind_before", 1)
        set(v) = sp.edit().putInt("remind_before", v).apply()

    /** Автоскрытие инфопанели, с */
    var infoHide: Int
        get() = int("info_hide", 5)
        set(v) = sp.edit().putInt("info_hide", v).apply()

    var animations: Boolean
        get() = bool("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()

    var adaptiveSort: Boolean
        get() = bool("adaptive_sort", false)
        set(v) = sp.edit().putBoolean("adaptive_sort", v).apply()

    /** Вид гида: true — сетка «ТВ-гид», false — «Список» */
    var guideGrid: Boolean
        get() = bool("guide_grid", true)
        set(v) = sp.edit().putBoolean("guide_grid", v).apply()

    /** Обновлять программу каждые N часов */
    var epgRefreshHours: Int
        get() = int("epg_refresh", 12)
        set(v) = sp.edit().putInt("epg_refresh", v).apply()

    /** Перекачивать плейлист при запуске, если ему больше N часов */
    var playlistRefreshHours: Int
        get() = int("playlist_refresh", 24)
        set(v) = sp.edit().putInt("playlist_refresh", v).apply()

    /** PIN блокировки каналов и категорий; пусто — блокировка выключена */
    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    /** Спрашивать PIN: true — каждый раз, false — один раз до выхода из приложения */
    var pinEveryTime: Boolean
        get() = bool("pin_every", false)
        set(v) = sp.edit().putBoolean("pin_every", v).apply()

    /** Ввод PIN: 0 — кольцо (как Fire TV), 1 — ряды (как Google), 2 — клавиатура */
    var pinInput: Int
        get() = int("pin_input", 2)
        set(v) = sp.edit().putInt("pin_input", v).apply()

    var lastPlaylist: Long
        get() = sp.getLong("last_playlist", 0)
        set(v) = sp.edit().putLong("last_playlist", v).apply()

    fun lastChannel(playlistId: Long): String = sp.getString("last_channel_$playlistId", "") ?: ""
    fun lastGroup(playlistId: Long): String? = sp.getString("last_group_$playlistId", null)

    fun saveLast(playlistId: Long, channelKey: String, groupKey: String) {
        sp.edit().putString("last_channel_$playlistId", channelKey).putString("last_group_$playlistId", groupKey).apply()
    }
}
