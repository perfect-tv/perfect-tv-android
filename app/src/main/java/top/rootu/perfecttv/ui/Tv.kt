package top.rootu.perfecttv.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import top.rootu.perfecttv.App
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Catalog
import top.rootu.perfecttv.data.Channel
import top.rootu.perfecttv.data.DataEvent
import top.rootu.perfecttv.data.Bus
import top.rootu.perfecttv.data.Playlist
import top.rootu.perfecttv.data.Reminders
import top.rootu.perfecttv.core.Alarms
import top.rootu.perfecttv.player.CatchupUrl
import top.rootu.perfecttv.player.Playback

/**
 * Оркестратор полноэкранного ТВ (порт app.js плагина): состояние воспроизведения,
 * стек слоёв, клавиши плеера, переключение каналов, архив и перемотка, пауза эфира.
 *
 * Время — unix-секунды [Clock.now]. Позиция в архиве:
 *   position = archive.start + (periodPosition − archive.base) / 1000,
 * где base — позиция плеера, соответствующая началу ссылки (см. §8).
 */
class Tv(val activity: TvActivity, private val root: FrameLayout, val playback: Playback) {

    val repo = App.repo
    private val main = Handler(Looper.getMainLooper())
    var catalog: Catalog? = null
        private set
    var playlist: Playlist? = null
        private set

    class Archive(val start: Long, var base: Long? = null, val timeshift: Boolean = false) {
        var continued = false
    }

    class State {
        var channel: Channel? = null
        var groupKey: String? = null
        var archive: Archive? = null
        var height = 0
        var error = false
        val isArchive get() = archive != null
    }

    val state = State()
    private val layers = ArrayList<Layer>()
    private val overlay = FrameLayout(activity)
    val info = InfoPanel(activity)
    private val toast: Toast
    private val osd = Osd()
    private val carousel = Carousel(activity)
    val guide = GuideLayer(this)
    private val keys = Keys { top() }

    private var pending: Channel? = null
    private var backTime = 0L
    private var numBuf = ""
    private var pauseInfo: Long? = null
    private var retry = 0

    init {
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        carousel.attach(overlay)
        info.attach(overlay)
        osd.attach(overlay)
        toast = Toast(root)
        playback.listener = object : Playback.Listener {
            override fun onState(playing: Boolean, buffering: Boolean) {
                if (playing) {
                    retry = 0
                    state.error = false
                    onReady()
                }
                updateInfo()
            }

            override fun onEnded() = onStreamEnded()
            override fun onError(e: PlaybackException) = onStreamError(e)
            override fun onVideoSize(width: Int, height: Int) {
                state.height = height
                updateInfo()
            }
        }
    }

    private val onData: (DataEvent) -> Unit = { e ->
        when (e) {
            is DataEvent.EpgProgress -> {
                guide.refresh()
                updateInfo()
                (layers.lastOrNull() as? ScheduleLayer)?.refresh()
            }
            is DataEvent.PlaylistProgress -> if (e.playlistId == playlist?.id) onPlaylistProgress(e)
        }
    }

    private var ticks = 0

    // раз в секунду: инфопанель, сторож архива, проверка «догнали эфир»; раз в 30 с — напоминания
    private val tick = object : Runnable {
        override fun run() {
            checkArchiveLive()
            watchArchive()
            if (info.visible) updateInfo()
            if (ticks % 30 == 0) reminders.check()
            if (ticks > 0 && ticks % AUTO_REFRESH == 0) autoRefresh()
            ticks++
            main.postDelayed(this, 1000)
        }
    }

    val reminders = repo.reminders
    private val onReminders: () -> Unit = {
        Alarms.schedule(activity)
        guide.refresh()
        (layers.lastOrNull() as? ScheduleLayer)?.refresh()
    }

    init {
        Bus.on(onData)
        reminders.onDue = { notifyReminder(it) }
        reminders.listen(onReminders)
        main.post(tick)
    }

    // ================================================================== плейлист

    /** Открыть плейлист: каталог из БД сразу, загрузка в фоне если устарел или пуст */
    fun open(p: Playlist) {
        playlist = p
        Prefs.lastPlaylist = p.id
        catalog = repo.loadCatalog(p, "Избранное")
        val cat = catalog!!
        if (cat.channels.isEmpty() || repo.isStale(p)) {
            if (cat.channels.isEmpty()) {
                showLoading()
            }
            repo.refresh(p, false) { err -> onRefreshed(p, err) }
        } else repo.refreshEpg(p, false)
        startFromLast()
    }

    private fun startFromLast() {
        val cat = catalog ?: return
        if (cat.channels.isEmpty()) return
        val p = playlist ?: return
        pendingPlay?.let { r ->
            pendingPlay = null
            cat.byKey[r.chKey]?.let { ch -> return if (r.missed(Clock.now())) playArchive(ch, r.start, r.stop) else playChannel(ch) }
        }
        val last = cat.byKey[Prefs.lastChannel(p.id)]
        if (last != null) playChannel(last, Prefs.lastGroup(p.id)) else openGuide()
    }

    private var loadingShown = false

    private fun showLoading() {
        loadingShown = true
        openGuide()
    }

    private fun onPlaylistProgress(e: DataEvent.PlaylistProgress) {
        // первая загрузка: каналы появляются по мере разбора — перечитать каталог
        val p = playlist ?: return
        if (state.channel == null && (catalog?.channels?.size ?: 0) < e.channels) {
            catalog = repo.loadCatalog(p, "Избранное")
            if (layers.contains(guide)) guide.reload()
        }
    }

    private fun onRefreshed(p0: Playlist, err: String?) {
        val p = repo.playlist(p0.id) ?: return
        if (playlist?.id != p.id) return
        playlist = p
        if (err != null) {
            if ((catalog?.channels?.size ?: 0) == 0) return showError(err)
            toast.show("Плейлист не обновился: " + errorText(err))
            return
        }
        val cur = state.channel
        catalog = repo.loadCatalog(p, "Избранное")
        // тот же канал в новом каталоге (по ключу)
        cur?.let { c -> catalog?.byKey?.get(c.key)?.let { state.channel = it } }
        if (layers.contains(guide)) guide.reload()
        if (state.channel == null) startFromLast()
    }

