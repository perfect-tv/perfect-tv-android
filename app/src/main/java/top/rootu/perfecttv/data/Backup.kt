package top.rootu.perfecttv.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.perfecttv.core.Prefs

/**
 * Резервная копия: настройки и пользовательские данные одним JSON — плейлисты (без каналов:
 * они скачаются заново), избранное, недавние каналы, скрытое, блокировки, очки сортировки,
 * напоминания.
 * Восстановление заменяет всё перечисленное; плейлисты сохраняют свои id, поэтому ссылки
 * на них из остальных таблиц остаются верными.
 */
object Backup {
    /** Прежнее имя файла копии (старые копии восстанавливаются и с ним) */
    const val LEGACY_FILE = "perfecttv-backup.json"
    const val EXT = "perfecttv"
    const val MIME = "application/x-perfecttv"

    /** Имя файла копии: `backup-20261115192016.perfecttv` (местное время) */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        "backup-" + java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(java.util.Date(now)) + "." + EXT

    /** Дата создания копии (unix-секунды) или null, если это не копия Perfect TV (чужой JSON, мусор) */
    fun created(json: String): Long? = try {
        val o = JSONObject(json)
        if (!o.has("tables") || !o.has("prefs")) null else o.optLong("created", 0L)
    } catch (_: Exception) { null }
    private const val VERSION = 1
    private val TABLES = listOf("playlist", "favorite", "history", "hidden", "locked", "score", "reminder")

    fun export(db: SQLiteDatabase): String {
        val root = JSONObject().put("version", VERSION).put("created", top.rootu.perfecttv.core.Clock.now())
        val prefs = JSONObject()
        for ((k, v) in Prefs.all()) {
            if (k.startsWith("home_")) continue   // id карточек главного экрана — свои у каждого устройства
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> continue }
            prefs.put(k, JSONObject().put("t", t).put("v", v))
        }
        root.put("prefs", prefs)
        val tables = JSONObject()
        for (name in TABLES) {
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $name", null).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                    for (i in 0 until c.columnCount) when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> o.put(c.getColumnName(i), c.getString(i))
                    }
                    rows.put(o)
                }
            }
            tables.put(name, rows)
        }
        return root.put("tables", tables).toString(1)
    }

    /** Заменить настройки и пользовательские данные содержимым копии; бросает исключение на чужом файле */
    fun restore(db: SQLiteDatabase, json: String) {
        val root = JSONObject(json)
        val tables = root.getJSONObject("tables")
        val prefs = root.getJSONObject("prefs")
        db.beginTransaction()
        try {
            // каналы привязаны к id плейлистов — убрать, плейлисты скачаются заново
            db.delete("channel", null, null)
            for (name in TABLES) {
                val rows = tables.optJSONArray(name) ?: continue
                val known = HashSet<String>()
                db.rawQuery("SELECT * FROM $name LIMIT 0", null).use { known.addAll(it.columnNames) }
                db.delete(name, null, null)
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    val cv = ContentValues()
                    for (k in o.keys()) {
                        if (k !in known) continue
                        when (val v = o.get(k)) {
                            is Int -> cv.put(k, v.toLong())
                            is Long -> cv.put(k, v)
                            is Double -> cv.put(k, v)
                            else -> cv.put(k, v.toString())
                        }
                    }
                    if (name == "playlist") {
                        cv.put("loaded_at", 0); cv.put("channels_count", 0)
                        cv.put("etag", ""); cv.put("last_modified", ""); cv.put("error", "")
                    }
                    db.insertWithOnConflict(name, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val values = HashMap<String, Any>()
        for (k in prefs.keys()) {
            val e = prefs.getJSONObject(k)
            values[k] = when (e.getString("t")) {
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                else -> e.getString("v")
            }
        }
        Prefs.replace(values)
    }
}
