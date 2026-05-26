package top.rootu.perfecttv.ui

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
        head(null, "Управление")
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
        val TEXT: List<Pair<String, List<Pair<String, String>>>> = listOf(
            "Плеер" to listOf(
                "← / →" to "Предыдущий / следующий канал категории (в архиве — перемотка)",
                "PG+ / PG−" to "Следующий / предыдущий канал",
                "0–9" to "Канал по номеру; ниже — подсказки для следующей цифры",
                "OK" to "ТВ-гид; при открытой инфопанели — пауза / продолжить",
                "удерж. OK, Меню" to "Меню канала",
                "↓" to "Инфопанель; ещё раз — описание передачи",
                "удерж. ↓" to "Недавние каналы",
                "↑" to "Программа и архив канала (в настройках — недавние каналы); при открытой инфопанели — свернуть описание / закрыть",
                "удерж. ↑" to "Напоминания",
                "⏯ ⏪ ⏩" to "Пауза, перемотка (из эфира — уход в архив)",
                "Гид, Инфо" to "ТВ-гид, инфопанель",
                "Назад ×2" to "Выход из приложения"
            ),
            "ТВ-гид" to listOf(
                "↑ / ↓" to "Канал",
                "← / →" to "Категория",
                "удерж. ←" to "Программа и архив канала",
                "удерж. →" to "Перейти в сетку программы",
                "OK / удерж. OK" to "Смотреть / меню канала",
                "В сетке" to "Стрелки — по передачам, OK — карточка передачи, Назад — к списку каналов",
                "Назад" to "Закрыть гид"
            ),
            "Программа и архив" to listOf(
                "↑ / ↓" to "Передача",
                "→" to "К датам: ↑/↓ — день, ← — обратно в список",
                "←" to "К вкладкам Архив / Напоминания: ↑/↓ — переключить, → — обратно в список",
                "OK" to "Прошедшая — из архива, текущая — эфир, будущая — карточка",
                "удерж. OK" to "Карточка передачи («Смотреть сначала», «Напомнить»)"
            ),
            "Карточка передачи" to listOf(
                "← / →" to "Кнопки",
                "↑ / ↓" to "Прокрутка описания",
                "OK" to "Действие кнопки"
            ),
            "Блокировка" to listOf(
                "Меню канала" to "«Заблокировать канал / категорию» — по PIN (при первой блокировке PIN задаётся)",
                "Окно PIN" to "Цифры пульта или способ из настроек: кольцо — ↑→↓← = 1–4, OK = 0, «Меню»/удерж. OK — 5–9; ряды — ←/OK/→ по ряду, ↑/↓ — ряд, на 7·8·9 ↓ = 0; клавиатура. «Назад» — стереть",
                "PIN" to "4–6 цифр, видны только набранные точки; сменить или убрать — в настройках"
            ),
            "Напоминания" to listOf(
                "«Напомнить»" to "В карточке будущей передачи",
                "Окно напоминания" to "«Смотреть», «Отложить на 5 мин», «Закрыть»; несколько сразу — счётчик «1 / 3», ↑/↓ — другие, «Закрыть все»",
                "Пропустили" to "Передача с архивом остаётся в списке: «Смотреть из архива», «Позже», «Удалить»",
                "Приложение закрыто" to "Системное уведомление; OK по нему — включить передачу"
            )
        )
    }
}