    /**
     * Пока приложение открыто: плейлист старше «перекачивать через» — обновить (с программой),
     * иначе — источники программы, которым больше «обновлять программу каждые».
     */
    private fun autoRefresh() {
        val p = repo.playlist(playlist?.id ?: return) ?: return
        if (repo.isStale(p)) repo.refresh(p, false) { err -> onRefreshed(p, err) }
        else repo.refreshEpg(p, false)
    }

    private fun showError(code: String) {
        layers.toList().forEach { remove(it) }
        push(MessageLayer(activity, "Плейлист не загрузился", errorText(code), listOf("Повторить" to "retry", "Настройки" to "settings")) { a ->
            when (a) {
                "retry" -> {
                    layers.toList().forEach { remove(it) }
                    playlist?.let { open(it) }
                }
                "settings" -> activity.openSettings()
                else -> activity.finish()
            }
        })
    }

    fun errorText(code: String) = when {
        code == "no_url" -> "Неверный адрес плейлиста"
        code == "network" -> "Нет связи с сервером"
        code.startsWith("http_") -> "Сервер ответил ошибкой " + code.removePrefix("http_")
        code == "format" -> "Файл не похож на M3U-плейлист"
        code == "empty" -> "В плейлисте нет каналов"
        else -> code
    }

    // ================================================================== слои

    fun top(): Layer = layers.lastOrNull() ?: playerLayer

    fun push(layer: Layer) {
        keys.release()
        layers.add(layer)
        layer.view?.let { v ->
            if (v.parent == null) overlay.addView(v, FrameLayout.LayoutParams(-1, -1))
            v.bringToFront()
            Anim.show(v)
        }
        toastFront()
    }

    fun remove(layer: Layer) {
        keys.release()
        if (!layers.remove(layer)) return
        layer.destroy()
        layer.view?.let { v -> Anim.hide(v) { if (layer !== guide) overlay.removeView(v) } }
    }

    private fun toastFront() {
        osd.bringToFront()
    }

    fun onKeyDown(code: Int, e: android.view.KeyEvent) = keys.onKeyDown(code, e)
    fun onKeyUp(code: Int) = keys.onKeyUp(code)

    /** Базовый слой: управление в режиме просмотра */
    private val playerLayer = object : Layer {
        override val view: View? = null

        // долгие ↑/↓: напоминания / недавние каналы (короткие срабатывают на отпускание)
        override fun hasLong(name: String) = name == "up" || name == "down"

        override fun keyUp(name: String) {
            if (name == "left" || name == "right") seekKeyUp()
        }

        override fun key(name: String, long: Boolean, digit: Int) {
            if (name == "digit") return digit(digit)
            if (long && name == "down") return openHistory()
            if (long && name == "up") return openReminders(state.channel)
            val seekable = state.archive != null && state.channel?.hasArchive == true
            when (name) {
                "left" -> if (seekable) seekStep(-1) else switchRelative(-1)
                "right" -> if (seekable) seekStep(1) else switchRelative(1)
                "pgup" -> switchRelative(1)
                "pgdown" -> switchRelative(-1)
                "up" -> {
                    // закреплённая инфопанель закрывается ↑, иначе — программа/архив или история
                    if (info.visible && info.more) return info.collapse()
                    if (info.visible && info.sticky) return info.hide()
                    if (Prefs.upArchive) state.channel?.let { openSchedule(it, 0) } ?: openGuide() else openHistory()
                }
                "down" -> {
                    if (!info.visible || !info.sticky) showInfo(true)
                    else if (!info.more) expandInfo()
                }
            }
        }

        override fun enter() {
            if (numBuf.isNotEmpty()) return applyNumber()
            // при закреплённой (↓) инфопанели OK — пауза/продолжить
            if (info.visible && info.sticky) return togglePause()
            openGuide()
        }

        override fun long() {
            state.channel?.let { openMenu(it) }
        }

        override fun back() = playerBack()

        override fun media(name: String) = mediaKey(name)
    }

    private fun playerBack() {
        if (numBuf.isNotEmpty()) return cancelNumber()
        if (seek != null) return cancelSeek()
        if (info.visible && info.more) return info.collapse()
        // закреплённую панель «назад» просто закрывает; показанная сама закрывается вместе с первым «назад»
        if (info.visible && info.sticky) return info.hide()
        if (info.visible) info.hide()
        exitRequest()
    }

    /** «Назад» дважды (в течение 2 с) — выход */
    fun exitRequest() {
        val t = System.currentTimeMillis()
        if (t - backTime < BACK_EXIT) {
            backTime = 0
            activity.finish()
            return
        }
        backTime = t
        toast.show("Нажмите «Назад» ещё раз для выхода")
    }

    // ================================================================== воспроизведение

    fun hasVideo() = state.channel != null

    /** Текущая позиция воспроизведения (unix-сек) */
    fun position(): Long {
        val a = state.archive ?: return pauseInfo ?: Clock.now()
        val base = a.base ?: return a.start
        return a.start + maxOf(0L, (playback.periodPositionMs - base) / 1000)
    }

    /** Секунд в буфере впереди позиции */
    private fun bufferAhead(): Long = maxOf(0L, (playback.bufferedPosition - playback.position) / 1000)

    private fun groupKeyFor(ch: Channel, prefer: String?): String? = catalog?.groupOf(ch, prefer ?: state.groupKey)?.key

