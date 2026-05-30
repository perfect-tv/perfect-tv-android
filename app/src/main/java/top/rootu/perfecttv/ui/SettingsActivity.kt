package top.rootu.perfecttv.ui

import top.rootu.perfecttv.core.L
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import top.rootu.perfecttv.App
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Bus
import top.rootu.perfecttv.data.DataEvent
import top.rootu.perfecttv.data.Loaders
import top.rootu.perfecttv.data.Playlist

/**
 * Настройки (минимум из ТЗ §11): плейлисты с источниками программы и параметры.
 * Обычные View с фокусом Android: ↑/↓ — пункты, OK — изменить, «Назад» — выйти.
 * С EXTRA_FIRST — мастер первого плейлиста: сразу форма добавления.
 */
class SettingsActivity : Activity() {

    private val repo get() = App.repo
    private lateinit var frame: FrameLayout
    private var editing: Long? = null
    private val onData: (DataEvent) -> Unit = { if (editing == null) showMain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        U.init(this)
        frame = FrameLayout(this).apply { setBackgroundColor(U.argbGuide()) }
        setContentView(frame)
        if (intent.getBooleanExtra(EXTRA_FIRST, false) && repo.playlists().isEmpty()) showEdit(null) else showMain()
        Bus.on(onData)
    }

    override fun onDestroy() {
        Bus.off(onData)
        super.onDestroy()
    }

