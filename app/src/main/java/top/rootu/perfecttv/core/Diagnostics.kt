package top.rootu.perfecttv.core

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import top.rootu.perfecttv.BuildConfig

/**
 * Отчёт для диагностики (Настройки → Данные → «Диагностика», экран ошибки): устройство, экран и HDR, аудиовыходы
 * и проброс, видео- и аудиодекодеры (в том числе встроенный FFmpeg), настройки просмотра, последняя ошибка,
 * лог приложения. Каждый раздел собирается отдельно — сбой в одном (кривая прошивка) не ломает остальные.
 * Служебный текст для разработчика: не локализуется. Адреса сокращаются до хоста ([redact]) — в ссылках
 * провайдеров бывают логин и пароль.
 */
object Diagnostics {
    private const val TAG = "IPTV"

    class Section(val title: String, val lines: List<String>)

    private val VIDEO = listOf(MimeTypes.VIDEO_H264 to "H.264", MimeTypes.VIDEO_H265 to "HEVC", MimeTypes.VIDEO_MPEG2 to "MPEG-2",
        MimeTypes.VIDEO_AV1 to "AV1", MimeTypes.VIDEO_VP9 to "VP9", MimeTypes.VIDEO_MP4V to "MPEG-4")
    private val AUDIO = listOf(MimeTypes.AUDIO_AAC to "AAC", MimeTypes.AUDIO_AC3 to "AC-3", MimeTypes.AUDIO_E_AC3 to "E-AC-3",
        MimeTypes.AUDIO_DTS to "DTS", MimeTypes.AUDIO_MPEG_L2 to "MP2", MimeTypes.AUDIO_MPEG to "MP3", MimeTypes.AUDIO_OPUS to "Opus")
    private val PASSTHROUGH = listOf(C.ENCODING_AC3 to "AC-3", C.ENCODING_E_AC3 to "E-AC-3", C.ENCODING_E_AC3_JOC to "E-AC-3 JOC",
        C.ENCODING_DTS to "DTS", C.ENCODING_DTS_HD to "DTS-HD", C.ENCODING_DOLBY_TRUEHD to "TrueHD")

    fun collect(c: Context): List<Section> {
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .onFailure { Log.w(TAG, "codec list: $it") }.getOrDefault(emptyList())
        val out = ArrayList<Section>()
        Crash.last(c)?.let { out.add(Section("Last crash", it.lines())) }
        out.add(section("Device") { device(c) })
        out.add(section("Display") { display(c) })
        out.add(section("Audio output") { audioOutput(c) })
        out.add(section("Video decoders") { video(decoders) })
        out.add(section("Audio decoders") { audio(decoders) })
        out.add(section("Settings") { settings() })
        out.add(section("App log") { appLog() })
        return out
    }

    fun text(sections: List<Section>): String = redact(sections.joinToString("\n\n") { "== ${it.title} ==\n" + it.lines.joinToString("\n") })

    private fun section(title: String, lines: () -> List<String>): Section = try {
        Section(title, lines())
    } catch (e: Throwable) {
        Log.w(TAG, "diagnostics $title: $e")
        Section(title, listOf("Error: ${e.javaClass.simpleName}: ${e.message}"))
    }

    /** Адреса — до хоста: `https://host/…` (логин и пароль провайдера в пути и параметрах не выгружаем) */
    fun redact(s: String): String = Regex("(https?|rtmp|rtsp|udp)://[^/\\s\"'<>?]+[^\\s\"'<>]*").replace(s) { m ->
        val full = m.value
        val host = full.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
        full.substringBefore("://") + "://" + host + (if (full.length > full.indexOf(host) + host.length) "/…" else "")
    }