    /**
     * Включить канал.
     * @param archive unix-сек начала архива (null — эфир); program — передача (её конец задаёт длительность)
     */
    fun playChannel(ch: Channel, groupKey: String? = null, archive: Long? = null, programEnd: Long = 0, reload: Boolean = false, unlocked: Boolean = false) {
        // блокировка: заблокированный канал или категория — только с PIN; играющий повторно не спрашиваем
        if (!unlocked && ch !== state.channel && catalog?.isLocked(ch) == true && !sessionUnlocked(ch)) {
            main.removeCallbacks(switchRun)
            pending = null
            return askPin("${ch.num}. ${ch.name}", {
                if (!Prefs.pinEveryTime) { unlockedKeys.add(ch.key); unlockedKeys.add("g:" + ch.groupKey) }
                playChannel(ch, groupKey, archive, programEnd, reload, unlocked = true)
            }) {
                if (!hasVideo()) openGuide() else updateInfo()
            }
        }
        main.removeCallbacks(switchRun)
        pending = null
        stopSeekHold()
        seek = null
        main.removeCallbacks(applySeekRun)
        pauseInfo = null
        watch = null
        val gk = groupKeyFor(ch, groupKey)
        if (ch === state.channel && archive == null && state.archive == null && !reload && !state.error) {
            state.groupKey = gk
            closeGuide(true)
            showInfo()
            return
        }
        var url: String
        var a: Archive? = null
        if (archive != null && ch.hasArchive) {
            val tpl = CatchupUrl.template(ch.url, ch.catchupType ?: "", ch.catchupSource)
            if (tpl == null) {
                toast.show("Канал не поддерживает архив")
                return
            }
            val now = Clock.now()
            val start = archive
            var dur = (now - start).coerceIn(60, 6 * 3600)
            if (programEnd > start) dur = maxOf(dur, minOf(programEnd, now) - start)
            // для flussonic «архив до текущего момента» — растущий плейлист
            val ready = if (CatchupUrl.isFlussonic(ch.catchupType ?: "") && now - start < 6 * 3600) tpl.replace("\${duration}", "now") else tpl
            url = CatchupUrl.prepare(ready, start, dur, now)
            a = Archive(start)
            Log.i(TAG, "archive ${ch.name} $url")
        } else url = CatchupUrl.prepare(ch.url, null, 0, Clock.now())

        if (state.channel !== ch) {
            catalog?.addHistory(ch)
            main.removeCallbacks(scoreRun)
            scoreFor = ch
            main.postDelayed(scoreRun, ADAPTIVE_AFTER)
        }
        state.channel = ch
        state.groupKey = gk
        state.archive = a
        state.height = 0
        state.error = false
        forceHls = false
        lastUrl = url
        playlist?.let { Prefs.saveLast(it.id, ch.key, gk ?: "") }
        // архив — с начала окна (у «эфирного» потока ExoPlayer иначе начнёт с края)
        playback.play(ch, url, if (a != null) 0 else C.TIME_UNSET)
        closeGuide(true)
        showInfo()
    }

    // разблокировано до выхода из приложения (настройка «Спрашивать PIN: один раз»)
    private val unlockedKeys = HashSet<String>()

    private fun sessionUnlocked(ch: Channel): Boolean {
        val cat = catalog ?: return false
        val chOk = !cat.isChannelLocked(ch) || ch.key in unlockedKeys
        val gOk = !cat.isGroupLocked(ch.groupKey) || ("g:" + ch.groupKey) in unlockedKeys
        return chOk && gOk
    }

    /** Окно PIN поверх всего; ok — верный PIN, cancel — «Назад» */
    fun askPin(title: String, ok: () -> Unit, cancel: () -> Unit = {}) {
        var layer: PinLayer? = null
        layer = PinLayer(activity, PinLayer.Mode.CHECK, title, onDone = { remove(layer!!); ok() }, onCancel = { remove(layer!!); cancel() })
        push(layer)
    }

    /** Задать PIN (если ещё нет) и затем [ok] */
    private fun ensurePin(ok: () -> Unit) {
        if (Prefs.pin.isNotEmpty()) return ok()
        var layer: PinLayer? = null
        layer = PinLayer(activity, PinLayer.Mode.CREATE, "Новый PIN", onDone = { remove(layer!!); toast.show("PIN задан. Сменить или убрать — в настройках"); ok() },
            onCancel = { remove(layer!!) })
        push(layer)
    }

    private var lastUrl = ""
    private var forceHls = false
    private var scoreFor: Channel? = null
    private val scoreRun = Runnable {
        val ch = scoreFor
        if (ch != null && state.channel === ch && Prefs.adaptiveSort) catalog?.addScore(ch)
    }

    fun playArchive(ch: Channel, start0: Long, programEnd: Long = 0) {
        if (!ch.hasArchive) return toast.show("Канал не поддерживает архив")
        val start = maxOf(start0, Clock.now() - ch.archiveDays * 86400L)
        val gk = if (state.channel === ch) state.groupKey else null
        if (start >= Clock.now() - 30) return playChannel(ch, gk)
        playChannel(ch, gk, start, programEnd)
    }

    /** Первые данные: привязать позицию плеера к началу ссылки архива */
    private fun onReady() {
        val a = state.archive ?: return
        if (a.base == null) a.base = playback.periodPositionMs
    }

    private fun onStreamEnded() {
        if (state.archive == null) {
            // эфирный поток оборвался — переподключаемся
            state.channel?.let { playChannel(it, state.groupKey, reload = true) }
            return
        }
        // архив доиграл раньше передачи — новая ссылка с того же момента; один раз на ссылку
        val a = state.archive ?: return
        if (a.continued) return
        a.continued = true
        restorePosition(position())
    }

    private var archiveRetry = 0L

    private fun onStreamError(e: PlaybackException) {
        val ch = state.channel ?: return
        Log.w(TAG, "play error ${e.errorCodeName} ${ch.name}")
        // ссылка без расширения оказалась HLS — повторить с явным типом
        if (!forceHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            forceHls = true
            playback.play(ch, lastUrl, if (state.archive != null) 0 else C.TIME_UNSET, forceHls = true)
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && state.archive == null) {
            playback.play(ch, lastUrl, forceHls = forceHls)
            return
        }
        if (state.archive != null) {
            val t = System.currentTimeMillis()
            if (t - archiveRetry > 15_000) {
                archiveRetry = t
                return restorePosition(position())
            }
        }
        state.error = true
        updateInfo(true)
        // эфир: повтор через 2, 5, 10 с
        val delay = RETRY.getOrNull(retry++) ?: return
        main.postDelayed({ if (state.channel === ch && state.error) playback.play(ch, lastUrl, forceHls = forceHls) }, delay)
    }

    /** Вернуться к реальной позиции после потери потока: архив (новая ссылка) или эфир */
    private fun restorePosition(pos: Long) {
        val ch = state.channel ?: return
        if (ch.hasArchive && pos < Clock.now() - 30) return playArchive(ch, pos)
        if (state.archive?.timeshift == true) {
            state.archive = null
            return updateInfo()
        }
        playChannel(ch, state.groupKey, reload = true)
    }

    /** Архив догнал эфир — считаем, что смотрим эфир */
    private fun checkArchiveLive() {
        val a = state.archive ?: return
        if (a.base != null && seek == null && position() >= Clock.now() - 3) state.archive = null
    }

    private class Watch(var pos: Long, var at: Long, var moved: Long)

    private var watch: Watch? = null

