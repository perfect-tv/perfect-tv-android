package top.rootu.perfecttv.data

import top.rootu.perfecttv.core.Prefs

/**
 * Каталог каналов одного плейлиста в памяти: категории, избранное, история, скрытое,
 * очки адаптивной сортировки (порт tv_plugin/src/playlist/catalog.js).
 * Пользовательские данные хранятся в БД ([Repo]) по ключу канала.
 */
class Catalog(val playlist: Playlist, val channels: List<Channel>, private val repo: Repo, favoritesTitle: String, private val allTitle: String) {

    class Group(val key: String, val title: String, val channels: List<Channel>, val favorites: Boolean = false)

    val byKey = HashMap<String, Channel>()
    private val allGroups = ArrayList<Group>()
    var favorites: MutableList<String> = repo.favorites(playlist.id)
    var history: MutableList<String> = repo.history(playlist.id)
    var hiddenGroups: MutableMap<String, String> = repo.hidden(playlist.id, "group")
    var hiddenChannels: MutableMap<String, String> = repo.hidden(playlist.id, "channel")
    private val scores: MutableMap<String, Int> = repo.scores(playlist.id)
    private val lockedGroups: MutableSet<String> = repo.locked(playlist.id, "group")
    private val lockedChannels: MutableSet<String> = repo.locked(playlist.id, "channel")

    var favGroup = Group(FAV_KEY, favoritesTitle, emptyList(), true)
        private set
    private var groupList: List<Group> = emptyList()
    private var groupMap: Map<String, Group> = emptyMap()

    init {
        for (ch in channels) byKey.getOrPut(ch.key) { ch }
        val map = LinkedHashMap<String, ArrayList<Channel>>()
        for (ch in channels) map.getOrPut(ch.group) { ArrayList() }.add(ch)
        for ((k, list) in map) allGroups.add(Group(k, k, list))
        refreshFavorites()
        applyView()
    }

    /**
     * Видимые категории: без скрытых категорий и каналов; при адаптивной сортировке —
     * по очкам (больше — выше), при равенстве — по номеру. Порядок меняется при следующем
     * открытии гида, а не на глазах.
     */
    fun applyView() {
        val adaptive = Prefs.adaptiveSort
        val list = ArrayList<Group>()
        val map = HashMap<String, Group>()
        for (g in allGroups) {
            if (g.key in hiddenGroups) continue
            var chs = g.channels.filter { it.key !in hiddenChannels }
            if (chs.isEmpty()) continue
            if (adaptive) chs = chs.sortedWith(compareByDescending<Channel> { scores[it.key] ?: 0 }.thenBy { it.num })
            val v = Group(g.key, g.title, chs)
            list.add(v)
            map[g.key] = v
        }
        // «Все каналы» — первой после избранного: каналы всех видимых категорий в порядке плейлиста
        // (без скрытых); скрыть или заблокировать её нельзя — это не категория плейлиста
        if (list.isNotEmpty()) {
            var all = channels.filter { it.group !in hiddenGroups && it.key !in hiddenChannels }
            if (adaptive) all = all.sortedWith(compareByDescending<Channel> { scores[it.key] ?: 0 }.thenBy { it.num })
            val g = Group(ALL_KEY, allTitle, all)
            list.add(0, g)
            map[ALL_KEY] = g
        }
        groupList = list
        groupMap = map
    }

    fun refreshFavorites() {
        favGroup = Group(FAV_KEY, favGroup.title, favorites.mapNotNull { byKey[it] }, true)
    }

    /** Категории для навигации: избранное (если не пусто) + категории плейлиста */
    fun groups(): List<Group> = if (favGroup.channels.isNotEmpty()) listOf(favGroup) + groupList else groupList

    fun group(key: String?): Group? = when (key) {
        null -> null
        FAV_KEY -> favGroup.takeIf { it.channels.isNotEmpty() }
        else -> groupMap[key]
    }

