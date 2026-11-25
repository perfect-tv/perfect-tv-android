package top.rootu.perfecttv.data

import android.os.Process
import android.util.Log
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Net
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Загрузка плейлиста и программы в БД потоком: данные пишутся пачками по ходу чтения,
 * интерфейс видит их сразу (WAL), файл целиком в памяти не бывает.
 * Вызывать в фоновом потоке (см. [Repo]).
 */
class Loaders(private val db: Db, private val resolver: android.content.ContentResolver) {

    class LoadError(val code: String, message: String) : IOException(message)

    private class Source(val input: InputStream, val length: Long, val etag: String, val lastModified: String, val notModified: Boolean, val close: () -> Unit)

    /** URL, файл из системного выбора (content://) или локальный файл (file://, абсолютный путь) */
    private fun open(url: String, etag: String, lastModified: String, userAgent: String): Source {
        if (url.startsWith("content://")) {
            val input = try {
                resolver.openInputStream(android.net.Uri.parse(url))
            } catch (e: Exception) {
                null
            } ?: throw LoadError("no_url", "файл недоступен (нет доступа или удалён)")
            return Source(input, -1, "", "", false) { input.close() }
        }
        if (url.startsWith("file://") || url.startsWith("/")) {
            val f = File(url.removePrefix("file://"))
            if (!f.exists()) throw LoadError("no_url", "нет файла $url")
            val input = FileInputStream(f)
            return Source(input, f.length(), "", f.lastModified().toString(), false) { input.close() }
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) throw LoadError("no_url", "неверный адрес")
        val r = try {
            Net.get(url, etag, lastModified, userAgent)
        } catch (e: Net.HttpError) {
            throw LoadError("http_${e.code}", e.message ?: "")
        } catch (e: IOException) {
            throw LoadError("network", e.message ?: e.javaClass.simpleName)
        }
        if (r.code == 304) {
            r.close()
            return Source(java.io.ByteArrayInputStream(ByteArray(0)), 0, etag, lastModified, true) {}
        }
        val body = r.body ?: throw LoadError("network", "пустой ответ")
        return Source(body.byteStream(), body.contentLength(), r.header("ETag") ?: "", r.header("Last-Modified") ?: "", false) { r.close() }
    }

    // ------------------------------------------------------------------ плейлист