    /**
     * Сторож архива (раз в секунду): позиция скачет вперёд быстрее реального времени (поток ушёл
     * к краю эфира) или стоит 15 с без паузы (поток кончился или завис) — новая ссылка с позиции.
     */
    private fun watchArchive() {
        val a = state.archive ?: return
        if (seek != null || a.base == null) return
        val pos = playback.periodPositionMs
        val t = System.currentTimeMillis()
        val w = watch
        if (w == null || playback.paused) {
            watch = Watch(pos, t, t)
            return
        }
        val dWall = t - w.at
        val dPos = pos - w.pos
        if (dPos > dWall + 5000) {
            val expected = a.start + maxOf(0L, (w.pos - a.base!!) / 1000) + dWall / 1000
            Log.i(TAG, "stream jumped, restore $expected")
            watch = null
            return restorePosition(expected)
        }
        w.pos = pos
        w.at = t
        if (dPos != 0L) w.moved = t
        else if (t - w.moved > 15_000 && t - archiveRetry > 15_000) {
            archiveRetry = t
            w.moved = t
            Log.i(TAG, "archive stalled, continue from ${position()}")
            onStreamEnded()
        }
    }

    // ================================================================== пауза

    fun togglePause() {
        if (!hasVideo()) return
        if (!playback.paused) {
            pauseInfo = position()
            playback.pause()
        } else {
            val p = pauseInfo
            pauseInfo = null
            // эфир после паузы — «архив со сдвигом»: поток играет из буфера
            if (p != null && state.archive == null && Clock.now() - p > 3) {
                state.archive = Archive(p, playback.periodPositionMs, timeshift = true)
            }
            watch = null
            playback.resume()
        }
        updateInfo()
        info.show(if (info.sticky) 0 else Prefs.infoHide)
    }

    private fun mediaKey(name: String) {
        if (!hasVideo()) return
        when (name) {
            "playpause" -> togglePause()
            "play" -> if (playback.paused) togglePause()
            "pause" -> if (!playback.paused) togglePause()
            "forward" -> seekStep(1)
            "rewind" -> seekStep(-1)
            "stop" -> return exitRequest()
            "guide" -> return openGuide()
            "info" -> return if (info.visible) info.hide() else showInfo(true)
            "menu" -> return state.channel?.let { openMenu(it) } ?: Unit
            "history" -> return openHistory()
        }
        main.postDelayed({ showInfo() }, 50)
    }

    // ================================================================== ←/→, цифры

    private val switchRun = Runnable {
        val next = pending ?: return@Runnable
        val gk = switchGroup
        pending = null
        playChannel(next, gk)
        main.postDelayed({ carousel.hide() }, 1500)
    }
    private var switchGroup: String? = null

    private fun switchRelative(dir: Int) {
        val base = pending ?: state.channel ?: return openGuide()
        val cat = catalog ?: return
        val g = cat.group(state.groupKey) ?: cat.groupOf(base, null)
        val list = g.channels
        val n = list.size
        if (n == 0) return
        val i = list.indexOf(base).coerceAtLeast(0)
        val ni = (i + dir + n) % n
        val next = list[ni]
        pending = next
        switchGroup = g.key
        updateInfo()
        info.show(Prefs.infoHide)
        carousel.show(list, ni, info.view)
        main.removeCallbacks(switchRun)
        main.postDelayed(switchRun, SWITCH_DELAY)
    }

    fun digit(d: Int) {
        val cat = catalog ?: return
        numBuf = (numBuf + d).takeLast(4)
        osd.show(numBuf, cat)
        main.removeCallbacks(numRun)
        if (top() === guide) {
            cat.byNum(numBuf.toInt())?.let { guide.focusNumber(it.num) }
            main.postDelayed({ cancelNumber() }, OSD_NUMBER)
            return
        }
        if (top() !== playerLayer) return
        // следующая цифра невозможна — сразу
        main.postDelayed(numRun, if ((numBuf + "0").toInt() > cat.channels.size) 300 else OSD_NUMBER)
    }

    private val numRun = Runnable { applyNumber() }

    fun numberPending() = numBuf.isNotEmpty()

    fun applyNumber() {
        val ch = catalog?.byNum(numBuf.toIntOrNull() ?: 0)
        val inGuide = top() === guide
        cancelNumber()
        if (ch != null && !inGuide) playChannel(ch)
        else if (ch != null) guide.focusNumber(ch.num)
    }

    fun cancelNumber() {
        main.removeCallbacks(numRun)
        numBuf = ""
        osd.hide()
    }

    // ================================================================== перемотка

    private class Seek(var target: Long) {
        var dir = 0
        var last = 0L
        var released = true
        var holdStart = 0L
        var holdTick = 0L
        var holding = false
    }

    private var seek: Seek? = null
    private val applySeekRun = Runnable { applySeek() }
    private val seekTick = object : Runnable {
        override fun run() {
            val s = seek ?: return
            val t = System.currentTimeMillis()
            if (t - s.last > SEEK_GAP) return seekKeyUp()
            val rate = minOf(SEEK_MAX, SEEK_START + SEEK_GROW * (t - s.holdStart) / 1000f)
            moveSeek((s.dir * rate * (t - s.holdTick) / 1000f).toLong())
            s.holdTick = t
            main.postDelayed(this, 100)
        }
    }

    /** Нажатие — шаг 30 с; повтор той же клавиши без отпускания — удержание с ускорением */
    private fun seekStep(dir: Int) {
        val ch = state.channel ?: return
        if (!ch.hasArchive) return showInfo()
        val t = System.currentTimeMillis()
        val s = seek ?: Seek(position())
        val repeat = s.dir == dir && !s.released && t - s.last < SEEK_GAP
        s.last = t
        s.dir = dir
        s.released = false
        seek = s
        main.removeCallbacks(applySeekRun)
        if (!repeat) {
            stopSeekHold()
            moveSeek(dir * SEEK_TAP)
            main.postDelayed(applySeekRun, SEEK_DELAY)
        } else if (!s.holding) {
            s.holding = true
            s.holdStart = t
            s.holdTick = t
            main.postDelayed(seekTick, 100)
        }
    }

    private fun moveSeek(delta: Long) {
        val ch = state.channel ?: return
        val s = seek ?: return
        val now = Clock.now()
        s.target = (s.target + delta).coerceIn(now - ch.archiveDays * 86400L, now)
        updateInfo()
        info.show(Prefs.infoHide)
    }

    private fun stopSeekHold() {
        main.removeCallbacks(seekTick)
        seek?.holding = false
    }

