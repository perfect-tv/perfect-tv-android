package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Окно «Управление» (ui/help.js плагина): кнопки по разделам. ↑/↓ — прокрутка,
 * PG± — на страницу, OK/«Назад» — закрыть. Текст держать в согласии с раскладкой
 * (Tv, GuideLayer, ScheduleLayer) и ../tv_plugin/docs/controls.md.
 */
class HelpLayer(context: Context, private val onClose: () -> Unit) : PopupLayer(context, 56f, 0.86f) {

    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; Fade.scroll(this) }

    init {
        head(null, str(R.string.controls))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(U.pxi(1.5f), 0, U.pxi(1.5f), U.pxi(1.2f))
        }
        for ((title, rows) in TEXT) {
            col.addView(U.text(context, 1.15f, bold = true).apply { text = title }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.9f); bottomMargin = U.pxi(0.3f) })
            for ((key, action) in rows) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                row.addView(U.text(context, 1f, 0.95f).apply {
                    text = key
                    background = U.bg(U.FILL_BTN, U.RADIUS_S)
                    setPadding(U.pxi(0.5f), U.pxi(0.2f), U.pxi(0.5f), U.pxi(0.2f))
                }, LinearLayout.LayoutParams(-2, -2))
                val keyCol = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                keyCol.addView(row)
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                line.addView(keyCol, LinearLayout.LayoutParams(U.pxi(12f), -2))
                line.addView(U.text(context, 1f, 0.8f, lines = 0).apply { text = action; gravity = Gravity.TOP }, LinearLayout.LayoutParams(0, -2, 1f))
                col.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.35f) })
            }
        }
        scroll.addView(col)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun key(name: String, long: Boolean, digit: Int) {
        val page = scroll.height - U.pxi(3f)
        when (name) {
            "up" -> scroll.smoothScrollBy(0, -U.pxi(3f))
            "down" -> scroll.smoothScrollBy(0, U.pxi(3f))
            "pgup" -> scroll.smoothScrollBy(0, -page)
            "pgdown" -> scroll.smoothScrollBy(0, page)
        }
    }

    override fun enter() = onClose()
    override fun back() = onClose()

    companion object {
        // функция, а не поле: строки — на текущем языке
        val TEXT: List<Pair<String, List<Pair<String, String>>>> get() = listOf(
            str(R.string.player) to listOf(
                "← / →" to str(R.string.previous_next_channel_of_the),
                "PG+ / PG−" to str(R.string.next_previous_channel),
                "0–9" to str(R.string.channel_by_number_below_hints_for),
                "OK" to str(R.string.tv_guide_with_the_info_panel_open),
                str(R.string.hold_ok_menu) to str(R.string.channel_menu),
                "↓" to str(R.string.info_panel_again_programme),
                str(R.string.hold_down) to str(R.string.recent_channels),
                "↑" to str(R.string.channel_programme_and_archive_in),
                str(R.string.hold_up) to str(R.string.reminders),
                "⏯ ⏪ ⏩" to str(R.string.pause_seek_from_live_into_the),
                str(R.string.guide_info) to str(R.string.tv_guide_info_panel),
                str(R.string.back_twice) to str(R.string.exit_the_app)
            ),
            str(R.string.tv_guide) to listOf(
                "↑ / ↓" to str(R.string.channel),
                "← / →" to str(R.string.category),
                str(R.string.hold_left) to str(R.string.channel_programme_and_archive),
                str(R.string.hold_right) to str(R.string.go_to_the_programme_grid),
                str(R.string.ok_hold_ok) to str(R.string.watch_channel_menu),
                str(R.string.in_the_grid) to str(R.string.arrows_programmes_ok_programme_card),
                str(R.string.back) to str(R.string.close_the_guide)
            ),
            str(R.string.programme_and_archive) to listOf(
                "↑ / ↓" to str(R.string.programme),
                "→" to str(R.string.to_dates_up_down_day_left_back_to),
                "←" to str(R.string.to_archive_reminders_tabs_up_down),
                "OK" to str(R.string.past_from_archive_current_live),
                str(R.string.hold_ok) to str(R.string.programme_card_watch_from_start)
            ),
            str(R.string.programme_card) to listOf(
                "← / →" to str(R.string.buttons),
                "↑ / ↓" to str(R.string.scroll_description),
                "OK" to str(R.string.button_action)
            ),
            str(R.string.parental_lock) to listOf(
                str(R.string.channel_menu) to str(R.string.lock_channel_category_by_pin_the_pin),
                str(R.string.pin_window) to str(R.string.remote_digits_or_the_method_from),
                "PIN" to str(R.string.n4_6_digits_only_entered_dots_are)
            ),
            str(R.string.reminders) to listOf(
                str(R.string.remind) to str(R.string.in_the_card_of_an_upcoming_programme),
                str(R.string.reminder_window) to str(R.string.watch_snooze_5_min_close_several_at),
                str(R.string.missed) to str(R.string.a_programme_with_an_archive_stays_in),
                str(R.string.app_closed) to str(R.string.system_notification_ok_on_it_play)
            )
        )
    }
}
