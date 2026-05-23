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
    private val loaders = Loaders(db) { maxOf(Prefs.archiveDays, maxArchiveDays) }
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
                invalidateEpg(e.sourceId)
            }
        }
    }

    // ================================================================== плейлисты

    fun playlists(): List<Playlist> {
        val out = ArrayList<Playlist>()
        db.readableDatabase.rawQuery("SELECT id,name,url,epg_mode,user_epg,header_epg,loaded_at,channels_count,error FROM playlist ORDER BY sort,id", null).use { c ->
            while (c.moveToNext()) out.add(Playlist(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3),
                c.getString(4).lines().filter { it.isNotBlank() }, c.getString(5).lines().filter { it.isNotBlank() },
                c.getLong(6), c.getInt(7), c.getString(8)
            ))
        }
        return out
    }

    fun playlist(id: Long): Playlist? = playlists().firstOrNull { it.id == id }

    fun addPlaylist(name: String, url: String): Long =
        db.writableDatabase.insert("playlist", null, ContentValues().apply { put("name", name); put("url", url.trim()) })

    fun updatePlaylist(id: Long, name: String, url: String, epgMode: Int, userEpg: List<String>) {
        db.writableDatabase.update("playlist", ContentValues().apply {
            put("name", name); put("url", url.trim()); put("epg_mode", epgMode); put("user_epg", userEpg.joinToString("\n"))
        }, "id=?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        db.writableDatabase.tx {
            for (t in listOf("channel", "favorite", "history", "hidden", "score")) delete(t, "playlist_id=?", arrayOf(id.toString()))
            delete("playlist", "id=?", arrayOf(id.toString()))
        }
    }

    /** Каналы плейлиста из БД (то, что уже записано, — в том числе во время первой загрузки) */
    fun loadCatalog(p: Playlist, favoritesTitle: String): Catalog {
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
                    userAgent = c.getString(12), referrer = c.getString(13), headersJson = c.getString(14)
                ))
            }
        }
        maxArchiveDays = maxOf(maxArchiveDays, list.maxOfOrNull { it.archiveDays } ?: 0)
        mapFor.remove(p.id)
        return Catalog(p, list, this, favoritesTitle)
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

    private fun queueEpg(p: Playlist, force: Boolean) {
        val maxAge = Prefs.epgRefreshHours * 3600L
        for (url in p.epgUrls()) {
            val id = db.sourceId(url)
            var fresh = false
            db.readableDatabase.rawQuery("SELECT loaded_at, status FROM epg_source WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) fresh = it.getInt(1) == Loaders.STATUS_READY && Clock.now() - it.getLong(0) < maxAge
            }
            if (fresh && !force) continue
            worker.execute {
                try {
                    loaders.loadEpg(id, url, wantedFor(url), force, langs())
                } catch (_: Loaders.LoadError) {
                }
            }
        }
    }

    /** Каналы всех плейлистов, в источниках которых есть [url] */
    private fun wantedFor(url: String): List<Loaders.Wanted> {
        val out = ArrayList<Loaders.Wanted>()
        val seen = HashSet<String>()
        for (p in playlists()) {
            if (url !in p.epgUrls()) continue
            db.readableDatabase.rawQuery("SELECT key,tvg_id,tvg_name,name FROM channel WHERE playlist_id=?", arrayOf(p.id.toString())).use { c ->
                while (c.moveToNext()) if (seen.add(c.getString(0))) out.add(Loaders.Wanted(c.getString(0), c.getString(1), c.getString(2), c.getString(3)))
            }
        }
        return out
    }

    private fun langs(): List<String> = listOf(Locale.getDefault().language.lowercase(), "ru", "en").distinct()

    /** Устарел ли плейлист (перекачать при запуске) */
    fun isStale(p: Playlist) = p.channelsCount == 0 || Clock.now() - p.loadedAt > Prefs.playlistRefreshHours * 3600L

    // ================================================================== программа

    // ключ канала → (источник, xml_id) для каждого плейлиста; источники — по приоритету
    private val mapFor = HashMap<Long, Map<String, Pair<Long, String>>>()
    private val cache = object : LinkedHashMap<String, CachedEpg>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEpg>?) = size > 300
    }

    private class CachedEpg(val sourceId: Long, val list: List<Programme>)

    private fun mapping(playlistId: Long): Map<String, Pair<Long, String>> {
        mapFor[playlistId]?.let { return it }
        val p = playlist(playlistId) ?: return emptyMap()
        val out = HashMap<String, Pair<Long, String>>()
        for (url in p.epgUrls()) {
            var sid = -1L
            db.readableDatabase.rawQuery("SELECT id FROM epg_source WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) sid = it.getLong(0) }
            if (sid < 0) continue
            db.readableDatabase.rawQuery("SELECT ch_key, xml_id FROM epg_map WHERE source_id=?", arrayOf(sid.toString())).use { c ->
                while (c.moveToNext()) out.putIfAbsent(c.getString(0), sid to c.getString(1))
            }
        }
        mapFor[playlistId] = out
        return out
    }

    private fun invalidateEpg(sourceId: Long) {
        mapFor.clear()
        cache.entries.removeAll { it.value.sourceId == sourceId || it.value.sourceId < 0 }
    }

    /**
     * Программа канала по возрастанию, без перекрытий (конец обрезан по началу следующей),
     * со сдвигом tvg-shift. Пустой список — программы нет (или ещё не загружена).
     */
    fun programmes(ch: Channel): List<Programme> {
        val ck = ch.playlistId.toString() + ":" + ch.key
        cache[ck]?.let { return it.list }
        val m = mapping(ch.playlistId)[ch.key]
        if (m == null) {
            cache[ck] = CachedEpg(-1, emptyList())
            return emptyList()
        }
        val shift = Math.round(ch.tvgShift * 3600).toLong()
        val list = ArrayList<Programme>()
        db.readableDatabase.rawQuery("SELECT start,stop,title,sub_title,descr,category,icon FROM programme WHERE source_id=? AND xml_id=? ORDER BY start",
            arrayOf(m.first.toString(), m.second)).use { c ->
            while (c.moveToNext()) {
                val p = Programme(c.getLong(0) + shift, c.getLong(1) + shift, c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))
                val prev = list.lastOrNull()
                if (prev != null && prev.stop > p.start) prev.stop = p.start
                if (p.stop > p.start) list.add(p)
            }
        }
        if (ch.logo.isEmpty() && ch.epgIcon.isEmpty()) {
            db.readableDatabase.rawQuery("SELECT icon FROM epg_channel WHERE source_id=? AND xml_id=?", arrayOf(m.first.toString(), m.second)).use {
                if (it.moveToFirst()) ch.epgIcon = it.getString(0)
            }
        }
        cache[ck] = CachedEpg(m.first, list)
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
}