    private fun seekKeyUp() {
        val s = seek ?: return
        s.released = true
        if (!s.holding) return
        stopSeekHold()
        main.removeCallbacks(applySeekRun)
        main.postDelayed(applySeekRun, SEEK_DELAY)
    }

    private fun applySeek() {
        val s = seek
        stopSeekHold()
        seek = null
        if (s == null) return
        val ch = state.channel ?: return
        if (s.target >= Clock.now() - 30) {
            if (state.archive != null) playChannel(ch, state.groupKey)
            return updateInfo()
        }
        // внутри окна текущей ссылки — перемотка плеера, иначе новая ссылка на этот момент
        val a = state.archive
        if (a != null && a.base != null && playback.seekable) {
            val target = (s.target - a.start) * 1000 + a.base!! - playback.windowOffsetMs
            if (target >= 0 && target < playback.windowDurationMs - 5000) {
                watch = null
                playback.seekTo(target)
                return updateInfo()
            }
        }
        playArchive(ch, s.target)
    }

    private fun cancelSeek() {
        main.removeCallbacks(applySeekRun)
        stopSeekHold()
        seek = null
        updateInfo()
    }

    // ================================================================== инфопанель

    fun showInfo(sticky: Boolean = false) {
        updateInfo()
        info.show(if (sticky) 0 else Prefs.infoHide)
    }

    fun updateInfo(show: Boolean = false) {
        val ch = pending ?: state.channel ?: return
        val same = ch === state.channel
        val pos = if (same) position() else Clock.now()
        val sk = if (same) seek?.target else null
        // при перемотке — передача в точке назначения
        val ref = sk ?: pos
        val p = repo.at(ch, ref)
        info.update(InfoPanel.Model(
            channel = ch,
            program = p,
            next = repo.after(ch, p?.stop ?: ref),
            archive = same && state.archive != null,
            position = pos,
            ahead = if (same) bufferAhead() else 0,
            seek = sk,
            height = if (same) state.height else 0,
            paused = same && playback.paused,
            error = same && state.error
        ))
        if (show) info.show(Prefs.infoHide)
    }

    /** ↓↓: описание передачи под инфопанелью */
    private fun expandInfo() {
        val ch = state.channel ?: return
        val p = repo.at(ch, position())
        if (p == null) {
            info.setMore("Нет программы", ch.name, "")
            return
        }
        val tags = listOf(Fmt.dayTimeRange(p.start, p.stop), p.category, p.subTitle).filter { it.isNotEmpty() }.joinToString(" · ")
        info.setMore(p.title, tags, p.desc)
    }

    // ================================================================== гид и окна

    fun openGuide(toGrid: Boolean = false) {
        info.hide()
        if (!layers.contains(guide)) {
            layers.toList().forEach { remove(it) }
            push(guide)
        }
        guide.open(state.channel, state.groupKey, toGrid)
    }

    /** Закрыть гид. Без видео гид закрывать некуда — это запрос на выход */
    fun closeGuide(silent: Boolean = false) {
        if (!layers.contains(guide)) return
        if (!silent && !hasVideo()) return exitRequest()
        layers.toList().reversed().forEach { if (it !== guide) remove(it) }
        remove(guide)
    }

    fun openHistory() {
        val cat = catalog ?: return
        val now = Clock.now()
        info.hide()
        var layer: ListLayer? = null
        val items = cat.recent(state.channel).map { ch ->
            val p = repo.at(ch, now)
            ListLayer.Item("${ch.num}. ${ch.name}", p?.title ?: "", ch, p?.let { Fmt.progress(it.start, it.stop, now) } ?: -1f)
        }
        layer = ListLayer(activity, "Недавние каналы", items,
            onSelect = { it.channel?.let { ch -> remove(layer!!); playChannel(ch) } },
            onLong = { it.channel?.let { ch -> openMenu(ch) } },
            onBack = { remove(layer!!) },
            empty = "История пуста")
        push(layer)
    }

    /**
     * Окно «Программа и архив» / «Напоминания»: слева вкладки видов (на вкладке архива —
     * канал [ch]). view: 0 — архив, 1 — напоминания. Без канала — только напоминания.
     */
    fun openSchedule(ch: Channel?, view: Int) {
        info.hide()
        var layer: ScheduleLayer? = null
        val close = { remove(layer!!) }
        val views = if (ch != null) listOf(Icons.Kind.ARCHIVE, Icons.Kind.REMIND) else emptyList()
        val make = { k: Int -> if (k == 0 && ch != null) archiveContent(ch, close) else remindersContent() }
        layer = ScheduleLayer(activity, views, if (ch != null) view else 1, make) { close() }
        push(layer)
    }

    fun openReminders(ch: Channel?) = openSchedule(ch, 1)

    /** Вид «Напоминания»: передачи по дням, в строке — логотип, канал, плейлист, «до начала» */
    private fun remindersContent(): ScheduleLayer.Content {
        val multi = repo.playlists().size > 1
        val build = {
            val rows = ArrayList<ScheduleLayer.Row>()
            val tabs = ArrayList<ScheduleLayer.Tab>()
            var day = Long.MIN_VALUE
            for (r in reminders.all()) {
                val d = Fmt.dayIndex(r.start)
                if (d != day) {
                    day = d
                    rows.add(ScheduleLayer.Row(true, d, Fmt.dayLabel(r.start)))
                    tabs.add(ScheduleLayer.Tab(d, Fmt.dayTab(r.start)))
                }
                rows.add(ScheduleLayer.Row(
                    separator = false, day = d, title = r.title, id = r.start, time = Fmt.hhmm(r.start),
                    sub = r.chName + (if (multi && r.playlistName.isNotEmpty()) " · " + r.playlistName else "") + " · " + whenText(r),
                    channel = Channel.stub(r.chName, r.chNum, r.logo), payload = r,
                    past = r.missed(Clock.now()), archive = r.missed(Clock.now())
                ))
            }
            ScheduleLayer.Built(rows, tabs)
        }
        return ScheduleLayer.Content(
            channel = null,
            icon = Icons.Kind.REMIND,
            title = "Напоминания",
            subtitle = "Передачи, о которых напомнить",
            empty = "Напоминаний нет. Добавьте их в карточке будущей передачи.",
            build = build,
            sub = { reminders.all().size.toString() },
            onSelect = { row -> reminderDetails(row.payload as Reminders.Reminder) },
            onLong = { row -> reminderDetails(row.payload as Reminders.Reminder) },
            watch = { refresh ->
                // «до начала …» в подписях
                val t = object : Runnable {
                    override fun run() { refresh(); main.postDelayed(this, 30_000) }
                }
                main.postDelayed(t, 30_000)
                ({ main.removeCallbacks(t) })
            }
        )
    }

