package top.rootu.perfecttv.data

import android.content.ContentValues
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs

/**
 * Напоминания о передачах — общие для всех плейлистов (порт reminders/store.js).
 *
 * id = ключ канала + ':' + начало: одна передача канала — одно напоминание, из какого бы
 * плейлиста её ни добавили (запускается из плейлиста, где добавили).
 * snooze — unix-сек следующего показа после «Отложить».
 * Закончившиеся удаляются при загрузке и при каждой проверке. Проверку вызывает интерфейс
 * ([check], раз в 30 с) — плюс системный будильник, когда приложение закрыто ([Alarms]).
 */
class Reminders(private val db: Db) {

    class Reminder(
        val id: String,
        val playlistId: Long,
        val playlistName: String,
        val chKey: String,
        val chName: String,
        val chNum: Int,
        val logo: String,
        val start: Long,
        val stop: Long,
        val title: String,
        val desc: String,
        var snooze: Long
    )

    private var list: MutableList<Reminder>? = null
    private val shown = HashSet<String>()
    private val changeListeners = ArrayList<() -> Unit>()
    /** Пора показать (вызывается из [check]) */
    var onDue: ((Reminder) -> Unit)? = null

    fun listen(l: () -> Unit) = changeListeners.add(l)
    fun unlisten(l: () -> Unit) = changeListeners.remove(l)

    private fun load(): MutableList<Reminder> {
        list?.let { return it }
        val out = ArrayList<Reminder>()
        db.readableDatabase.rawQuery("SELECT id,playlist_id,playlist_name,ch_key,ch_name,ch_num,logo,start,stop,title,descr,snooze FROM reminder", null).use { c ->
            while (c.moveToNext()) out.add(Reminder(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5),
                c.getString(6), c.getLong(7), c.getLong(8), c.getString(9), c.getString(10), c.getLong(11)))
        }
        list = out
        return out
    }

    private fun changed() {
        changeListeners.toList().forEach { it() }
    }

    fun keyOf(chKey: String, start: Long) = "$chKey:$start"

    /** Все по времени начала */
    fun all(): List<Reminder> = load().sortedBy { it.start }

    fun has(ch: Channel, start: Long) = load().any { it.id == keyOf(ch.key, start) }

    fun add(p: Playlist, ch: Channel, pr: Programme) {
        if (has(ch, pr.start)) return
        val r = Reminder(keyOf(ch.key, pr.start), p.id, p.name, ch.key, ch.name, ch.num, ch.logoUrl(), pr.start, pr.stop, pr.title, pr.desc, 0)
        load().add(r)
        db.writableDatabase.insertWithOnConflict("reminder", null, ContentValues().apply {
            put("id", r.id); put("playlist_id", r.playlistId); put("playlist_name", r.playlistName); put("ch_key", r.chKey)
            put("ch_name", r.chName); put("ch_num", r.chNum); put("logo", r.logo); put("start", r.start); put("stop", r.stop)
            put("title", r.title); put("descr", r.desc); put("snooze", 0)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun remove(id: String) {
        load().removeAll { it.id == id }
        shown.remove(id)
        db.writableDatabase.delete("reminder", "id=?", arrayOf(id))
        changed()
    }

    /** Отложить на 5 минут (не дальше конца передачи) */
    fun snooze(id: String) {
        val r = load().firstOrNull { it.id == id } ?: return
        r.snooze = Clock.now() + SNOOZE
        if (r.snooze >= r.stop) return remove(id)
        shown.remove(id)
        db.writableDatabase.execSQL("UPDATE reminder SET snooze=? WHERE id=?", arrayOf<Any>(r.snooze, id))
        changed()
    }

    /** Момент показа: за N минут до начала (настройка) или после «Отложить» */
    fun dueAt(r: Reminder) = if (r.snooze > 0) r.snooze else r.start - Prefs.remindBefore * 60L

    /** Ближайший будущий показ (для будильника), 0 — нет */
    fun nextDue(): Long = load().filter { !shown.contains(it.id) }.map { dueAt(it) }.filter { it > Clock.now() }.minOrNull() ?: 0

    /** Удалить закончившиеся передачи */
    fun cleanup() {
        val t = Clock.now()
        val old = load().filter { it.stop <= t }
        if (old.isEmpty()) return
        for (r in old) {
            load().remove(r)
            db.writableDatabase.delete("reminder", "id=?", arrayOf(r.id))
        }
        changed()
    }

    /** Кого пора показать — в том числе пропущенные, пока передача идёт */
    fun check() {
        cleanup()
        val t = Clock.now()
        for (r in all()) {
            if (r.id !in shown && dueAt(r) <= t && t < r.stop) {
                shown.add(r.id)
                onDue?.invoke(r)
            }
        }
    }

    /** Показ закрыт без решения — можно показать снова */
    fun release(id: String) = shown.remove(id)

    companion object {
        const val SNOOZE = 300L
    }
}
