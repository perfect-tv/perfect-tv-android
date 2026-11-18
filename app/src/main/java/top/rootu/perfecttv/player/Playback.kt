package top.rootu.perfecttv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONObject
import top.rootu.perfecttv.core.Net
import top.rootu.perfecttv.data.Channel

/**
 * Один ExoPlayer на всё время работы. Заголовки канала (User-Agent, Referer, #EXTHTTP)
 * подставляются в каждый запрос потока. Тип потока — по расширению, иначе автоопределение.
 */
@OptIn(UnstableApi::class)
class Playback(context: Context) {

    interface Listener {
        fun onState(playing: Boolean, buffering: Boolean) {}
        fun onEnded() {}
        fun onError(e: PlaybackException) {}
        fun onVideoSize(width: Int, height: Int) {}
        /** Сторож зависания включил поправку: 1 — программный видеодекодер */
        fun onFix(step: Int) {}
    }

    private var headers: Map<String, String> = emptyMap()
    private var userAgent = Net.USER_AGENT
    var listener: Listener? = null

    /** Cookie провайдера на время одного запуска канала (см. [Cookies]) */
    private val cookies = Cookies()
    private val http = OkHttpDataSource.Factory(Net.client.newBuilder().addNetworkInterceptor(cookies).build())
        .setUserAgent(Net.USER_AGENT)
    private val dataSource = http

    /** Источники данных: http(s) — OkHttp, udp / rtp — [UdpSources] */
    private fun source(): DataSource = UdpSources.Factory(DataSource.Factory { dataSource.setUserAgent(userAgent).setDefaultRequestProperties(headers).createDataSource() }).createDataSource()

