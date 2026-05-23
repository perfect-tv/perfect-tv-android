package top.rootu.perfecttv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite приложения. WAL: загрузчики пишут пачками в своих транзакциях,
 * интерфейс в это время читает уже записанное.
 *
 * Время — unix-секунды. Пользовательские данные (избранное, история, скрытое, напоминания)
 * привязаны к ключу канала [Names.key], а не к номеру: переживают перезагрузку плейлиста.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "perfecttv.db", null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(false)
        // синхронизация NORMAL в WAL безопасна и заметно быстрее на flash приставок
        db.rawQuery("PRAGMA synchronous=NORMAL", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE playlist(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            epg_mode INTEGER NOT NULL DEFAULT 0,      -- 0 из плейлиста, 1 плейлист + свои, 2 только свои
            user_epg TEXT NOT NULL DEFAULT '',        -- свои URL через \n
            header_epg TEXT NOT NULL DEFAULT '',      -- URL из #EXTM3U через \n
            loaded_at INTEGER NOT NULL DEFAULT 0,
            etag TEXT NOT NULL DEFAULT '',
            last_modified TEXT NOT NULL DEFAULT '',
            channels_count INTEGER NOT NULL DEFAULT 0,
            error TEXT NOT NULL DEFAULT '',
            sort INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE channel(
            playlist_id INTEGER NOT NULL,
            num INTEGER NOT NULL,
            key TEXT NOT NULL,
            name TEXT NOT NULL,
            url TEXT NOT NULL,
            grp TEXT NOT NULL,
            logo TEXT NOT NULL,
            tvg_id TEXT NOT NULL,
            tvg_name TEXT NOT NULL,
            tvg_shift REAL NOT NULL,
            catchup_type TEXT,                         -- NULL — архива нет
            catchup_source TEXT NOT NULL,
            catchup_days INTEGER NOT NULL,
            user_agent TEXT NOT NULL,
            referrer TEXT NOT NULL,
            headers_json TEXT NOT NULL,
            PRIMARY KEY(playlist_id, num)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE epg_source(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL UNIQUE,
            loaded_at INTEGER NOT NULL DEFAULT 0,
            etag TEXT NOT NULL DEFAULT '',
            last_modified TEXT NOT NULL DEFAULT '',
            status INTEGER NOT NULL DEFAULT 0,         -- 0 нет данных, 1 грузится, 2 готово, 3 ошибка
            error TEXT NOT NULL DEFAULT '',
            programmes_count INTEGER NOT NULL DEFAULT 0,
            load_id INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE epg_channel(
            source_id INTEGER NOT NULL,
            xml_id TEXT NOT NULL,
            names TEXT NOT NULL,
            icon TEXT NOT NULL,
            PRIMARY KEY(source_id, xml_id)) WITHOUT ROWID""")
        // сопоставление канала плейлиста (по ключу) с каналом источника
        db.execSQL("""CREATE TABLE epg_map(
            source_id INTEGER NOT NULL,
            ch_key TEXT NOT NULL,
            xml_id TEXT NOT NULL,
            PRIMARY KEY(source_id, ch_key)) WITHOUT ROWID""")
        createProgramme(db)
        db.execSQL("""CREATE TABLE favorite(
            playlist_id INTEGER NOT NULL, key TEXT NOT NULL, sort INTEGER NOT NULL,
            PRIMARY KEY(playlist_id, key)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE history(
            playlist_id INTEGER NOT NULL, key TEXT NOT NULL, at INTEGER NOT NULL,
            PRIMARY KEY(playlist_id, key)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE hidden(
            playlist_id INTEGER NOT NULL, kind TEXT NOT NULL, key TEXT NOT NULL, title TEXT NOT NULL,
            PRIMARY KEY(playlist_id, kind, key)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE score(
            playlist_id INTEGER NOT NULL, key TEXT NOT NULL, value INTEGER NOT NULL,
            PRIMARY KEY(playlist_id, key)) WITHOUT ROWID""")
        db.execSQL("""CREATE TABLE reminder(
            id TEXT PRIMARY KEY, playlist_id INTEGER NOT NULL, playlist_name TEXT NOT NULL,
            ch_key TEXT NOT NULL, ch_name TEXT NOT NULL, ch_num INTEGER NOT NULL, logo TEXT NOT NULL,
            start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT NOT NULL,
            snooze INTEGER NOT NULL DEFAULT 0,
            archive_days INTEGER NOT NULL DEFAULT 0,     -- глубина архива канала при добавлении
            missed_seen INTEGER NOT NULL DEFAULT 0)     -- пропущенная уже показана
            """)
    }

    /**
     * Программа — обычная таблица с rowid и уникальным индексом: строки большие (описания),
     * а WITHOUT ROWID с такими строками заполняет страницы наполовину (БД в полтора раза больше).
     */
    private fun createProgramme(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE programme(
            source_id INTEGER NOT NULL,
            xml_id TEXT NOT NULL,
            start INTEGER NOT NULL,
            stop INTEGER NOT NULL,
            title TEXT NOT NULL,
            sub_title TEXT NOT NULL,
            descr TEXT NOT NULL,
            category TEXT NOT NULL,
            icon TEXT NOT NULL,
            load_id INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX programme_key ON programme(source_id, xml_id, start)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // миграции по версиям
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE reminder ADD COLUMN archive_days INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE reminder ADD COLUMN missed_seen INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // новая раскладка программы: пересоздать и загрузить источники заново
            db.execSQL("DROP TABLE programme")
            createProgramme(db)
            db.execSQL("UPDATE epg_source SET status=0, etag='', last_modified='', programmes_count=0")
            needVacuum = true
        }
    }

    /** Источник EPG по URL (создаёт при первом обращении) */
    fun sourceId(url: String): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM epg_source WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) return it.getLong(0) }
        return db.insert("epg_source", null, ContentValues().apply { put("url", url) })
    }

    /** После миграции с удалением больших таблиц — сжать файл (VACUUM нельзя внутри onUpgrade) */
    @Volatile var needVacuum = false

    companion object {
        const val VERSION = 3
    }
}

/** Выполнить [block] в транзакции */
inline fun <T> SQLiteDatabase.tx(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}