    private fun whenText(r: Reminders.Reminder): String {
        val now = Clock.now()
        val left = r.start - now
        return when {
            r.missed(now) -> "пропущено · смотреть из архива"
            left > 0 -> "до начала " + Fmt.until(left)
            else -> "уже идёт"
        }
    }

    /** Карточка запланированной передачи: «Удалить напоминание» (+ «Смотреть», если уже идёт) */
    private fun reminderDetails(r: Reminders.Reminder) {
        val buttons = ArrayList<Pair<String, String>>()
        val now = Clock.now()
        if (r.missed(now)) buttons.add("Смотреть из архива" to "watch")
        else if (r.start <= now) buttons.add("Смотреть" to "watch")
        buttons.add("Удалить напоминание" to "remove")
        var layer: DetailsLayer? = null
        layer = DetailsLayer(activity, Channel.stub(r.chName, r.chNum, r.logo),
            kicker = (if (r.missed(now)) "Пропущено · в архиве ещё " + Fmt.until(r.start + r.archiveDays * 86400L - now) else "Напоминание · " + whenText(r)) +
                if (r.playlistName.isNotEmpty()) " · " + r.playlistName else "",
            title = r.title, time = Fmt.dayTimeRange(r.start, r.stop), desc = r.desc, buttons = buttons,
            onAction = { a ->
                remove(layer!!)
                reminders.remove(r.id)
                if (a == "watch") launch(r)
            },
            onBack = { remove(layer!!) })
        push(layer)
    }

    // ------------------------------------------------------------------ показ напоминаний

    private var reminderLayer: ReminderLayer? = null

    /** Пора показать: все в одном окне со счётчиком (см. [ReminderLayer]) */
    private fun notifyReminder(r: Reminders.Reminder) {
        reminderLayer?.let { it.add(r); return }
        var layer: ReminderLayer? = null
        layer = ReminderLayer(activity, onAction = { rem, a -> reminderAction(rem, a) }, onEmpty = {
            remove(layer!!)
            reminderLayer = null
        })
        reminderLayer = layer
        layer.add(r)
        push(layer)
    }

    private fun reminderAction(r: Reminders.Reminder, a: String) {
        when (a) {
            "snooze" -> reminders.snooze(r.id)
            "later" -> reminders.keepMissed(r.id)
            "release" -> reminders.release(r.id)
            "close" -> reminders.remove(r.id)
            "watch" -> { reminders.remove(r.id); launch(r) }
            "archive" -> { reminders.remove(r.id); launch(r) }
        }
    }

    /** Включить передачу: идёт — эфир, закончилась — из архива. Другой плейлист — сменить */
    fun launch(r: Reminders.Reminder) {
        val p = repo.playlist(r.playlistId) ?: return toast.show("Плейлист удалён")
        if (playlist?.id != p.id) {
            pendingPlay = r
            switchPlaylist(p)
            return
        }
        val ch = catalog?.byKey?.get(r.chKey) ?: return toast.show("Канала больше нет в плейлисте")
        layers.toList().reversed().forEach { if (it !== guide && it !== reminderLayer) remove(it) }
        if (r.missed(Clock.now())) playArchive(ch, r.start, r.stop) else playChannel(ch)
    }

    /** Открыть напоминание из уведомления (приложение было закрыто) */
    fun launchReminder(id: String) {
        val r = reminders.all().firstOrNull { it.id == id } ?: return
        // то же напоминание уже в окне — убрать оттуда
        reminderLayer?.drop(id)
        reminders.remove(id)
        launch(r)
    }

    private var pendingPlay: Reminders.Reminder? = null

    fun openHelp() {
        var layer: HelpLayer? = null
        layer = HelpLayer(activity) { remove(layer!!) }
        push(layer)
    }

    private fun archiveContent(ch: Channel, close: () -> Unit): ScheduleLayer.Content {
        val time = Clock.now()
        val days = ch.archiveDays
        val archFrom = time - days * 86400L
        val from = Fmt.dayStart(time - maxOf(days, 1) * 86400L)
        val to = Fmt.dayStart(time) + 8 * 86400L
        val build = {
            val tm = Clock.now()
            val playingPos = if (state.channel === ch) position() else -1
            val list = Cells.cells(repo.range(ch, from, to), from, to).filter { c ->
                c.start >= from && (c.program != null || (days > 0 && c.start >= archFrom - Cells.SLOT && c.start < tm))
            }
            val rows = ArrayList<ScheduleLayer.Row>()
            val tabs = ArrayList<ScheduleLayer.Tab>()
            var day = Long.MIN_VALUE
            var focusId: Long? = null
            for (c in list) {
                val d = Fmt.dayIndex(c.start)
                if (d != day) {
                    day = d
                    rows.add(ScheduleLayer.Row(true, d, Fmt.dayLabel(c.start)))
                    tabs.add(ScheduleLayer.Tab(d, Fmt.dayTab(c.start)))
                }
                val live = c.start <= tm && tm < c.end
                val playing = playingPos >= c.start && playingPos < c.end
                if (playing || (live && focusId == null)) focusId = c.start
                rows.add(ScheduleLayer.Row(
                    separator = false, day = d, title = c.label(), id = c.start, time = Fmt.hhmm(c.start),
                    past = c.end <= tm, live = live, future = c.start > tm,
                    archive = ch.hasArchive && c.start >= archFrom - Cells.SLOT && c.start < tm,
                    playing = playing, remind = c.start > tm && reminders.has(ch, c.start), payload = c
                ))
            }
            ScheduleLayer.Built(rows, tabs, focusId)
        }
        return ScheduleLayer.Content(
            channel = ch,
            icon = null,
            title = "${ch.num}  ${ch.name}",
            subtitle = ch.group + if (ch.hasArchive) " · Архив ${ch.archiveDays} дн." else "",
            empty = "Нет программы",
            build = build,
            sub = { if (repo.epgLoading()) "…" else "" },
            onSelect = { r ->
                val c = r.payload as Cell
                when {
                    r.live -> { close(); playChannel(ch, if (state.channel === ch) state.groupKey else null) }
                    r.past && r.archive -> { close(); playArchive(ch, c.start, c.end) }
                    else -> openDetails(ch, c)
                }
            },
            onLong = { r -> openDetails(ch, r.payload as Cell) },
            watch = { { } }
        )
    }