    /**
     * Загрузить плейлист. Первая загрузка пишет каналы сразу под его id (список растёт на глазах),
     * повторная — во временный набор (-id) и подменяет старые одной транзакцией.
     * @return число каналов (-1 — не изменился, 304)
     */
    fun loadPlaylist(p: Playlist, force: Boolean): Int {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val w = db.writableDatabase
        var etag = ""
        var lm = ""
        w.rawQuery("SELECT etag, last_modified FROM playlist WHERE id=?", arrayOf(p.id.toString())).use {
            if (it.moveToFirst() && !force && p.channelsCount > 0) { etag = it.getString(0); lm = it.getString(1) }
        }
        val src = open(p.url, etag, lm, p.userAgent.ifBlank { Net.DEFAULT_USER_AGENT })
        try {
            if (src.notModified) {
                w.execSQL("UPDATE playlist SET loaded_at=?, error='' WHERE id=?", arrayOf<Any>(Clock.now(), p.id))
                return -1
            }
            val staging = p.channelsCount > 0
            val target = if (staging) -p.id else p.id
            w.execSQL("DELETE FROM channel WHERE playlist_id=?", arrayOf<Any>(target))
            val parser = M3uParser("")
            val ins = w.compileStatement("INSERT OR REPLACE INTO channel(playlist_id,num,key,name,url,grp,logo,tvg_id,tvg_name,tvg_shift," +
                "catchup_type,catchup_source,catchup_days,user_agent,referrer,headers_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            var inBatch = 0
            w.beginTransactionNonExclusive()
            try {
                val reader = Streams.open(src.input) { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) }.bufferedReader(Charsets.UTF_8)
                parser.parse(reader) { c ->
                    ins.clearBindings()
                    ins.bindLong(1, target)
                    ins.bindLong(2, c.num.toLong())
                    ins.bindString(3, Names.key(c.name))
                    ins.bindString(4, c.name)
                    ins.bindString(5, c.url)
                    ins.bindString(6, c.group)
                    ins.bindString(7, c.logo)
                    ins.bindString(8, c.tvgId)
                    ins.bindString(9, c.tvgName)
                    ins.bindDouble(10, c.tvgShift.toDouble())
                    if (c.catchup != null) ins.bindString(11, c.catchup.type) else ins.bindNull(11)
                    ins.bindString(12, c.catchup?.source ?: "")
                    ins.bindLong(13, (c.catchup?.days ?: 0).toLong())
                    ins.bindString(14, c.userAgent)
                    ins.bindString(15, c.referrer)
                    ins.bindString(16, c.headersJson)
                    ins.executeInsert()
                    if (++inBatch >= PLAYLIST_BATCH) {
                        inBatch = 0
                        w.setTransactionSuccessful()
                        w.endTransaction()
                        if (!staging) Bus.post(DataEvent.PlaylistProgress(p.id, parser.count, false))
                        w.beginTransactionNonExclusive()
                    }
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
            if (!parser.isM3u) throw LoadError("format", "это не M3U")
            if (parser.count == 0) throw LoadError("empty", "в плейлисте нет каналов")
            val epg = M3uParser.epgUrls(parser.header).joinToString("\n")
            w.tx {
                if (staging) {
                    execSQL("DELETE FROM channel WHERE playlist_id=?", arrayOf<Any>(p.id))
                    execSQL("UPDATE channel SET playlist_id=? WHERE playlist_id=?", arrayOf<Any>(p.id, -p.id))
                }
                execSQL("UPDATE playlist SET header_epg=?, loaded_at=?, etag=?, last_modified=?, channels_count=?, error='' WHERE id=?",
                    arrayOf<Any>(epg, Clock.now(), src.etag, src.lastModified, parser.count, p.id))
            }
            return parser.count
        } catch (e: LoadError) {
            w.execSQL("DELETE FROM channel WHERE playlist_id=?", arrayOf<Any>(-p.id))
            w.execSQL("UPDATE playlist SET error=? WHERE id=?", arrayOf<Any>(e.code, p.id))
            throw e
        } catch (e: Exception) {
            w.execSQL("DELETE FROM channel WHERE playlist_id=?", arrayOf<Any>(-p.id))
            w.execSQL("UPDATE playlist SET error='network' WHERE id=?", arrayOf<Any>(p.id))
            throw LoadError(if (e is Streams.UnsupportedFormat) "format" else "network", e.message ?: e.javaClass.simpleName)
        } finally {
            src.close()
        }
    }

    // ------------------------------------------------------------------ программа

    /** Каналы, для которых нужна программа этого источника: ключ, tvg-id, tvg-name, имя, глубина архива в днях (0 — архива нет) */
    class Wanted(val key: String, val tvgId: String, val tvgName: String, val name: String, val archiveDays: Int = 0)

    /**
     * Загрузить XMLTV источника. Хранится программа только каналов плейлистов, а из прошлого — не глубже архива
     * канала (+ сутки). Передачи пишутся с load_id этой загрузки (INSERT OR REPLACE). Прошедшие дни (до начала
     * сегодняшнего) уже сохранённого диапазона не разбираются и не перезаписываются: прошлое не меняется.
     * После успешного окончания удаляются только заменённые передачи (сегодня и позже), ненужные каналы и
     * устаревшее по глубине архива; прошлое, которого в новой выгрузке нет, остаётся — оно нужно архиву.
     * Обрыв посередине ничего не теряет.
     */
    fun loadEpg(sourceId: Long, url: String, channels: List<Wanted>, force: Boolean, langs: List<String>, userAgent: String) {
        // большой XMLTV сканируется почти минуту: не догонять интерфейс и playback-поток плеера
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        val w = db.writableDatabase
        var etag = ""
        var lm = ""
        var loadId = 1L
        w.rawQuery("SELECT etag, last_modified, load_id, status FROM epg_source WHERE id=?", arrayOf(sourceId.toString())).use {
            if (it.moveToFirst()) {
                if (!force && it.getInt(3) == STATUS_READY) { etag = it.getString(0); lm = it.getString(1) }
                loadId = it.getLong(2) + 1
            }
        }
        w.execSQL("UPDATE epg_source SET status=?, error='' WHERE id=?", arrayOf<Any>(STATUS_LOADING, sourceId))
        val t0 = System.currentTimeMillis()
        val src = try {
            open(url, etag, lm, userAgent.ifBlank { Net.DEFAULT_USER_AGENT })
        } catch (e: LoadError) {
            fail(sourceId, url, e.code)
            throw e
        }
        try {
            if (src.notModified) {
                w.execSQL("UPDATE epg_source SET status=?, loaded_at=? WHERE id=?", arrayOf<Any>(STATUS_READY, Clock.now(), sourceId))
                Bus.post(DataEvent.EpgProgress(sourceId, url, 0, 100, true))
                return
            }
            val now = Clock.now()
            val maxT = now + 8 * 86400L
            // начало сегодняшнего дня по местному времени: всё, что закончилось раньше, — «прошлое», оно не меняется
            val todayStart = java.util.Calendar.getInstance().apply {
                timeInMillis = now * 1000
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000
            // глубина архива по каналам источника (xml-id → дней), заполняется после сопоставления
            val xmlDays = HashMap<String, Int>()
            // уже сохранённое прошлое по каналам: от первой до последней передачи
            class Span(val from: Long, val to: Long)
            val have = HashMap<String, Span>()
            w.rawQuery("SELECT xml_id, MIN(start), MAX(start) FROM programme WHERE source_id=? AND stop<=? GROUP BY xml_id",
                arrayOf(sourceId.toString(), todayStart.toString())).use { while (it.moveToNext()) have[it.getString(0)] = Span(it.getLong(1), it.getLong(2)) }
            val inRange = { ch: String, start: Long, stop: Long ->
                // глубже архива канала (+ сутки) и дальше недели вперёд не нужно; прошлое внутри сохранённого — уже есть
                val keepFrom = now - ((xmlDays[ch] ?: 0) + 1) * 86400L
                val h = have[ch]
                stop >= keepFrom && start <= maxT && !(stop <= todayStart && h != null && start >= h.from && start <= h.to)
            }
            val counting = Streams.Counting(src.input)
            var skippedOf: () -> Int = { 0 }
            val matcher = EpgMatcher()
            val wanted = HashSet<String>()
            val chIns = w.compileStatement("INSERT OR REPLACE INTO epg_channel(source_id,xml_id,names,icon) VALUES(?,?,?,?)")
            val prIns = w.compileStatement("INSERT OR REPLACE INTO programme(source_id,xml_id,start,stop,title,sub_title,descr,category,icon,load_id) VALUES(?,?,?,?,?,?,?,?,?,?)")
            var inBatch = 0
            var written = 0
            var stored = 0   // всего передач источника в БД после загрузки (written — записано в этой)
            var lastEvent = 0L
            var dbNs = 0L      // время записи в БД (вставки и коммиты) — для разбивки в логе
            val commit = {
                val c0 = System.nanoTime()
                w.setTransactionSuccessful()
                w.endTransaction()
                dbNs += System.nanoTime() - c0
                val t = System.currentTimeMillis()
                if (t - lastEvent > 700) {
                    lastEvent = t
                    val pct = if (src.length > 0) (counting.bytes * 100 / src.length).toInt().coerceIn(0, 99) else -1
                    Bus.post(DataEvent.EpgProgress(sourceId, url, written, pct, false))
                }
                w.beginTransactionNonExclusive()
            }
            // на время загрузки: кэш страниц побольше и checkpoint WAL реже — меньше повторной записи
            w.rawQuery("PRAGMA cache_size=-16000", null).close()
            w.rawQuery("PRAGMA wal_autocheckpoint=8000", null).close()
            w.beginTransactionNonExclusive()
            try {
                val parser = XmltvParser(
                    langs = langs,
                    onChannel = { c ->
                        matcher.addChannel(c)
                        chIns.clearBindings()
                        chIns.bindLong(1, sourceId)
                        chIns.bindString(2, c.id)
                        chIns.bindString(3, c.names.joinToString("\n"))
                        chIns.bindString(4, c.icon)
                        chIns.executeInsert()
                    },
                    beforeProgrammes = {
                        // все <channel> прочитаны: сопоставить каналы плейлистов и выбрать нужные
                        w.execSQL("DELETE FROM epg_map WHERE source_id=?", arrayOf<Any>(sourceId))
                        wanted.clear()
                        val mapIns = w.compileStatement("INSERT OR REPLACE INTO epg_map(source_id,ch_key,xml_id) VALUES(?,?,?)")
                        for (c in channels) {
                            val xml = matcher.match(c.tvgId, c.tvgName, c.name) ?: continue
                            wanted.add(xml)
                            xmlDays.merge(xml, c.archiveDays, ::maxOf)
                            mapIns.bindLong(1, sourceId)
                            mapIns.bindString(2, c.key)
                            mapIns.bindString(3, xml)
                            mapIns.executeInsert()
                        }
                        mapIns.close()
                        Log.i(TAG, "epg $url: каналов ${matcher.size}, сопоставлено ${wanted.size} из ${channels.size}")
                    },
                    wanted = { it in wanted },
                    onProgramme = { pr ->
                        if (inRange(pr.channel, pr.start, pr.stop)) {
                            prIns.clearBindings()
                            prIns.bindLong(1, sourceId)
                            prIns.bindString(2, pr.channel)
                            prIns.bindLong(3, pr.start)
                            prIns.bindLong(4, pr.stop)
                            prIns.bindString(5, pr.title)
                            prIns.bindString(6, pr.subTitle)
                            prIns.bindString(7, pr.desc)
                            prIns.bindString(8, pr.category)
                            prIns.bindString(9, pr.icon)
                            prIns.bindLong(10, loadId)
                            val i0 = System.nanoTime()
                            prIns.executeInsert()
                            dbNs += System.nanoTime() - i0
                            written++
                            if (++inBatch >= EPG_BATCH) {
                                inBatch = 0
                                commit()
                            }
                        }
                    },
                    accept = inRange
                )
                skippedOf = { parser.skipped }
                parser.parse(Streams.open(counting) { it.endsWith(".xml", true) || !it.contains('.') })
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
                chIns.close()
                prIns.close()
                w.rawQuery("PRAGMA wal_autocheckpoint=1000", null).close()
            }
            w.tx {
                // каналы, которых нет в плейлистах (или пропали из сопоставления)
                execSQL("DELETE FROM programme WHERE source_id=? AND xml_id NOT IN (SELECT xml_id FROM epg_map WHERE source_id=?)", arrayOf<Any>(sourceId, sourceId))
                // заменённое: передачи прошлой загрузки с сегодняшнего дня, начиная с первой передачи новой выгрузки канала
                // (нет новых передач канала на сегодня и дальше — все его будущие передачи устарели)
                execSQL("DELETE FROM programme WHERE source_id=? AND load_id<>? AND stop>? AND start>=COALESCE((SELECT MIN(p2.start) FROM programme p2 " +
                    "WHERE p2.source_id=programme.source_id AND p2.xml_id=programme.xml_id AND p2.load_id=? AND p2.stop>?), 0)",
                    arrayOf<Any>(sourceId, loadId, todayStart, loadId, todayStart))
                // глубже архива канала (+ сутки): по группам каналов с одинаковой глубиной
                for ((days, ids) in xmlDays.entries.groupBy({ it.value }, { it.key })) {
                    val keepFrom = now - (days + 1) * 86400L
                    for (chunk in ids.chunked(400)) {
                        execSQL("DELETE FROM programme WHERE source_id=? AND stop<? AND xml_id IN (" + chunk.joinToString(",") { "?" } + ")",
                            (listOf<Any>(sourceId, keepFrom) + chunk).toTypedArray())
                    }
                }
                val total = compileStatement("SELECT COUNT(*) FROM programme WHERE source_id=?").also { it.bindLong(1, sourceId) }.simpleQueryForLong()
                total.let { stored = it.toInt() }
                execSQL("UPDATE epg_source SET status=?, loaded_at=?, etag=?, last_modified=?, programmes_count=?, load_id=?, error='' WHERE id=?",
                    arrayOf<Any>(STATUS_READY, Clock.now(), src.etag, src.lastModified, stored, loadId, sourceId))
            }
            Log.i(TAG, "epg $url: передач записано $written (в БД $stored) за ${System.currentTimeMillis() - t0} мс " +
                "(${counting.bytes / 1048576} МБ скачано, запись в БД ${dbNs / 1_000_000} мс, пропущено ${skippedOf()} передач)")
            Bus.post(DataEvent.EpgProgress(sourceId, url, written, 100, true))
        } catch (e: Exception) {
            val code = when (e) {
                is LoadError -> e.code
                is Streams.UnsupportedFormat -> "format"
                is org.xmlpull.v1.XmlPullParserException -> "format"
                else -> "network"
            }
            Log.w(TAG, "epg $url: $code ${e.message}")
            fail(sourceId, url, code)
            throw LoadError(code, e.message ?: "")
        } finally {
            src.close()
        }
    }

    private fun fail(sourceId: Long, url: String, code: String) {
        db.writableDatabase.execSQL("UPDATE epg_source SET status=?, error=? WHERE id=?", arrayOf<Any>(STATUS_ERROR, code, sourceId))
        Bus.post(DataEvent.EpgProgress(sourceId, url, 0, 0, true, code))
    }

    companion object {
        const val TAG = "IPTV"
        const val PLAYLIST_BATCH = 500
        const val EPG_BATCH = 5000
        const val STATUS_NONE = 0
        const val STATUS_LOADING = 1
        const val STATUS_READY = 2
        const val STATUS_ERROR = 3
    }
}