    /** Категория канала: предпочтительно [preferKey], если канал в ней есть, иначе родная */
    fun groupOf(ch: Channel, preferKey: String?): Group {
        group(preferKey)?.let { if (ch in it.channels) return it }
        groupList.firstOrNull { it.key != ALL_KEY && ch in it.channels }?.let { return it }
        groupList.firstOrNull { ch in it.channels }?.let { return it }
        return groupList.firstOrNull() ?: favGroup
    }

    fun byNum(num: Int): Channel? = channels.firstOrNull { it.num == num }

    // ---- избранное ----
    fun isFavorite(ch: Channel) = ch.key in favorites

    fun toggleFavorite(ch: Channel) {
        if (!favorites.remove(ch.key)) favorites.add(ch.key)
        repo.saveFavorites(playlist.id, favorites)
        refreshFavorites()
    }

    /** Сдвиг в избранном: dir −1 вверх, +1 вниз */
    fun moveFavorite(ch: Channel, dir: Int) {
        val i = favorites.indexOf(ch.key)
        val j = i + dir
        if (i < 0 || j < 0 || j >= favorites.size) return
        favorites[i] = favorites[j]
        favorites[j] = ch.key
        repo.saveFavorites(playlist.id, favorites)
        refreshFavorites()
    }

    fun clearFavorites() {
        favorites.clear()
        repo.saveFavorites(playlist.id, favorites)
        refreshFavorites()
    }

    // ---- история запусков ----
    fun addHistory(ch: Channel) {
        history.remove(ch.key)
        history.add(0, ch.key)
        while (history.size > HISTORY_SIZE) history.removeAt(history.size - 1)
        repo.addHistory(playlist.id, ch.key)
    }

    /** Недавние каналы без текущего */
    fun recent(current: Channel?): List<Channel> = history.mapNotNull { byKey[it] }.filter { it !== current }.take(HISTORY_SIZE - 1)

    // ---- скрытое ----
    fun isHidden(ch: Channel) = ch.key in hiddenChannels

    fun hideChannel(ch: Channel) {
        hiddenChannels[ch.key] = ch.name
        repo.setHidden(playlist.id, "channel", ch.key, ch.name, true)
        applyView()
    }

    fun hideGroup(key: String) {
        if (key == FAV_KEY || key == ALL_KEY) return
        hiddenGroups[key] = key
        repo.setHidden(playlist.id, "group", key, key, true)
        applyView()
    }

    fun unhide(kind: String, key: String) {
        (if (kind == "group") hiddenGroups else hiddenChannels).remove(key)
        repo.setHidden(playlist.id, kind, key, "", false)
        applyView()
    }

    fun hiddenCount() = hiddenGroups.size + hiddenChannels.size

    // ---- блокировка по PIN (действует, только если PIN задан) ----
    fun isChannelLocked(ch: Channel) = ch.key in lockedChannels
    fun isGroupLocked(key: String) = key in lockedGroups

    /** Канал заблокирован сам или его категория */
    fun isLocked(ch: Channel) = Prefs.pin.isNotEmpty() && (ch.key in lockedChannels || ch.groupKey in lockedGroups)

    fun setLocked(kind: String, key: String, on: Boolean) {
        val set = if (kind == "group") lockedGroups else lockedChannels
        if (on) set.add(key) else set.remove(key)
        repo.setLocked(playlist.id, kind, key, on)
    }

    // ---- адаптивная сортировка ----
    fun addScore(ch: Channel) {
        val v = (scores[ch.key] ?: 0) + 1
        scores[ch.key] = v
        repo.setScore(playlist.id, ch.key, v)
    }

    fun resetScores() {
        scores.clear()
        repo.resetScores(playlist.id)
    }

    companion object {
        const val FAV_KEY = ""
        /** «Все каналы»: ключ не совпадёт ни с одной категорией плейлиста (символ частной зоны —
         *  допустим в XML SharedPreferences, куда сохраняется последняя категория) */
        const val ALL_KEY = "\uE000all"
        const val HISTORY_SIZE = 11
    }
}
