package top.rootu.perfecttv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Lang
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.Repo
import java.util.concurrent.Executors
import androidx.core.graphics.withClip
import kotlin.math.abs

/**
 * Поиск по каналам и программе (на Canvas, во весь экран). Слева — поле запроса, клавиатура
 * 11 × 5 и под ней запросы (недавние и подсказки); справа — результаты: каналы, сейчас в эфире,
 * скоро, в архиве. Каналы ищутся сразу, передачи — в фоне с задержкой после набора.
 *
 * Зоны фокуса: клавиатура (→ с правого края — результаты, ↓ с нижнего ряда — запросы),
 * запросы (↑ — клавиатура, → — результаты), результаты (← и «Назад» — клавиатура).
 */
class SearchLayer(
    context: Context,
    private val repo: Repo,
    private val channels: () -> List<Channel>,
    private val onChannel: (Channel) -> Unit,
    /** Передача: канал, начало, конец (что делать — решает Tv по времени и архиву) */
    private val onProgramme: (Channel, Long, Long) -> Unit,
    private val onLong: (Channel) -> Unit,
    /** Голосовой ввод (null — распознавания на устройстве нет): язык — тег по раскладке клавиатуры (ru-RU / en-US) */
    private val onVoice: ((String) -> Unit)?,
    private val onBack: () -> Unit
) : Layer {

    private class Key(val label: String, val text: String, val span: Int = 1, val action: Int = TYPE) {
        var col = 0
    }

    /** Строка результатов: заголовок раздела или находка */
    private class Item(val kind: Int, val title: String, val channel: Channel? = null, val start: Long = 0, val stop: Long = 0,
                       val time: String = "", val side: String = "")

    private class Sug(val text: String, val recent: Boolean)

    private enum class Zone { KEYS, QUERIES, RESULTS, MIC }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search") }
    private val query = StringBuilder()
    private var lang = if (Lang.isEn) EN else RU
    private var textLang = lang          // язык букв, к которому возвращает «символы»
    private var rows: List<List<Key>> = emptyList()
    private var kr = 1
    private var kc = 1
    private var zone = Zone.KEYS
    private var items: List<Item> = emptyList()
    private var found: List<Repo.Hit> = emptyList()
    private var searching = false
    private var ri = -1
    private var sugs: List<Sug> = emptyList()
    private var si = 0
    private var gen = 0
    private val list = SearchView(context)
    override val view: View get() = list

    init {
        setLang(lang)
        kr = 1; kc = 0
        update()
    }

    // ================================================================== клавиатура

    private fun letters(l: Int): List<String> = when (l) {
        RU if Prefs.keyboardAbc -> listOf("абвгдеёжзий", "клмнопрстуф", "хцчшщъыьэюя")
        RU -> listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбюъё")
        EN if Prefs.keyboardAbc -> listOf("abcdefghijk", "lmnopqrstuv", "wxyz+',.?*;")
        EN -> listOf("qwertyuiop+", "asdfghjkl';", "zxcvbnm,.?*")
        else -> listOf("@#$%&()<>*\"", "_/\\:;+=?!,.")
    }

    private fun setLang(l: Int) {
        lang = l
        if (l != SYM) textLang = l
        val out = ArrayList<List<Key>>()
        for (r in letters(l)) out.add(r.map { Key(it.toString(), it.toString()) })
        if (l == SYM) out.add(listOf(Key("http://", "http://", 3), Key("https://", "https://", 3), Key(".m3u8", ".m3u8", 2), Key(".xml.gz", ".xml.gz", 3)))
        out.add("1234567890-".map { Key(it.toString(), it.toString()) })
        // микрофон — если на устройстве есть распознавание речи; пробел тогда на клавишу уже
        val mic = onVoice != null && Prefs.voiceButton == 1
        val tail = arrayListOf(Key(str(R.string.key_space), " ", if (mic) 4 else 5))
        if (mic) tail.add(Key("", "", 1, VOICE))
        tail.add(Key("", "", 2, DEL))
        out.add((if (l == SYM) listOf(Key("RU", "", 2, TO_RU), Key("EN", "", 2, TO_EN))
        else listOf(Key("#&", "", 2, TO_SYM), Key(if (l == RU) "EN" else "RU", "", 2, if (l == RU) TO_EN else TO_RU))) + tail)
        for (r in out) { var c = 0; for (k in r) { k.col = c; c += k.span } }
        rows = out
        kr = kr.coerceIn(0, rows.size - 1)
        kc = kc.coerceIn(0, rows[kr].size - 1)
    }

    /** Язык голосового ввода — по раскладке клавиатуры (английские буквы — английский, иначе русский / язык приложения) */
    private fun voice() {
        val tag = when (textLang) { EN -> "en-US"; else -> if (Lang.isEn) "en-US" else "ru-RU" }
        onVoice?.invoke(tag)
    }

    /** Кнопка микрофона слева от строки ввода (настройка «Кнопка голосового ввода») */
    private val micLeft get() = onVoice != null && Prefs.voiceButton == 0

    /** Клавиша ряда [r] над/под серединой текущей */
    private fun keyAt(r: Int, center: Float): Int {
        val row = rows[r]
        for (i in row.indices) if (center >= row[i].col && center < row[i].col + row[i].span) return i
        return row.size - 1
    }

    private fun press(k: Key) {
        when (k.action) {
            TYPE -> if (query.length < MAX_QUERY) { query.append(k.text); changed() }
            DEL -> if (query.isNotEmpty()) { query.setLength(query.length - 1); changed() }
            TO_RU -> setLang(RU)
            TO_EN -> setLang(EN)
            TO_SYM -> setLang(SYM)
            VOICE -> voice()
        }
        list.invalidate()
    }

    // ================================================================== поиск

    private val runSearch = Runnable { searchProgrammes() }

    private fun changed() {
        gen++
        found = emptyList()
        main.removeCallbacks(runSearch)
        searching = words().any { it.length >= 2 }
        if (searching) main.postDelayed(runSearch, DELAY)
        update()
    }

    private fun words() = query.toString().trim().lowercase().split(' ').filter { it.isNotEmpty() }

    private fun searchProgrammes() {
        val g = gen
        val w = words()
        val plan = repo.searchPlan(channels())
        val now = Clock.now()
        worker.execute {
            val hits = try { repo.searchProgrammes(plan, w, now) { g != gen } } catch (_: Exception) { emptyList() }
            main.post {
                if (g != gen) return@post
                found = hits
                searching = false
                update()
            }
        }
    }

    /** Собрать строки результатов и запросы из того, что найдено к этому моменту */
    private fun update() {
        val w = words()
        val now = Clock.now()
        val out = ArrayList<Item>()
        val chans = if (w.isEmpty()) emptyList() else {
            val q = w.joinToString(" ")
            val num = q.toIntOrNull()
            channels().filter { ch -> ch.num == num || w.all { ch.name.contains(it, true) } }
                .sortedBy { if (it.num == num) 0 else if (it.name.startsWith(q, true)) 1 else 2 }.take(MAX_CHANNELS)
        }
        if (chans.isNotEmpty()) {
            out.add(Item(HEAD, str(R.string.found_channels, chans.size)))
            for (ch in chans) out.add(Item(CHANNEL, ch.name, ch, time = ch.num.toString(), side = repo.at(ch, now)?.title ?: ""))
        }
        val live = found.filter { it.start <= now && now < it.stop }.take(MAX_PART)
        val soon = found.filter { it.start > now }.sortedBy { it.start }.take(MAX_PART)
        val arch = found.filter { it.stop <= now }.sortedByDescending { it.start }.take(MAX_PART)
        if (live.isNotEmpty()) {
            out.add(Item(HEAD, str(R.string.found_live, live.size)))
            for (h in live) out.add(Item(LIVE, h.title, h.channel, h.start, h.stop, str(R.string.until_x, Fmt.hhmm(h.stop)), h.channel.name))
        }
        if (soon.isNotEmpty()) {
            out.add(Item(HEAD, str(R.string.found_soon, soon.size)))
            for (h in soon) out.add(Item(SOON, h.title, h.channel, h.start, h.stop, whenText(h.start), h.channel.name))
        }
        if (arch.isNotEmpty()) {
            out.add(Item(HEAD, str(R.string.found_archive, arch.size)))
            for (h in arch) out.add(Item(ARCH, h.title, h.channel, h.start, h.stop, whenText(h.start), h.channel.name))
        }
        items = out
        ri = if (out.isEmpty()) -1 else next(-1, 1)
        if (out.isEmpty() && zone == Zone.RESULTS) zone = Zone.KEYS
        list.resetScroll()

        // запросы: недавние (подходящие к набранному), затем подсказки из найденного
        val q = query.toString().trim()
        val s = ArrayList<Sug>()
        for (r in Prefs.searchRecent) if (s.size < MAX_SUGS && !r.equals(q, true) && (q.isEmpty() || r.contains(q, true))) s.add(Sug(r, true))
        if (q.isNotEmpty()) {
            for (t in chans.map { it.name } + live.map { it.title } + soon.map { it.title } + arch.map { it.title }) {
                if (s.size >= MAX_SUGS) break
                if (!t.equals(q, true) && s.none { it.text.equals(t, true) }) s.add(Sug(t, false))
            }
        }
        sugs = s
        si = si.coerceIn(0, maxOf(0, s.size - 1))
        if (s.isEmpty() && zone == Zone.QUERIES) zone = Zone.KEYS
        list.invalidate()
    }

    /** Подставить запрос целиком (голосовой ввод) */
    fun setQuery(text: String) {
        query.setLength(0)
        query.append(text.trim().take(MAX_QUERY))
        changed()
    }

    private fun whenText(t: Long) = Fmt.dayLabel(t).lowercase() + " " + Fmt.hhmm(t)

    private fun next(from: Int, dir: Int): Int {
        var i = from + dir
        while (i >= 0 && i < items.size) {
            if (items[i].kind != HEAD) return i
            i += dir
        }
        return -1
    }

    private fun remember() {
        val q = query.toString().trim()
        if (q.length < 2) return
        Prefs.searchRecent = (listOf(q) + Prefs.searchRecent.filter { !it.equals(q, true) }).take(MAX_RECENT)
    }

    // ================================================================== клавиши

    override fun key(name: String, long: Boolean, digit: Int) {
        if (name == "digit") {
            if (query.length < MAX_QUERY) { query.append(digit); changed() }
            return
        }
        when (zone) {
            Zone.KEYS -> {
                val k = rows[kr][kc]
                val center = k.col + k.span / 2f
                when (name) {
                    "left" -> if (kc > 0) kc--
                    "right" -> if (kc < rows[kr].size - 1) kc++ else if (ri >= 0) zone = Zone.RESULTS
                    "up" -> if (kr > 0) { kr--; kc = keyAt(kr, center) } else if (micLeft) zone = Zone.MIC
                    "down" -> if (kr < rows.size - 1) { kr++; kc = keyAt(kr, center) } else if (sugs.isNotEmpty()) { zone = Zone.QUERIES; si = 0 }
                }
            }
            Zone.MIC -> when (name) {
                "down" -> zone = Zone.KEYS
                "right" -> zone = Zone.KEYS
            }
            Zone.QUERIES -> when (name) {
                "up" -> if (si > 0) si-- else zone = Zone.KEYS
                "down" -> if (si < sugs.size - 1) si++
                "right" -> if (ri >= 0) zone = Zone.RESULTS
            }
            Zone.RESULTS -> when (name) {
                "up" -> next(ri, -1).takeIf { it >= 0 }?.let { ri = it }
                "down" -> next(ri, 1).takeIf { it >= 0 }?.let { ri = it }
                "pgup" -> repeat(6) { next(ri, -1).takeIf { it >= 0 }?.let { ri = it } }
                "pgdown" -> repeat(6) { next(ri, 1).takeIf { it >= 0 }?.let { ri = it } }
                "left" -> zone = Zone.KEYS
            }
        }
        list.invalidate()
    }

    override fun enter() {
        when (zone) {
            Zone.MIC -> voice()
            Zone.KEYS -> press(rows[kr][kc])
            Zone.QUERIES -> sugs.getOrNull(si)?.let {
                query.setLength(0)
                query.append(it.text.take(MAX_QUERY))
                zone = Zone.KEYS
                changed()
            }
            Zone.RESULTS -> items.getOrNull(ri)?.let { it0 ->
                val ch = it0.channel ?: return
                remember()
                if (it0.kind == CHANNEL) onChannel(ch) else onProgramme(ch, it0.start, it0.stop)
            }
        }
    }

    /** Удержание OK: на ⌫ — очистить поле, на недавнем запросе — забыть его, на находке — программа канала */
    override fun long() {
        when (zone) {
            Zone.MIC -> voice()
            Zone.KEYS -> if (rows[kr][kc].action == DEL && query.isNotEmpty()) { query.setLength(0); changed() } else press(rows[kr][kc])
            Zone.QUERIES -> sugs.getOrNull(si)?.takeIf { it.recent }?.let { s ->
                Prefs.searchRecent = Prefs.searchRecent.filter { it != s.text }
                update()
            }
            Zone.RESULTS -> items.getOrNull(ri)?.channel?.let { onLong(it) }
        }
    }

    override fun back() {
        if (zone == Zone.KEYS) onBack() else { zone = Zone.KEYS; list.invalidate() }
    }

    /** Кнопка поиска / микрофона на пульте в открытом поиске — голосовой ввод */
    override fun media(name: String) {
        if (name == "search" && Prefs.voiceButton != 2) voice()
    }

    override fun destroy() {
        gen++
        main.removeCallbacks(runSearch)
        worker.shutdown()
    }

    // ================================================================== отрисовка

    @SuppressLint("ViewConstructor")
    private inner class SearchView(context: Context) : View(context) {
        private val em = U.em
        private val padX = resources.getDimension(R.dimen.screen_pad)
        private val padY = resources.getDimension(R.dimen.screen_pad_v)
        private val keyW = 3.1f * em
        private val keyH = 3.1f * em
        private val gap = 0.4f * em
        private val leftW = 11 * keyW + 10 * gap
        private val fieldH = 4f * em
        private val rowH = 3.3f * em
        private val headH = 2.7f * em
        private val sugH = 3f * em
        private val hintsH get() = if (Prefs.keyHints) 2.6f * em else 0f

        private val pQuery = U.paint(1.7f)
        private val pPlace = U.paint(1.4f, U.wa(0.45f))
        private val pKey = U.paint(1.45f)
        private val pFn = U.paint(1.25f)
        private val pHead = U.paint(1.15f, U.wa(0.5f), bold = true)
        private val pTitle = U.paint(1.4f)
        private val pTime = U.paint(1.25f, U.wa(0.6f))
        private val pSide = U.paint(1.2f, U.wa(0.5f))
        private val pSug = U.paint(1.35f)
        private val pHint = U.paint(1.15f, U.wa(0.6f))
        private val pHintKey = U.paint(1.1f, U.wa(0.8f))
        private val pEmpty = U.paint(1.35f, U.wa(0.6f))
        private val path = Path()
        private var scroll = 0f
        private var target = 0f

        fun resetScroll() { scroll = 0f; target = 0f }

        private fun h(i: Item) = if (i.kind == HEAD) headH else rowH

        override fun onDraw(c: Canvas) {
            // непрозрачный фон: под поиском может быть гид, его текст просвечивал бы сквозь результаты
            c.drawColor(0xFF0B0C0E.toInt())
            drawField(c)
            drawKeys(c)
            drawQueries(c)
            drawResults(c)
            drawHints(c)
        }

        private fun drawField(c: Canvas) {
            var l = padX
            val t = padY
            var fw = leftW
            if (micLeft) {
                // микрофон слева от строки, квадрат высотой в строку; в фокусе — белый
                val f = zone == Zone.MIC
                U.roundRect(c, l, t, l + fieldH, t + fieldH, U.px(U.RADIUS), if (f) U.WHITE else U.wa(0.12f))
                drawMic(c, l + fieldH / 2, t + fieldH / 2, if (f) U.BLACK else U.WHITE)
                l += fieldH + gap
                fw -= fieldH + gap
            }
            U.roundRect(c, l, t, l + fw, t + fieldH, U.px(U.RADIUS), U.wa(0.12f))
            val cy = t + fieldH / 2
            // лупа
            stroke(U.wa(0.5f), 0.16f * em)
            val ix = l + 1.9f * em
            c.drawCircle(ix - 0.15f * em, cy - 0.15f * em, 0.6f * em, U.fill)
            c.drawLine(ix + 0.3f * em, cy + 0.3f * em, ix + 0.8f * em, cy + 0.8f * em, U.fill)
            U.fill.style = Paint.Style.FILL
            val x = l + 3.6f * em
            val w = fw - 4.8f * em
            if (query.isEmpty()) {
                U.drawText(c, str(R.string.search_placeholder), x, cy, w, pPlace)
                U.fill.color = U.WHITE
                c.drawRect(x - 0.3f * em, cy - 0.9f * em, x - 0.15f * em, cy + 0.9f * em, U.fill)
                return
            }
            // длинный запрос — виден его конец
            val tw = pQuery.measureText(query, 0, query.length)
            c.withClip(x, t, x + w + 0.5f * em, t + fieldH) {
                val tx = if (tw > w) x + w - tw else x
                c.drawText(
                    query,
                    0,
                    query.length,
                    tx,
                    cy - (pQuery.descent() + pQuery.ascent()) / 2,
                    pQuery
                )
                U.fill.color = U.WHITE
                c.drawRect(
                    tx + tw + 0.15f * em,
                    cy - 0.9f * em,
                    tx + tw + 0.3f * em,
                    cy + 0.9f * em,
                    U.fill
                )
            }
        }

        private fun stroke(color: Int, w: Float) {
            U.fill.style = Paint.Style.STROKE
            U.fill.strokeWidth = w
            U.fill.color = color
        }

        private val keysTop get() = padY + fieldH + 1.2f * em

        private fun drawKeys(c: Canvas) {
            var y = keysTop
            for (r in rows.indices) {
                for (i in rows[r].indices) {
                    val k = rows[r][i]
                    val x = padX + k.col * (keyW + gap)
                    val w = k.span * keyW + (k.span - 1) * gap
                    val focus = zone == Zone.KEYS && r == kr && i == kc
                    // место фокуса видно и когда он ушёл в запросы или результаты — рамкой
                    U.roundRect(c, x, y, x + w, y + keyH, U.px(U.RADIUS_S), if (focus) U.WHITE else if (k.action != TYPE || k.span > 1) U.wa(0.17f) else U.wa(0.1f))
                    if (!focus && zone != Zone.KEYS && r == kr && i == kc) {
                        stroke(U.wa(0.8f), 0.15f * em)
                        c.drawRoundRect(x + 0.07f * em, y + 0.07f * em, x + w - 0.07f * em, y + keyH - 0.07f * em, U.px(U.RADIUS_S), U.px(U.RADIUS_S), U.fill)
                        U.fill.style = Paint.Style.FILL
                    }
                    val fg = if (focus) U.BLACK else U.WHITE
                    val cy = y + keyH / 2
                    when (k.action) {
                        DEL -> drawDel(c, x + w / 2, cy, fg)
                        VOICE -> drawMic(c, x + w / 2, cy, fg)
                        else -> {
                            val p = if (k.label.length > 1) pFn else pKey
                            p.color = fg
                            c.drawText(
                                k.label,
                                x + (w - p.measureText(k.label)) / 2,
                                cy - (p.descent() + p.ascent()) / 2,
                                p
                            )
                        }
                    }
                }
                y += keyH + gap
            }
        }

        /** Значок «стереть»: стрелка-ярлык с крестиком */
        private fun drawDel(c: Canvas, cx: Float, cy: Float, color: Int) {
            val w = 1.1f * em
            val hh = 0.7f * em
            path.rewind()
            path.moveTo(cx - w, cy)
            path.lineTo(cx - w * 0.4f, cy - hh)
            path.lineTo(cx + w, cy - hh)
            path.lineTo(cx + w, cy + hh)
            path.lineTo(cx - w * 0.4f, cy + hh)
            path.close()
            stroke(color, 0.15f * em)
            U.fill.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, U.fill)
            val d = 0.3f * em
            val xx = cx + w * 0.3f
            c.drawLine(xx - d, cy - d, xx + d, cy + d, U.fill)
            c.drawLine(xx - d, cy + d, xx + d, cy - d, U.fill)
            U.fill.style = Paint.Style.FILL
        }

        private val micRect = android.graphics.RectF()

        /** Значок микрофона: капсула, дуга под ней и ножка */
        private fun drawMic(c: Canvas, cx: Float, cy: Float, color: Int) {
            U.fill.style = Paint.Style.FILL
            U.fill.color = color
            c.drawRoundRect(cx - 0.3f * em, cy - 0.95f * em, cx + 0.3f * em, cy + 0.2f * em, 0.3f * em, 0.3f * em, U.fill)
            stroke(color, 0.15f * em)
            micRect.set(cx - 0.6f * em, cy - 0.7f * em, cx + 0.6f * em, cy + 0.5f * em)
            c.drawArc(micRect, 0f, 180f, false, U.fill)
            c.drawLine(cx, cy + 0.5f * em, cx, cy + 0.95f * em, U.fill)
            U.fill.style = Paint.Style.FILL
        }

        private fun drawQueries(c: Canvas) {
            if (sugs.isEmpty()) return
            var y = keysTop + rows.size * (keyH + gap) + 0.6f * em
            U.drawText(c, str(R.string.queries).uppercase(), padX + 0.9f * em, y + headH / 2, leftW, pHead)
            y += headH
            val bottom = height - padY - hintsH
            for (i in sugs.indices) {
                if (y + sugH > bottom) break
                val s = sugs[i]
                val focus = zone == Zone.QUERIES && i == si
                if (focus) U.roundRect(c, padX, y, padX + leftW, y + sugH, U.px(U.RADIUS_S), U.WHITE)
                val fg = if (focus) U.BLACK else U.WHITE
                val cy = y + sugH / 2
                val ic = if (focus) U.BLACK else U.wa(0.5f)
                if (s.recent) Icons.draw(c, Icons.Kind.ARCHIVE, padX + 0.8f * em, cy - 0.75f * em, 1.5f * em, ic)
                else {
                    stroke(ic, 0.15f * em)
                    c.drawCircle(padX + 1.45f * em, cy - 0.1f * em, 0.45f * em, U.fill)
                    c.drawLine(padX + 1.8f * em, cy + 0.25f * em, padX + 2.15f * em, cy + 0.6f * em, U.fill)
                    U.fill.style = Paint.Style.FILL
                }
                pSug.color = fg
                U.drawText(c, s.text, padX + 3.1f * em, cy, leftW - 4f * em, pSug)
                y += sugH
            }
        }

        private fun drawResults(c: Canvas) {
            val l = padX + leftW + 2.4f * em
            val r = width - padX
            val top = padY
            val bottom = height - padY - hintsH
            val vh = bottom - top
            if (items.isEmpty()) {
                val msg = when {
                    query.isBlank() -> str(R.string.search_start_typing)
                    searching -> str(R.string.searching)
                    else -> str(R.string.nothing_found)
                }
                U.drawText(c, msg, l + 0.9f * em, top + fieldH / 2, r - l, pEmpty)
                return
            }
            // строка в фокусе — к середине, у краёв — без пустоты
            var total = 0f
            var fy = 0f
            var fh = 0f
            for (i in items.indices) {
                if (i == ri) { fy = total; fh = h(items[i]) }
                total += h(items[i])
            }
            target = if (zone == Zone.RESULTS) (fy - vh / 2 + fh / 2).coerceIn(0f, maxOf(0f, total - vh)) else 0f
            if (Anim.on && abs(target - scroll) > 0.5f) {
                scroll += (target - scroll) * 0.35f
                postInvalidateOnAnimation()
            } else scroll = target
            val save = Fade.begin(c, l, top, r, bottom)
            var y = top - scroll
            for (i in items.indices) {
                val it = items[i]
                val ih = h(it)
                if (y + ih >= top && y <= bottom) drawItem(c, it, i, l, r, y, ih)
                y += ih
                if (y > bottom) break
            }
            Fade.end(c, save, l, top, r, bottom, scroll / Fade.length, (total - vh - scroll) / Fade.length)
        }

        private fun drawItem(c: Canvas, it: Item, i: Int, l: Float, r: Float, y: Float, ih: Float) {
            val cy = y + ih / 2
            if (it.kind == HEAD) {
                U.drawText(c, it.title.uppercase(), l + 0.9f * em, y + ih - 1.1f * em, r - l, pHead)
                return
            }
            val focus = zone == Zone.RESULTS && i == ri
            if (focus) U.roundRect(c, l, y, r, y + ih, U.px(U.RADIUS_S), U.WHITE)
            val fg = if (focus) U.BLACK else U.WHITE
            val dim = if (focus) U.BLACK else U.wa(0.6f)
            var x = l + 0.8f * em
            val ch = it.channel!!
            val sideW = minOf(pSide.measureText(it.side), (r - l) * 0.32f)
            pSide.color = if (focus) U.BLACK else U.wa(0.5f)
            if (it.kind == CHANNEL) {
                Logos.draw(c, context, ch, x, y + 0.35f * em, x + 4.2f * em, y + ih - 0.35f * em, plate = focus, badge = false) { invalidate() }
                x += 5f * em
                pTime.color = dim
                c.drawText(it.time, x, cy - (pTime.descent() + pTime.ascent()) / 2, pTime)
                x += maxOf(2.8f * em, pTime.measureText(it.time) + 0.8f * em)
            } else {
                val isz = 1.5f * em
                when (it.kind) {
                    LIVE -> { U.fill.color = if (focus) U.BLACK else U.LIVE; c.drawCircle(x + isz / 2, cy, 0.42f * em, U.fill) }
                    SOON -> Icons.draw(c, Icons.Kind.REMIND, x, cy - isz / 2, isz, dim)
                    else -> Icons.draw(c, Icons.Kind.ARCHIVE, x, cy - isz / 2, isz, if (focus) U.BLACK else U.ARCHIVE)
                }
                x += isz + 0.7f * em
                pTime.color = dim
                c.drawText(it.time, x, cy - (pTime.descent() + pTime.ascent()) / 2, pTime)
                x += if (it.kind == LIVE) 6.2f * em else 9.6f * em
            }
            pTitle.color = fg
            U.drawText(c, it.title, x, cy, r - x - sideW - 1.8f * em, pTitle)
            U.drawText(c, it.side, r - 0.8f * em - sideW, cy, sideW, pSide)
        }

        // подсказки: список пересобирается при смене зоны (и языка — строки не кэшируются надолго)
        private var hintZone: Zone? = null
        private var hintList: List<Pair<String, String>> = emptyList()

        private fun drawHints(c: Canvas) {
            if (hintsH == 0f) return
            if (hintZone != zone) {
                hintZone = zone
                val ok = "OK"
                hintList = when (zone) {
                    Zone.KEYS -> listOf(ok to str(R.string.hint_type), "▶︎" to str(R.string.hint_results), "▼" to str(R.string.queries),
                        str(R.string.key_hold_ok) to str(R.string.hint_clear_field))
                    Zone.MIC -> listOf(ok to str(R.string.hint_voice), "▼" to str(R.string.hint_keyboard))
                    Zone.QUERIES -> listOf(ok to str(R.string.hint_use_query), str(R.string.key_hold_ok) to str(R.string.hint_forget_query), "▲" to str(R.string.hint_keyboard))
                    Zone.RESULTS -> listOf(ok to str(R.string.watch), str(R.string.key_hold_ok) to str(R.string.hint_programme), "◀︎" to str(R.string.hint_keyboard))
                }
            }
            val cy = height - padY - 0.8f * em
            var x = padX
            for ((key, action) in hintList) {
                val kw = maxOf(2f * em, Keycap.width(key, pHintKey) + em)
                stroke(U.wa(0.4f), 0.1f * em)
                c.drawRoundRect(x, cy - 0.85f * em, x + kw, cy + 0.85f * em, 0.4f * em, 0.4f * em, U.fill)
                U.fill.style = Paint.Style.FILL
                Keycap.draw(c, key, x + (kw - Keycap.width(key, pHintKey)) / 2, cy, pHintKey)
                x += kw + 0.6f * em
                c.drawText(action, x, cy - (pHint.descent() + pHint.ascent()) / 2, pHint)
                x += pHint.measureText(action) + 1.8f * em
            }
        }
    }

    companion object {
        private const val RU = 0
        private const val EN = 1
        private const val SYM = 2
        private const val TYPE = 0
        private const val DEL = 1
        private const val TO_RU = 2
        private const val TO_EN = 3
        private const val TO_SYM = 4
        private const val VOICE = 5
        private const val HEAD = 0
        private const val CHANNEL = 1
        private const val LIVE = 2
        private const val SOON = 3
        private const val ARCH = 4
        private const val MAX_QUERY = 40
        private const val MAX_CHANNELS = 12
        private const val MAX_PART = 30
        private const val MAX_SUGS = 5
        private const val MAX_RECENT = 8
        private const val DELAY = 300L
    }
}
