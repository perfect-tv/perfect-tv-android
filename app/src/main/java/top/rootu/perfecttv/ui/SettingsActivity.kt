package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.core.Lang
import top.rootu.perfecttv.core.plural
import android.app.Activity
import android.os.Bundle
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
 * Настройки в два столбца: слева — разделы, справа — пункты раздела (ТЗ §11).
 * Обычные View с фокусом Android. В разделах: ↑/↓ — раздел (страница справа меняется сразу),
 * → / OK — к пунктам. В пунктах: ↑/↓ — пункт, ← / → — предыдущее / следующее значение
 * (у пункта без значений ← — к разделам), OK — следующее значение или действие,
 * «Назад» — к разделам, из разделов — выйти. Формы (плейлист, PIN, TMDB) открываются справа,
 * разделы на это время недоступны.
 * С EXTRA_FIRST — мастер первого плейлиста: сразу форма добавления.
 */
class SettingsActivity : Activity() {

    private val repo get() = App.repo
    private lateinit var frame: FrameLayout
    private var editing: Long? = null
    private val onData: (DataEvent) -> Unit = { if (editing == null) showSection(false) }
    private lateinit var nav: LinearLayout
    private var section = 0

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        U.init(this)
        setContentView(R.layout.activity_settings)
        frame = findViewById(R.id.frame)
        nav = findViewById(R.id.settings_nav)
        section = intent.getIntExtra(EXTRA_SECTION, 0)
        buildNav()
        buildHints()
        if (intent.getBooleanExtra(EXTRA_FIRST, false) && repo.playlists().isEmpty()) showEdit(null) else showMain()
        Bus.on(onData)
    }

    override fun onDestroy() {
        Bus.off(onData)
        super.onDestroy()
    }

    // та же страница перестраивается (изменили настройку, восстановили скрытое) — фокус и прокрутка
    // остаются на месте: номер пункта в списке и прокрутка старой страницы
    private var keepIndex = -1
    private var keepScroll = 0

    // ------------------------------------------------------------------ разделы

    private fun sectionTitles() = listOf(str(R.string.playlists), str(R.string.viewing), str(R.string.appearance), str(R.string.channel_list),
        str(R.string.parental_lock), "TMDB", str(R.string.data_and_backup))

    private fun buildNav() {
        nav.removeAllViews()
        sectionTitles().forEachIndexed { i, title ->
            val v = layoutInflater.inflate(R.layout.settings_nav_item, nav, false) as TextView
            v.text = title
            // фокус на разделе — его страница справа сразу
            v.setOnFocusChangeListener { _, has -> if (has && !building && (section != i || editing != null)) { section = i; showSection(false) } }
            v.setOnClickListener { section = i; showSection(true) }
            v.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { showSection(true); true } else false
            }
            nav.addView(v)
        }
    }

    /** Разделы доступны только на страницах разделов; в форме — приглушены и без фокуса */
    private fun setNav(on: Boolean) {
        for (i in 0 until nav.childCount) nav.getChildAt(i).apply {
            isFocusable = on
            isActivated = on && i == section
            alpha = if (on || i == section) 1f else 0.4f
        }
    }

    private fun focusNav() {
        nav.getChildAt(section)?.takeIf { it.isFocusable }?.requestFocus()
    }

    private fun buildHints() {
        val box = findViewById<LinearLayout>(R.id.settings_hints)
        if (!Prefs.keyHints || U.touch) return
        box.visibility = View.VISIBLE
        for ((k, action) in listOf("←  →" to str(R.string.hint_change), "OK" to str(R.string.hint_select), str(R.string.key_back) to str(R.string.hint_sections))) {
            val v = layoutInflater.inflate(R.layout.hint_item, box, false)
            v.findViewById<TextView>(R.id.hint_key).text = k
            v.findViewById<TextView>(R.id.hint_action).text = action
            box.addView(v)
        }
    }

    /** Новая страница (settings_page.xml) с заголовком; вернёт список для пунктов */
    private fun page(title: String, form: Boolean = true): LinearLayout {
        // форма: разделы недоступны (снимать фокусируемость с раздела в фокусе нельзя — фокус уйдёт)
        if (form) setNav(false)
        keepIndex = -1
        keepScroll = 0
        (frame.getChildAt(0) as? ScrollView)?.let { old ->
            if (old.findViewById<TextView>(R.id.settings_title)?.text?.toString() != title) return@let
            val list = old.findViewById<LinearLayout>(R.id.settings_list) ?: return@let
            var v: View? = list.findFocus()
            while (v != null && v.parent !== list) v = v.parent as? View
            if (v != null) keepIndex = list.indexOfChild(v)
            keepScroll = old.scrollY
        }
        frame.removeAllViews()
        val scroll = layoutInflater.inflate(R.layout.settings_page, frame, false)
        scroll.findViewById<TextView>(R.id.settings_title).text = title
        frame.addView(scroll)
        return scroll.findViewById(R.id.settings_list)
    }

    /** Фокус: на тот же пункт, что до перестройки страницы (или соседний выше), иначе — на [first] */
    private fun focus(col: LinearLayout, first: View?) {
        var i = minOf(keepIndex, col.childCount - 1)
        while (i >= 0 && !col.getChildAt(i).isFocusable) i--
        val v = if (keepIndex >= 0 && i >= 0) col.getChildAt(i) else null
        if (v == null) {
            first?.requestFocus()
            return
        }
        val scroll = col.parent as ScrollView
        val y = keepScroll
        scroll.post { scroll.scrollTo(0, y) }
        v.requestFocus()
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView((layoutInflater.inflate(R.layout.settings_section, col, false) as TextView).apply { text = title })
    }

    /** Пояснение на странице (settings_note.xml) */
    private fun note(col: LinearLayout, text: String) {
        col.addView((layoutInflater.inflate(R.layout.settings_note, col, false) as TextView).apply { this.text = text })
    }

    /**
     * Пункт (settings_item.xml): название, значение справа, подпись снизу; OK — [onClick].
     * ← / → — [onSide] (−1 / +1): смена значения на месте; без него ← уводит к разделам.
     */
    private fun item(col: LinearLayout, title: String, value: String, sub: String = "", onSide: ((Int) -> Unit)? = null, onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.settings_item, col, false)
        row.findViewById<TextView>(R.id.item_title).text = title
        row.findViewById<TextView>(R.id.item_value).text = value
        row.findViewById<TextView>(R.id.item_sub).apply { text = sub; U.show(this, sub.isNotEmpty()) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, code, e ->
            when {
                e.action == KeyEvent.ACTION_UP && Keys.isOk(code) -> { onClick(); true }
                e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT -> { if (onSide != null) onSide(-1) else focusNav(); true }
                e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT -> { onSide?.invoke(1); true }
                else -> false
            }
        }
        col.addView(row)
        return row
    }

    /** Пункт с выбором из [labels]: «‹ значение ›», ← / → и OK меняют на месте (по кругу) */
    private fun choice(col: LinearLayout, title: String, labels: List<String>, index: Int, sub: String = "", onPick: (Int) -> Unit): View {
        val n = labels.size
        val i = index.coerceIn(0, n - 1)
        return item(col, title, "‹  " + labels[i] + "  ›", sub, onSide = { d -> onPick((i + d + n) % n) }) { onPick((i + 1) % n) }
    }

    private fun toggle(col: LinearLayout, title: String, value: Boolean, sub: String = "", onPick: (Boolean) -> Unit) =
        choice(col, title, listOf(str(R.string.off), str(R.string.on)), if (value) 1 else 0, sub) { onPick(it == 1) }

    /** Выбор из чисел: [label] — подпись значения */
    private fun number(col: LinearLayout, title: String, values: List<Int>, cur: Int, sub: String = "", label: (Int) -> String, onPick: (Int) -> Unit) =
        choice(col, title, values.map(label), values.indexOf(cur).coerceAtLeast(0), sub) { onPick(values[it]) }

    /** Экран пересоздаётся (язык, размер интерфейса) — вернуться в тот же раздел на тот же пункт */
    private fun recreateHere() {
        val list = frame.findViewById<LinearLayout>(R.id.settings_list)
        var v: View? = list?.findFocus()
        while (v != null && v.parent !== list) v = v.parent as? View
        intent.putExtra(EXTRA_SECTION, section)
        intent.putExtra(EXTRA_FOCUS_INDEX, if (v != null) list.indexOfChild(v) else -1)
        Lang.init(applicationContext)
        recreate()
    }

    // ------------------------------------------------------------------ главная

    /** Вернуться к странице раздела с фокусом в пунктах (из формы, после действия) */
    private fun showMain() = showSection(true)

    /**
     * Страница текущего раздела. [takeFocus] — поставить фокус в пункты; иначе он остаётся
     * в разделах (листаем разделы, пришло обновление данных), а если был в пунктах — на том же.
     */
    private fun showSection(takeFocus: Boolean) {
        // старая страница убирается — фокус на миг перескакивает на раздел: это не выбор раздела
        building = true
        try {
            buildSection(takeFocus)
        } finally {
            building = false
        }
    }

    private var building = false

    private fun buildSection(takeFocus: Boolean) {
        editing = null
        val col = page(sectionTitles()[section], form = false)
        setNav(true)
        val first = when (section) {
            0 -> sectionPlaylists(col)
            1 -> sectionViewing(col)
            2 -> sectionAppearance(col)
            3 -> sectionChannels(col)
            4 -> sectionLock(col)
            5 -> sectionTmdb(col)
            else -> sectionData(col)
        }
        // после пересоздания экрана (язык, размер) — на тот же пункт
        val back = intent.getIntExtra(EXTRA_FOCUS_INDEX, -1)
        if (back >= 0) {
            intent.removeExtra(EXTRA_FOCUS_INDEX)
            keepIndex = back
        }
        if (takeFocus || keepIndex >= 0) focus(col, first) else focusNav()
    }

    private fun sectionPlaylists(col: LinearLayout): View? {
        var first: View? = null
        for (p in repo.playlists()) {
            val status = when {
                p.error.isNotEmpty() -> str(R.string.error_2) + p.error
                p.channelsCount > 0 -> plural(R.plurals.channels, p.channelsCount, p.channelsCount)
                else -> str(R.string.not_loaded)
            }
            val v = item(col, p.name, if (p.id == Prefs.lastPlaylist) "●" else "", p.url + " · " + status) { showEdit(p) }
            if (first == null) first = v
        }
        val add = item(col, str(R.string.plus_add_playlist), "") { showEdit(null) }
        return first ?: add
    }

    private fun sectionViewing(col: LinearLayout): View? {
        val first = number(col, str(R.string.default_archive_depth), listOf(1, 2, 3, 4, 5, 7, 10, 14), Prefs.archiveDays,
            str(R.string.if_the_playlist_does_not_set_it), { str(R.string.x_d, it) }) { Prefs.archiveDays = it; showMain() }
        choice(col, str(R.string.up_key_in_the_player), listOf(str(R.string.recent_channels), str(R.string.programme_and_archive)), if (Prefs.upArchive) 1 else 0) {
            Prefs.upArchive = it == 1; showMain()
        }
        number(col, str(R.string.remind_about_a_programme), listOf(0, 1, 2, 3, 4, 5, 10), Prefs.remindBefore,
            label = { if (it == 0) str(R.string.when_it_starts) else str(R.string.x_min_before, it) }) { Prefs.remindBefore = it; showMain() }
        toggle(col, str(R.string.start_with_the_last_channel), Prefs.autoplayLast, str(R.string.off_the_guide_opens_on_start)) { Prefs.autoplayLast = it; showMain() }
        number(col, str(R.string.reload_programme_every), listOf(6, 12, 24), Prefs.epgRefreshHours, label = { str(R.string.x_h, it) }) { Prefs.epgRefreshHours = it; showMain() }
        return first
    }

    private fun sectionAppearance(col: LinearLayout): View? {
        val langs = listOf("", "ru", "en")
        val first = choice(col, "Язык / Language", listOf(getString(R.string.system), "Русский", "English"), langs.indexOf(Prefs.language)) {
            Prefs.language = langs[it]
            recreateHere()
        }
        choice(col, str(R.string.interface_size), listOf(str(R.string.size_small), str(R.string.size_normal), str(R.string.size_large)), Prefs.uiScale, str(R.string.larger_for_a_big_room)) {
            Prefs.uiScale = it
            recreateHere()
        }
        choice(col, str(R.string.channel_number), listOf(str(R.string.hidden), str(R.string.next_to_the_name), str(R.string.badge_on_the_logo)), Prefs.numberMode,
            str(R.string.without_a_logo_the_number_is_always)) { Prefs.numberMode = it; showMain() }
        choice(col, str(R.string.guide_view), listOf(str(R.string.list), str(R.string.tv_guide)), if (Prefs.guideGrid) 1 else 0) { Prefs.guideGrid = it == 1; showMain() }
        number(col, str(R.string.hide_info_panel_after), listOf(3, 5, 7, 10), Prefs.infoHide, label = { str(R.string.x_s, it) }) { Prefs.infoHide = it; showMain() }
        choice(col, str(R.string.keyboard_layout), listOf(str(R.string.layout_standard), str(R.string.layout_abc)), if (Prefs.keyboardAbc) 1 else 0) { Prefs.keyboardAbc = it == 1; showMain() }
        toggle(col, str(R.string.key_hints), Prefs.keyHints, str(R.string.a_row_at_the_bottom_of_the_pinned)) { Prefs.keyHints = it; showMain() }
        toggle(col, str(R.string.smooth_animations), Prefs.animations, str(R.string.turn_off_on_weak_boxes)) { Prefs.animations = it; Anim.on = it; showMain() }
        return first
    }

    private fun sectionChannels(col: LinearLayout): View? {
        val first = toggle(col, str(R.string.adaptive_sorting), Prefs.adaptiveSort, str(R.string.frequently_watched_channels_go_up)) { Prefs.adaptiveSort = it; showMain() }
        item(col, str(R.string.reset_sorting_scores), "", str(R.string.all_playlists_order_by_numbers_again)) {
            repo.resetAllScores()
            android.widget.Toast.makeText(this, str(R.string.scores_reset), android.widget.Toast.LENGTH_SHORT).show()
        }
        val hiddenCount = repo.allHidden().size
        item(col, str(R.string.hidden_categories_and_channels), if (hiddenCount > 0) hiddenCount.toString() else str(R.string.none), str(R.string.all_playlists_ok_restore)) { showHidden() }
        return first
    }

    private fun sectionLock(col: LinearLayout): View? {
        if (Prefs.pin.isEmpty()) return item(col, str(R.string.pin_code), str(R.string.not_set), str(R.string.channels_and_categories_are_locked)) { showPin(null) }
        val first = item(col, str(R.string.change_pin), "") { showPin(Prefs.pin) }
        choice(col, str(R.string.ask_for_pin), listOf(str(R.string.once_until_exit), str(R.string.every_time)), if (Prefs.pinEveryTime) 1 else 0) { Prefs.pinEveryTime = it == 1; showMain() }
        choice(col, str(R.string.pin_input), PIN_INPUTS, Prefs.pinInput, str(R.string.ring_and_rows_do_not_show_which)) { Prefs.pinInput = it; showMain() }
        item(col, str(R.string.remove_pin), "", str(R.string.locks_stop_working_marks_are_kept)) { showPin(Prefs.pin, remove = true) }
        return first
    }

    private fun sectionTmdb(col: LinearLayout): View? =
        item(col, str(R.string.tmdb_key), if (Prefs.tmdbKey.isEmpty()) str(R.string.not_set) else str(R.string.set),
            str(R.string.programme_banner_and_description_on)) { showTmdb() }

    private fun sectionData(col: LinearLayout): View? {
        val first = item(col, str(R.string.backup_save), "", str(R.string.backup_save_sub)) { backupSave() }
        item(col, str(R.string.backup_restore), "", str(R.string.backup_restore_sub)) { backupOpen() }
        item(col, str(R.string.logo_cache), Fmt.size(Logos.cacheSize()), str(R.string.logo_cache_sub)) {
            Logos.clearCache()
            android.widget.Toast.makeText(this, str(R.string.logo_cache_cleared), android.widget.Toast.LENGTH_SHORT).show()
            showMain()
        }
        return first
    }

    // ------------------------------------------------------------------ резервная копия

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    /** Есть ли на устройстве настоящий системный выбор файла (на Android TV часто заглушка) */
    private fun hasPicker(i: android.content.Intent): Boolean {
        @Suppress("DEPRECATION")
        val handlers = packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        return handlers.isNotEmpty() && !handlers.all { it.contains("stub", ignoreCase = true) }
    }

    private val backupFile get() = java.io.File(getExternalFilesDir(null) ?: filesDir, top.rootu.perfecttv.data.Backup.FILE)

    /** Сохранить копию: системным выбором места, а без него — в папку приложения */
    private fun backupSave() {
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, top.rootu.perfecttv.data.Backup.FILE)
        }
        if (hasPicker(i)) try {
            @Suppress("DEPRECATION")
            return startActivityForResult(i, BACKUP_SAVE)
        } catch (_: android.content.ActivityNotFoundException) {
        }
        try {
            backupFile.writeText(top.rootu.perfecttv.data.Backup.export(repo.db.readableDatabase))
            toast(str(R.string.backup_saved_x, backupFile.absolutePath))
        } catch (e: Exception) {
            toast(str(R.string.backup_failed_x, e.message ?: e.toString()))
        }
    }

    private fun backupOpen() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        if (hasPicker(i)) try {
            @Suppress("DEPRECATION")
            return startActivityForResult(i, BACKUP_OPEN)
        } catch (_: android.content.ActivityNotFoundException) {
        }
        if (!backupFile.exists()) return toast(str(R.string.backup_not_found_x, backupFile.absolutePath))
        backupRestore(backupFile.readText())
    }

    /** Заменить данные копией и перезапустить приложение: экран ТВ и каталог строятся заново */
    private fun backupRestore(json: String) {
        try {
            top.rootu.perfecttv.data.Backup.restore(repo.db.writableDatabase, json)
        } catch (e: Exception) {
            return toast(str(R.string.backup_failed_x, e.message ?: e.toString()))
        }
        toast(str(R.string.backup_restored))
        val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName) ?: packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------ скрытое

    private fun showHidden() {
        editing = -4
        val col = page(str(R.string.hidden_categories_and_channels))
        val list = repo.allHidden()
        if (list.isEmpty()) note(col, str(R.string.nothing_is_hidden))
        var first: View? = null
        for (h in list) {
            val v = item(col, (if (h.kind == "group") str(R.string.category_2) else "") + h.title, str(R.string.restore), h.playlistName) {
                repo.setHidden(h.playlistId, h.kind, h.key, "", false)
                showHidden()
            }
            if (first == null) first = v
        }
        focus(col, first)
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
        if (!hasPicker(i)) return pickBuiltIn()
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
        val col = page(str(R.string.choose_file))
        val roots = ArrayList<java.io.File>()
        @Suppress("DEPRECATION")
        roots.add(android.os.Environment.getExternalStorageDirectory())
        java.io.File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.let { roots.addAll(it) }
        val found = ArrayList<java.io.File>()
        for (r in roots) for (d in listOf(r, java.io.File(r, "Download"), java.io.File(r, "Downloads"))) {
            d.listFiles()?.filter { it.isFile && (it.name.endsWith(".m3u", true) || it.name.endsWith(".m3u8", true)) }?.let { found.addAll(it) }
        }
        if (found.isEmpty()) note(col, str(R.string.no_playlists_m3u_m3u8_found_in))
        var first: View? = null
        for (f in found.distinctBy { it.absolutePath }) {
            val v = item(col, f.name, "", f.parent ?: "") {
                pickTarget?.setText(f.absolutePath)
                editing = back
                returnToEdit()
            }
            if (first == null) first = v
        }
        focus(col, first)
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
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == BACKUP_SAVE) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(top.rootu.perfecttv.data.Backup.export(repo.db.readableDatabase).toByteArray()) }
                toast(str(R.string.backup_saved_x, uri.lastPathSegment ?: ""))
            } catch (e: Exception) {
                toast(str(R.string.backup_failed_x, e.message ?: e.toString()))
            }
            return
        }
        if (requestCode == BACKUP_OPEN) {
            val json = try { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
            return if (json != null) backupRestore(json) else toast(str(R.string.backup_failed_x, uri.toString()))
        }
        if (requestCode != PICK_FILE) return
        // доступ к файлу и после перезапуска (плейлист перечитывается при обновлении)
        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        pickTarget?.setText(uri.toString())
    }

    // ------------------------------------------------------------------ TMDB

    private fun showTmdb() {
        editing = -3
        val col = page("TMDB")
        val form = FormView.create(col, str(R.string.tmdb_key),
            str(R.string.api_v3_key_32_characters_or_api_v4) +
            str(R.string.addresses_can_be_replaced_with_a))
        val key = form.field(str(R.string.key_or_token), Prefs.tmdbKey)
        val api = form.field(str(R.string.api_address), Prefs.tmdbApi, url = true)
        val img = form.field(str(R.string.images_address), Prefs.tmdbImages, url = true)
        val save = {
            Prefs.tmdbKey = key.text.toString()
            Prefs.tmdbApi = api.text.toString().ifBlank { Prefs.TMDB_API }
            Prefs.tmdbImages = img.text.toString().ifBlank { Prefs.TMDB_IMAGES }
        }
        lateinit var status: TextView
        form.button(str(R.string.save)) { save(); showMain() }
        form.button(str(R.string.check)) {
            save()
            status.text = str(R.string.checking)
            top.rootu.perfecttv.core.Tmdb.test { err -> status.text = if (err == null) str(R.string.works) else str(R.string.not_working_x, err) }
        }
        form.button(str(R.string.remove_key)) { Prefs.tmdbKey = ""; showMain() }
        status = form.note()
        key.requestFocus()
    }

    // ------------------------------------------------------------------ PIN

    /**
     * Задать, сменить или убрать PIN. [current] — нужно сначала ввести текущий.
     * Поля — системная клавиатура (цифровая), как и остальные формы настроек.
     */
    private fun showPin(current: String?, remove: Boolean = false) {
        editing = -2
        val col = page(if (remove) str(R.string.remove_pin) else if (current == null) str(R.string.new_pin) else str(R.string.change_pin))
        val form = FormView.create(col, str(R.string.pin_code), str(R.string.n4_to_6_digits_asked_when_a_locked))
        val old = if (current != null) form.field(str(R.string.current_pin), "", pin = true) else null
        val new1 = if (!remove) form.field(str(R.string.new_pin), "", pin = true) else null
        val new2 = if (!remove) form.field(str(R.string.repeat), "", pin = true) else null
        form.button(if (remove) str(R.string.remove) else str(R.string.save)) {
            if (old != null && old.text.toString() != current) { old.error = str(R.string.wrong_pin); old.requestFocus(); return@button }
            if (remove) { Prefs.pin = ""; showMain(); return@button }
            val p = new1!!.text.toString()
            if (!Regex("^\\d{4,6}$").matches(p)) { new1.error = str(R.string.x_to_x_digits_required, 4, 6); new1.requestFocus(); return@button }
            if (new2!!.text.toString() != p) { new2.error = str(R.string.does_not_match); new2.requestFocus(); return@button }
            Prefs.pin = p
            showMain()
        }
        (old ?: new1)?.requestFocus()
    }

    // ------------------------------------------------------------------ плейлист

    private fun showEdit(p: Playlist?) {
        editing = p?.id ?: -1
        val col = page(if (p == null) str(R.string.new_playlist) else p.name)
        val form = FormView.create(col, str(R.string.playlist), str(R.string.m3u_m3u8_address_http_https_or_a))
        val name = form.field(str(R.string.name), p?.name ?: str(R.string.tv))
        val url = form.field(str(R.string.playlist_address), p?.url ?: "", url = true)

        var mode = p?.epgMode ?: Playlist.EPG_PLAYLIST
        val userEpg = form.field(str(R.string.own_programme_sources_xmltv_comma), p?.userEpg?.joinToString(", ") ?: "", url = true)
        lateinit var modeBtn: TextView
        val modeText = { m: Int -> when (m) { Playlist.EPG_BOTH -> str(R.string.programme_playlist_plus_own); Playlist.EPG_USER_ONLY -> str(R.string.programme_own_only); else -> str(R.string.programme_from_playlist) } }
        form.button(str(R.string.save)) { save(p, name, url, userEpg, mode) }
        modeBtn = form.button(modeText(mode)) {
            mode = (mode + 1) % 3
            modeBtn.text = modeText(mode)
            // у готового плейлиста режим (и свои источники из поля выше) сохраняется сразу:
            // переключатель выглядит как настройка, «Сохранить» после него не ждут
            if (p != null) {
                val epg = userEpg.text.toString().split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                repo.playlist(p.id)?.let { cur -> repo.updatePlaylist(p.id, cur.name, cur.url, mode, epg) }
                repo.playlist(p.id)?.let { repo.refreshEpg(it, false) }
                android.widget.Toast.makeText(this, str(R.string.saved), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        form.newRow()
        form.button(str(R.string.choose_file_2)) {
            editRoot = frame.getChildAt(0)
            pickFile(url)
        }
        if (p != null) {
            form.button(str(R.string.up)) { repo.movePlaylist(p.id, -1); android.widget.Toast.makeText(this, str(R.string.playlist_moved_up), android.widget.Toast.LENGTH_SHORT).show() }
            form.button(str(R.string.down)) { repo.movePlaylist(p.id, 1); android.widget.Toast.makeText(this, str(R.string.playlist_moved_down), android.widget.Toast.LENGTH_SHORT).show() }
            form.button(str(R.string.reload)) {
                save(p, name, url, userEpg, mode, back = false)
                repo.playlist(p.id)?.let { fresh -> repo.refresh(fresh, true) { showEdit(repo.playlist(p.id)) } }
            }
            form.button(str(R.string.delete)) {
                repo.deletePlaylist(p.id)
                if (Prefs.lastPlaylist == p.id) Prefs.lastPlaylist = 0
                showMain()
            }
        }
        if (p != null) {
            section(col, str(R.string.programme_sources))
            if (p.headerEpg.isEmpty()) note(col, str(R.string.the_playlist_has_no_programme_source))
            for (u in p.headerEpg) epgRow(col, u, str(R.string.from_playlist))
            for (u in p.userEpg) epgRow(col, u, str(R.string.own))
        }
        (if (p == null) url else name).requestFocus()
    }

    private fun epgRow(col: LinearLayout, url: String, kind: String) {
        val id = repo.db.sourceId(url)
        var status = str(R.string.not_loaded)
        repo.db.readableDatabase.rawQuery("SELECT status, error, programmes_count, loaded_at FROM epg_source WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) status = when (it.getInt(0)) {
                Loaders.STATUS_LOADING -> str(R.string.loading)
                Loaders.STATUS_READY -> plural(R.plurals.programmes_ago, it.getInt(2), it.getInt(2), Fmt.until(Clock.now() - it.getLong(3)))
                Loaders.STATUS_ERROR -> str(R.string.error_2) + it.getString(1)
                else -> str(R.string.not_loaded)
            }
        }
        item(col, url, "", "$kind · $status") {}
    }

    private fun save(p: Playlist?, name: EditText, url: EditText, userEpg: EditText, mode: Int, back: Boolean = true) {
        val u = url.text.toString().trim()
        if (u.isEmpty()) {
            url.error = str(R.string.enter_the_address)
            url.requestFocus()
            return
        }
        // путь к файлу на устройстве: до Android 13 нужен доступ к памяти
        if (u.startsWith("/") && android.os.Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
        val n = name.text.toString().trim().ifEmpty { str(R.string.tv) }
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
        // из пунктов — к разделам, из разделов — выйти
        else if (currentFocus != null && currentFocus?.parent !== nav && nav.getChildAt(section)?.isFocusable == true) focusNav()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_FIRST = "first"
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_FOCUS_INDEX = "focus_index"
        const val BACKUP_SAVE = 12
        const val BACKUP_OPEN = 13
        const val PICK_FILE = 10
        const val PICK_PERMISSION = 11
        val PIN_INPUTS get() = listOf(str(R.string.ring_like_fire_tv), str(R.string.rows_like_google), str(R.string.keypad))
    }
}
