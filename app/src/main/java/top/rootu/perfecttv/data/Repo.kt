package top.rootu.perfecttv.data

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Доступ к данным для интерфейса: плейлисты, каталог, программа, пользовательские данные.
 * Загрузки идут в одном фоновом потоке по очереди, результат — через [Bus] и колбэки
 * в главном потоке. Чтение программы — синхронное из кэша в памяти (см. [programmes]).
 */
class Repo(context: Context) {
    val db = Db(context)
    val reminders = Reminders(db)
    private val loaders = Loaders(db, context.contentResolver)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "loader").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    /** Наибольшая глубина архива в загруженных каталогах — сколько программы хранить */
    @Volatile var maxArchiveDays = 0

    /** Источники EPG, которые сейчас грузятся (id) */
    val loadingSources = HashSet<Long>()

    init {
        // первое открытие БД (и миграция) — в фоне; после пересоздания таблиц — VACUUM
        worker.execute {
            db.writableDatabase
            if (db.needVacuum) {
                db.needVacuum = false
                try { db.writableDatabase.execSQL("VACUUM") } catch (e: Exception) { Log.w(Loaders.TAG, "vacuum: $e") }
            }
        }
        Bus.on { e ->
            if (e is DataEvent.EpgProgress) {
                if (e.done) loadingSources.remove(e.sourceId) else loadingSources.add(e.sourceId)
                invalidateEpg(e.sourceId, e.done)
            }
        }
    }

    // ================================================================== плейлисты

    fun playlists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        db.readableDatabase.rawQuery("SELECT id,name,url,epg_mode,user_epg,header_epg,user_agent,loaded_at,channels_count,error FROM playlist ORDER BY sort,id", null).use { c ->
            while (c.moveToNext()) out.add(Playlist(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3),
                c.getString(4).lines().filter { it.isNotBlank() }, c.getString(5).lines().filter { it.isNotBlank() },
                c.getString(6), c.getLong(7), c.getInt(8), c.getString(9)
            ))
        }
        return out
    }

    fun playlist(id: Long): Playlist? = playlists().firstOrNull { it.id == id }

    fun addPlaylist(name: String, url: String): Long =
        db.writableDatabase.insert("playlist", null, ContentValues().apply { put("name", name); put("url", url.trim()) })

    fun updatePlaylist(id: Long, name: String, url: String, epgMode: Int, userEpg: List<String>, userAgent: String) {
        db.writableDatabase.update("playlist", ContentValues().apply {
            put("name", name); put("url", url.trim()); put("epg_mode", epgMode); put("user_epg", userEpg.joinToString("\n"))
            put("user_agent", userAgent.trim())
        }, "id=?", arrayOf(id.toString()))
    }

    /** Сдвинуть плейлист в списке (dir −1 — выше, +1 — ниже) */
    fun movePlaylist(id: Long, dir: Int) {
        val list = playlists().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j < 0 || j >= list.size) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        db.writableDatabase.tx {
            list.forEachIndexed { k, p -> execSQL("UPDATE playlist SET sort=? WHERE id=?", arrayOf<Any>(k, p.id)) }
        }
    }

    /** Скрытое всех плейлистов: (плейлист, kind, key, title) */
    fun allHidden(): List<HiddenItem> {
        val names = playlists().associate { it.id to it.name }
        val out = ArrayList<HiddenItem>()
        db.readableDatabase.rawQuery("SELECT playlist_id, kind, key, title FROM hidden ORDER BY playlist_id, kind, title", null).use {
            while (it.moveToNext()) out.add(HiddenItem(it.getLong(0), names[it.getLong(0)] ?: "", it.getString(1), it.getString(2), it.getString(3)))
        }
        return out
    }

    class HiddenItem(val playlistId: Long, val playlistName: String, val kind: String, val key: String, val title: String)

    fun resetAllScores() {
        db.writableDatabase.delete("score", null, null)
    }

    fun deletePlaylist(id: Long) {
        db.writableDatabase.tx {
            for (t in listOf("channel", "favorite", "history", "hidden", "score", "locked")) delete(t, "playlist_id=?", arrayOf(id.toString()))
            delete("playlist", "id=?", arrayOf(id.toString()))
        }
    }

    /** Каналы плейлиста из БД (то, что уже записано, — в том числе во время первой загрузки) */
    fun loadCatalog(p: Playlist, favoritesTitle: String, allTitle: String): Catalog {
        val list = ArrayList<Channel>()
        db.readableDatabase.rawQuery("SELECT num,key,name,url,grp,logo,tvg_id,tvg_name,tvg_shift,catchup_type,catchup_source,catchup_days," +
            "user_agent,referrer,headers_json FROM channel WHERE playlist_id=? ORDER BY num", arrayOf(p.id.toString())).use { c ->
            val def = Prefs.archiveDays
            while (c.moveToNext()) {
                val type = if (c.isNull(9)) null else c.getString(9)
                list.add(Channel(
                    id = list.size, playlistId = p.id, num = c.getInt(0), key = c.getString(1), name = c.getString(2),
                    url = c.getString(3), group = c.getString(4).ifEmpty { "—" }, logo = c.getString(5), tvgId = c.getString(6),
                    tvgName = c.getString(7), tvgShift = c.getFloat(8), catchupType = type, catchupSource = c.getString(10),
                    archiveDays = if (type == null) 0 else c.getInt(11).takeIf { it > 0 } ?: def,
                    userAgent = c.getString(12), referrer = c.getString(13), headersJson = c.getString(14), playlistUserAgent = p.userAgent
                ))
            }
        }
        maxArchiveDays = maxOf(maxArchiveDays, list.maxOfOrNull { it.archiveDays } ?: 0)
        synchronized(this) { mapFor.remove(p.id) }
        return Catalog(p, list, this, favoritesTitle, allTitle)
    }

    /**
     * Обновить плейлист и затем его программу.
     * @param done в главном потоке: null — успех, иначе код ошибки (no_url, network, http_404, format, empty)
     */
    fun refresh(p: Playlist, force: Boolean, done: (String?) -> Unit) {
        worker.execute {
            val err = try {
                val n = loaders.loadPlaylist(p, force)
                Bus.post(DataEvent.PlaylistProgress(p.id, n, true))
                null
            } catch (e: Loaders.LoadError) {
                Log.w(Loaders.TAG, "playlist ${p.url}: ${e.code} ${e.message}")
                Bus.post(DataEvent.PlaylistProgress(p.id, 0, true, e.code))
                e.code
            }
            main.post { done(err) }
            if (err == null) playlist(p.id)?.let { queueEpg(it, force) }
        }
    }

    /** Программа плейлиста: загрузить устаревшие (или все при force) источники */
    fun refreshEpg(p: Playlist, force: Boolean) {
        worker.execute { queueEpg(p, force) }
    }

    // источники в очереди загрузки: повторная проверка не ставит их второй раз
    private val queued = java.util.Collections.synchronizedSet(HashSet<Long>())

    private fun queueEpg(p: Playlist, force: Boolean) {
        val maxAge = Prefs.epgRefreshHours * 3600L
        for (url in p.epgUrls()) {
            val id = db.sourceId(url)
            if (id in queued) continue
            var fresh = false
            db.readableDatabase.rawQuery("SELECT loaded_at, status FROM epg_source WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) fresh = it.getInt(1) == Loaders.STATUS_READY && Clock.now() - it.getLong(0) < maxAge
            }
            if (fresh && !force) continue
            queued.add(id)
            worker.execute {
                try {
                    loaders.loadEpg(id, url, wantedFor(url), force, langs(), p.userAgent.ifBlank { top.rootu.perfecttv.core.Net.DEFAULT_USER_AGENT })
                } catch (_: Loaders.LoadError) {
                } finally {
                    queued.remove(id)
                }
            }
        }
    }

    /** Каналы всех плейлистов, в источниках которых есть [url] */
    private fun wantedFor(url: String): List<Loaders.Wanted> {
        val out = LinkedHashMap<String, Loaders.Wanted>()
        for (p in playlists()) {
            if (url !in p.epgUrls()) continue
            db.readableDatabase.rawQuery("SELECT key,tvg_id,tvg_name,name,catchup_type,catchup_days FROM channel WHERE playlist_id=?", arrayOf(p.id.toString())).use { c ->
                val def = Prefs.archiveDays
                while (c.moveToNext()) {
                    // глубина архива канала: нет архива — 0, нет своей глубины — общая из настроек
                    val days = if (c.isNull(4)) 0 else c.getInt(5).takeIf { it > 0 } ?: def
                    val key = c.getString(0)
                    // один канал в нескольких плейлистах: программа общая, глубина — наибольшая
                    val prev = out[key]
                    if (prev == null) out[key] = Loaders.Wanted(key, c.getString(1), c.getString(2), c.getString(3), days)
                    else if (days > prev.archiveDays) out[key] = Loaders.Wanted(key, prev.tvgId, prev.tvgName, prev.name, days)
                }
            }
        }
        return out.values.toList()
    }

    private fun langs(): List<String> = listOf(Locale.getDefault().language.lowercase(), "ru", "en").distinct()

    /** Устарел ли плейлист (перекачать при запуске); 0 в настройке — автоматически не обновлять */
    fun isStale(p: Playlist) = p.channelsCount == 0 ||
        (Prefs.playlistRefreshHours > 0 && Clock.now() - p.loadedAt > Prefs.playlistRefreshHours * 3600L)

    // ================================================================== программа

    // ключ канала → (источник, xml_id) для каждого плейлиста; источники — по приоритету
    private val mapFor = HashMap<Long, Map<String, Pair<Long, String>>>()
    private val cache = object : LinkedHashMap<String, CachedEpg>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEpg>?) = size > 300
    }

    private class CachedEpg(val sourceId: Long, val list: List<Programme>)

    @Synchronized private fun mapping(playlistId: Long): Map<String, Pair<Long, String>> {
        mapFor[playlistId]?.let { return it }
        val p = playlist(playlistId) ?: return emptyMap()
        val out = HashMap<String, Pair<Long, String>>()
        for (url in p.epgUrls()) {
            var sid = -1L
            db.readableDatabase.rawQuery("SELECT id FROM epg_source WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) sid = it.getLong(0) }
            if (sid < 0) continue
            db.readableDatabase.rawQuery("SELECT ch_key, xml_id FROM epg_map WHERE source_id=?", arrayOf(sid.toString())).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { sid to c.getString(1) }
            }
        }
        mapFor[playlistId] = out
        return out
    }

    // иконки каналов из программы: "источник:xml_id" → адрес (пусто — у канала иконки нет или программа ещё не загружена)
    private val iconCache = HashMap<String, String>()

    /**
     * Иконка канала из программы (XMLTV) — для логотипа, когда он не загружается из плейлиста или выбран
     * приоритет программы. Кэшируется; сбрасывается вместе с программой.
     */
    @Synchronized fun epgIcon(ch: Channel): String {
        if (ch.epgIcon.isNotEmpty()) return ch.epgIcon
        val m = mapping(ch.playlistId)[ch.key] ?: return ""
        val k = m.first.toString() + ":" + m.second
        val icon = iconCache.getOrPut(k) {
            db.readableDatabase.rawQuery("SELECT icon FROM epg_channel WHERE source_id=? AND xml_id=?", arrayOf(m.first.toString(), m.second)).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
        }
        if (icon.isNotEmpty()) ch.epgIcon = icon
        return icon
    }

    /**
     * Сброс кэшей программы. Загрузка закончена ([done]) — всё, что связано с источником. Пока она идёт
     * (события приходят на каждую пачку), сбрасываются только пустые записи: передачи, которых ещё не было,
     * появятся по мере загрузки, а готовые списки остаются. Иначе каждое переключение канала и каждая
     * отрисовка строки заново читали бы БД в главном потоке, пока в неё пишет загрузчик, — интерфейс
     * подвисал бы до конца загрузки.
     */
    @Synchronized private fun invalidateEpg(sourceId: Long, done: Boolean) {
        if (done) {
            mapFor.clear()
            iconCache.clear()
            cache.entries.removeAll { it.value.sourceId == sourceId || it.value.sourceId < 0 }
        } else {
            mapFor.entries.removeAll { it.value.isEmpty() }
            iconCache.values.removeAll { it.isEmpty() }
            cache.entries.removeAll { it.value.list.isEmpty() && (it.value.sourceId == sourceId || it.value.sourceId < 0) }
        }
    }

    @Synchronized private fun getCachedEpg(ck: String): CachedEpg? = cache[ck]
    @Synchronized private fun putCachedEpg(ck: String, v: CachedEpg) { cache[ck] = v }

    /**
     * Программа канала по возрастанию, без перекрытий (конец обрезан по началу следующей),
     * со сдвигом tvg-shift. Пустой список — программы нет (или ещё не загружена).
     */
    fun programmes(ch: Channel): List<Programme> {
        val ck = ch.playlistId.toString() + ":" + ch.key
        getCachedEpg(ck)?.let { return it.list }
        val m = mapping(ch.playlistId)[ch.key]
        if (m == null) {
            putCachedEpg(ck, CachedEpg(-1, emptyList()))
            return emptyList()
        }
        val shift = Math.round(ch.tvgShift * 3600).toLong()
        if (Looper.myLooper() === Looper.getMainLooper() && epgBusy()) {
            // идёт загрузка программы: читать БД в главном потоке нельзя — запись загрузчика замедляет чтение в разы
            // (замер: 100–240 мс на канал вместо 5–40), а строки гида просят программу каждым кадром. Читаем в фоне,
            // пока — пусто (в строке «…»); когда прочитано — событие EpgRead перерисовывает.
            if (reading.add(ck)) readWorker.execute {
                val list = readProgrammes(m.first, m.second, shift)
                main.post {
                    reading.remove(ck)
                    // загрузка успела закончиться и сбросить кэш — прочитанное могло устареть: не кэшируем, прочитается заново
                    if (epgBusy()) putCachedEpg(ck, CachedEpg(m.first, list))
                    scheduleReadEvent()
                }
            }
            return emptyList()
        }
        val t0 = System.nanoTime()
        val list = readProgrammes(m.first, m.second, shift)
        epgIcon(ch)
        putCachedEpg(ck, CachedEpg(m.first, list))
        // диагностика: чтение программы в главном потоке дольше кадра — признак борьбы с загрузчиком за БД
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= 30 && Looper.myLooper() === Looper.getMainLooper()) Log.w("IPTV", "slow epg read ${ms} ms (${list.size} передач)")
        return list
    }

    /** Идёт (или стоит в очереди) загрузка программы какого-либо источника */
    fun epgBusy() = queued.isNotEmpty()

    // чтение программы в фоне: каналы в работе и отдельный поток (не поток загрузчика — тот занят разбором)
    private val reading = HashSet<String>()
    private val readWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "epg-read").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val readEvent = Runnable { Bus.post(DataEvent.EpgRead) }

    // одно событие на пачку прочитанных каналов, а не по событию на канал
    private fun scheduleReadEvent() {
        main.removeCallbacks(readEvent)
        main.postDelayed(readEvent, 80)
    }

    /** Передачи источника [sourceId] / xml-канала [xml] по возрастанию, без перекрытий, со сдвигом [shift] секунд */
    private fun readProgrammes(sourceId: Long, xml: String, shift: Long): List<Programme> {
        val list = ArrayList<Programme>()
        db.readableDatabase.rawQuery("SELECT start,stop,title,sub_title,descr,category,icon FROM programme WHERE source_id=? AND xml_id=? ORDER BY start",
            arrayOf(sourceId.toString(), xml)).use { c ->
            while (c.moveToNext()) {
                val p = Programme(c.getLong(0) + shift, c.getLong(1) + shift, c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
                val prev = list.lastOrNull()
                if (prev != null && prev.stop > p.start) prev.stop = p.start
                if (p.stop > p.start) list.add(p)
            }
        }
        return list
    }

    /** Передача в момент [t] */
    fun at(ch: Channel, t: Long): Programme? {
        val list = programmes(ch)
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = list[mid]
            when {
                t < p.start -> hi = mid - 1
                t >= p.stop -> lo = mid + 1
                else -> return p
            }
        }
        return null
    }

    /** Первая передача, начинающаяся не раньше [t] */
    fun after(ch: Channel, t: Long): Programme? = programmes(ch).firstOrNull { it.start >= t }

    /** Передачи, пересекающие [from, to) */
    fun range(ch: Channel, from: Long, to: Long): List<Programme> = programmes(ch).filter { it.stop > from && it.start < to }

    fun epgLoading() = loadingSources.isNotEmpty()

    // ================================================================== поиск

    /** Найденная передача (время — уже со сдвигом tvg-shift) */
    class Hit(val channel: Channel, val start: Long, var stop: Long, val title: String)

    /** Где искать программу канала */
    class SearchSource(val channel: Channel, val sourceId: Long, val xmlId: String)

    /** Каналы с программой — для [searchProgrammes]; вызывать с главного потока (кэш сопоставления) */
    fun searchPlan(channels: List<Channel>): List<SearchSource> =
        channels.mapNotNull { ch -> mapping(ch.playlistId)[ch.key]?.let { SearchSource(ch, it.first, it.second) } }

    /**
     * Передачи, в названии которых есть все слова [words] (в нижнем регистре): идущие, будущие
     * (на неделю вперёд) и прошедшие в пределах архива канала. Сравнение — в коде, а не LIKE:
     * в SQLite LIKE не различает регистр только у латиницы. Можно звать из фонового потока;
     * [cancelled] проверяется между каналами.
     */
    fun searchProgrammes(plan: List<SearchSource>, words: List<String>, now: Long, cancelled: () -> Boolean): List<Hit> {
        val out = ArrayList<Hit>()
        if (words.isEmpty()) return out
        val rd = db.readableDatabase
        for (s in plan) {
            if (cancelled() || out.size >= SEARCH_LIMIT) break
            val ch = s.channel
            val shift = Math.round(ch.tvgShift * 3600).toLong()
            val archFrom = if (ch.hasArchive) now - ch.archiveDays * 86400L else now
            // идущая передача могла начаться давно — запас в 6 часов
            val from = minOf(archFrom, now - 6 * 3600L) - shift
            val to = now + 7 * 86400L - shift
            rd.rawQuery("SELECT start,stop,title FROM programme WHERE source_id=? AND xml_id=? AND start>=? AND start<? ORDER BY start",
                arrayOf(s.sourceId.toString(), s.xmlId, from.toString(), to.toString())).use { c ->
                var prevHit: Hit? = null
                while (c.moveToNext()) {
                    val start = c.getLong(0) + shift
                    val stop = c.getLong(1) + shift
                    if (stop <= start) continue
                    prevHit?.let { h ->
                        if (h.stop > start) {
                            h.stop = start
                            if (h.stop <= h.start || (h.stop <= now && h.start < archFrom)) out.remove(h)
                        }
                    }
                    prevHit = null
                    val title = c.getString(2)
                    if (!words.all { title.contains(it, true) }) continue
                    if (stop > now || start >= archFrom) {
                        val h = Hit(ch, start, stop, title)
                        out.add(h)
                        prevHit = h
                    }
                }
            }
        }
        return out
    }

    // ================================================================== пользовательские данные

    private fun keys(sql: String, id: Long): MutableList<String> {
        val out = ArrayList<String>()
        db.readableDatabase.rawQuery(sql, arrayOf(id.toString())).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    fun favorites(id: Long) = keys("SELECT key FROM favorite WHERE playlist_id=? ORDER BY sort", id)

    fun saveFavorites(id: Long, list: List<String>) {
        db.writableDatabase.tx {
            delete("favorite", "playlist_id=?", arrayOf(id.toString()))
            list.forEachIndexed { i, k -> insert("favorite", null, ContentValues().apply { put("playlist_id", id); put("key", k); put("sort", i) }) }
        }
    }

    fun history(id: Long) = keys("SELECT key FROM history WHERE playlist_id=? ORDER BY at DESC LIMIT ${Catalog.HISTORY_SIZE}", id)

    /** История целиком: первый — самый недавний */
    fun setHistory(id: Long, keys: List<String>) {
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            db.delete("history", "playlist_id=?", arrayOf(id.toString()))
            val now = Clock.nowMs()
            keys.forEachIndexed { i, k ->
                db.insert("history", null, ContentValues().apply { put("playlist_id", id); put("key", k); put("at", now - i) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addHistory(id: Long, key: String) {
        db.writableDatabase.insertWithOnConflict("history", null, ContentValues().apply {
            put("playlist_id", id); put("key", key); put("at", Clock.nowMs())
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun hidden(id: Long, kind: String): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        db.readableDatabase.rawQuery("SELECT key,title FROM hidden WHERE playlist_id=? AND kind=?", arrayOf(id.toString(), kind)).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getString(1)
        }
        return out
    }

    fun setHidden(id: Long, kind: String, key: String, title: String, on: Boolean) {
        val w = db.writableDatabase
        if (on) w.insertWithOnConflict("hidden", null, ContentValues().apply {
            put("playlist_id", id); put("kind", kind); put("key", key); put("title", title)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        else w.delete("hidden", "playlist_id=? AND kind=? AND key=?", arrayOf(id.toString(), kind, key))
    }

    fun locked(id: Long, kind: String): MutableSet<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery("SELECT key FROM locked WHERE playlist_id=? AND kind=?", arrayOf(id.toString(), kind)).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    fun setLocked(id: Long, kind: String, key: String, on: Boolean) {
        val w = db.writableDatabase
        if (on) w.insertWithOnConflict("locked", null, ContentValues().apply {
            put("playlist_id", id); put("kind", kind); put("key", key)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        else w.delete("locked", "playlist_id=? AND kind=? AND key=?", arrayOf(id.toString(), kind, key))
    }

    fun scores(id: Long): MutableMap<String, Int> {
        val out = HashMap<String, Int>()
        db.readableDatabase.rawQuery("SELECT key,value FROM score WHERE playlist_id=?", arrayOf(id.toString())).use {
            while (it.moveToNext()) out[it.getString(0)] = it.getInt(1)
        }
        return out
    }

    fun setScore(id: Long, key: String, v: Int) {
        db.writableDatabase.insertWithOnConflict("score", null, ContentValues().apply {
            put("playlist_id", id); put("key", key); put("value", v)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun resetScores(id: Long) {
        db.writableDatabase.delete("score", "playlist_id=?", arrayOf(id.toString()))
    }

    companion object {
        /** Больше находок поиск не собирает */
        const val SEARCH_LIMIT = 400
    }
}