    /** Карточка передачи: кнопки по времени и архиву канала (detailsButtons плагина) */
    fun openDetails(ch: Channel, cell: Cell) {
        val now = Clock.now()
        val buttons = ArrayList<Pair<String, String>>()
        val inArchive = ch.hasArchive && cell.start >= now - ch.archiveDays * 86400L
        if (cell.end <= now) {
            if (inArchive) buttons.add("Смотреть" to "archive")
        } else if (cell.start <= now) {
            if (ch.hasArchive) buttons.add("Смотреть сначала" to "archive")
            if (state.channel === ch && state.archive != null) buttons.add("Смотреть эфир" to "live")
            else if (state.channel !== ch) buttons.add("Смотреть" to "live")
        } else if (cell.program != null) {
            // будущая передача — напоминание
            buttons.add(if (reminders.has(ch, cell.start)) "Удалить напоминание" to "unremind" else "Напомнить" to "remind")
        }
        val p = cell.program
        var layer: DetailsLayer? = null
        layer = DetailsLayer(activity, ch,
            kicker = listOfNotNull(p?.category?.takeIf { it.isNotEmpty() }, if (cell.start <= now && now < cell.end) "Сейчас в эфире" else null).joinToString(" · "),
            title = cell.label(),
            time = Fmt.dayTimeRange(cell.start, cell.end),
            desc = listOf(p?.subTitle ?: "", p?.desc ?: "").filter { it.isNotEmpty() }.joinToString("\n\n"),
            buttons = buttons,
            onAction = { a ->
                // карточка и окна над плеером закрываются при запуске
                when (a) {
                    "archive" -> { layers.toList().reversed().forEach { if (it !== guide) remove(it) }; playArchive(ch, cell.start, cell.end) }
                    "live" -> { layers.toList().reversed().forEach { if (it !== guide) remove(it) }; playChannel(ch) }
                    "remind" -> {
                        remove(layer!!)
                        playlist?.let { reminders.add(it, ch, cell.program!!) }
                        activity.askNotifications()
                        toast.show("Напомню " + (if (Prefs.remindBefore > 0) "за ${Prefs.remindBefore} мин до начала" else "в начале передачи"))
                    }
                    "unremind" -> {
                        remove(layer!!)
                        reminders.remove(reminders.keyOf(ch.key, cell.start))
                    }
                }
            },
            onBack = { remove(layer!!) })
        push(layer)
    }

    /** Меню канала (удерж. OK) */
    fun openMenu(ch: Channel, guide: Boolean = false) {
        val cat = catalog ?: return
        val now = Clock.now()
        val p = repo.at(ch, now)
        val items = ArrayList<ListLayer.Item>()
        if (guide) items.add(ListLayer.Item("Смотреть", action = "play"))
        if (p != null) items.add(ListLayer.Item("О передаче", p.title, action = "about"))
        if (ch.hasArchive) {
            if (p != null) items.add(ListLayer.Item("Смотреть сначала", action = "restart"))
            items.add(ListLayer.Item("Архив", "${ch.archiveDays} дн.", action = "archive"))
        }
        if (state.channel === ch && state.archive != null) items.add(ListLayer.Item("Смотреть эфир", action = "live"))
        if (!guide) items.add(ListLayer.Item("ТВ-гид", action = "guide"))
        items.add(ListLayer.Item("Недавние каналы", action = "history"))
        items.add(ListLayer.Item("Напоминания", reminders.all().size.takeIf { it > 0 }?.toString() ?: "", action = "reminders"))
        items.add(ListLayer.Item(if (cat.isFavorite(ch)) "Удалить из избранного" else "Добавить в избранное", action = "fav"))
        if (cat.isFavorite(ch) && this.guide.groupKey() == Catalog.FAV_KEY && layers.contains(this.guide)) {
            items.add(ListLayer.Item("Сдвинуть вверх", action = "fav_up"))
            items.add(ListLayer.Item("Сдвинуть вниз", action = "fav_down"))
        }
        items.add(ListLayer.Item("Скрыть канал", action = "hide"))
        if (ch.group.isNotEmpty()) items.add(ListLayer.Item("Скрыть категорию", ch.group, action = "hide_group"))
        val pinOn = Prefs.pin.isNotEmpty()
        items.add(ListLayer.Item(if (pinOn && cat.isChannelLocked(ch)) "Разблокировать канал" else "Заблокировать канал", "по PIN", action = if (pinOn && cat.isChannelLocked(ch)) "unlock" else "lock"))
        items.add(ListLayer.Item(if (pinOn && cat.isGroupLocked(ch.groupKey)) "Разблокировать категорию" else "Заблокировать категорию", ch.groupKey,
            action = if (pinOn && cat.isGroupLocked(ch.groupKey)) "unlock_group" else "lock_group"))
        if (cat.hiddenCount() > 0) items.add(ListLayer.Item("Скрытое", cat.hiddenCount().toString(), action = "hidden"))
        items.add(ListLayer.Item("Адаптивная сортировка", if (Prefs.adaptiveSort) "Вкл." else "Выкл.", action = "adaptive"))
        items.add(ListLayer.Item("Вид", if (Prefs.guideGrid) "ТВ-гид" else "Список", action = "view"))
        if (repo.playlists().size > 1) items.add(ListLayer.Item("Плейлист", playlist?.name ?: "", action = "playlist"))
        items.add(ListLayer.Item("Обновить плейлист", action = "reload"))
        items.add(ListLayer.Item("Обновить программу", action = "reload_epg"))
        items.add(ListLayer.Item("Управление", action = "help"))
        items.add(ListLayer.Item("Настройки", action = "settings"))
        var layer: ListLayer? = null
        layer = ListLayer(activity, "${ch.num}. ${ch.name}", items, onSelect = { it ->
            remove(layer!!)
            menuAction(ch, it.action)
        }, onBack = { remove(layer!!) })
        push(layer)
    }

