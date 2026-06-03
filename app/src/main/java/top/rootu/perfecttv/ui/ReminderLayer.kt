package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.content.Context
import android.view.View
import android.widget.TextView
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.Reminders

/**
 * Окно напоминаний: одно на все, что пора показать. Сверху — счётчик «1 / 3»,
 * ↑/↓ листают напоминания, ←/→ — кнопки, OK — действие. После действия показывается
 * следующее, пока список не кончится. Новые напоминания добавляются в это же окно.
 *
 * Порядок: идущие и скоро начинающиеся (по началу), затем пропущенные.
 * Кнопки: идущая/скорая — «Смотреть», «Отложить на 5 мин», «Закрыть»;
 * пропущенная (есть в архиве) — «Смотреть из архива», «Позже» (оставить в списке), «Удалить».
 * При нескольких — ещё «Закрыть все». «Назад» — «Закрыть» / «Позже» у текущего.
 */
class ReminderLayer(
    context: Context,
    private val onAction: (Reminders.Reminder, String) -> Unit,
    private val onEmpty: () -> Unit
) : PopupLayer(context, 46f) {

    private val items = ArrayList<Reminders.Reminder>()
    private var index = 0
    private var button = 0
    private var buttons: List<Pair<String, String>> = emptyList()
    private val btnViews = ArrayList<TextView>()
    // пользователь уже листал — новые напоминания не сбивают текущее
    private var touched = false

    val count get() = items.size

    fun has(id: String) = items.any { it.id == id }

    /** Добавить в очередь (окно уже открыто — счётчик растёт, текущее не меняется) */
    fun add(r: Reminders.Reminder) {
        if (has(r.id)) return
        val cur = items.getOrNull(index)
        items.add(r)
        val now = Clock.now()
        items.sortWith(compareBy<Reminders.Reminder> { it.missed(now) }.thenBy { it.start })
        index = if (touched) cur?.let { items.indexOf(it) } ?: 0 else 0
        render()
    }

    /** Убрать (действие выполнено или напоминание удалили в другом месте) */
    fun drop(id: String) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items.removeAt(i)
        if (index >= items.size) index = maxOf(0, items.size - 1)
        if (items.isEmpty()) return onEmpty()
        button = 0
        render()
    }

    private fun render() {
        val r = items.getOrNull(index) ?: return
        box.removeAllViews()
        btnViews.clear()
        val now = Clock.now()
        val missed = r.missed(now)
        val kicker = when {
            missed -> str(R.string.missed_in_archive_for) + Fmt.until(r.start + r.archiveDays * 86400L - now)
            r.start <= now -> str(R.string.reminder_on_air)
            else -> str(R.string.reminder_in) + Fmt.until(r.start - now)
        } + if (r.playlistName.isNotEmpty()) " · " + r.playlistName else ""

        head(Channel.stub(r.chName, r.chNum, r.logo), r.chName, kicker)
        if (items.size > 1) {
            // счётчик и подсказка листания
            box.findViewById<View>(R.id.head_counter).visibility = View.VISIBLE
            box.findViewById<TextView>(R.id.head_counter_text).text = str(R.string.counter, index + 1, items.size)
        }
        val body = add<View>(R.layout.reminder_body)
        body.findViewById<TextView>(R.id.reminder_time).text = Fmt.dayTimeRange(r.start, r.stop)
        body.findViewById<TextView>(R.id.reminder_title).text = r.title
        body.findViewById<TextView>(R.id.reminder_desc).apply { text = r.desc; U.show(this, r.desc.isNotEmpty()) }

        val list = ArrayList<Pair<String, String>>()
        if (missed) {
            list.add(str(R.string.watch_from_archive) to "archive")
            list.add(str(R.string.later) to "later")
            list.add(str(R.string.delete) to "close")
        } else {
            list.add(str(R.string.watch) to "watch")
            list.add(str(R.string.snooze_5_min) to "snooze")
            list.add(str(R.string.close) to "close")
        }
        if (items.size > 1) list.add(str(R.string.close_all) to "close_all")
        buttons = list
        button = button.coerceIn(0, list.size - 1)
        btnViews.addAll(buttons(list.map { it.first }) { button = it; enter() })
        focus()
    }

    private fun focus() = select(btnViews, button)
    override fun key(name: String, long: Boolean, digit: Int) {
        touched = true
        when (name) {
            "left" -> if (button > 0) { button--; focus() }
            "right" -> if (button < btnViews.size - 1) { button++; focus() }
            "up" -> if (index > 0) { index--; button = 0; render() }
            "down" -> if (index < items.size - 1) { index++; button = 0; render() }
        }
    }

    override fun enter() {
        val r = items.getOrNull(index) ?: return
        val a = buttons.getOrNull(button)?.second ?: return
        if (a == "close_all") {
            for (it in items.toList()) onAction(it, if (it.missed(Clock.now())) "later" else "close")
            items.clear()
            return onEmpty()
        }
        // «Смотреть» закрывает окно целиком: остальные покажутся снова при следующей проверке
        if (a == "watch" || a == "archive") {
            for (it in items) if (it !== r) onAction(it, "release")
            items.clear()
            onAction(r, a)
            return onEmpty()
        }
        onAction(r, a)
        drop(r.id)
    }

    /** «Назад» — как «Закрыть» у идущей и «Позже» у пропущенной */
    override fun back() {
        val r = items.getOrNull(index) ?: return onEmpty()
        onAction(r, if (r.missed(Clock.now())) "later" else "close")
        drop(r.id)
    }
}
