package top.rootu.perfecttv.player

import java.net.URLEncoder
import java.util.TimeZone

/**
 * Шаблоны URL архива (catchup) — порт tv_plugin/src/core/url.js с тем же поведением.
 *
 * Подстановки:
 *   ${start} ${utc} ${end} ${utcend} ${offset} ${duration} ${now} ${lutc} ${timestamp}
 *   ${(b)yyyy-MM-dd:HH-mm}  начало;  ${(e)...} конец;  ${(n)...} сейчас
 *   ${(bu)...} — в UTC, ${(b+3)...} — со сдвигом часового пояса
 *   ${(d)M} ${(d)S} ${(d)h} ${(d)m} — длительность
 */
object CatchupUrl {

    // скобки экранированы везде: ICU-регулярки Android строже JVM к «{}» без экрана
    private val VAR_RE = Regex("\\$\\{(\\((([a-zA-Z\\d]+?)(u)?)([+-]\\d+)?\\))?([^\\$\\{\\}]+)\\}")

    /**
     * Шаблон архива канала.
     * @return шаблон (с ${...}) или null, если архив не поддерживается
     */
    fun template(url: String, type0: String, source: String): String? {
        var type = type0.lowercase()
        if (type.isEmpty()) {
            type = if (source.isNotEmpty()) {
                if (Regex("^[?&/][^/]").containsMatchIn(source)) "append" else "default"
            } else if (!url.contains("\${")) "shift" else "default"
        }
        val out: String
        when (type) {
            "append", "timeshift", "shift" -> {
                if (type == "append" && source.isNotEmpty()) {
                    out = (if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(source)) "" else url) + source
                } else {
                    val base = source.ifEmpty { url }
                    return base + (if (base.contains('?')) "&" else "?") + "utc=\${start}&lutc=\${timestamp}"
                }
            }
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> {
                // /ch/index.m3u8 -> /ch/archive-{start}-{duration}.m3u8; /ch/mono.m3u8 -> /ch/mono-…; /ch/mpegts -> /ch/timeshift_abs-{start}.ts
                return url
                    .replaceFirst(Regex("/(video|mono)\\.(m3u8|ts)"), "/$1-\\\${start}-\\\${duration}.$2")
                    .replaceFirst(Regex("/(index|playlist)\\.(m3u8|ts)"), "/archive-\\\${start}-\\\${duration}.$2")
                    .replaceFirst(Regex("/mpegts"), "/timeshift_abs-\\\${start}.ts")
            }
            "xc" -> {
                // http://host:8080/live/user/pass/1477.m3u8 -> /timeshift/user/pass/{min}/{Y-m-d:H-M}/1477.m3u8
                out = url
                    .replaceFirst(Regex("^(https?://[^/]+)(/live)?(/[^/]+/[^/]+/)([^/.]+)\\.m3u8?$"), "$1/timeshift$3\\\${(d)M}/\\\${(b)yyyy-MM-dd:HH-mm}/$4.m3u8")
                    .replaceFirst(Regex("^(https?://[^/]+)(/live)?(/[^/]+/[^/]+/)([^/.]+)(\\.ts|)$"), "$1/timeshift$3\\\${(d)M}/\\\${(b)yyyy-MM-dd:HH-mm}/$4.ts")
            }
            "default" -> out = source.ifEmpty { url }
            else -> return null   // disabled и неизвестные
        }
        if (!out.contains("\${")) return template(out, "shift", "")
        return out
    }

    /**
     * Flussonic с HLS-архивом (archive-/mono-/video-…-START-DURATION.m3u8): вместо длительности
     * можно подставить `now` — EVENT-плейлист от начала до текущего момента, дописывается.
     */
    fun flussonicHls(type: String, tpl: String) =
        Regex("^(flussonic|fs)", RegexOption.IGNORE_CASE).containsMatchIn(type) &&
            Regex("-\\$\\{start\\}-\\$\\{duration\\}\\.m3u8").containsMatchIn(tpl)

    /**
     * Подставить значения в шаблон.
     * @param start unix-секунды начала, duration — секунды; null — без диапазона (эфир: start = сейчас)
     */
    fun prepare(url0: String, start0: Long?, duration0: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val vars = HashMap<String, (MatchResult) -> String>()
        if (start0 == null) {
            vars["start"] = { now.toString() }
            vars["offset"] = { "0" }
        } else {
            val start = start0
            val dur = maxOf(60L, duration0)
            val startMin = start / 60.0
            val durMin = Math.round(dur / 60.0)
            vars["start"] = { start.toString() }
            vars["utc"] = { start.toString() }
            vars["end"] = { (start + dur).toString() }
            vars["utcend"] = { (start + dur).toString() }
            vars["offset"] = { (now - start).toString() }
            vars["duration"] = { dur.toString() }
            vars["now"] = { now.toString() }
            vars["lutc"] = { now.toString() }
            vars["d"] = { m ->
                replaceAll(m.groupValues[6], linkedMapOf(
                    "M" to durMin.toString(), "S" to dur.toString(), "h" to (durMin / 60).toString(),
                    "m" to pad2((durMin % 60).toInt()), "s" to "00"
                ))
            }
            vars["b"] = { m -> formatTime(startMin, m.groupValues[6], m.groupValues[4], m.groupValues[5], tz) }
            vars["e"] = { m -> formatTime(startMin + durMin, m.groupValues[6], m.groupValues[4], m.groupValues[5], tz) }
            vars["n"] = { m -> formatTime(now / 60.0, m.groupValues[6], m.groupValues[4], m.groupValues[5], tz) }
        }
        vars["timestamp"] = { now.toString() }
        var url = url0
        var guard = 0
        while (guard++ < 50) {
            val m = VAR_RE.find(url) ?: break
            val g2 = m.groupValues[2]
            val g3 = m.groupValues[3]
            val key = m.groupValues[6]
            val v = when {
                g2.isNotEmpty() && vars[g2] != null -> vars[g2]!!(m)
                g3.isNotEmpty() && vars[g3] != null -> vars[g3]!!(m)
                vars[key] != null -> vars[key]!!(m)
                else -> m.groupValues[1]
            }
            url = url.replaceRange(m.range, encode(v))
        }
        return url
    }

    /** formatUrlTime из core/time.js: utc — «u» в (bu), tz — сдвиг часов из (b+3) */
    private fun formatTime(minutes: Double, format: String, utc: String, tzShift: String, tz: TimeZone): String {
        var shift = (tzShift.toIntOrNull() ?: 0) * 60.0
        val ms = Math.round(minutes * 60000)
        if (utc.isEmpty()) shift += tz.getOffset(ms) / 60000.0
        val t = Math.round((minutes + shift) * 60000)
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = t
        return replaceAll(format, linkedMapOf(
            "yyyy" to c.get(java.util.Calendar.YEAR).toString(),
            "MM" to pad2(c.get(java.util.Calendar.MONTH) + 1),
            "dd" to pad2(c.get(java.util.Calendar.DAY_OF_MONTH)),
            "HH" to pad2(c.get(java.util.Calendar.HOUR_OF_DAY)),
            "mm" to pad2(c.get(java.util.Calendar.MINUTE)),
            "ss" to pad2(c.get(java.util.Calendar.SECOND)),
            "UTF" to ms.toString()
        ))
    }

    private fun replaceAll(s: String, map: Map<String, String>): String {
        var out = s
        for ((k, v) in map) out = out.replace(k, v)
        return out
    }

    private fun pad2(n: Int) = if (n < 10) "0$n" else n.toString()

    /** encodeURIComponent */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
}
