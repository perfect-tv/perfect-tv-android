package top.rootu.perfecttv.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Потоковый парсер XMLTV поверх XmlPullParser: документ в памяти не держится,
 * каналы и передачи отдаются по мере чтения.
 *
 * Передачи ненужных каналов ([wanted] = false) пропускаются без разбора содержимого.
 * [beforeProgrammes] вызывается один раз — перед первой передачей (все <channel> обычно
 * идут в начале файла): здесь вызывающий сопоставляет каналы и готовит [wanted].
 * Если <channel> встретится уже после передач, снова вызывается [beforeProgrammes].
 */
class XmltvParser(
    private val langs: List<String> = listOf("ru"),
    private val onChannel: (EpgChannelRaw) -> Unit,
    private val beforeProgrammes: () -> Unit,
    private val wanted: (String) -> Boolean,
    private val onProgramme: (EpgProgramme) -> Unit
) {
    var channels = 0
        private set
    var programmes = 0
        private set
    var skipped = 0
        private set

    // передача без stop ждёт следующую передачу того же канала
    private val noStop = HashMap<String, EpgProgramme>()

    fun parse(input: InputStream) {
        val p = newParser()
        p.setInput(input, null)   // кодировку берёт из пролога <?xml encoding=...?>
        var programmesStarted = false
        var channelsDirty = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.depth == 2) {
                when (p.name) {
                    "channel" -> {
                        readChannel(p)?.let {
                            channels++
                            onChannel(it)
                            if (programmesStarted) channelsDirty = true
                        }
                    }
                    "programme" -> {
                        if (!programmesStarted || channelsDirty) {
                            programmesStarted = true
                            channelsDirty = false
                            beforeProgrammes()
                        }
                        readProgramme(p)
                    }
                    else -> skip(p)
                }
            }
            ev = p.next()
        }
        // хвосты без stop: до следующей передачи не дошли — час
        for (pr in noStop.values) emit(pr.copy(stop = pr.start + 3600))
        noStop.clear()
    }

    private fun readChannel(p: XmlPullParser): EpgChannelRaw? {
        val id = p.getAttributeValue(null, "id")
        val names = ArrayList<String>(2)
        var icon = ""
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "display-name" -> readText(p).trim().takeIf { it.isNotEmpty() }?.let { if (it !in names) names.add(it) }
                "icon" -> if (icon.isEmpty()) icon = p.getAttributeValue(null, "src") ?: ""
            }
        }
        if (id.isNullOrEmpty()) return null
        return EpgChannelRaw(id, names, icon)
    }

    private fun readProgramme(p: XmlPullParser) {
        val ch = p.getAttributeValue(null, "channel")
        if (ch.isNullOrEmpty() || !wanted(ch)) {
            skipped++
            skip(p)
            return
        }
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stopAttr = p.getAttributeValue(null, "stop")
        val stop = if (stopAttr.isNullOrEmpty()) 0L else parseTime(stopAttr)
        val title = Pick(langs)
        val sub = Pick(langs)
        val desc = Pick(langs)
        val cat = Pick(langs)
        var icon = ""
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> title.offer(p.getAttributeValue(null, "lang"), readText(p))
                "sub-title" -> sub.offer(p.getAttributeValue(null, "lang"), readText(p))
                "desc" -> desc.offer(p.getAttributeValue(null, "lang"), readText(p))
                "category" -> cat.offer(p.getAttributeValue(null, "lang"), readText(p))
                "icon" -> if (icon.isEmpty()) icon = p.getAttributeValue(null, "src") ?: ""
                else -> skip(p)
            }
        }
        if (start <= 0) return
        val pr = EpgProgramme(ch, start, stop, title.value.trim(), sub.value.trim(), desc.value.trim(), cat.value.trim(), icon)
        val prev = noStop.remove(ch)
        if (prev != null) emit(prev.copy(stop = maxOf(start, prev.start + 60)))
        if (stop <= start) noStop[ch] = pr else emit(pr)
    }

    private fun emit(pr: EpgProgramme) {
        programmes++
        onProgramme(pr)
    }

    /** Текст элемента вместе с вложенными (бывает <desc>текст <b>жирный</b></desc>) */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT || ev == XmlPullParser.ENTITY_REF) sb.append(p.text ?: "")
            else if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            else if (ev == XmlPullParser.END_DOCUMENT) break
        }
        return sb.toString()
    }

    private fun skip(p: XmlPullParser) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev == XmlPullParser.END_DOCUMENT) return
        }
    }

    /** Значение по языкам: сначала из [langs] по порядку, иначе первое попавшееся */
    private class Pick(private val langs: List<String>) {
        var value = ""
            private set
        private var rank = Int.MAX_VALUE

        fun offer(lang: String?, text: String) {
            if (text.isBlank()) return
            val i = if (lang == null) langs.size else langs.indexOf(lang.lowercase()).let { if (it < 0) langs.size + 1 else it }
            if (i < rank) {
                rank = i
                value = text
            }
        }
    }

    companion object {
        private fun newParser(): XmlPullParser {
            val f = XmlPullParserFactory.newInstance()
            f.isNamespaceAware = false
            val p = f.newPullParser()
            // нестрогий режим KXml: неизвестные сущности (&nbsp;) и мелкие ошибки не роняют разбор
            try { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) } catch (_: Exception) {}
            return p
        }

        /**
         * Время XMLTV: «yyyyMMddHHmmss ±hhmm». Секунд, минут и смещения может не быть
         * (без смещения — UTC). Результат — unix-секунды, 0 — не разобрано.
         */
        fun parseTime(s: String?): Long {
            if (s == null) return 0
            val str = s.trim()
            var i = 0
            fun num(len: Int): Int {
                if (i + len > str.length) return -1
                var v = 0
                for (k in 0 until len) {
                    val c = str[i + k]
                    if (c !in '0'..'9') return -1
                    v = v * 10 + (c - '0')
                }
                i += len
                return v
            }
            val y = num(4); val mo = num(2); val d = num(2)
            if (y < 0 || mo < 1 || d < 1) return 0
            val h = num(2).coerceAtLeast(0)
            val mi = num(2).coerceAtLeast(0)
            val se = num(2).coerceAtLeast(0)
            while (i < str.length && str[i] == ' ') i++
            var offset = 0
            if (i < str.length && (str[i] == '+' || str[i] == '-')) {
                val sign = if (str[i] == '-') -1 else 1
                i++
                val oh = num(2)
                if (i < str.length && str[i] == ':') i++
                val om = num(2)
                if (oh >= 0) offset = sign * (oh * 3600 + om.coerceAtLeast(0) * 60)
            }
            return daysFromCivil(y, mo, d) * 86400L + h * 3600 + mi * 60 + se - offset
        }

        /** Дни от 1970-01-01 (алгоритм Howard Hinnant), без Calendar — быстро на миллионах строк */
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468
        }
    }
}

data class EpgChannelRaw(val id: String, val names: List<String>, val icon: String)

data class EpgProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String,
    val desc: String,
    val category: String,
    val icon: String
)
