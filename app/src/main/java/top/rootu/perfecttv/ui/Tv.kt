package top.rootu.perfecttv.ui

import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.core.plural
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
import top.rootu.perfecttv.core.Tmdb
import top.rootu.perfecttv.player.CatchupUrl
import top.rootu.perfecttv.player.Playback

/**
 * Оркестратор полноэкранного ТВ: состояние воспроизведения,
 * стек слоёв, клавиши плеера, переключение каналов, архив и перемотка, пауза эфира.
 *
 * Время — unix-секунды [Clock.now]. Позиция в архиве:
 *   HLS с EXT-X-PROGRAM-DATE-TIME (Flussonic и др.) — реальное время кадра из плейлиста
 *     ([Playback.wallClockMs]): не зависит от того, с какого места плеер начал;
 *   иначе position = archive.start + (periodPosition − archive.base) / 1000,
 *     где base — позиция плеера, соответствующая началу ссылки (см. docs/TZ.md, §8).
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
        /** Время кадра берётся из PROGRAM-DATE-TIME плейлиста (а не от base) */
        var pdt = false
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
    // разметка — activity_tv.xml: overlay с каруселью, инфопанелью и номером, поверх — тост
    private val overlay: FrameLayout = root.findViewById(R.id.overlay)
    val info = InfoPanel(root.findViewById(R.id.info_panel))
    private val toast = Toast(root.findViewById(R.id.toast))
    private val bufferingView: View = root.findViewById(R.id.buffering)
    private val showBufferingLater = Runnable { bufferingView.visibility = View.VISIBLE }

    /** Индикатор загрузки потока: с задержкой, чтобы не мигал при коротких подгрузках */
    private fun showBuffering(on: Boolean) {
        main.removeCallbacks(showBufferingLater)
        if (on) {
            if (bufferingView.visibility != View.VISIBLE) main.postDelayed(showBufferingLater, 600)
        } else bufferingView.visibility = View.GONE
    }

    private val osd = Osd(root.findViewById(R.id.osd))
    private val carousel = Carousel(root.findViewById(R.id.carousel))
    val guide = GuideLayer(this)
    private val keys = Keys({ top() })

    private var pending: Channel? = null
    private var backTime = 0L
    private var numBuf = ""
    private var pauseInfo: Long? = null
    private var retry = 0

    init {
        overlay.setOnTouchListener { _, e -> android.util.Log.d("IPTV", "touch ${e.action} layers=${layers.size}"); layers.isEmpty() && gestures.onTouchEvent(e) }
        info.onAction = { a ->
            info.show(if (info.sticky) 0 else Prefs.infoHide)
            when (a) {
                "prev" -> playerLayer.key("left", false, 0)
                "next" -> playerLayer.key("right", false, 0)
                "pause" -> togglePause()
                "more" -> { info.show(0); if (info.more) info.collapse() else expandInfo() }
                "guide" -> openGuide()
                "menu" -> state.channel?.let { openMenu(it) }
                "history" -> openHistory()
            }
        }
        playback.listener = object : Playback.Listener {
            override fun onState(playing: Boolean, buffering: Boolean) {
                if (playing) {
                    retry = 0
                    state.error = false
                    onReady()
                }
                showBuffering(buffering && !playing && !state.error)
                updateInfo()
            }

            override fun onEnded() = onStreamEnded()
            override fun onFix(step: Int) {
                toast.show(str(R.string.fix_software_decoder))
            }
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
            is DataEvent.EpgRead -> {
                guide.refresh()
                updateInfo()
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
            playback.checkStall()
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

    // ================================================================== тач и мышь

    /**
     * Жесты по видео (когда окон нет): тап — инфопанель (на сенсорных — с кнопками),
     * двойной тап или свайп вверх — гид, свайп вниз — недавние каналы,
     * свайп влево/вправо — следующий/предыдущий канал (в архиве — перемотка).
     */
    private val gestures = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true

        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
            if (info.visible) info.hide() else showInfo(true)
            return true
        }

        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
            openGuide()
            return true
        }

        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            val min = U.px(4f)
            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > min) {
                playerLayer.key(if (dx < 0) "right" else "left", false, 0)
                // одиночный свайп — шаг; удержания нет: сразу «отпустить»
                playerLayer.keyUp(if (dx < 0) "right" else "left")
            } else if (Math.abs(dy) > min) {
                if (dy < 0) openGuide() else openHistory()
            }
            return true
        }
    })

    // ================================================================== плейлист

    /** Открыть плейлист: каталог из БД сразу, загрузка в фоне если устарел или пуст */
    fun open(p: Playlist) {
        playlist = p
        Prefs.lastPlaylist = p.id
        catalog = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
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
        pendingExternal?.let { pendingExternal = null; return handleExternal(it) }
        val last = cat.byKey[Prefs.lastChannel(p.id)]
        if (last != null && Prefs.autoplayLast) playChannel(last, Prefs.lastGroup(p.id))
        else {
            // без автозапуска — гид на последнем канале (видео нет: «Назад» в гиде — выход)
            state.groupKey = Prefs.lastGroup(p.id)
            openGuide()
            last?.let { guide.open(it, Prefs.lastGroup(p.id)) }
        }
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
            catalog = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
            if (layers.contains(guide)) guide.reload()
        }
    }

    private fun onRefreshed(p0: Playlist, err: String?) {
        val p = repo.playlist(p0.id) ?: return
        if (playlist?.id != p.id) return
        playlist = p
        if (err != null) {
            if ((catalog?.channels?.size ?: 0) == 0) return showError(err)
            toast.show(str(R.string.the_playlist_was_not_updated) + errorText(err))
            return
        }
        val cur = state.channel
        catalog = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
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
        push(MessageLayer(activity, str(R.string.the_playlist_did_not_load), errorText(code), listOf(str(R.string.retry) to "retry", str(R.string.settings) to "settings")) { a ->
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
        code == "no_url" -> str(R.string.invalid_playlist_address)
        code == "network" -> str(R.string.no_connection_to_the_server)
        code.startsWith("http_") -> str(R.string.server_error) + code.removePrefix("http_")
        code == "format" -> str(R.string.the_file_does_not_look_like_an_m3u)
        code == "empty" -> str(R.string.the_playlist_has_no_channels)
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
            // в архиве (и на паузе эфира у канала с архивом) ←/→ мотают, а не листают каналы — панель при
            // этом скрыта или показана, значения не имеет
            val seekable = state.archive != null && state.channel?.hasArchive == true
            when (name) {
                "left" -> if (seekable) seekStep(-1) else switchRelative(-1)
                "right" -> if (seekable) seekStep(1) else switchRelative(1)
                "pgup" -> switchRelative(1)
                "pgdown" -> switchRelative(-1)
                "up" -> {
                    // показанная инфопанель — закреплённая она или сама скроется по таймеру — закрывается ↑,
                    // иначе — программа/архив или история
                    if (info.visible && info.more) return info.collapse()
                    if (info.visible) return info.hide()
                    if (Prefs.upArchive) state.channel?.let { openSchedule(it, 0) } ?: openGuide() else openHistory()
                }
                "down" -> {
                    // панель уже на экране (в том числе показанная по таймеру после смены канала) —
                    // закрепить её (автоскрытие отменяется) и развернуть описание; скрытая — только закрепить
                    val shown = info.visible
                    if (!info.sticky) showInfo(true)
                    if (shown && !info.more) expandInfo()
                }
            }
        }

        override fun enter() {
            if (numBuf.isNotEmpty()) return applyNumber()
            // при показанной инфопанели OK — пауза/продолжить (при перемотке — применить её сразу)
            if (seek != null) return applySeek()
            if (info.visible) return togglePause()
            openGuide(onArchive = true)
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

    /**
     * «Назад» дважды (в течение 2 с) — выход. Смотрим закрытые каналы, открытые по PIN, —
     * вместо выхода возврат к последнему открытому каналу; недавние каналы — как до закрытых.
     */
    fun exitRequest() {
        val t = System.currentTimeMillis()
        val back = lockedReturn()
        if (t - backTime < BACK_EXIT) {
            backTime = 0
            if (back != null) return leaveLocked(back)
            activity.finish()
            return
        }
        backTime = t
        toast.show(if (back != null) str(R.string.press_back_again_to_leave_locked) else str(R.string.press_back_again_to_exit))
    }

    /**
     * Куда вернуться из закрытого канала по «Назад» ×2: последний незаблокированный канал перед
     * закрытыми, а если его нет (запустились сразу на закрытом) — первый незаблокированный
     * из недавних. Играет не закрытый канал — null (обычный выход).
     */
    private fun lockedReturn(): Channel? {
        val cat = catalog ?: return null
        val cur = state.channel ?: return null
        if (!cat.isLocked(cur)) return null
        beforeLocked?.let { if (!cat.isLocked(it)) return it }
        return (historyBeforeLocked ?: cat.history).mapNotNull { cat.byKey[it] }.firstOrNull { it !== cur && !cat.isLocked(it) }
    }

    /** Выйти из закрытых каналов: история — как до них, PIN снова нужен, играет [ch] */
    private fun leaveLocked(ch: Channel) {
        val cat = catalog
        val gk = beforeLockedGroup
        // история — как до закрытых; если её не запомнили — просто без закрытых каналов
        val h = historyBeforeLocked ?: cat?.history?.filter { k -> cat.byKey[k]?.let { !cat.isLocked(it) } ?: true }
        h?.let { cat?.replaceHistory(it) }
        unlockedKeys.clear()
        pinPassed = false
        beforeLocked = null
        historyBeforeLocked = null
        closeGuide(true)
        playChannel(ch, gk)
    }

    // ================================================================== воспроизведение

    fun hasVideo() = state.channel != null

    /** Текущая позиция воспроизведения (unix-сек) */
    fun position(): Long {
        val a = state.archive ?: return pauseInfo ?: Clock.now()
        if (a.pdt) playback.wallClockMs?.let { return it / 1000 }
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
        // из закрытого канала, открытого по PIN, в другой закрытый — PIN уже вводили, не спрашиваем
        if (!unlocked && ch !== state.channel && catalog?.isLocked(ch) == true && !sessionUnlocked(ch) && !pinPassed) {
            main.removeCallbacks(switchRun)
            pending = null
            val dir = switchDirection
            return askPin(ch.title(), {
                if (!Prefs.pinEveryTime) { unlockedKeys.add(ch.key); unlockedKeys.add("g:" + ch.groupKey) }
                playChannel(ch, groupKey, archive, programEnd, reload, unlocked = true)
            }) {
                // листали ←/→ — следующий в ту же сторону канал без PIN (закрытые пропускаем)
                val skip = if (dir != 0) nextOpen(ch, groupKey, dir) else null
                if (skip != null) playChannel(skip, groupKey)
                else if (!hasVideo()) openGuide() else updateInfo()
            }
        }
        main.removeCallbacks(switchRun)
        pending = null
        // канал выбран в просматриваемом плейлисте — он становится открытым
        if (previewOrigin != null) { previewOrigin = null; playlist?.let { Prefs.lastPlaylist = it.id } }
        // PIN «действует», пока смотрим закрытые каналы подряд; открытый канал — сброс. Канал,
        // который заблокировали прямо во время просмотра, PIN не открывал — флаг не ставится.
        if (ch !== state.channel) {
            val was = pinPassed
            pinPassed = catalog?.isLocked(ch) == true && (unlocked || pinPassed || sessionUnlocked(ch))
            // вошли в закрытые каналы — запомнить, куда вернуться по «Назад» ×2, и историю до них
            if (pinPassed && !was) {
                beforeLocked = state.channel?.takeIf { catalog?.isLocked(it) != true }
                beforeLockedGroup = state.groupKey
                historyBeforeLocked = catalog?.history?.toList()
            }
            if (!pinPassed) {
                beforeLocked = null
                historyBeforeLocked = null
            }
        }
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
                toast.show(str(R.string.the_channel_has_no_archive))
                return
            }
            val now = Clock.now()
            val start = archive
            var dur = (now - start).coerceIn(60, 6 * 3600)
            if (programEnd > start) dur = maxOf(dur, minOf(programEnd, now) - start)
            // Flussonic HLS, передача моложе 6 ч: archive-START-now.m3u8 — EVENT-плейлист от начала
            // до «сейчас», дописывается; недавнюю передачу можно смотреть до края эфира без
            // переподключений. С окном фиксированной длины (конец — «сейчас») ссылка кончалась бы
            // через время, равное отставанию, и менялась бы по кругу. «?event=true» из документации
            // не годится: балансировщики провайдеров отбрасывают параметры и отдают VOD.
            // Плеер начинает EVENT-плейлист у края эфира — onReady ставит его на начало передачи.
            val ready = if (CatchupUrl.flussonicHls(ch.catchupType ?: "", tpl) && now - start < 6 * 3600) tpl.replace("\${duration}", "now") else tpl
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

    /** Играет закрытый канал, открытый вводом PIN (или переходом из такого же) */
    private var pinPassed = false
    /** Последний открытый канал перед закрытыми (и его категория), история до закрытых */
    private var beforeLocked: Channel? = null
    private var beforeLockedGroup: String? = null
    private var historyBeforeLocked: List<String>? = null

    /** Следующий от [from] в сторону [dir] канал категории, для которого PIN не нужен; нет — null */
    private fun nextOpen(from: Channel, groupKey: String?, dir: Int): Channel? {
        val cat = catalog ?: return null
        val list = (cat.group(groupKey) ?: cat.groupOf(from, null)).channels
        val n = list.size
        val i = list.indexOf(from)
        if (n == 0 || i < 0) return null
        for (k in 1 until n) {
            val c = list[((i + dir * k) % n + n) % n]
            if (c === state.channel || !cat.isLocked(c) || sessionUnlocked(c)) return c
        }
        return null
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
        layer = PinLayer(activity, PinLayer.Mode.CREATE, str(R.string.new_pin), onDone = { remove(layer!!); toast.show(str(R.string.pin_set_change_or_remove_it_in)); ok() },
            onCancel = { remove(layer!!) })
        push(layer)
    }

    private var lastUrl = ""
    private var forceHls = false
    private var scoreFor: Channel? = null
    private val scoreRun = Runnable {
        val ch = scoreFor
        // очки копятся всегда, и при выключенной адаптивной сортировке: после включения порядок готов,
        // а ряд «Популярные» на главном экране берёт их же
        if (ch != null && state.channel === ch) catalog?.addScore(ch)
    }

    fun playArchive(ch: Channel, start0: Long, programEnd: Long = 0) {
        if (!ch.hasArchive) return toast.show(str(R.string.the_channel_has_no_archive))
        val start = maxOf(start0, Clock.now() - ch.archiveDays * 86400L)
        val gk = if (state.channel === ch) state.groupKey else null
        if (start >= Clock.now() - 30) return playChannel(ch, gk)
        playChannel(ch, gk, start, programEnd)
    }

    /** Первые данные: привязать позицию плеера к началу ссылки архива */
    private fun onReady() {
        val a = state.archive ?: return
        if (a.base != null) return
        a.base = playback.periodPositionMs
        if (a.timeshift) return
        // HLS с PROGRAM-DATE-TIME: время кадра — из плейлиста. Правдоподобно (окно архива
        // в пределах суток до «сейчас») — пользуемся им и, если плеер начал не с нужного места
        // (растущий EVENT-плейлист плеер начинает ближе к краю эфира), ставим его на начало.
        val now = Clock.now()
        val w = playback.wallClockMs?.takeIf { it / 1000 in (a.start - 86400)..(now + 120) }
        if (w == null) {
            // без PROGRAM-DATE-TIME «растущий» (EVENT) плейлист плеер тоже начинает у края эфира:
            // ставим в начало окна — оно и есть начало ссылки
            if (playback.live && playback.position > 5000) {
                Log.i(TAG, "archive: live playlist without PDT, seek to window start")
                watch = null
                a.base = playback.windowOffsetMs
                playback.seekTo(0)
            }
            return
        }
        a.pdt = true
        val target = a.start * 1000 - playback.windowStartTimeMs
        if (Math.abs(w / 1000 - a.start) > 8 && target >= 0 && target < playback.windowDurationMs - 5000) {
            Log.i(TAG, "archive: start ${w / 1000 - a.start} s off, seek to start")
            watch = null
            playback.seekTo(target)
        }
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
        showBuffering(false)
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
        if (a.base != null && seek == null && position() >= Clock.now() - 3) {
            // позиция «догнала» эфир — архив считается законченным и ←/→ снова листают каналы
            Log.i(TAG, "archive: position caught up with live, back to live (base=${a.base}, position=${position()}, now=${Clock.now()})")
            state.archive = null
        }
    }

    /** pos — позиция плеера, shown — показанное время (с) на момент at */
    private class Watch(var pos: Long, var at: Long, var moved: Long, var shown: Long)

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
            watch = Watch(pos, t, t, position())
            return
        }
        val dWall = t - w.at
        val dPos = pos - w.pos
        if (dPos > dWall + 5000) {
            val expected = w.shown + dWall / 1000
            Log.i(TAG, "stream jumped, restore $expected")
            watch = null
            return restorePosition(expected)
        }
        w.pos = pos
        w.at = t
        w.shown = position()
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
            // ⏹: в архиве — вернуться в эфир, в эфире — как «Назад»
            "stop" -> return if (state.archive != null) state.channel?.let { playChannel(it, state.groupKey) } ?: Unit else exitRequest()
            "guide" -> return openGuide()
            "info" -> return if (info.visible) info.hide() else showInfo(true)
            "menu" -> return state.channel?.let { openMenu(it) } ?: Unit
            "history" -> return openHistory()
            "search" -> return openSearch()
            "teletext" -> return openTeletext()
            "red", "green", "yellow", "blue" -> return  // цветные кнопки — в телетексте
        }
        main.postDelayed({ showInfo() }, 50)
    }

    // ================================================================== ←/→, цифры

    private val switchRun = Runnable {
        val next = pending ?: return@Runnable
        val gk = switchGroup
        pending = null
        switchDirection = pendingDir
        playChannel(next, gk)
        switchDirection = 0
        main.postDelayed({ carousel.hide() }, 1500)
    }
    private var switchGroup: String? = null
    // направление листания ←/→ (для отмены PIN: включить следующий в ту же сторону)
    private var pendingDir = 0
    private var switchDirection = 0

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
        pendingDir = dir
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
            val target = if (a.pdt && playback.windowStartTimeMs != C.TIME_UNSET) s.target * 1000 - playback.windowStartTimeMs
            else (s.target - a.start) * 1000 + a.base!! - playback.windowOffsetMs
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
        val next = repo.after(ch, p?.stop ?: ref)
        info.update(InfoPanel.Model(
            channel = ch,
            program = p,
            next = next,
            next2 = next?.let { repo.after(ch, it.stop) },
            archive = same && state.archive != null,
            position = pos,
            ahead = if (same) bufferAhead() else 0,
            seek = sk,
            height = if (same) state.height else 0,
            decoder = if (same) playback.videoSoftware?.let { if (it) "SW" else "HW" } else null,
            teletext = same && playback.teletext.hasPages(),
            audios = if (same) playback.trackCount(androidx.media3.common.C.TRACK_TYPE_AUDIO) else 0,
            subtitles = if (same) playback.trackCount(androidx.media3.common.C.TRACK_TYPE_TEXT) else 0,
            paused = same && playback.paused,
            error = same && state.error
        ))
        // передача сменилась при раскрытом описании — обновить описание
        if (info.more && same && moreKey != ch.key + ":" + (p?.start ?: 0)) expandInfo()
        if (show) info.show(Prefs.infoHide)
    }

    // какая передача раскрыта (ответ TMDB для другой — не применять)
    private var moreKey = ""

    /**
     * ↓↓: описание передачи под инфопанелью. Сразу — EPG и логотип канала в области баннера,
     * затем (если задан ключ TMDB и нашлось) баннер, год, рейтинг и описание TMDB.
     */
    private fun expandInfo() {
        val ch = state.channel ?: return
        val p = repo.at(ch, position())
        val key = ch.key + ":" + (p?.start ?: 0)
        moreKey = key
        // «Далее на канале»: блок не пустой и без описания
        val upcoming = repo.programmes(ch).asSequence().filter { it.start >= (p?.stop ?: position()) }.take(4).toList()
        if (p == null) {
            info.setMore(str(R.string.no_programme), "", ch = ch, upcoming = upcoming)
            return
        }
        val tags = arrayListOf(Fmt.dayTimeRange(p.start, p.stop), p.category, p.subTitle).filter { it.isNotEmpty() }.toMutableList()
        // картинка: из EPG, затем TMDB; нет или не загрузилась — логотип канала
        info.setMore(tags.joinToString(" · "), p.desc, p.icon, ch, upcoming)
        Tmdb.find(p.title, p.desc) { r ->
            if (r == null || !info.more || moreKey != key) return@find
            if (r.year.isNotEmpty()) tags.add(r.year)
            if (r.vote > 0) tags.add("★ " + r.vote)
            if (r.original.isNotEmpty() && r.original != r.title) tags.add(r.original)
            // описание TMDB — если в программе его нет или совпадение однозначно
            val text = if (r.overview.isNotEmpty() && (p.desc.isEmpty() || r.unique)) r.overview else p.desc
            info.setMore(tags.joinToString(" · "), text, r.backdrop.ifEmpty { r.poster }.ifEmpty { p.icon }, ch, upcoming)
        }
    }

    // ================================================================== гид и окна

    /** [onArchive] — OK в архиве: в видах со списком программ фокус сразу на играющей передаче (в сетке — каналы) */
    fun openGuide(toGrid: Boolean = false, onArchive: Boolean = false) {
        info.hide()
        if (!layers.contains(guide)) {
            layers.toList().forEach { remove(it) }
            push(guide)
        }
        guide.open(state.channel, state.groupKey, toGrid)
        if (onArchive && state.archive != null) guide.focusArchive()
    }

    /** Закрыть гид. Без видео гид закрывать некуда — это запрос на выход */
    fun closeGuide(silent: Boolean = false) {
        if (!layers.contains(guide)) return
        if (!silent && !hasVideo()) return exitRequest()
        revertPlaylistPreview()
        layers.toList().reversed().forEach { if (it !== guide) remove(it) }
        remove(guide)
    }

    /** Поиск по каналам (без скрытых) и программе; открывается поверх плеера или гида */
    fun openSearch(query: String = "") {
        val cat = catalog ?: return
        if (layers.any { it is SearchLayer }) return
        var layer: SearchLayer? = null
        val leave = { layers.toList().reversed().forEach { if (it !== guide) remove(it) } }
        val speech = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val canSpeak = speech.resolveActivity(activity.packageManager) != null
        layer = SearchLayer(activity, repo,
            channels = { cat.group(Catalog.ALL_KEY)?.channels ?: emptyList() },
            onChannel = { ch -> leave(); playChannel(ch) },
            onProgramme = { ch, start, stop ->
                val now = Clock.now()
                when {
                    start <= now && now < stop -> { leave(); playChannel(ch) }
                    stop <= now && ch.hasArchive -> { leave(); playArchive(ch, start, stop) }
                    // будущая — карточка с кнопкой «Напомнить»
                    else -> openDetails(ch, Cell(start, stop, repo.at(ch, start)))
                }
            },
            onLong = { ch -> openSchedule(ch, 0) },
            onVoice = if (!canSpeak) null else { tag ->
                // язык — явно и дважды (часть распознавателей читает только предпочтение), иначе берётся английский
                val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                try { activity.startActivityForResult(i, REQ_VOICE) } catch (e: Exception) { toast.show(str(R.string.voice_unavailable)) }
            },
            onBack = { remove(layer!!) })
        push(layer)
        if (query.isNotEmpty()) layer.setQuery(query)
    }

    /** Результат голосового ввода (TvActivity.onActivityResult) — в открытый поиск */
    fun onVoiceResult(text: String) {
        (layers.lastOrNull { it is SearchLayer } as SearchLayer?)?.setQuery(text)
    }

    fun openHistory() {
        val cat = catalog ?: return
        val now = Clock.now()
        info.hide()
        var layer: ListLayer? = null
        val items = cat.recent(state.channel).map { ch ->
            val p = repo.at(ch, now)
            ListLayer.Item(ch.title(), p?.title ?: "", ch, p?.let { Fmt.progress(it.start, it.stop, now) } ?: -1f)
        }
        layer = ListLayer(activity, str(R.string.recent_channels), items,
            onSelect = { it.channel?.let { ch -> remove(layer!!); playChannel(ch) } },
            onLong = { it.channel?.let { ch -> openMenu(ch) } },
            onBack = { remove(layer!!) },
            empty = str(R.string.history_is_empty))
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
        val views = if (ch != null) listOf(str(R.string.hint_programme), str(R.string.reminders)) else emptyList()
        val make = { k: Int -> if (k == 0 && ch != null) archiveContent(ch, close) else remindersContent() }
        // «Напоминания · 2»: счётчик обновляется вместе со списком
        val label = { k: Int -> if (k == 1) reminders.all().size.let { n -> if (n > 0) views[1] + " · " + n else views[1] } else views[k] }
        layer = ScheduleLayer(activity, views, if (ch != null) view else 1, label, make) { close() }
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
                    past = r.missed(Clock.now()), archive = r.missed(Clock.now()),
                    desc = r.desc, range = Fmt.dayTimeRange(r.start, r.stop)
                ))
            }
            ScheduleLayer.Built(rows, tabs)
        }
        return ScheduleLayer.Content(
            channel = null,
            icon = Icons.Kind.REMIND,
            title = str(R.string.reminders),
            subtitle = str(R.string.programmes_to_remind_about),
            empty = str(R.string.no_reminders_add_them_in_the_card_of),
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
            r.missed(now) -> str(R.string.missed_watch_from_archive)
            left > 0 -> str(R.string.starts_in) + Fmt.until(left)
            else -> str(R.string.on_air)
        }
    }

    /** Карточка запланированной передачи: «Удалить напоминание» (+ «Смотреть», если уже идёт) */
    private fun reminderDetails(r: Reminders.Reminder) {
        val buttons = ArrayList<Pair<String, String>>()
        val now = Clock.now()
        if (r.missed(now)) buttons.add(str(R.string.watch_from_archive) to "watch")
        else if (r.start <= now) buttons.add(str(R.string.watch) to "watch")
        buttons.add(str(R.string.delete_reminder) to "remove")
        var layer: DetailsLayer? = null
        layer = DetailsLayer(activity, Channel.stub(r.chName, r.chNum, r.logo),
            kicker = (if (r.missed(now)) str(R.string.missed_in_archive_for) + Fmt.until(r.start + r.archiveDays * 86400L - now) else str(R.string.reminder) + whenText(r)) +
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
        val p = repo.playlist(r.playlistId) ?: return toast.show(str(R.string.the_playlist_was_deleted))
        if (playlist?.id != p.id) {
            pendingPlay = r
            switchPlaylist(p)
            return
        }
        val ch = catalog?.byKey?.get(r.chKey) ?: return toast.show(str(R.string.the_channel_is_no_longer_in_the))
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

    /** Запуск извне: канал с главного экрана ([channelKey]) или голосовая команда ([query]) */
    class External(val playlistId: Long, val channelKey: String?, val query: String?)

    private var pendingExternal: External? = null

    fun openExternal(e: External) {
        val p = (if (e.playlistId > 0) repo.playlist(e.playlistId) else null) ?: playlist ?: return
        if (playlist?.id != p.id) {
            pendingExternal = e
            return switchPlaylist(p)
        }
        // каталог ещё грузится — выполнится в startFromLast
        if (catalog == null) { pendingExternal = e; return }
        handleExternal(e)
    }

    private fun handleExternal(e: External) {
        val cat = catalog ?: return
        layers.toList().reversed().forEach { if (it !== guide) remove(it) }
        e.channelKey?.let { k -> return cat.byKey[k]?.let { playChannel(it) } ?: toast.show(str(R.string.the_channel_is_no_longer_in_the)) }
        val q = e.query?.trim().orEmpty()
        if (q.isEmpty()) return openSearch()
        // голос: номер или название канала — включить, иначе — поиск с этим запросом
        val words = q.lowercase().split(' ').filter { it.isNotEmpty() }
        val all = cat.group(Catalog.ALL_KEY)?.channels ?: cat.channels
        val match = q.toIntOrNull()?.let { n -> all.firstOrNull { it.num == n } }
            ?: all.firstOrNull { it.name.equals(q, true) }
            ?: all.filter { ch -> words.all { ch.name.contains(it, true) } }.minByOrNull { it.name.length }
        if (match != null) playChannel(match) else openSearch(q)
    }

    /** Обновить ряды на главном экране Android TV (при уходе из приложения) */
    fun updateHome() {
        val cat = catalog ?: return
        val last = state.channel
        // скрытые и заблокированные каналы на главный экран не выносим — он виден всем в комнате
        fun open(l: List<Channel>) = l.filter { !cat.isHidden(it) && !cat.isLocked(it) }
        val now = Clock.now()
        fun rows(l: List<Channel>) = open(l).map { repo.epgIcon(it); top.rootu.perfecttv.data.Home.Row(it, repo.at(it, now)) }
        val favs = rows(cat.favGroup.channels)
        val popular = rows(cat.popular())
        val lastRow = last?.let { rows(listOf(it)).firstOrNull() }
        val ctx = activity.applicationContext
        Thread { top.rootu.perfecttv.data.Home.update(ctx, favs, popular, lastRow) }.start()
    }

    fun openTeletext() {
        val dec = playback.teletext
        if (!dec.declared && dec.numbers().isEmpty()) return toast.show(str(R.string.no_teletext))
        val nums = dec.numbers()
        val start = if (nums.isEmpty() || 0x100 in nums) 0x100 else nums.first()
        var layer: TeletextLayer? = null
        layer = TeletextLayer(activity, dec, start, position = { playback.periodPositionMs * 1000 }, onMenu = { openTeletextMenu(layer!!) }, onClose = { closeMenu(); remove(layer!!) })
        push(layer)
    }

    // меню телетекста (удерж. OK, MENU): вид и выход
    private fun openTeletextMenu(t: TeletextLayer) {
        closeMenu()
        val page = {
            MenuLayer.Page(str(R.string.teletext), str(R.string.ttx_page_n, "%03X".format(t.page)), sections = listOf(
                MenuLayer.Section(str(R.string.ttx_sec_view), listOf(
                    MenuLayer.Item(str(R.string.ttx_keypad), str(if (t.padVisible) R.string.ttx_shown else R.string.ttx_hidden), keep = true) { t.toggleKeypad() },
                    MenuLayer.Item(str(R.string.ttx_background), str(if (t.mix) R.string.ttx_bg_video else R.string.ttx_bg_black), keep = true) { t.toggleMix() },
                    MenuLayer.Item(str(R.string.ttx_reveal), onOff(t.reveal), keep = true) { t.toggleReveal() }
                )),
                MenuLayer.Section(str(R.string.ttx_sec_pages), listOf(
                    MenuLayer.Item(str(R.string.ttx_subpages), str(if (t.hold) R.string.ttx_subpages_stop else R.string.ttx_subpages_auto), keep = true) { t.toggleHold() },
                    MenuLayer.Item(str(R.string.ttx_exit)) { t.close() }
                ))
            ))
        }
        val layer = MenuLayer(activity, page, ::closeMenu)
        menu = layer
        push(layer)
    }

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
                    playing = playing, remind = c.start > tm && reminders.has(ch, c.start), payload = c,
                    desc = c.program?.desc ?: "", range = Fmt.dayTimeRange(c.start, c.end),
                    // что сделает OK — как в onSelect ниже
                    action = str(when {
                        live -> R.string.watch_live
                        c.end <= tm && ch.hasArchive && c.start >= archFrom - Cells.SLOT -> R.string.watch_from_archive
                        else -> R.string.about
                    }),
                    progress = if (live) Fmt.progress(c.start, c.end, tm) else -1f
                ))
            }
            ScheduleLayer.Built(rows, tabs, focusId)
        }
        return ScheduleLayer.Content(
            channel = ch,
            icon = null,
            title = ch.title(),
            subtitle = ch.group + if (ch.hasArchive) " · " + str(R.string.archive_x_d, ch.archiveDays) else "",
            empty = str(R.string.no_programme),
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

    /** Карточка передачи: кнопки по времени и архиву канала */
    fun openDetails(ch: Channel, cell: Cell) {
        val now = Clock.now()
        val buttons = ArrayList<Pair<String, String>>()
        val inArchive = ch.hasArchive && cell.start >= now - ch.archiveDays * 86400L
        if (cell.end <= now) {
            if (inArchive) buttons.add(str(R.string.watch) to "archive")
        } else if (cell.start <= now) {
            if (ch.hasArchive) buttons.add(str(R.string.watch_from_start) to "archive")
            if (state.channel === ch && state.archive != null) buttons.add(str(R.string.watch_live) to "live")
            else if (state.channel !== ch) buttons.add(str(R.string.watch) to "live")
        } else if (cell.program != null) {
            // будущая передача — напоминание
            buttons.add(if (reminders.has(ch, cell.start)) str(R.string.delete_reminder) to "unremind" else str(R.string.remind_2) to "remind")
        }
        val p = cell.program
        var layer: DetailsLayer? = null
        layer = DetailsLayer(activity, ch,
            kicker = listOfNotNull(p?.category?.takeIf { it.isNotEmpty() }, if (cell.start <= now && now < cell.end) str(R.string.on_air_now) else null).joinToString(" · "),
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
                        toast.show(str(R.string.i_will_remind) + (if (Prefs.remindBefore > 0) str(R.string.x_min_before_the_start, Prefs.remindBefore) else str(R.string.when_the_programme_starts)))
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
    private var menu: MenuLayer? = null

    private fun closeMenu() {
        menu?.let { remove(it) }
        menu = null
    }

    /**
     * Меню канала — шторка справа (MenuLayer): до четырёх плиток частых действий, ниже — группы
     * «Просмотр», «Канал», «Приложение»; редкое — на втором уровне. Что не поместилось
     * в плитки, идёт первыми пунктами «Просмотра».
     */
    fun openMenu(ch: Channel, guide: Boolean = false) {
        closeMenu()
        val layer = MenuLayer(activity, { menuPage(ch, guide) }, ::closeMenu)
        menu = layer
        push(layer)
    }

    /** Названия видов гида (меню канала, настройки) */
    private fun guideModeName(m: Int) = str(when (m) {
        1 -> R.string.view_channels_programme
        2 -> R.string.view_categories_channels
        3 -> R.string.view_channels
        else -> R.string.tv_guide
    })

    private fun onOff(v: Boolean) = str(if (v) R.string.on else R.string.off)

    private fun menuPage(ch: Channel, guide: Boolean): MenuLayer.Page {
        val cat = catalog ?: return MenuLayer.Page(ch.title(), sections = emptyList())
        val p = repo.at(ch, Clock.now())
        val current = state.channel === ch
        val fav = cat.isFavorite(ch)
        fun act(a: String): () -> Unit = { menuAction(ch, a) }

        // частые действия по убыванию важности: первые четыре — плитки, остальные — пункты
        val quick = ArrayList<MenuLayer.Tile>()
        if (guide) quick.add(MenuLayer.Tile(R.drawable.ic_play, str(R.string.watch), act("play")))
        if (current && state.archive != null) quick.add(MenuLayer.Tile(R.drawable.ic_live, str(R.string.tile_live), act("live")))
        if (ch.hasArchive && p != null) quick.add(MenuLayer.Tile(R.drawable.ic_restart, str(R.string.tile_restart), act("restart")))
        quick.add(MenuLayer.Tile(R.drawable.ic_list, str(R.string.hint_programme), act("archive")))
        quick.add(MenuLayer.Tile(R.drawable.ic_fav, str(if (fav) R.string.tile_fav_remove else R.string.tile_fav_add), act("fav")))
        quick.add(MenuLayer.Tile(R.drawable.ic_recent, str(R.string.hint_recent), act("history")))

        val viewing = ArrayList<MenuLayer.Item>()
        quick.drop(4).forEach { t -> viewing.add(MenuLayer.Item(t.title, run = t.run)) }
        if (p != null) viewing.add(MenuLayer.Item(str(R.string.about), run = act("about")))
        if (current) {
            val audio = playback.tracks(C.TRACK_TYPE_AUDIO)
            val text = playback.tracks(C.TRACK_TYPE_TEXT)
            if (audio.size > 1 || text.isNotEmpty()) {
                val now = audio.firstOrNull { it.selected }?.label ?: ""
                viewing.add(MenuLayer.Item(str(R.string.audio_and_subtitles), now, sub = { tracksPage(ch) }))
            }
            if (playback.teletext.declared || playback.teletext.hasPages()) viewing.add(MenuLayer.Item(str(R.string.teletext)) { openTeletext() })
            if (playback.fixStep > 0) viewing.add(MenuLayer.Item(str(R.string.fix_reset)) { playback.resetFix(ch, lastUrl) })
            viewing.add(MenuLayer.Item(str(R.string.picture_format), aspectName(), keep = true) { Prefs.aspect = (Prefs.aspect + 1) % 3; applyAspect() })
        }
        viewing.add(MenuLayer.Item(str(R.string.sleep_timer), sleepValue(), keep = true) { cycleSleep() })

        val channel = ArrayList<MenuLayer.Item>()
        if (fav && this.guide.groupKey() == Catalog.FAV_KEY && layers.contains(this.guide)) {
            channel.add(MenuLayer.Item(str(R.string.move_up), keep = true, run = act("fav_up")))
            channel.add(MenuLayer.Item(str(R.string.move_down), keep = true, run = act("fav_down")))
        }
        channel.add(MenuLayer.Item(str(R.string.hide_channel), run = act("hide")))
        val pinOn = Prefs.pin.isNotEmpty()
        val chLocked = pinOn && cat.isChannelLocked(ch)
        channel.add(MenuLayer.Item(str(if (chLocked) R.string.unlock_channel else R.string.lock_channel), str(R.string.by_pin), run = act(if (chLocked) "unlock" else "lock")))
        if (ch.group.isNotEmpty()) channel.add(MenuLayer.Item(str(R.string.category_x, ch.group), sub = {
            val gLocked = pinOn && cat.isGroupLocked(ch.groupKey)
            MenuLayer.Page(ch.group, str(R.string.category), sections = listOf(MenuLayer.Section("", listOf(
                MenuLayer.Item(str(R.string.hide_category), run = act("hide_group")),
                MenuLayer.Item(str(if (gLocked) R.string.unlock_category else R.string.lock_category), str(R.string.by_pin), run = act(if (gLocked) "unlock_group" else "lock_group"))
            ))))
        }))

        val app = ArrayList<MenuLayer.Item>()
        app.add(MenuLayer.Item(str(R.string.search), run = act("search")))
        if (!guide) app.add(MenuLayer.Item(str(R.string.tv_guide), run = act("guide")))
        app.add(MenuLayer.Item(str(R.string.reminders), reminders.all().size.takeIf { it > 0 }?.toString() ?: "", run = act("reminders")))
        app.add(MenuLayer.Item(str(R.string.channel_list), sub = {
            val l = arrayListOf(
                MenuLayer.Item(str(R.string.view), guideModeName(Prefs.guideMode), keep = true, run = act("view")),
                MenuLayer.Item(str(R.string.adaptive_sorting), onOff(Prefs.adaptiveSort), keep = true, run = act("adaptive"))
            )
            if (cat.hiddenCount() > 0) l.add(MenuLayer.Item(str(R.string.hidden_2), cat.hiddenCount().toString(), run = act("hidden")))
            MenuLayer.Page(str(R.string.channel_list), sections = listOf(MenuLayer.Section("", l)))
        }))
        app.add(MenuLayer.Item(str(R.string.playlist_and_programme), playlist?.name ?: "", sub = {
            val l = ArrayList<MenuLayer.Item>()
            if (repo.playlists().size > 1) l.add(MenuLayer.Item(str(R.string.playlist), playlist?.name ?: "", run = act("playlist")))
            l.add(MenuLayer.Item(str(R.string.reload_playlist), run = act("reload")))
            l.add(MenuLayer.Item(str(R.string.reload_programme), run = act("reload_epg")))
            MenuLayer.Page(str(R.string.playlist_and_programme), playlist?.name ?: "", sections = listOf(MenuLayer.Section("", l)))
        }))
        app.add(MenuLayer.Item(str(R.string.controls), run = act("help")))
        app.add(MenuLayer.Item(str(R.string.settings), run = act("settings")))

        return MenuLayer.Page(ch.title(), p?.let { it.title + " · " + Fmt.timeRange(it.start, it.stop) } ?: "", ch, quick.take(4), listOf(
            MenuLayer.Section(str(R.string.viewing), viewing),
            MenuLayer.Section(str(R.string.channel), channel),
            MenuLayer.Section(str(R.string.app), app)
        ))
    }

    /** «Звук и субтитры»: дорожки потока; выбор запоминается как предпочитаемый язык */
    private fun tracksPage(ch: Channel): MenuLayer.Page {
        fun label(t: Playback.Track, i: Int) = t.label.ifEmpty { str(R.string.track_x, i + 1) }
        fun mark(on: Boolean) = if (on) str(R.string.selected_mark) else ""
        val audio = playback.tracks(C.TRACK_TYPE_AUDIO)
        val text = playback.tracks(C.TRACK_TYPE_TEXT)
        // выбор применяется не мгновенно — страницу перестроить, когда плеер переключит дорожку
        fun pick(type: Int, t: Playback.Track?) {
            playback.selectTrack(type, t)
            if (type == C.TRACK_TYPE_AUDIO) Prefs.audioLang = t?.language ?: "" else Prefs.subtitleLang = t?.language ?: ""
            main.postDelayed({ menu?.refresh() }, 400)
        }
        val a = audio.mapIndexed { i, t -> MenuLayer.Item(label(t, i), mark(t.selected), keep = true) { pick(C.TRACK_TYPE_AUDIO, t) } }
        val off = text.none { it.selected }
        val s = arrayListOf(MenuLayer.Item(str(R.string.off), mark(off), keep = true) { pick(C.TRACK_TYPE_TEXT, null) })
        text.forEachIndexed { i, t -> s.add(MenuLayer.Item(label(t, i), mark(t.selected), keep = true) { pick(C.TRACK_TYPE_TEXT, t) }) }
        return MenuLayer.Page(str(R.string.audio_and_subtitles), ch.title(), sections = listOf(
            MenuLayer.Section(str(R.string.audio), a.ifEmpty { listOf(MenuLayer.Item(str(R.string.no_tracks), enabled = false)) }),
            MenuLayer.Section(str(R.string.subtitles), if (text.isEmpty()) listOf(MenuLayer.Item(str(R.string.no_tracks), enabled = false)) else s)
        ))
    }

    // ---------------------------------------------------------- формат кадра, таймер сна

    private val playerView: androidx.media3.ui.PlayerView = root.findViewById(R.id.player)

    private fun aspectName() = str(when (Prefs.aspect) { 1 -> R.string.format_stretch; 2 -> R.string.format_zoom; else -> R.string.auto })

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyAspect() {
        playerView.resizeMode = when (Prefs.aspect) {
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // таймер сна — таймер интерфейса (uptime), а не время передач
    private var sleepAt = 0L
    private val sleepWarn = Runnable { toast.show(str(R.string.sleep_timer_in_a_minute)) }
    private val sleepRun = Runnable { sleepAt = 0; activity.finish() }

    private fun sleepValue(): String {
        if (sleepAt == 0L) return str(R.string.off)
        return str(R.string.x_min, (sleepAt - android.os.SystemClock.uptimeMillis() + 59_999) / 60_000)
    }

    /** Следующее значение: выкл. → 15 → 30 → 45 → 60 → 90 → 120 мин → выкл. */
    private fun cycleSleep() {
        val left = if (sleepAt == 0L) 0 else ((sleepAt - android.os.SystemClock.uptimeMillis() + 59_999) / 60_000).toInt()
        val next = SLEEP_STEPS.firstOrNull { it > left } ?: 0
        main.removeCallbacks(sleepWarn)
        main.removeCallbacks(sleepRun)
        sleepAt = 0
        if (next == 0) return
        sleepAt = android.os.SystemClock.uptimeMillis() + next * 60_000L
        main.postDelayed(sleepWarn, (next - 1) * 60_000L)
        main.postDelayed(sleepRun, next * 60_000L)
    }

    init {
        applyAspect()
        playback.preferLanguages(Prefs.audioLang, Prefs.subtitleLang)
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
            "search" -> openSearch()
            "reminders" -> openReminders(ch)
            "help" -> openHelp()
            "fav" -> { cat.toggleFavorite(ch); guide.reload() }
            "fav_up" -> { cat.moveFavorite(ch, -1); guide.reload() }
            "fav_down" -> { cat.moveFavorite(ch, 1); guide.reload() }
            "hide" -> { guide.reloadAfterHide({ it.key == ch.key }, null) { cat.hideChannel(ch) }; toast.show(str(R.string.channel_hidden)) }
            "hide_group" -> { guide.reloadAfterHide({ it.group == ch.group }, ch.group) { cat.hideGroup(ch.group) }; toast.show(str(R.string.category_hidden)) }
            "hidden" -> openHidden()
            "lock" -> ensurePin { cat.setLocked("channel", ch.key, true); unlockedKeys.remove(ch.key); guide.refresh(); toast.show(str(R.string.channel_locked)) }
            "lock_group" -> ensurePin { cat.setLocked("group", ch.groupKey, true); unlockedKeys.remove("g:" + ch.groupKey); guide.refresh(); toast.show(str(R.string.category_locked)) }
            "unlock" -> askPin(str(R.string.unlock_channel), { cat.setLocked("channel", ch.key, false); guide.refresh() })
            "unlock_group" -> askPin(str(R.string.unlock_category), { cat.setLocked("group", ch.groupKey, false); guide.refresh() })
            "adaptive" -> { Prefs.adaptiveSort = !Prefs.adaptiveSort; cat.applyView(); guide.reload() }
            "view" -> { Prefs.guideMode = (Prefs.guideMode + 1) % 4; guide.setView(Prefs.guideMode) }
            "playlist" -> openPlaylists()
            "reload" -> playlist?.let { p -> toast.show(str(R.string.reloading_playlist)); repo.refresh(p, true) { err -> onRefreshed(p, err) } }
            "reload_epg" -> playlist?.let { p -> toast.show(str(R.string.reloading_programme)); repo.refreshEpg(p, true) }
            "settings" -> activity.openSettings()
        }
    }

    private fun openHidden() {
        val cat = catalog ?: return
        val items = cat.hiddenGroups.map { ListLayer.Item(str(R.string.category_2) + it.value, str(R.string.ok_restore), action = "group:" + it.key) } +
            cat.hiddenChannels.map { ListLayer.Item(it.value, str(R.string.ok_restore), action = "channel:" + it.key) }
        var layer: ListLayer? = null
        layer = ListLayer(activity, str(R.string.hidden_2), items, onSelect = {
            val kind = it.action.substringBefore(':')
            cat.unhide(kind, it.action.substringAfter(':'))
            guide.reload()
            remove(layer!!)
            if (cat.hiddenCount() > 0) openHidden()
        }, onBack = { remove(layer!!) }, empty = str(R.string.nothing_is_hidden))
        push(layer)
    }

    private fun openPlaylists() {
        val list = repo.playlists()
        var layer: ListLayer? = null
        layer = ListLayer(activity, str(R.string.playlist), list.map { ListLayer.Item(it.name, if (it.channelsCount > 0) plural(R.plurals.channels, it.channelsCount, it.channelsCount) else it.url, action = it.id.toString()) },
            onSelect = { it ->
                remove(layer!!)
                val p = list.first { p -> p.id.toString() == it.action }
                if (p.id != playlist?.id) switchPlaylist(p)
            }, onBack = { remove(layer!!) }, start = list.indexOfFirst { it.id == playlist?.id })
        push(layer)
    }

    /**
     * Перечитать каталог из БД (вернулись из настроек: скрытое, очки, порядок могли измениться),
     * не прерывая воспроизведение — канал находится по ключу.
     */
    fun reloadCatalog() {
        val p = repo.playlist(playlist?.id ?: return) ?: return
        playlist = p
        val cur = state.channel
        catalog = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
        cur?.let { c -> catalog?.byKey?.get(c.key)?.let { state.channel = it } }
        if (layers.contains(guide)) guide.reload()
    }

    /** Плейлист, который был открыт до просмотра другого в колонке плейлистов гида (null — просмотра нет) */
    private var previewOrigin: Playlist? = null

    /**
     * Просмотр плейлиста из колонки плейлистов гида: каналы и категории справа меняются сразу, а
     * видео, слои и история остаются. Закреплается, когда включают канал ([playChannel]); иначе при
     * закрытии гида или «Назад» в колонке возвращается прежний ([revertPlaylistPreview]).
     */
    fun previewPlaylist(p: Playlist) {
        if (p.id == playlist?.id) return
        if (previewOrigin == null) previewOrigin = playlist
        playlist = p
        val cat = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
        catalog = cat
        if (cat.channels.isEmpty() || repo.isStale(p)) repo.refresh(p, false) { err -> onRefreshed(p, err) }
        else repo.refreshEpg(p, false)
    }

    /** Вернуть плейлист, открытый до просмотра (каталог перечитывается; гид обновляет вызывающий) */
    fun revertPlaylistPreview(): Boolean {
        val o = previewOrigin ?: return false
        previewOrigin = null
        val p = repo.playlist(o.id) ?: o
        playlist = p
        catalog = repo.loadCatalog(p, str(R.string.favourites), str(R.string.all_channels))
        state.channel?.let { c -> catalog?.byKey?.get(c.key)?.let { state.channel = it } }
        return true
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

    /** Набор номера (osd_number.xml): номер, логотип и название, подсказки для следующей цифры */
    private inner class Osd(private val box: View) {
        private val num: TextView = box.findViewById(R.id.osd_num)
        private val logo: ImageView = box.findViewById(R.id.osd_logo)
        private val name: TextView = box.findViewById(R.id.osd_name)
        private val hints: LinearLayout = box.findViewById(R.id.osd_hints)

        fun bringToFront() = box.bringToFront()

        fun show(buf: String, cat: Catalog) {
            val n = buf.toIntOrNull() ?: 0
            val ch = cat.byNum(n)
            num.text = buf
            if (ch != null) Logos.into(logo, ch)
            U.show(logo, ch != null)
            name.text = ch?.name ?: "—"
            name.alpha = if (ch != null) 1f else 0.5f
            // подсказки: каналы, которые выберутся следующей цифрой
            hints.removeAllViews()
            if (n > 0 && buf.length < 4) {
                val inflater = LayoutInflater.from(activity)
                for (k in n * 10..n * 10 + 9) {
                    val h = cat.byNum(k) ?: continue
                    hints.addView((inflater.inflate(R.layout.osd_hint, hints, false) as TextView).apply { text = str(R.string.num_name, h.num, h.name) })
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
        const val REQ_VOICE = 71
        const val TAG = "IPTV"
        const val BACK_EXIT = 2000L
        /** Проверка устаревания плейлиста и программы, тиков (с) */
        const val AUTO_REFRESH = 15 * 60
        const val SWITCH_DELAY = 700L
        /** Шаги таймера сна, мин */
        val SLEEP_STEPS = intArrayOf(15, 30, 45, 60, 90, 120)
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