    // мультикаст по Wi-Fi без этой блокировки отбрасывается прошивкой (по Ethernet не нужна)
    private val multicastLock: android.net.wifi.WifiManager.MulticastLock? = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.createMulticastLock("perfecttv-multicast")?.apply { setReferenceCounted(false) }
    } catch (_: Exception) { null }

    private fun multicast(on: Boolean) {
        try { multicastLock?.let { if (on && !it.isHeld) it.acquire() else if (!on && it.isHeld) it.release() } } catch (_: Exception) {}
    }

    // программный видеодекодер (поправка для потоков, у которых аппаратный зависает на первом кадре): при включённом
    // флаге программные декодеры идут в списке первыми; флаг читается при каждом запуске декодера
    @Volatile private var softwareVideo = false
    private val codecSelector = MediaCodecSelector { mime, secure, tunneling ->
        val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        if (softwareVideo && MimeTypes.isVideo(mime)) list.sortedBy { if (it.softwareOnly) 0 else 1 } else list
    }

    /**
     * Флаги разбора MPEG-TS для битых и вещательных IPTV-потоков (общие для HLS и прямого TS):
     * non-IDR ключевые кадры (см. [hlsFactory]), поиск границ кадров по access unit (а не по началу PES:
     * иначе при кадрах, пересекающих PES, картинка рвётся или поток встаёт), DTS-звук HDMV, игнор splice info.
     */
    private val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
        DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

    // сбой загрузки сегмента или куска — не меньше 5 повторов, прежде чем считать это ошибкой потока
    private val loadPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
    }

    // не-HLS ссылки (прямой TS по HTTP, MP4…): те же флаги TS и поиск меток времени подальше от начала
    // (в потоках, где PCR редкий, стандартных 600 пакетов не хватает)
    private val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
        .setTsExtractorFlags(tsFlags)
        .setTsExtractorTimestampSearchBytes(5000 * androidx.media3.extractor.ts.TsExtractor.TS_PACKET_SIZE)

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context)
        // FFmpeg-декодер звука из AAR — после системных (MP2, AC-3, DTS без своего кодека на приставке)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(codecSelector))
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors).setDataSourceFactory { source() }.setLoadErrorHandlingPolicy(loadPolicy))
        .setLoadControl(DefaultLoadControl.Builder()
            // быстрый старт после переключения канала; дальше буфер растёт как обычно
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build())
        .build()

    private val main = Handler(Looper.getMainLooper())

    /** Страницы телетекста текущего канала (дорожка читается экстрактором HLS, см. [TeletextHlsExtractorFactory]) */
    val teletext = top.rootu.perfecttv.teletext.TtxDecoder()
    private var teletextKey = ""

    /**
     * HLS-источник с разбором MPEG-TS, который считает ключевыми и non-IDR I-кадры с recovery point.
     * Вещательные кодеры (у них же чересстрочное H.264 и несколько звуковых дорожек) часто не ставят IDR вовсе:
     * без этого флага ExoPlayer не видит ни одного ключевого кадра — поток «висит» на буферизации или стоит
     * на первом кадре. Для обычных потоков (с IDR) разницы нет.
     */
    private val hlsFactory = HlsMediaSource.Factory(DataSource.Factory { source() })
        .setExtractorFactory(TeletextHlsExtractorFactory(tsFlags, teletext))
        .setLoadErrorHandlingPolicy(loadPolicy)

    init {
        // диагностика: какие декодеры взяты и что не так — для разбора «зависает на первом кадре» (тег IPTV в logcat)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                android.util.Log.i("IPTV", "video decoder $decoderName (${initializationDurationMs} ms)")
                // программный или аппаратный — по описанию декодера в списке системы (бейдж в инфопанели)
                // у старых OMX-декодеров системного набора признак «программный» может быть не выставлен — смотрим и по имени
                val byName = decoderName.startsWith("OMX.google.") || decoderName.startsWith("c2.android.") ||
                    decoderName.startsWith("c2.google.") || decoderName.startsWith("OMX.ffmpeg.") || decoderName.endsWith(".sw.dec")
                videoSoftware = byName || try {
                    val mime = player.videoFormat?.sampleMimeType
                    androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime ?: MimeTypes.VIDEO_H264, false, false)
                        .firstOrNull { it.name == decoderName }?.softwareOnly == true
                } catch (e: Exception) { false }
            }

            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                videoSoftware = null
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                android.util.Log.i("IPTV", "audio decoder $decoderName")
            }

            override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
                android.util.Log.w("IPTV", "video codec error: $videoCodecError")
            }

            override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                android.util.Log.w("IPTV", "audio codec error: $audioCodecError")
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                android.util.Log.i("IPTV", "first frame")
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                android.util.Log.w("IPTV", "dropped $droppedFrames frames in $elapsedMs ms")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener?.onEnded()
                notifyState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyState()

            // диагностика: что за дорожки в потоке (кодек, размер, чересстрочность по SPS) — для разбора зависаний
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                try {
                    var interlaced = false
                    var hasVideo = false
                    for (g in tracks.groups) {
                        if (g.type == C.TRACK_TYPE_VIDEO && g.isSelected && g.length > 0) { hasVideo = true; interlaced = isInterlaced(g.getTrackFormat(0)) }
                        if (g.type != C.TRACK_TYPE_VIDEO && g.type != C.TRACK_TYPE_AUDIO && g.type != C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            var extra = ""
                            if (g.type == C.TRACK_TYPE_VIDEO && f.initializationData.isNotEmpty()) {
                                val sps = f.initializationData[0]
                                if (sps.size > 5) extra = " interlaced=" + !androidx.media3.container.NalUnitUtil.parseSpsNalUnit(sps, 4, sps.size).frameMbsOnlyFlag
                            }
                            android.util.Log.i("IPTV", "track ${when (g.type) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_TEXT -> "text"; else -> "audio" }} ${f.sampleMimeType} ${f.codecs ?: ""} " +
                                "${if (f.width > 0) f.width.toString() + "x" + f.height else ""} ${f.channelCount.takeIf { it > 0 }?.let { "ch=$it" } ?: ""}$extra " +
                                "supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}")
                        }
                    }
                    // события треков без выбранного видео (смена источника) состояние не меняют: иначе эффект мигал бы
                    if (hasVideo) setDeinterlace(interlaced)
                } catch (e: Exception) { android.util.Log.w("IPTV", "tracks log: $e") }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) listener?.onVideoSize(videoSize.width, videoSize.height)
            }
        })
    }

    /** Чересстрочный ли поток: кадр не «только кадры» (frame_mbs_only_flag = 0) в SPS H.264 */
    private fun isInterlaced(f: androidx.media3.common.Format): Boolean {
        if (f.sampleMimeType != MimeTypes.VIDEO_H264 || f.initializationData.isEmpty()) return false
        val sps = f.initializationData[0]
        return try { sps.size > 5 && !androidx.media3.container.NalUnitUtil.parseSpsNalUnit(sps, 4, sps.size).frameMbsOnlyFlag } catch (e: Exception) { false }
    }

    private var deinterlaced = false

    /** Деинтерлейсинг: по настройке и только для чересстрочного потока; эффект меняется, только когда состояние сменилось */
    fun setDeinterlace(interlaced: Boolean) {
        val want = interlaced && top.rootu.perfecttv.core.Prefs.deinterlace
        if (want == deinterlaced) return
        deinterlaced = want
        android.util.Log.i("IPTV", "deinterlace ${if (want) "on" else "off"}")
        player.setVideoEffects(if (want) listOf(DeinterlaceEffect()) else emptyList())
    }

    private fun notifyState() {
        listener?.onState(player.isPlaying, player.playbackState == Player.STATE_BUFFERING)
    }

    /**
     * Включить поток канала.
     * @param url готовая ссылка (эфир или подготовленный шаблон архива)
     * @param positionMs с какой позиции (C.TIME_UNSET — по умолчанию: у эфира — край)
     * @param forceHls считать поток HLS (ссылка без расширения, на которой автоопределение не справилось)
     */
    fun play(ch: Channel, url: String, positionMs: Long = C.TIME_UNSET, forceHls: Boolean = false) {
        // поправка, найденная раньше для этого канала (см. [checkStall])
        if (ch.key != teletextKey) { teletextKey = ch.key; teletext.reset() }
        fixKey = ch.key
        fixStep = top.rootu.perfecttv.core.Prefs.playFix(ch.key)
        applyFix()
        stallCount = -1L
        stallTicks = 0
        userAgent = ch.userAgent.ifEmpty { Net.USER_AGENT }
        val h = HashMap<String, String>()
        if (ch.referrer.isNotEmpty()) h["Referer"] = ch.referrer
        if (ch.headersJson.isNotEmpty()) {
            try {
                val o = JSONObject(ch.headersJson)
                for (k in o.keys()) {
                    if (k.equals("user-agent", true)) userAgent = o.getString(k) else h[k] = o.getString(k)
                }
            } catch (_: Exception) {
            }
        }
        headers = h
        cookies.clear()
        multicast(UdpSources.isUdp(url))
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        (if (forceHls) MimeTypes.APPLICATION_M3U8 else mimeOf(url))?.let { b.setMimeType(it) }
        val live = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET)
        // архив с позиции (в т.ч. растущий EVENT-плейлист): играть ровно с неё, без подгонки
        // скорости к краю эфира — иначе время на шкале расходится с передачей
        if (positionMs != C.TIME_UNSET) live.setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
        b.setLiveConfiguration(live.build())
        val item = b.build()
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            val src = hlsFactory.createMediaSource(item)
            if (positionMs != C.TIME_UNSET) player.setMediaSource(src, positionMs) else player.setMediaSource(src)
        } else if (positionMs != C.TIME_UNSET) player.setMediaItem(item, positionMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
        multicast(false)
        stallCount = -1L
        stallTicks = 0
    }

    // ---------------------------------------------------------- сторож зависания видео

    /** Текущий видеодекодер: true — программный, false — аппаратный, null — ещё не запущен */
    @Volatile var videoSoftware: Boolean? = null
        private set

    private var fixKey = ""
    /** Текущая поправка канала: 0 — нет, 1 — программный видеодекодер */
    var fixStep = 0
        private set
    private var stallCount = -1L
    private var stallTicks = 0
    private var stalls = 0

    private fun applyFix() {
        // «Программный» — всегда; «Авто» — если для канала найдена поправка; «Аппаратный» — никогда
        softwareVideo = when (top.rootu.perfecttv.core.Prefs.videoDecoder) { 2 -> true; 1 -> false; else -> fixStep >= 1 }
    }

    /** Убрать поправку канала и запустить заново — меню канала («Сбросить поправку потока») */
    fun resetFix(ch: Channel, url: String) {
        top.rootu.perfecttv.core.Prefs.setPlayFix(ch.key, 0)
        play(ch, url)
    }

    /**
     * Раз в секунду. Видео «зависло на первом кадре»: плеер играет (READY, не пауза), а счётчик показанных кадров
     * не растёт [STALL_SECONDS] секунд. Тогда включается программный видеодекодер (у аппаратного бывают
     * проблемы с чересстрочным H.264 и необычными потоками); поправка запоминается за каналом. Звук не
     * трогаем. Зависание без буферизации — то, что сторож считает неисправностью; загрузка («буферизация») — не он.
     */
    fun checkStall(live: () -> Unit = {}) {
        // сторож — только в режиме «Авто»: «Аппаратный» и «Программный» выбраны явно
        if (top.rootu.perfecttv.core.Prefs.videoDecoder != 0) {
            stallCount = -1L
            stallTicks = 0
            return
        }
        if (!player.isPlaying || player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }) {
            stallCount = -1L
            stallTicks = 0
            return
        }
        val counters = player.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val n = counters.renderedOutputBufferCount.toLong()
        if (stallCount < 0 || n != stallCount) {
            stallCount = n
            stallTicks = 0
            return
        }
        if (++stallTicks < STALL_SECONDS || fixKey.isEmpty() || fixStep >= 1) return
        stallTicks = 0
        stallCount = -1L
        fixStep++
        android.util.Log.w("IPTV", "video stalled on ${fixKey}: fix $fixStep")
        top.rootu.perfecttv.core.Prefs.setPlayFix(fixKey, fixStep)
        applyFix()
        // новый запуск декодера с поправкой: тот же поток с начала подготовки
        player.prepare()
        listener?.onFix(fixStep)
    }

    // ---------------------------------------------------------- звук и субтитры

    /** Дорожка потока: [label] — название или язык, [selected] — играет сейчас */
    class Track(val group: androidx.media3.common.TrackGroup, val index: Int, val label: String, val language: String, val selected: Boolean)

    /** Сколько дорожек типа [type], которые плеер умеет играть (без подписей — дёшево, для инфопанели) */
    fun trackCount(type: Int): Int {
        var n = 0
        for (g in player.currentTracks.groups) if (g.type == type) for (i in 0 until g.length) if (g.isTrackSupported(i)) n++
        return n
    }

    /** Дорожки типа [type] (C.TRACK_TYPE_AUDIO / C.TRACK_TYPE_TEXT), которые плеер умеет играть */
    fun tracks(type: Int): List<Track> {
        val out = ArrayList<Track>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED } ?: ""
                val name = lang.takeIf { it.isNotEmpty() }?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(top.rootu.perfecttv.core.Lang.locale).replaceFirstChar { c -> c.uppercase() } } ?: ""
                var label = listOf(name, f.label ?: "").filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
                    val ch = when (f.channelCount) { 1 -> "1.0"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> f.channelCount.toString() }
                    label = if (label.isEmpty()) ch else "$label · $ch"
                }
                out.add(Track(g.mediaTrackGroup, i, label, lang, g.isTrackSelected(i)))
            }
        }
        return out
    }

    /** Выбрать дорожку; null — звук: как в потоке, субтитры: выключить */
    fun selectTrack(type: Int, t: Track?) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (t != null) {
            b.setTrackTypeDisabled(type, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(t.group, t.index))
            if (t.language.isNotEmpty()) { if (type == C.TRACK_TYPE_AUDIO) b.setPreferredAudioLanguage(t.language) else b.setPreferredTextLanguage(t.language) }
        } else if (type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(type, true)
        player.trackSelectionParameters = b.build()
    }

    /** Языки по умолчанию для следующих каналов (последний выбор пользователя); пусто — не задан */
    fun preferLanguages(audio: String, text: String) {
        val b = player.trackSelectionParameters.buildUpon()
        if (audio.isNotEmpty()) b.setPreferredAudioLanguage(audio)
        if (text.isNotEmpty()) b.setPreferredTextLanguage(text)
        player.trackSelectionParameters = b.build()
    }

    val textDisabled get() = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    val paused get() = !player.playWhenReady

    /** Позиция в текущем окне, мс */
    val position get() = player.currentPosition

    private val window = androidx.media3.common.Timeline.Window()

    /** Смещение начала окна от начала периода, мс (у «эфирного» HLS окно ползёт, период — нет) */
    val windowOffsetMs: Long
        get() {
            val tl = player.currentTimeline
            if (tl.isEmpty) return 0
            tl.getWindow(player.currentMediaItemIndex, window)
            return window.positionInFirstPeriodMs
        }

    /** Реальное время начала окна по EXT-X-PROGRAM-DATE-TIME, мс UTC; нет метки — C.TIME_UNSET */
    val windowStartTimeMs: Long
        get() {
            val tl = player.currentTimeline
            if (tl.isEmpty) return C.TIME_UNSET
            tl.getWindow(player.currentMediaItemIndex, window)
            return window.windowStartTimeMs
        }

    /** Реальное время текущего кадра, мс UTC (по PROGRAM-DATE-TIME); нет метки — null */
    val wallClockMs: Long? get() = windowStartTimeMs.takeIf { it != C.TIME_UNSET }?.let { it + player.currentPosition }

    /** Позиция от начала периода, мс: не прыгает, когда окно «эфирного» потока сдвигается */
    val periodPositionMs get() = player.currentPosition + windowOffsetMs

    val ready get() = player.playbackState == Player.STATE_READY

    val live get() = player.isCurrentMediaItemLive

    val duration get() = player.duration

    val bufferedPosition get() = player.bufferedPosition

    /** Перематываемый поток (VOD или DVR-окно эфира) */
    val seekable get() = player.isCurrentMediaItemSeekable

    /** Длина окна потока, мс (у эфира без DVR — 0) */
    val windowDurationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun release() {
        main.removeCallbacksAndMessages(null)
        multicast(false)
        player.release()
    }

    companion object {
        /** Сколько секунд видео не двигается при включённом воспроизведении, чтобы счесть его зависшим */
        const val STALL_SECONDS = 8
        fun mimeOf(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                url.startsWith("rtsp://", true) -> MimeTypes.APPLICATION_RTSP
                else -> null
            }
        }
    }
}
