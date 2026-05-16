package top.rootu.perfecttv.data

/** Плейлист из настроек */
data class Playlist(
    val id: Long,
    val name: String,
    val url: String,
    val epgMode: Int,
    val userEpg: List<String>,
    val headerEpg: List<String>,
    val loadedAt: Long,
    val channelsCount: Int,
    val error: String
) {
    /** Источники программы по приоритету с учётом режима */
    fun epgUrls(): List<String> = when (epgMode) {
        EPG_USER_ONLY -> userEpg
        EPG_BOTH -> (userEpg + headerEpg).distinct()
        else -> headerEpg.ifEmpty { userEpg }
    }

    companion object {
        const val EPG_PLAYLIST = 0
        const val EPG_BOTH = 1
        const val EPG_USER_ONLY = 2
    }
}

/**
 * Канал плейлиста.
 *   id      — индекс в каталоге (стабилен в пределах загрузки)
 *   key     — [Names.key] имени: избранное, история, скрытое, напоминания
 *   archive — глубина архива в днях (0 — архива нет)
 */
class Channel(
    val id: Int,
    val playlistId: Long,
    val num: Int,
    val key: String,
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val tvgName: String,
    val tvgShift: Float,
    val catchupType: String?,
    val catchupSource: String,
    val archiveDays: Int,
    val userAgent: String,
    val referrer: String,
    val headersJson: String
) {
    /** Категория для навигации (с учётом разбиения на страницы «#2») */
    var groupKey: String = group

    /** Логотип из EPG, если в плейлисте его нет */
    var epgIcon: String = ""

    val hasArchive get() = catchupType != null

    fun logoUrl(): String = logo.ifEmpty { epgIcon }

    companion object {
        /** Канал-«заглушка» для логотипа (запись напоминания) */
        fun stub(name: String, num: Int, logo: String) = Channel(-1, 0, num, Names.key(name), name, "", "", logo, "", "", 0f, null, "", 0, "", "", "")
    }
}

/** Передача (время — unix-секунды) */
class Programme(
    val start: Long,
    var stop: Long,
    val title: String,
    val subTitle: String,
    val desc: String,
    val category: String,
    val icon: String
) {
    fun contains(t: Long) = t >= start && t < stop
}
