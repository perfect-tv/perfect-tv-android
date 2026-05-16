package top.rootu.perfecttv.ui

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
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
        val ctx = root.context
        box.removeAllViews()
        btnViews.clear()
        val now = Clock.now()
        val missed = r.missed(now)
        val kicker = when {
            missed -> "Пропущено · в архиве ещё " + Fmt.until(r.start + r.archiveDays * 86400L - now)
            r.start <= now -> "Напоминание · уже идёт"
            else -> "Напоминание · через " + Fmt.until(r.start - now)
        } + if (r.playlistName.isNotEmpty()) " · " + r.playlistName else ""

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(U.pxi(1.5f), U.pxi(1.2f), U.pxi(1.5f), U.pxi(0.8f))
        }
        val logo = ImageView(ctx)
        Logos.into(logo, Channel.stub(r.chName, r.chNum, r.logo))
        head.addView(logo, LinearLayout.LayoutParams(U.pxi(3.4f), U.pxi(2.4f)).apply { rightMargin = U.pxi(1f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(U.text(ctx, 1.4f, bold = true).apply { text = r.chName })
        col.addView(U.text(ctx, 0.95f, 0.6f).apply { text = kicker }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = U.pxi(0.15f) })
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (items.size > 1) {
            // счётчик и подсказка листания
            val cnt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            cnt.addView(U.text(ctx, 1.3f, bold = true).apply { text = "${index + 1} / ${items.size}"; gravity = Gravity.END })
            cnt.addView(U.text(ctx, 0.85f, 0.55f).apply { text = "↑/↓ — другие"; gravity = Gravity.END })
            head.addView(cnt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = U.pxi(1f) })
        }
        box.addView(head)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(U.pxi(1.5f), 0, U.pxi(1.5f), 0) }
        body.addView(U.text(ctx, 1f, 0.7f).apply { text = Fmt.dayTimeRange(r.start, r.stop) })
        body.addView(U.text(ctx, 1.5f, bold = true, lines = 2).apply { text = r.title }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.3f) })
        if (r.desc.isNotEmpty()) body.addView(U.text(ctx, 1.05f, 0.8f, lines = 4).apply { text = r.desc; gravity = Gravity.TOP },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.6f) })
        box.addView(body)

        val list = ArrayList<Pair<String, String>>()
        if (missed) {
            list.add("Смотреть из архива" to "archive")
            list.add("Позже" to "later")
            list.add("Удалить" to "close")
        } else {
            list.add("Смотреть" to "watch")
            list.add("Отложить на 5 мин" to "snooze")
            list.add("Закрыть" to "close")
        }
        if (items.size > 1) list.add("Закрыть все" to "close_all")
        buttons = list
        button = button.coerceIn(0, list.size - 1)
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(U.pxi(1.5f), U.pxi(1.2f), U.pxi(1.5f), U.pxi(1.4f)) }
        list.forEachIndexed { i, b ->
            val v = U.text(ctx, 1.05f).apply {
                text = b.first
                setPadding(U.pxi(1.1f), U.pxi(0.55f), U.pxi(1.1f), U.pxi(0.55f))
                setOnClickListener { button = i; enter() }
            }
            btnViews.add(v)
            bar.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = U.pxi(0.6f) })
        }
        box.addView(bar)
        focus()
    }

    private fun focus() = btnViews.forEachIndexed { i, v ->
        v.background = U.bg(if (i == button) U.WHITE else U.FILL_BTN, U.RADIUS_S)
        v.setTextColor(if (i == button) U.BLACK else U.WHITE)
    }

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
