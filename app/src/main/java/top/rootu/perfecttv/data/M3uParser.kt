package top.rootu.perfecttv.data

import java.io.BufferedReader

/**
 * Потоковый парсер M3U/M3U8: читает построчно и отдаёт каналы по одному в [onChannel],
 * весь файл в памяти не держит. Правила — §4.
 */
class M3uParser(private val defaultGroup: String = "") {

    /** Атрибуты строки #EXTM3U (ключи в нижнем регистре) */
    var header: Map<String, String> = emptyMap()
        private set

    /** Был ли заголовок #EXTM3U (иначе это не плейлист) */
    var isM3u = false
        private set

    var count = 0
        private set

    internal class Pending(val name: String, val attrs: Map<String, String>) {
        val options = HashMap<String, String>()
        var headersJson: String? = null
    }

    /**
     * Разобрать поток. Плейлист без #EXTM3U в первой непустой строке — ошибка формата
     * ([isM3u] = false, каналов нет).
     */
    fun parse(reader: BufferedReader, onChannel: (RawChannel) -> Unit) {
        var group = defaultGroup
        var pending: Pending? = null
        var first = true
        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                line = line.removePrefix("\uFEFF")
                if (line.isBlank()) continue
                first = false
                val t = line.trim()
                if (!t.startsWith("#EXTM3U", ignoreCase = true)) return
                isM3u = true
                header = parseAttrs(t.substring(7))
                continue
            }
            line = line.trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) -> pending = parseExtinf(line.substring(8))
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        val g = line.substring(8).trim()
                        if (g.isNotEmpty()) group = g
                    }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = line.substring(11)
                        val eq = opt.indexOf('=')
                        if (eq > 0 && pending != null) pending.options[opt.substring(0, eq).trim().lowercase()] = opt.substring(eq + 1).trim()
                    }
                    line.startsWith("#EXTHTTP:", ignoreCase = true) -> pending?.headersJson = line.substring(9).trim()
                }
                continue
            }
            if (!URL_RE.matches(line)) continue
            val p = pending ?: Pending("", emptyMap())
            pending = null
            if (YOUTUBE_RE.containsMatchIn(line)) continue
            count++
            onChannel(build(count, line, p, group))
        }
    }

    private fun build(num: Int, url: String, p: Pending, group: String): RawChannel {
        val a = p.attrs
        val name = cleanName(p.name).ifEmpty { "Ch $num" }
        return RawChannel(
            num = num,
            name = name,
            url = url,
            group = a["group-title"]?.takeIf { it.isNotEmpty() } ?: group,
            logo = a["tvg-logo"] ?: a["logo"] ?: "",
            tvgId = a["tvg-id"] ?: "",
            tvgName = a["tvg-name"] ?: "",
            tvgShift = a["tvg-shift"]?.toFloatOrNull() ?: header["tvg-shift"]?.toFloatOrNull() ?: 0f,
            catchup = catchupOf(a),
            userAgent = p.options["http-user-agent"] ?: a["http-user-agent"] ?: a["user-agent"] ?: "",
            referrer = p.options["http-referrer"] ?: p.options["http-referer"] ?: a["http-referrer"] ?: a["http-referer"] ?: a["referer"] ?: "",
            headersJson = p.headersJson ?: ""
        )
    }

    /** Архив канала. days = 0 — «по умолчанию из настроек» */
    private fun catchupOf(a: Map<String, String>): Catchup? {
        val h = header
        val days = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"] ?: h["catchup-days"] ?: h["tvg-rec"] ?: h["timeshift"] ?: "0")
            .trim().toIntOrNull() ?: 0
        val enabled = if ("catchup-enable" in a) (a["catchup-enable"]?.toIntOrNull() ?: 0) > 0
        else days > 0 || !(a["catchup"] ?: a["catchup-type"] ?: a["catchup-source"]).isNullOrEmpty()
        val type = a["catchup"] ?: a["catchup-type"] ?: h["catchup"] ?: h["catchup-type"] ?: ""
        if (!enabled || type.equals("disabled", ignoreCase = true)) return null
        return Catchup(type, a["catchup-source"] ?: h["catchup-source"] ?: "", days)
    }

    companion object {
        private val URL_RE = Regex("^(https?|rtmpe?|rtsp|udp|rtp|mms)://.+", RegexOption.IGNORE_CASE)
        private val YOUTUBE_RE = Regex("^https?://(www\\.)?youtube\\.com", RegexOption.IGNORE_CASE)
        private val ATTR_RE = Regex("([^\\s=]+)=((\"|')(.*?)\\3|\\S+)")
        private val DURATION_RE = Regex("^\\s*-?\\d+(\\.\\d+)?")
        private val BADGES_RE = Regex("\\s+(\\s|ⓢ|ⓖ|ⓥ|ⓞ|Ⓢ|Ⓖ|Ⓥ|Ⓞ)")

        fun parseAttrs(str: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (m in ATTR_RE.findAll(str)) {
                val quoted = m.groupValues[3].isNotEmpty()
                out[m.groupValues[1].lowercase()] = if (quoted) m.groupValues[4] else m.groupValues[2]
            }
            return out
        }

        /**
         * «-1 attr="..." ...,Название». Название — после первой запятой вне кавычек значений:
         * в значениях бывают запятые (http-user-agent="... (KHTML, like Gecko) ...").
         */
        internal fun parseExtinf(rest: String): Pending? {
            val d = DURATION_RE.find(rest) ?: return null
            val s = rest.substring(d.range.last + 1)
            var quote = 0.toChar()
            for (i in s.indices) {
                val c = s[i]
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else if ((c == '"' || c == '\'') && i > 0 && s[i - 1] == '=') quote = c
                else if (c == ',') return Pending(s.substring(i + 1).trim(), parseAttrs(s.substring(0, i)))
            }
            return null
        }

        fun cleanName(name: String): String = name.replace(BADGES_RE, " ").trim()

        /** URL программы из заголовка: в url-tvg бывает несколько адресов через запятую или пробел */
        fun epgUrls(header: Map<String, String>): List<String> =
            listOfNotNull(header["url-tvg"], header["x-tvg-url"], header["tvg-url"])
                .flatMap { it.split(',', ' ') }
                .map { it.trim() }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .distinct()
    }
}

/** Архив канала: тип (catchup / catchup-type), шаблон (catchup-source), глубина в днях (0 — по умолчанию) */
data class Catchup(val type: String, val source: String, val days: Int)

data class RawChannel(
    val num: Int,
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val tvgName: String,
    val tvgShift: Float,
    val catchup: Catchup?,
    val userAgent: String,
    val referrer: String,
    val headersJson: String
)
