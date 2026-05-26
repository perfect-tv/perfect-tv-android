package top.rootu.perfecttv.ui

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
        val col = page("Настройки")
        section(col, "Плейлисты")
        var first: View? = null
        for (p in repo.playlists()) {
            val status = when {
                p.error.isNotEmpty() -> "ошибка: " + p.error
                p.channelsCount > 0 -> "${p.channelsCount} каналов"
                else -> "не загружен"
            }
            val v = item(col, p.name, if (p.id == Prefs.lastPlaylist) "●" else "", p.url + " · " + status) { showEdit(p) }
            if (first == null) first = v
        }
        val add = item(col, "+ Добавить плейлист", "") { showEdit(null) }
        if (first == null) first = add
        section(col, "Просмотр")
        item(col, "Глубина архива по умолчанию", "${Prefs.archiveDays} дн.", "если в плейлисте не указана") {
            Prefs.archiveDays = cycle(listOf(1, 2, 3, 4, 5, 7, 10, 14), Prefs.archiveDays); showMain()
        }
        item(col, "Клавиша ↑ в плеере", if (Prefs.upArchive) "Программа и архив" else "Недавние каналы") {
            Prefs.upArchive = !Prefs.upArchive; showMain()
        }
        item(col, "Напоминать о передаче", if (Prefs.remindBefore == 0) "Когда начнётся" else "За ${Prefs.remindBefore} мин") {
            Prefs.remindBefore = cycle(listOf(0, 1, 2, 3, 4, 5, 10), Prefs.remindBefore); showMain()
        }
        item(col, "Скрывать инфопанель через", "${Prefs.infoHide} с") {
            Prefs.infoHide = cycle(listOf(3, 5, 7, 10), Prefs.infoHide); showMain()
        }
        item(col, "Плавные анимации", if (Prefs.animations) "Вкл." else "Выкл.", "выключить на слабых приставках") {
            Prefs.animations = !Prefs.animations; Anim.on = Prefs.animations; showMain()
        }
        item(col, "Адаптивная сортировка", if (Prefs.adaptiveSort) "Вкл." else "Выкл.", "часто смотримые каналы — выше") {
            Prefs.adaptiveSort = !Prefs.adaptiveSort; showMain()
        }
        item(col, "Обновлять программу каждые", "${Prefs.epgRefreshHours} ч") {
            Prefs.epgRefreshHours = cycle(listOf(6, 12, 24), Prefs.epgRefreshHours); showMain()
        }
        section(col, "Блокировка")
        if (Prefs.pin.isEmpty()) {
            item(col, "PIN-код", "не задан", "каналы и категории блокируются в меню канала (удерж. OK)") { showPin(null) }
        } else {
            item(col, "Сменить PIN", "") { showPin(Prefs.pin) }
            item(col, "Спрашивать PIN", if (Prefs.pinEveryTime) "Каждый раз" else "Один раз до выхода") {
                Prefs.pinEveryTime = !Prefs.pinEveryTime; showMain()
            }
            item(col, "Ввод PIN", PIN_INPUTS[Prefs.pinInput.coerceIn(0, 2)], "кольцо и ряды не показывают, какая цифра набрана") {
                Prefs.pinInput = (Prefs.pinInput + 1) % 3; showMain()
            }
            item(col, "Убрать PIN", "", "блокировки перестанут действовать, отметки сохранятся") { showPin(Prefs.pin, remove = true) }
        }
        first?.requestFocus()
    }

    // ------------------------------------------------------------------ PIN

    /**
     * Задать, сменить или убрать PIN. [current] — нужно сначала ввести текущий.
     * Поля — системная клавиатура (цифровая), как и остальные формы настроек.
     */
    private fun showPin(current: String?, remove: Boolean = false) {
        editing = -2
        val col = page(if (remove) "Убрать PIN" else if (current == null) "Новый PIN" else "Сменить PIN")
        val form = FormView(this, "PIN-код", "От 4 до 6 цифр. Спрашивается при включении заблокированного канала или категории.")
        val old = if (current != null) form.field("Текущий PIN", "", pin = true) else null
        val new1 = if (!remove) form.field("Новый PIN", "", pin = true) else null
        val new2 = if (!remove) form.field("Повторите", "", pin = true) else null
        form.button(if (remove) "Убрать" else "Сохранить") {
            if (old != null && old.text.toString() != current) { old.error = "Неверный PIN"; old.requestFocus(); return@button }
            if (remove) { Prefs.pin = ""; showMain(); return@button }
            val p = new1!!.text.toString()
            if (!Regex("^\\d{4,6}$").matches(p)) { new1.error = "Нужно от 4 до 6 цифр"; new1.requestFocus(); return@button }
            if (new2!!.text.toString() != p) { new2.error = "Не совпадает"; new2.requestFocus(); return@button }
            Prefs.pin = p
            showMain()
        }
        col.addView(form)
        (old ?: new1)?.requestFocus()
    }

    // ------------------------------------------------------------------ плейлист

    private fun showEdit(p: Playlist?) {
        editing = p?.id ?: -1
        val col = page(if (p == null) "Новый плейлист" else p.name)
        val form = FormView(this, "Плейлист", "Адрес M3U/M3U8 (http, https) или путь к файлу на устройстве.")
        val name = form.field("Название", p?.name ?: "ТВ")
        val url = form.field("Адрес плейлиста", p?.url ?: "", url = true)
        col.addView(form)

        var mode = p?.epgMode ?: Playlist.EPG_PLAYLIST
        val userEpg = form.field("Свои источники программы (XMLTV, через запятую)", p?.userEpg?.joinToString(", ") ?: "", url = true)
        lateinit var modeBtn: TextView
        val modeText = { m: Int -> when (m) { Playlist.EPG_BOTH -> "Программа: плейлист + свои"; Playlist.EPG_USER_ONLY -> "Программа: только свои"; else -> "Программа: из плейлиста" } }
        form.button("Сохранить") { save(p, name, url, userEpg, mode) }
        modeBtn = form.button(modeText(mode)) {
            mode = (mode + 1) % 3
            modeBtn.text = modeText(mode)
        }
        if (p != null) {
            form.button("Обновить") {
                save(p, name, url, userEpg, mode, back = false)
                repo.playlist(p.id)?.let { fresh -> repo.refresh(fresh, true) { showEdit(repo.playlist(p.id)) } }
            }
            form.button("Удалить") {
                repo.deletePlaylist(p.id)
                if (Prefs.lastPlaylist == p.id) Prefs.lastPlaylist = 0
                showMain()
            }
        }
        if (p != null) {
            section(col, "Источники программы")
            if (p.headerEpg.isEmpty()) col.addView(U.text(this, 1f, 0.6f).apply { text = "В плейлисте источник программы не указан (url-tvg)" })
            for (u in p.headerEpg) epgRow(col, u, "из плейлиста")
            for (u in p.userEpg) epgRow(col, u, "свой")
        }
        (if (p == null) url else name).requestFocus()
    }

    private fun epgRow(col: LinearLayout, url: String, kind: String) {
        val id = repo.db.sourceId(url)
        var status = "не загружен"
        repo.db.readableDatabase.rawQuery("SELECT status, error, programmes_count, loaded_at FROM epg_source WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) status = when (it.getInt(0)) {
                Loaders.STATUS_LOADING -> "загружается…"
                Loaders.STATUS_READY -> "${it.getInt(2)} передач · " + Fmt.until(Clock.now() - it.getLong(3)) + " назад"
                Loaders.STATUS_ERROR -> "ошибка: " + it.getString(1)
                else -> "не загружен"
            }
        }
        item(col, url, "", "$kind · $status") {}
    }

    private fun save(p: Playlist?, name: EditText, url: EditText, userEpg: EditText, mode: Int, back: Boolean = true) {
        val u = url.text.toString().trim()
        if (u.isEmpty()) {
            url.error = "Укажите адрес"
            url.requestFocus()
            return
        }
        val n = name.text.toString().trim().ifEmpty { "ТВ" }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editing != null && repo.playlists().isNotEmpty()) showMain()
        else if (repo.playlists().isEmpty()) finishAffinity()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_FIRST = "first"
        val PIN_INPUTS = listOf("Кольцо (как Fire TV)", "Ряды (как Google)", "Клавиатура")
    }
}

private fun U.argbGuide() = android.graphics.Color.argb(255, 18, 19, 20)