    private fun page(title: String): LinearLayout {
        frame.removeAllViews()
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; Fade.scroll(this) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(U.pxi(4f), U.pxi(2f), U.pxi(4f), U.pxi(2f))
        }
        col.addView(U.text(this, 2f, bold = true).apply { text = title }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = U.pxi(1f) })
        scroll.addView(col)
        frame.addView(scroll, FrameLayout.LayoutParams(minOf(U.pxi(60f), resources.displayMetrics.widthPixels), -1, Gravity.CENTER_HORIZONTAL))
        return col
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(U.text(this, 1f, 0.5f, bold = true).apply { text = title.uppercase() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(1.4f); bottomMargin = U.pxi(0.4f) })
    }

    /** Пункт: название, значение справа (или подпись снизу), OK — [onClick] */
    private fun item(col: LinearLayout, title: String, value: String, sub: String = "", onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setPadding(U.pxi(1f), U.pxi(0.7f), U.pxi(1f), U.pxi(0.7f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = U.text(this, 1.15f).apply { text = title }
        val s = U.text(this, 0.9f, 0.6f, lines = 2).apply { text = sub }
        texts.addView(t)
        if (sub.isNotEmpty()) texts.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.2f) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val v = U.text(this, 1.05f, 0.7f).apply { text = value }
        row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = U.pxi(1f) })
        fun paint(f: Boolean) {
            row.background = U.bg(if (f) U.WHITE else U.FILL, U.RADIUS_S)
            val c = if (f) U.BLACK else U.WHITE
            t.setTextColor(c)
            s.setTextColor(if (f) U.BLACK else U.wa(0.6f))
            v.setTextColor(if (f) U.BLACK else U.wa(0.7f))
        }
        paint(false)
        row.setOnFocusChangeListener { _, f -> paint(f) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_UP && Keys.isOk(code)) { onClick(); true } else false }
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = U.pxi(0.3f) })
        return row
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1) % list.size]

    // ------------------------------------------------------------------ главная

    private fun showMain() {
        editing = null
        val col = page(L.t("Настройки"))
        section(col, L.t("Плейлисты"))
        var first: View? = null
        for (p in repo.playlists()) {
            val status = when {
                p.error.isNotEmpty() -> L.t("ошибка: ") + p.error
                p.channelsCount > 0 -> L.f("%s каналов", p.channelsCount)
                else -> L.t("не загружен")
            }
            val v = item(col, p.name, if (p.id == Prefs.lastPlaylist) "●" else "", p.url + " · " + status) { showEdit(p) }
            if (first == null) first = v
        }
        val add = item(col, L.t("+ Добавить плейлист"), "") { showEdit(null) }
        if (first == null) first = add
        section(col, L.t("Просмотр"))
        item(col, "Язык / Language", when (Prefs.language) { "ru" -> "Русский"; "en" -> "English"; else -> L.t("Системный") }) {
            Prefs.language = cycle(listOf("", "ru", "en"), Prefs.language)
            L.init()
            showMain()
        }
        item(col, L.t("Глубина архива по умолчанию"), L.f("%s дн.", Prefs.archiveDays), L.t("если в плейлисте не указана")) {
            Prefs.archiveDays = cycle(listOf(1, 2, 3, 4, 5, 7, 10, 14), Prefs.archiveDays); showMain()
        }
        item(col, L.t("Клавиша ↑ в плеере"), if (Prefs.upArchive) L.t("Программа и архив") else L.t("Недавние каналы")) {
            Prefs.upArchive = !Prefs.upArchive; showMain()
        }
        item(col, L.t("Напоминать о передаче"), if (Prefs.remindBefore == 0) L.t("Когда начнётся") else L.f("За %s мин", Prefs.remindBefore)) {
            Prefs.remindBefore = cycle(listOf(0, 1, 2, 3, 4, 5, 10), Prefs.remindBefore); showMain()
        }
        item(col, L.t("Скрывать инфопанель через"), L.f("%s с", Prefs.infoHide)) {
            Prefs.infoHide = cycle(listOf(3, 5, 7, 10), Prefs.infoHide); showMain()
        }
        item(col, L.t("Плавные анимации"), if (Prefs.animations) L.t("Вкл.") else L.t("Выкл."), L.t("выключить на слабых приставках")) {
            Prefs.animations = !Prefs.animations; Anim.on = Prefs.animations; showMain()
        }
        item(col, L.t("Запускать последний канал"), if (Prefs.autoplayLast) L.t("Вкл.") else L.t("Выкл."), L.t("выключено — при запуске открывается гид")) {
            Prefs.autoplayLast = !Prefs.autoplayLast; showMain()
        }
        item(col, L.t("Адаптивная сортировка"), if (Prefs.adaptiveSort) L.t("Вкл.") else L.t("Выкл."), L.t("часто смотримые каналы — выше")) {
            Prefs.adaptiveSort = !Prefs.adaptiveSort; showMain()
        }
        item(col, L.t("Сбросить очки сортировки"), "", L.t("все плейлисты: порядок снова по номерам")) {
            repo.resetAllScores()
            android.widget.Toast.makeText(this, L.t("Очки сброшены"), android.widget.Toast.LENGTH_SHORT).show()
        }
        val hiddenCount = repo.allHidden().size
        item(col, L.t("Скрытые категории и каналы"), if (hiddenCount > 0) hiddenCount.toString() else L.t("нет"), L.t("все плейлисты; OK — вернуть")) { showHidden() }
        item(col, L.t("Обновлять программу каждые"), L.f("%s ч", Prefs.epgRefreshHours)) {
            Prefs.epgRefreshHours = cycle(listOf(6, 12, 24), Prefs.epgRefreshHours); showMain()
        }
        section(col, L.t("Описания передач (TMDB)"))
        item(col, L.t("Ключ TMDB"), if (Prefs.tmdbKey.isEmpty()) L.t("не задан") else L.t("задан"),
            L.t("баннер и описание передачи по ↓↓ в плеере; без ключа — только из программы")) { showTmdb() }
        section(col, L.t("Блокировка"))
        if (Prefs.pin.isEmpty()) {
            item(col, L.t("PIN-код"), L.t("не задан"), L.t("каналы и категории блокируются в меню канала (удерж. OK)")) { showPin(null) }
        } else {
            item(col, L.t("Сменить PIN"), "") { showPin(Prefs.pin) }
            item(col, L.t("Спрашивать PIN"), if (Prefs.pinEveryTime) L.t("Каждый раз") else L.t("Один раз до выхода")) {
                Prefs.pinEveryTime = !Prefs.pinEveryTime; showMain()
            }
            item(col, L.t("Ввод PIN"), PIN_INPUTS[Prefs.pinInput.coerceIn(0, 2)], L.t("кольцо и ряды не показывают, какая цифра набрана")) {
                Prefs.pinInput = (Prefs.pinInput + 1) % 3; showMain()
            }
            item(col, L.t("Убрать PIN"), "", L.t("блокировки перестанут действовать, отметки сохранятся")) { showPin(Prefs.pin, remove = true) }
        }
        first?.requestFocus()
    }

    // ------------------------------------------------------------------ скрытое

    private fun showHidden() {
        editing = -4
        val col = page(L.t("Скрытые категории и каналы"))
        val list = repo.allHidden()
        if (list.isEmpty()) col.addView(U.text(this, 1.1f, 0.6f).apply { text = L.t("Ничего не скрыто") })
        var first: View? = null
        for (h in list) {
            val v = item(col, (if (h.kind == "group") L.t("Категория: ") else "") + h.title, L.t("вернуть"), h.playlistName) {
                repo.setHidden(h.playlistId, h.kind, h.key, "", false)
                showHidden()
            }
            if (first == null) first = v
        }
        first?.requestFocus()
    }

    // ------------------------------------------------------------------ файл плейлиста

    private var pickTarget: EditText? = null

    /**
     * Выбор файла плейлиста: системный (SAF), а если на устройстве его нет или стоит заглушка
     * (на Android TV — «нет приложения»), — встроенный список .m3u/.m3u8 в памяти и на USB.
     */
    private fun pickFile(target: EditText) {
        pickTarget = target
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        val handlers = packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        if (handlers.isEmpty() || handlers.all { it.contains("stub", ignoreCase = true) }) return pickBuiltIn()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PICK_FILE)
        } catch (_: android.content.ActivityNotFoundException) {
            pickBuiltIn()
        }
    }

    /** Встроенный выбор: плейлисты в корне памяти, «Загрузках» и на съёмных накопителях */
    private fun pickBuiltIn() {
        if (android.os.Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PICK_PERMISSION)
            return
        }
        val back = editing
        editing = -5
        val col = page(L.t("Выбор файла"))
        val roots = ArrayList<java.io.File>()
        @Suppress("DEPRECATION")
        roots.add(android.os.Environment.getExternalStorageDirectory())
        java.io.File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.let { roots.addAll(it) }
        val found = ArrayList<java.io.File>()
        for (r in roots) for (d in listOf(r, java.io.File(r, "Download"), java.io.File(r, "Downloads"))) {
            d.listFiles()?.filter { it.isFile && (it.name.endsWith(".m3u", true) || it.name.endsWith(".m3u8", true)) }?.let { found.addAll(it) }
        }
        if (found.isEmpty()) col.addView(U.text(this, 1.05f, 0.7f, lines = 3).apply {
            text = L.t("Плейлисты (.m3u, .m3u8) не найдены в памяти, «Загрузках» и на USB. Укажите путь вручную.")
        })
        var first: View? = null
        for (f in found.distinctBy { it.absolutePath }) {
            val v = item(col, f.name, "", f.parent ?: "") {
                pickTarget?.setText(f.absolutePath)
                editing = back
                returnToEdit()
            }
            if (first == null) first = v
        }
        first?.requestFocus()
    }

    // форма плейлиста, из которой ушли в выбор файла, — вернуть её (с выбранным путём в поле)
    private var editRoot: View? = null

    private fun returnToEdit() {
        val form = editRoot ?: return showMain()
        frame.removeAllViews()
        frame.addView(form)
        pickTarget?.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || uri == null) return
        // доступ к файлу и после перезапуска (плейлист перечитывается при обновлении)
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        pickTarget?.setText(uri.toString())
    }

    // ------------------------------------------------------------------ TMDB

    private fun showTmdb() {
        editing = -3
        val col = page("TMDB")
        val form = FormView(this, L.t("Ключ TMDB"),
            L.t("Ключ API v3 (32 символа) или токен чтения API v4 — themoviedb.org → Настройки → API. ") +
            L.t("Адреса можно заменить прокси, если TMDB недоступен."))
        val key = form.field(L.t("Ключ или токен"), Prefs.tmdbKey)
        val api = form.field(L.t("Адрес API"), Prefs.tmdbApi, url = true)
        val img = form.field(L.t("Адрес картинок"), Prefs.tmdbImages, url = true)
        val status = U.text(this, 1f, 0.7f, lines = 2)
        val save = {
            Prefs.tmdbKey = key.text.toString()
            Prefs.tmdbApi = api.text.toString().ifBlank { Prefs.TMDB_API }
            Prefs.tmdbImages = img.text.toString().ifBlank { Prefs.TMDB_IMAGES }
        }
        form.button(L.t("Сохранить")) { save(); showMain() }
        form.button(L.t("Проверить")) {
            save()
            status.text = L.t("Проверяю…")
            top.rootu.perfecttv.core.Tmdb.test { err -> status.text = if (err == null) L.t("Работает") else L.f("Не работает: %s", err) }
        }
        form.button(L.t("Убрать ключ")) { Prefs.tmdbKey = ""; showMain() }
        form.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(1f) })
        col.addView(form)
        key.requestFocus()
    }

    // ------------------------------------------------------------------ PIN

    /**
     * Задать, сменить или убрать PIN. [current] — нужно сначала ввести текущий.
     * Поля — системная клавиатура (цифровая), как и остальные формы настроек.
     */
    private fun showPin(current: String?, remove: Boolean = false) {
        editing = -2
        val col = page(if (remove) L.t("Убрать PIN") else if (current == null) L.t("Новый PIN") else L.t("Сменить PIN"))
        val form = FormView(this, L.t("PIN-код"), L.t("От 4 до 6 цифр. Спрашивается при включении заблокированного канала или категории."))
        val old = if (current != null) form.field(L.t("Текущий PIN"), "", pin = true) else null
        val new1 = if (!remove) form.field(L.t("Новый PIN"), "", pin = true) else null
        val new2 = if (!remove) form.field(L.t("Повторите"), "", pin = true) else null
        form.button(if (remove) L.t("Убрать") else L.t("Сохранить")) {
            if (old != null && old.text.toString() != current) { old.error = L.t("Неверный PIN"); old.requestFocus(); return@button }
            if (remove) { Prefs.pin = ""; showMain(); return@button }
            val p = new1!!.text.toString()
            if (!Regex("^\\d{4,6}$").matches(p)) { new1.error = "Нужно от 4 до 6 цифр"; new1.requestFocus(); return@button }
            if (new2!!.text.toString() != p) { new2.error = L.t("Не совпадает"); new2.requestFocus(); return@button }
            Prefs.pin = p
            showMain()
        }
        col.addView(form)
        (old ?: new1)?.requestFocus()
    }

    // ------------------------------------------------------------------ плейлист

    private fun showEdit(p: Playlist?) {
        editing = p?.id ?: -1
        val col = page(if (p == null) L.t("Новый плейлист") else p.name)
        val form = FormView(this, L.t("Плейлист"), L.t("Адрес M3U/M3U8 (http, https) или путь к файлу на устройстве."))
        val name = form.field(L.t("Название"), p?.name ?: L.t("ТВ"))
        val url = form.field(L.t("Адрес плейлиста"), p?.url ?: "", url = true)
        col.addView(form)

        var mode = p?.epgMode ?: Playlist.EPG_PLAYLIST
        val userEpg = form.field(L.t("Свои источники программы (XMLTV, через запятую)"), p?.userEpg?.joinToString(", ") ?: "", url = true)
        lateinit var modeBtn: TextView
        val modeText = { m: Int -> when (m) { Playlist.EPG_BOTH -> L.t("Программа: плейлист + свои"); Playlist.EPG_USER_ONLY -> L.t("Программа: только свои"); else -> L.t("Программа: из плейлиста") } }
        form.button(L.t("Сохранить")) { save(p, name, url, userEpg, mode) }
        modeBtn = form.button(modeText(mode)) {
            mode = (mode + 1) % 3
            modeBtn.text = modeText(mode)
        }
        form.newRow()
        form.button(L.t("Выбрать файл…")) {
            editRoot = frame.getChildAt(0)
            pickFile(url)
        }
        if (p != null) {
            form.button(L.t("Выше")) { repo.movePlaylist(p.id, -1); android.widget.Toast.makeText(this, L.t("Плейлист выше"), android.widget.Toast.LENGTH_SHORT).show() }
            form.button(L.t("Ниже")) { repo.movePlaylist(p.id, 1); android.widget.Toast.makeText(this, L.t("Плейлист ниже"), android.widget.Toast.LENGTH_SHORT).show() }
            form.button(L.t("Обновить")) {
                save(p, name, url, userEpg, mode, back = false)
                repo.playlist(p.id)?.let { fresh -> repo.refresh(fresh, true) { showEdit(repo.playlist(p.id)) } }
            }
            form.button(L.t("Удалить")) {
                repo.deletePlaylist(p.id)
                if (Prefs.lastPlaylist == p.id) Prefs.lastPlaylist = 0
                showMain()
            }
        }
        if (p != null) {
            section(col, L.t("Источники программы"))
            if (p.headerEpg.isEmpty()) col.addView(U.text(this, 1f, 0.6f).apply { text = L.t("В плейлисте источник программы не указан (url-tvg)") })
            for (u in p.headerEpg) epgRow(col, u, L.t("из плейлиста"))
            for (u in p.userEpg) epgRow(col, u, L.t("свой"))
        }
        (if (p == null) url else name).requestFocus()
    }

    private fun epgRow(col: LinearLayout, url: String, kind: String) {
        val id = repo.db.sourceId(url)
        var status = L.t("не загружен")
        repo.db.readableDatabase.rawQuery("SELECT status, error, programmes_count, loaded_at FROM epg_source WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) status = when (it.getInt(0)) {
                Loaders.STATUS_LOADING -> L.t("загружается…")
                Loaders.STATUS_READY -> L.f("%s передач · %s назад", it.getInt(2), Fmt.until(Clock.now() - it.getLong(3)))
                Loaders.STATUS_ERROR -> L.t("ошибка: ") + it.getString(1)
                else -> L.t("не загружен")
            }
        }
        item(col, url, "", "$kind · $status") {}
    }

    private fun save(p: Playlist?, name: EditText, url: EditText, userEpg: EditText, mode: Int, back: Boolean = true) {
        val u = url.text.toString().trim()
        if (u.isEmpty()) {
            url.error = L.t("Укажите адрес")
            url.requestFocus()
            return
        }
        // путь к файлу на устройстве: до Android 13 нужен доступ к памяти
        if (u.startsWith("/") && android.os.Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
        val n = name.text.toString().trim().ifEmpty { L.t("ТВ") }
        val epg = userEpg.text.toString().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val id = if (p == null) repo.addPlaylist(n, u) else p.id
        repo.updatePlaylist(id, n, u, mode, epg)
        // смена адреса — перекачать
        if (p != null && p.url != u) repo.playlist(id)?.let { repo.refresh(it, true) {} }
        if (p == null) {
            Prefs.lastPlaylist = id
            // мастер первого запуска: сразу смотреть
            if (intent.getBooleanExtra(EXTRA_FIRST, false)) return finish()
        }
        if (back) showMain()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PICK_PERMISSION && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) pickBuiltIn()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editing == -5L) return returnToEdit()
        if (editing != null && repo.playlists().isNotEmpty()) showMain()
        else if (repo.playlists().isEmpty()) finishAffinity()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_FIRST = "first"
        const val PICK_FILE = 10
        const val PICK_PERMISSION = 11
        val PIN_INPUTS = listOf(L.t("Кольцо (как Fire TV)"), L.t("Ряды (как Google)"), L.t("Клавиатура"))
    }
}

private fun U.argbGuide() = android.graphics.Color.argb(255, 18, 19, 20)
