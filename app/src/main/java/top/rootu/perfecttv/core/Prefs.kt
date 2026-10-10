package top.rootu.perfecttv.core

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения и «где остановились» (последний плейлист, канал, категория) */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    /** Все настройки как есть — для резервной копии (Backup) */
    fun all(): Map<String, *> = sp.all

    /** Заменить настройки значениями из резервной копии; commit — дальше приложение перезапускается */
    fun replace(values: Map<String, Any>) {
        val e = sp.edit().clear()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.commit()
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

    /** Строка подсказок клавиш у закреплённой инфопанели */
    var keyHints: Boolean
        get() = bool("key_hints", true)
        set(v) = sp.edit().putBoolean("key_hints", v).apply()

    /** Формат кадра: 0 — авто (вписать), 1 — растянуть, 2 — увеличить (обрезать края) */
    var aspect: Int
        get() = int("aspect", 0)
        set(v) = sp.edit().putInt("aspect", v).apply()

    /** Предпочитаемые языки звука и субтитров (последний выбор в меню); пусто — как в потоке */
    var audioLang: String
        get() = sp.getString("audio_lang", "") ?: ""
        set(v) = sp.edit().putString("audio_lang", v).apply()
    var subtitleLang: String
        get() = sp.getString("subtitle_lang", "") ?: ""
        set(v) = sp.edit().putString("subtitle_lang", v).apply()

    /**
     * Размер интерфейса: 0 — меньше, 1 — нормальный (по умолчанию), 2 — больше: экран считается
     * высотой 540, 480 или 432 dp (шаг — 12,5%). Прежние ключи ui_large / ui_size не читаются.
     */
    var uiScale: Int
        get() = int("ui_scale", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("ui_scale", v).apply()

    /** Высота экрана в dp, под которую подбирается плотность (1 em = 10 dp) */
    val screenDp: Int get() = when (uiScale) { 0 -> 540; 2 -> 432; else -> 480 }

    /** Недавние поисковые запросы, новые — первыми */
    var searchRecent: List<String>
        get() = (sp.getString("search_recent", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("search_recent", v.joinToString("\n")).apply()

    /** Клавиатура поиска: буквы по алфавиту вместо ЙЦУКЕН / QWERTY */
    var keyboardAbc: Boolean
        get() = bool("keyboard_abc", false)
        set(v) = sp.edit().putBoolean("keyboard_abc", v).apply()

    /** Главный экран Android TV: избранные каналы и «Продолжить просмотр» */
    var homeScreen: Boolean
        get() = bool("home_screen", true)
        set(v) = sp.edit().putBoolean("home_screen", v).apply()

    /** Служебное: id своего канала-превью и карточки «Продолжить» в провайдере ТВ (в копию не входят) */
    var homeChannelId: Long
        get() = sp.getLong("home_channel_id", 0L)
        set(v) = sp.edit().putLong("home_channel_id", v).apply()
    /** Версия раскладки рядов: 2 — «Популярные» по умолчанию, «Избранные» по выбору */
    var homeLayout: Int
        get() = int("home_layout", 0)
        set(v) = sp.edit().putInt("home_layout", v).apply()
    var homePopularId: Long
        get() = sp.getLong("home_popular_id", 0L)
        set(v) = sp.edit().putLong("home_popular_id", v).apply()
    var homeWatchNextId: Long
        get() = sp.getLong("home_watch_next_id", 0L)
        set(v) = sp.edit().putLong("home_watch_next_id", v).apply()

    /** Деинтерлейсинг чересстрочных потоков (шейдер поверх видео) */
    /** HTTPS без проверки сертификата и имени узла */
    var anyCert: Boolean
        get() = bool("any_cert", true)
        set(v) = sp.edit().putBoolean("any_cert", v).apply()

    var deinterlace: Boolean
        get() = bool("deinterlace", false)
        set(v) = sp.edit().putBoolean("deinterlace", v).apply()

    /** Видеодекодер: 0 — авто (аппаратный; зависло видео — для канала программный), 1 — аппаратный, 2 — программный */
    var videoDecoder: Int
        get() = int("video_decoder", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("video_decoder", v).apply()

    /**
     * Поправка воспроизведения канала, найденная сторожем зависания видео ([top.rootu.perfecttv.player.Playback]):
     * 0 — нет, 1 — программный видеодекодер.
     */
    fun playFix(channelKey: String): Int = int("play_fix_$channelKey", 0)
    fun setPlayFix(channelKey: String, step: Int) {
        sp.edit().apply { if (step <= 0) remove("play_fix_$channelKey") else putInt("play_fix_$channelKey", step) }.apply()
    }

    /** Приоритет логотипов: 0 — из плейлиста, 1 — из программы (XMLTV); не загрузился — берётся другой источник */
    var logoPriority: Int
        get() = int("logo_priority", 0).coerceIn(0, 1)
        set(v) = sp.edit().putInt("logo_priority", v).apply()

    /** Шапка гида: 0 — канал и передача, 1 — только канал, 2 — скрыть */
    var guideHeader: Int
        get() = int("guide_header", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("guide_header", v).apply()

    /** Экранная клавиатура рядом со страницей телетекста */
    var ttxKeypad: Boolean
        get() = bool("ttx_keypad", true)
        set(v) = sp.edit().putBoolean("ttx_keypad", v).apply()

    /** Телетекст поверх видео (прозрачный фон) вместо чёрного экрана */
    var ttxMix: Boolean
        get() = bool("ttx_mix", false)
        set(v) = sp.edit().putBoolean("ttx_mix", v).apply()

    /** Вид «Список»: при переходе в программу каналы сжимаются до логотипов, справа выезжает панель передачи */
    var guideDesc: Boolean
        get() = bool("guide_desc", false)
        set(v) = sp.edit().putBoolean("guide_desc", v).apply()

    var animations: Boolean
        get() = bool("animations", true)
        set(v) = sp.edit().putBoolean("animations", v).apply()

    var adaptiveSort: Boolean
        get() = bool("adaptive_sort", false)
        set(v) = sp.edit().putBoolean("adaptive_sort", v).apply()

    /**
     * Вид гида: 0 — «ТВ-гид» (сетка), 1 — «Каналы + программа», 2 — «Категории + каналы», 3 — «Каналы».
     * Прежняя настройка guide_grid (сетка / список) читается, пока новая не выбрана.
     */
    var guideMode: Int
        get() = if (sp.contains("guide_mode")) int("guide_mode", 0).coerceIn(0, 3) else if (bool("guide_grid", true)) 0 else 1
        set(v) = sp.edit().putInt("guide_mode", v).apply()

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

    /** Ключ TMDB (v3 api_key или v4 токен); пусто — описания и баннеры только из EPG */
    var tmdbKey: String
        get() = sp.getString("tmdb_key", "") ?: ""
        set(v) = sp.edit().putString("tmdb_key", v.trim()).apply()

    /** Адрес API TMDB (можно заменить прокси) */
    var tmdbApi: String
        get() = sp.getString("tmdb_api", "")?.takeIf { it.isNotBlank() } ?: TMDB_API
        set(v) = sp.edit().putString("tmdb_api", v.trim()).apply()

    /** Адрес картинок TMDB */
    var tmdbImages: String
        get() = sp.getString("tmdb_images", "")?.takeIf { it.isNotBlank() } ?: TMDB_IMAGES
        set(v) = sp.edit().putString("tmdb_images", v.trim()).apply()

    const val TMDB_API = "https://api.themoviedb.org/3"
    const val TMDB_IMAGES = "https://image.tmdb.org"

    /** При запуске — сразу последний канал (иначе — гид на нём) */
    var autoplayLast: Boolean
        get() = bool("autoplay_last", true)
        set(v) = sp.edit().putBoolean("autoplay_last", v).apply()

    /** Язык интерфейса: "" — системный, "ru", "en" */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).apply()

    /** Где показывать номер канала: 0 — нигде, 1 — рядом с названием, 2 — бейдж на логотипе */
    var numberMode: Int
        get() = int("number_mode", NUMBER_BADGE)
        set(v) = sp.edit().putInt("number_mode", v).apply()

    const val NUMBER_NONE = 0
    const val NUMBER_NEAR_NAME = 1
    const val NUMBER_BADGE = 2

    var lastPlaylist: Long
        get() = sp.getLong("last_playlist", 0)
        set(v) = sp.edit().putLong("last_playlist", v).apply()

    fun lastChannel(playlistId: Long): String = sp.getString("last_channel_$playlistId", "") ?: ""
    fun lastGroup(playlistId: Long): String? = sp.getString("last_group_$playlistId", null)

    fun saveLast(playlistId: Long, channelKey: String, groupKey: String) {
        sp.edit().putString("last_channel_$playlistId", channelKey).putString("last_group_$playlistId", groupKey).apply()
    }
}