    fun device(c: Context): List<String> {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return listOf(
            "Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})",
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}",
            "ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}",
            "Heap: ${Runtime.getRuntime().maxMemory() shr 20} MB, RAM: ${am.memoryInfo().totalMem shr 20} MB, low-ram: ${am.isLowRamDevice}",
            "OpenGL ES: ${am.deviceConfigurationInfo.glEsVersion}",
            "Locale: ${java.util.Locale.getDefault()}, app language: ${Prefs.language.ifEmpty { "system" }}"
        )
    }

    private fun ActivityManager.memoryInfo() = ActivityManager.MemoryInfo().also { getMemoryInfo(it) }

    @Suppress("DEPRECATION")
    private fun display(c: Context): List<String> {
        val d: Display = (c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY) ?: return listOf("no display")
        val lines = ArrayList<String>()
        val m = d.mode
        lines += "Mode: ${m.physicalWidth}×${m.physicalHeight} @ ${"%.2f".format(m.refreshRate)}"
        lines += "Supported: " + d.supportedModes.sortedWith(compareByDescending<Display.Mode> { it.physicalWidth * it.physicalHeight }.thenByDescending { it.refreshRate })
            .joinToString(", ") { "${it.physicalWidth}×${it.physicalHeight}@${Math.round(it.refreshRate)}" }
        if (Build.VERSION.SDK_INT >= 24) {
            val hdr = d.hdrCapabilities?.supportedHdrTypes?.map { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "type $it" } }.orEmpty()
            lines += "HDR: " + hdr.joinToString(", ").ifEmpty { "none" }
        }
        return lines
    }

    private fun audioOutput(c: Context): List<String> {
        val lines = ArrayList<String>()
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { dev ->
            val enc = dev.encodings.toList().distinct().joinToString(",")
            lines += "${typeName(dev.type)} ${dev.productName ?: ""}".trim() + (if (enc.isNotEmpty()) " [encodings $enc]" else "")
        }
        val caps = AudioCapabilities.getCapabilities(c, AudioAttributes.DEFAULT, null)
        lines += "Passthrough: " + PASSTHROUGH.filter { caps.supportsEncoding(it.first) }.joinToString(", ") { it.second }.ifEmpty { "none" }
        lines += "Max channels: ${caps.maxChannelCount}"
        return lines
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"; AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"; AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"; AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line"
        AudioDeviceInfo.TYPE_IP -> "IP"; else -> "type $t"
    }

    private fun hw(i: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated else !(i.name.startsWith("OMX.google.") || i.name.startsWith("c2.android."))

    private fun video(decoders: List<MediaCodecInfo>) = VIDEO.map { (mime, label) ->
        val items = decoders.filter { !it.name.endsWith(".secure") && it.supportedTypes.any { t -> t.equals(mime, true) } }.map { i ->
            val v = i.getCapabilitiesForType(mime).videoCapabilities
            "${i.name} (${if (hw(i)) "HW" else "SW"}${if (v != null) ", ${v.supportedWidths.upper}×${v.supportedHeights.upper}" else ""})"
        }
        "$label: " + items.joinToString("; ").ifEmpty { "none" }
    }

    private fun audio(decoders: List<MediaCodecInfo>): List<String> {
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        return listOf("FFmpeg library: ${if (ffmpeg) "loaded" else "NOT loaded"}") + AUDIO.map { (mime, label) ->
            val items = decoders.filter { it.supportedTypes.any { t -> t.equals(mime, true) } }.map { "${it.name} (${if (hw(it)) "HW" else "SW"})" }.toMutableList()
            if (ffmpeg && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)) items += "FFmpeg (built-in)"
            "$label: " + items.joinToString("; ").ifEmpty { "none" }
        }
    }

    private fun settings() = listOf(
        "videoDecoder=${Prefs.videoDecoder} deinterlace=${Prefs.deinterlace} anyCert=${Prefs.anyCert} animations=${Prefs.animations}",
        "archiveDays=${Prefs.archiveDays} epgRefreshHours=${Prefs.epgRefreshHours} guideMode=${Prefs.guideMode} numberMode=${Prefs.numberMode}",
        "subtitles: size=${Prefs.subSize} color=${Prefs.subColor} bg=${Prefs.subBg} edge=${Prefs.subEdge} bottom=${Prefs.subBottom}"
    )

    /** Последние строки logcat приложения (без root видны только свои записи) */
    fun appLog(max: Int = 1000): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", max.toString()).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        p.destroy()
        lines.takeLast(max)
    } catch (e: Exception) {
        listOf("logcat: ${e.javaClass.simpleName}: ${e.message}")
    }
}
