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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
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
    }

    private var headers: Map<String, String> = emptyMap()
    private var userAgent = Net.USER_AGENT
    var listener: Listener? = null

    private val dataSource = OkHttpDataSource.Factory(Net.client)
        .setUserAgent(Net.USER_AGENT)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory {
            dataSource.setUserAgent(userAgent).setDefaultRequestProperties(headers).createDataSource()
        })
        .setLoadControl(DefaultLoadControl.Builder()
            // быстрый старт после переключения канала; дальше буфер растёт как обычно
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build())
        .build()

    private val main = Handler(Looper.getMainLooper())

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener?.onEnded()
                notifyState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyState()

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) listener?.onVideoSize(videoSize.width, videoSize.height)
            }
        })
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
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        (if (forceHls) MimeTypes.APPLICATION_M3U8 else mimeOf(url))?.let { b.setMimeType(it) }
        val live = MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET)
        // архив с позиции (в т.ч. растущий EVENT-плейлист): играть ровно с неё, без подгонки
        // скорости к краю эфира — иначе время на шкале расходится с передачей
        if (positionMs != C.TIME_UNSET) live.setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
        b.setLiveConfiguration(live.build())
        if (positionMs != C.TIME_UNSET) player.setMediaItem(b.build(), positionMs) else player.setMediaItem(b.build())
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
    }

    // ---------------------------------------------------------- звук и субтитры

    /** Дорожка потока: [label] — название или язык, [selected] — играет сейчас */
    class Track(val group: androidx.media3.common.TrackGroup, val index: Int, val label: String, val language: String, val selected: Boolean)

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
        player.release()
    }

    companion object {
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
