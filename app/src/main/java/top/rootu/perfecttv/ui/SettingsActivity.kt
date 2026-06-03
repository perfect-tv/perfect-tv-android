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

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        U.init(this)
        setContentView(R.layout.activity_settings)
        frame = findViewById(R.id.frame)
        if (intent.getBooleanExtra(EXTRA_FIRST, false) && repo.playlists().isEmpty()) showEdit(null) else showMain()
        Bus.on(onData)
    }

    override fun onDestroy() {
        Bus.off(onData)
        super.onDestroy()
    }

    /** Новая страница (settings_page.xml) с заголовком; вернёт список для пунктов */
    private fun page(title: String): LinearLayout {
        frame.removeAllViews()
        val scroll = layoutInflater.inflate(R.layout.settings_page, frame, false)
        scroll.findViewById<TextView>(R.id.settings_title).text = title
        frame.addView(scroll)
        return scroll.findViewById(R.id.settings_list)
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView((layoutInflater.inflate(R.layout.settings_section, col, false) as TextView).apply { text = title })
    }

    /** Пояснение на странице (settings_note.xml) */
    private fun note(col: LinearLayout, text: String) {
        col.addView((layoutInflater.inflate(R.layout.settings_note, col, false) as TextView).apply { this.text = text })
    }

    /** Пункт (settings_item.xml): название, значение справа, подпись снизу; OK — [onClick] */
    private fun item(col: LinearLayout, title: String, value: String, sub: String = "", onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.settings_item, col, false)
        row.findViewById<TextView>(R.id.item_title).text = title
        row.findViewById<TextView>(R.id.item_value).text = value
        row.findViewById<TextView>(R.id.item_sub).apply { text = sub; U.show(this, sub.isNotEmpty()) }
        row.setOnClickListener { onClick() }
        row.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_UP && Keys.isOk(code)) { onClick(); true } else false }
        col.addView(row)
        return row
    }

    private fun <T> cycle(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1) % list.size]

    // ------------------------------------------------------------------ главная

    private fun showMain() {
        editing = null
        val col = page(str(R.string.settings))
        section(col, str(R.string.playlists))
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
        if (first == null) first = add
        section(col, str(R.string.viewing))
        val lang = item(col, "Язык / Language", when (Prefs.language) { "ru" -> "Русский"; "en" -> "English"; else -> getString(R.string.system) }) {
            Prefs.language = cycle(listOf("", "ru", "en"), Prefs.language)
            Lang.init(applicationContext)
            // экран пересоздаётся на новом языке — фокус вернуть на «Язык»
            intent.putExtra(EXTRA_FOCUS_LANG, true)
            recreate()
        }
        if (intent.getBooleanExtra(EXTRA_FOCUS_LANG, false)) {
            intent.removeExtra(EXTRA_FOCUS_LANG)
            first = lang
        }
        item(col, str(R.string.default_archive_depth), str(R.string.x_d, Prefs.archiveDays), str(R.string.if_the_playlist_does_not_set_it)) {
            Prefs.archiveDays = cycle(listOf(1, 2, 3, 4, 5, 7, 10, 14), Prefs.archiveDays); showMain()
        }
        item(col, str(R.string.up_key_in_the_player), if (Prefs.upArchive) str(R.string.programme_and_archive) else str(R.string.recent_channels)) {
            Prefs.upArchive = !Prefs.upArchive; showMain()
        }
        item(col, str(R.string.remind_about_a_programme), if (Prefs.remindBefore == 0) str(R.string.when_it_starts) else str(R.string.x_min_before, Prefs.remindBefore)) {
            Prefs.remindBefore = cycle(listOf(0, 1, 2, 3, 4, 5, 10), Prefs.remindBefore); showMain()
        }
        item(col, str(R.string.channel_number), listOf(str(R.string.hidden), str(R.string.next_to_the_name), str(R.string.badge_on_the_logo))[Prefs.numberMode.coerceIn(0, 2)], str(R.string.without_a_logo_the_number_is_always)) {
            Prefs.numberMode = (Prefs.numberMode + 1) % 3; showMain()
        }
        item(col, str(R.string.hide_info_panel_after), str(R.string.x_s, Prefs.infoHide)) {
            Prefs.infoHide = cycle(listOf(3, 5, 7, 10), Prefs.infoHide); showMain()
        }
        item(col, str(R.string.smooth_animations), if (Prefs.animations) str(R.string.on) else str(R.string.off), str(R.string.turn_off_on_weak_boxes)) {
            Prefs.animations = !Prefs.animations; Anim.on = Prefs.animations; showMain()
        }
        item(col, str(R.string.start_with_the_last_channel), if (Prefs.autoplayLast) str(R.string.on) else str(R.string.off), str(R.string.off_the_guide_opens_on_start)) {
            Prefs.autoplayLast = !Prefs.autoplayLast; showMain()
        }
        item(col, str(R.string.adaptive_sorting), if (Prefs.adaptiveSort) str(R.string.on) else str(R.string.off), str(R.string.frequently_watched_channels_go_up)) {
            Prefs.adaptiveSort = !Prefs.adaptiveSort; showMain()
        }
        item(col, str(R.string.reset_sorting_scores), "", str(R.string.all_playlists_order_by_numbers_again)) {
            repo.resetAllScores()
            android.widget.Toast.makeText(this, str(R.string.scores_reset), android.widget.Toast.LENGTH_SHORT).show()
        }
        val hiddenCount = repo.allHidden().size
        item(col, str(R.string.hidden_categories_and_channels), if (hiddenCount > 0) hiddenCount.toString() else str(R.string.none), str(R.string.all_playlists_ok_restore)) { showHidden() }
        item(col, str(R.string.reload_programme_every), str(R.string.x_h, Prefs.epgRefreshHours)) {
            Prefs.epgRefreshHours = cycle(listOf(6, 12, 24), Prefs.epgRefreshHours); showMain()
        }
        section(col, str(R.string.programme_descriptions_tmdb))
        item(col, str(R.string.tmdb_key), if (Prefs.tmdbKey.isEmpty()) str(R.string.not_set) else str(R.string.set),
            str(R.string.programme_banner_and_description_on)) { showTmdb() }
        section(col, str(R.string.parental_lock))
        if (Prefs.pin.isEmpty()) {
            item(col, str(R.string.pin_code), str(R.string.not_set), str(R.string.channels_and_categories_are_locked)) { showPin(null) }
        } else {
            item(col, str(R.string.change_pin), "") { showPin(Prefs.pin) }
            item(col, str(R.string.ask_for_pin), if (Prefs.pinEveryTime) str(R.string.every_time) else str(R.string.once_until_exit)) {
                Prefs.pinEveryTime = !Prefs.pinEveryTime; showMain()
            }
            item(col, str(R.string.pin_input), PIN_INPUTS[Prefs.pinInput.coerceIn(0, 2)], str(R.string.ring_and_rows_do_not_show_which)) {
                Prefs.pinInput = (Prefs.pinInput + 1) % 3; showMain()
            }
            item(col, str(R.string.remove_pin), "", str(R.string.locks_stop_working_marks_are_kept)) { showPin(Prefs.pin, remove = true) }
        }
        first?.requestFocus()
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
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_FIRST = "first"
        private const val EXTRA_FOCUS_LANG = "focus_lang"
        const val PICK_FILE = 10
        const val PICK_PERMISSION = 11
        val PIN_INPUTS get() = listOf(str(R.string.ring_like_fire_tv), str(R.string.rows_like_google), str(R.string.keypad))
    }
}