    private fun menuAction(ch: Channel, a: String) {
        val cat = catalog ?: return
        when (a) {
            "play" -> playChannel(ch, guide.groupKey())
            "about" -> repo.at(ch, Clock.now())?.let { p -> openDetails(ch, Cell(p.start, p.stop, p)) }
            "restart" -> repo.at(ch, Clock.now())?.let { p -> playArchive(ch, p.start, p.stop) }
            "archive" -> openSchedule(ch, 0)
            "live" -> playChannel(ch, state.groupKey)
            "guide" -> openGuide()
            "history" -> openHistory()
            "reminders" -> openReminders(ch)
            "help" -> openHelp()
            "fav" -> { cat.toggleFavorite(ch); guide.reload() }
            "fav_up" -> { cat.moveFavorite(ch, -1); guide.reload() }
            "fav_down" -> { cat.moveFavorite(ch, 1); guide.reload() }
            "hide" -> { cat.hideChannel(ch); guide.reload(); toast.show("Канал скрыт") }
            "hide_group" -> { cat.hideGroup(ch.group); guide.reload(); toast.show("Категория скрыта") }
            "hidden" -> openHidden()
            "lock" -> ensurePin { cat.setLocked("channel", ch.key, true); unlockedKeys.remove(ch.key); guide.refresh(); toast.show("Канал заблокирован") }
            "lock_group" -> ensurePin { cat.setLocked("group", ch.groupKey, true); unlockedKeys.remove("g:" + ch.groupKey); guide.refresh(); toast.show("Категория заблокирована") }
            "unlock" -> askPin("Разблокировать канал", { cat.setLocked("channel", ch.key, false); guide.refresh() })
            "unlock_group" -> askPin("Разблокировать категорию", { cat.setLocked("group", ch.groupKey, false); guide.refresh() })
            "adaptive" -> { Prefs.adaptiveSort = !Prefs.adaptiveSort; cat.applyView(); guide.reload() }
            "view" -> { Prefs.guideGrid = !Prefs.guideGrid; guide.setView(Prefs.guideGrid) }
            "playlist" -> openPlaylists()
            "reload" -> playlist?.let { p -> toast.show("Обновляю плейлист…"); repo.refresh(p, true) { err -> onRefreshed(p, err) } }
            "reload_epg" -> playlist?.let { p -> toast.show("Обновляю программу…"); repo.refreshEpg(p, true) }
            "settings" -> activity.openSettings()
        }
    }

    private fun openHidden() {
        val cat = catalog ?: return
        val items = cat.hiddenGroups.map { ListLayer.Item("Категория: " + it.value, "OK — вернуть", action = "group:" + it.key) } +
            cat.hiddenChannels.map { ListLayer.Item(it.value, "OK — вернуть", action = "channel:" + it.key) }
        var layer: ListLayer? = null
        layer = ListLayer(activity, "Скрытое", items, onSelect = {
            val kind = it.action.substringBefore(':')
            cat.unhide(kind, it.action.substringAfter(':'))
            guide.reload()
            remove(layer!!)
            if (cat.hiddenCount() > 0) openHidden()
        }, onBack = { remove(layer!!) }, empty = "Ничего не скрыто")
        push(layer)
    }

    private fun openPlaylists() {
        val list = repo.playlists()
        var layer: ListLayer? = null
        layer = ListLayer(activity, "Плейлист", list.map { ListLayer.Item(it.name, if (it.channelsCount > 0) "${it.channelsCount} каналов" else it.url, action = it.id.toString()) },
            onSelect = { it ->
                remove(layer!!)
                val p = list.first { p -> p.id.toString() == it.action }
                if (p.id != playlist?.id) switchPlaylist(p)
            }, onBack = { remove(layer!!) }, start = list.indexOfFirst { it.id == playlist?.id })
        push(layer)
    }

    fun switchPlaylist(p: Playlist) {
        layers.toList().forEach { remove(it) }
        playback.stop()
        state.channel = null
        state.archive = null
        open(p)
    }

    fun destroy() {
        Bus.off(onData)
        reminders.unlisten(onReminders)
        reminders.onDue = null
        main.removeCallbacksAndMessages(null)
        keys.release()
        layers.toList().forEach { it.destroy() }
    }

    // ================================================================== набор номера

    private inner class Osd {
        private val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = U.bg(U.PANEL, U.RADIUS_L)
            setPadding(U.pxi(1.2f), U.pxi(1f), U.pxi(1.2f), U.pxi(1f))
            visibility = View.GONE
        }

        fun attach(root: FrameLayout) {
            root.addView(box, FrameLayout.LayoutParams(U.pxi(26f), -2, Gravity.TOP or Gravity.START).apply { leftMargin = U.pxi(2f); topMargin = U.pxi(2f) })
        }

        fun bringToFront() = box.bringToFront()

        fun show(buf: String, cat: Catalog) {
            box.removeAllViews()
            val num = buf.toIntOrNull() ?: 0
            val ch = cat.byNum(num)
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(U.text(activity, 2.6f, bold = true).apply { text = buf }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = U.pxi(1f) })
            if (ch != null) {
                val logo = ImageView(activity)
                Logos.into(logo, ch)
                head.addView(logo, LinearLayout.LayoutParams(U.pxi(4f), U.pxi(2.8f)).apply { rightMargin = U.pxi(0.8f) })
            }
            head.addView(U.text(activity, 1.2f, if (ch != null) 1f else 0.5f).apply { text = ch?.name ?: "—" }, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(head)
            // подсказки: каналы, которые выберутся следующей цифрой
            if (num > 0 && buf.length < 4) {
                for (n in num * 10..num * 10 + 9) {
                    val h = cat.byNum(n) ?: continue
                    box.addView(U.text(activity, 1f, 0.7f).apply { text = "${h.num}  ${h.name}" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = U.pxi(0.35f) })
                }
            }
            box.visibility = View.VISIBLE
            box.bringToFront()
        }

        fun hide() {
            box.visibility = View.GONE
        }
    }

    companion object {
        const val TAG = "IPTV"
        const val BACK_EXIT = 2000L
        /** Проверка устаревания плейлиста и программы, тиков (с) */
        const val AUTO_REFRESH = 15 * 60
        const val SWITCH_DELAY = 700L
        const val OSD_NUMBER = 2000L
        const val SEEK_DELAY = 900L
        const val ADAPTIVE_AFTER = 5 * 60 * 1000L
        const val SEEK_TAP = 30L
        const val SEEK_START = 30f
        const val SEEK_GROW = 120f
        const val SEEK_MAX = 600f
        const val SEEK_GAP = 700L
        val RETRY = listOf(2000L, 5000L, 10000L)
    }
}
