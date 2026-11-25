package top.rootu.perfecttv.data

import java.io.InputStream
import java.nio.charset.Charset

/**
 * Потоковый парсер XMLTV на уровне байтов (цель ТЗ — 1 ГБ распакованного XML меньше
 * чем за минуту на S905X).
 *
 * Общий XML-разборщик (XmlPullParser) декодирует и токенизирует каждый символ, а в XMLTV
 * обычно нужна малая часть каналов. Здесь у <programme> читаются только атрибуты открывающего
 * тега; передача ненужного канала ([wanted] = false) пропускается поиском «</programme>»
 * по сырым байтам, без декодирования. Нужные элементы копируются целиком и разбираются
 * маленьким разборщиком: title, sub-title, desc, category, icon, с учётом lang, сущностей
 * и CDATA. Кодировка — из пролога (UTF-8 по умолчанию, бывает windows-1251).
 *
 * [beforeProgrammes] вызывается перед первой передачей (все <channel> обычно идут
 * в начале): вызывающий сопоставляет каналы и готовит [wanted]. Если <channel> встретится
 * после передач — вызывается снова.
 */
class XmltvParser(
    private val langs: List<String> = listOf("ru"),
    private val onChannel: (EpgChannelRaw) -> Unit,
    private val beforeProgrammes: () -> Unit,
    private val wanted: (String) -> Boolean,
    private val onProgramme: (EpgProgramme) -> Unit,
    /**
     * Нужна ли передача (канал, начало, конец — unix-секунды): нет — она пропускается поиском «</programme>» без
     * разбора содержимого. Спрашивается только у передач с атрибутом stop (без него конец определяется по следующей).
     */
    private val accept: (String, Long, Long) -> Boolean = { _, _, _ -> true }
) {
    var channels = 0
        private set
    var programmes = 0
        private set
    var skipped = 0
        private set

    private var charset: Charset = Charsets.UTF_8
    private var utf8 = true

    // передача без stop ждёт следующую передачу того же канала
    private val noStop = HashMap<String, EpgProgramme>()
    private val elem = Bytes(16 * 1024)
    private val tag = Bytes(512)
    private val attrs = HashMap<String, String>()

    // канал предыдущей передачи: байты значения атрибута, id и решение wanted
    private var lastBytes = ByteArray(64)
    private var lastLen = -1
    private var lastId = ""
    private var lastWanted = false

    private fun sameAsLast(b: ByteArray, vs: Int, ve: Int): Boolean {
        if (ve - vs != lastLen) return false
        for (k in 0 until lastLen) if (b[vs + k] != lastBytes[k]) return false
        return true
    }

    fun parse(input: InputStream) {
        val s = Scanner(input)
        var programmesStarted = false
        var channelsDirty = false
        var sinceYield = 0
        fun pace() {
            // у большого XMLTV этот цикл на минуты занимает ядро: регулярно уступаем квант интерфейсу и видео
            if (++sinceYield >= 128) {
                sinceYield = 0
                Thread.yield()
            }
        }
        while (s.skipTo('<'.code.toByte())) {
            pace()
            val b = s.peek()
            if (b < 0) break
            when (b) {
                '?'.code -> {
                    tag.clear()
                    s.copyUntil(PI_END, tag)
                    prolog(tag)
                }
                '!'.code -> {
                    if (s.startsWith(COMMENT)) s.skipPast(COMMENT_END) else s.skipPast(GT)
                }
                else -> {
                    tag.clear()
                    if (!s.readTag(tag)) break
                    val nameEnd = nameEnd(tag)
                    when {
                        tag.equalsAt(0, nameEnd, CHANNEL) -> {
                            readAttrs(tag, nameEnd)
                            val id = attrs["id"]
                            elem.clear()
                            if (!tag.selfClosed()) s.copyUntil(CHANNEL_END, elem)
                            if (!id.isNullOrEmpty()) {
                                channels++
                                onChannel(channelOf(id, elem))
                                if (programmesStarted) channelsDirty = true
                            }
                        }
                        tag.equalsAt(0, nameEnd, PROGRAMME) -> {
                            if (!programmesStarted || channelsDirty) {
                                programmesStarted = true
                                channelsDirty = false
                                lastLen = -1
                                beforeProgrammes()
                            }
                            // канал — сравнением байтов с предыдущей передачей (XMLTV обычно идёт
                            // по каналам подряд): ни строк, ни поиска в множестве на каждую передачу
                            val r = attrRange(tag, nameEnd, tag.len, CHANNEL_ATTR)
                            if (r < 0) {
                                skipped++
                                if (!tag.selfClosed()) s.skipPast(PROGRAMME_END)
                                continue
                            }
                            val vs = (r ushr 32).toInt()
                            val ve = r.toInt()
                            if (!sameAsLast(tag.buf, vs, ve)) {
                                lastLen = ve - vs
                                if (lastBytes.size < lastLen) lastBytes = ByteArray(lastLen * 2)
                                System.arraycopy(tag.buf, vs, lastBytes, 0, lastLen)
                                lastId = decode(tag.buf, vs, ve)
                                lastWanted = lastId.isNotEmpty() && wanted(lastId)
                            }
                            if (!lastWanted) {
                                skipped++
                                if (!tag.selfClosed()) s.skipPast(PROGRAMME_END)
                            } else {
                                val start = attrOf(tag, nameEnd, tag.len, "start")
                                val stop = attrOf(tag, nameEnd, tag.len, "stop")
                                if (!stop.isNullOrEmpty() && !accept(lastId, parseTime(start), parseTime(stop))) {
                                    skipped++
                                    if (!tag.selfClosed()) s.skipPast(PROGRAMME_END)
                                    continue
                                }
                                elem.clear()
                                if (!tag.selfClosed()) s.copyUntil(PROGRAMME_END, elem)
                                programme(lastId, start, stop, elem)
                            }
                        }
                        // <tv>, </tv>, <!DOCTYPE> и прочее верхнего уровня — пропускаем
                    }
                }
            }
        }
        // хвосты без stop: до следующей передачи не дошли — час
        for (pr in noStop.values) emit(pr.copy(stop = pr.start + 3600))
        noStop.clear()
    }

    /** <?xml version="1.0" encoding="windows-1251"?> */
    private fun prolog(t: Bytes) {
        val str = String(t.buf, 0, t.len, Charsets.ISO_8859_1)
        val m = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']").find(str) ?: return
        try {
            charset = Charset.forName(m.groupValues[1])
            utf8 = charset == Charsets.UTF_8
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ элементы

    private fun channelOf(id: String, e: Bytes): EpgChannelRaw {
        val names = ArrayList<String>(2)
        var icon = ""
        children(e) { tagEnd, nEnd, open, textFrom, textTo ->
            when {
                e.equalsAt(open, nEnd, DISPLAY_NAME) -> text(e, textFrom, textTo).trim().takeIf { it.isNotEmpty() }?.let { if (it !in names) names.add(it) }
                e.equalsAt(open, nEnd, ICON) -> if (icon.isEmpty()) icon = attrOf(e, nEnd, tagEnd, "src")
            }
        }
        return EpgChannelRaw(id, names, icon)
    }

    private fun programme(ch: String, startAttr: String?, stopAttr: String?, e: Bytes) {
        val start = parseTime(startAttr)
        if (start <= 0) return
        val stop = if (stopAttr.isNullOrEmpty()) 0L else parseTime(stopAttr)
        val title = Pick(langs)
        val sub = Pick(langs)
        val desc = Pick(langs)
        val cat = Pick(langs)
        var icon = ""
        children(e) { tagEnd, nEnd, open, textFrom, textTo ->
            when {
                e.equalsAt(open, nEnd, TITLE) -> title.offer(langOf(e, nEnd, tagEnd)) { text(e, textFrom, textTo) }
                e.equalsAt(open, nEnd, SUB_TITLE) -> sub.offer(langOf(e, nEnd, tagEnd)) { text(e, textFrom, textTo) }
                e.equalsAt(open, nEnd, DESC) -> desc.offer(langOf(e, nEnd, tagEnd)) { text(e, textFrom, textTo) }
                e.equalsAt(open, nEnd, CATEGORY) -> cat.offer(langOf(e, nEnd, tagEnd)) { text(e, textFrom, textTo) }
                e.equalsAt(open, nEnd, ICON) -> if (icon.isEmpty()) icon = attrOf(e, nEnd, tagEnd, "src")
            }
        }
        val pr = EpgProgramme(ch, start, stop, title.value.trim(), sub.value.trim(), desc.value.trim(), cat.value.trim(), icon)
        val prev = noStop.remove(ch)
        if (prev != null) emit(prev.copy(stop = maxOf(start, prev.start + 60)))
        if (stop <= start) noStop[ch] = pr else emit(pr)
    }

    private fun emit(pr: EpgProgramme) {
        programmes++
        onProgramme(pr)
    }

    /**
     * Дочерние элементы первого уровня внутри [e]: block(tagEnd, nameEnd, open, textFrom, textTo),
     * где open — начало имени (после '<'), nameEnd — конец имени, tagEnd — '>' открывающего тега,
     * [textFrom, textTo) — содержимое до закрывающего тега (пусто у самозакрытого).
     */
    private inline fun children(e: Bytes, block: (tagEnd: Int, nameEnd: Int, open: Int, textFrom: Int, textTo: Int) -> Unit) {
        val b = e.buf
        val n = e.len
        var i = 0
        while (i < n) {
            if (b[i] != LT) { i++; continue }
            val open = i + 1
            if (open >= n) break
            val c = b[open]
            if (c == SLASH || c == BANG || c == QM) {
                // закрывающий тег, комментарий или CDATA вне текста — пропустить до '>'
                if (c == BANG && e.startsWithAt(open, COMMENT)) {
                    val end = e.indexOf(COMMENT_END, open)
                    i = if (end < 0) n else end + COMMENT_END.size
                } else {
                    i = e.indexOfByte(GT_B, open).let { if (it < 0) n else it + 1 }
                }
                continue
            }
            var nameEnd = open
            while (nameEnd < n && !isNameEnd(b[nameEnd])) nameEnd++
            val tagEnd = tagEndOf(e, nameEnd)
            if (tagEnd < 0) break
            if (b[tagEnd - 1] == SLASH) {
                block(tagEnd, nameEnd, open, tagEnd, tagEnd)
                i = tagEnd + 1
                continue
            }
            // закрывающий </имя> (внутри бывают вложенные теги — <desc>текст <b>жирный</b></desc>)
            val close = findClose(e, open, nameEnd, tagEnd + 1)
            val textTo = if (close < 0) n else close
            block(tagEnd, nameEnd, open, tagEnd + 1, textTo)
            i = if (close < 0) n else (e.indexOfByte(GT_B, close).let { if (it < 0) n else it + 1 })
        }
    }

    private fun findClose(e: Bytes, open: Int, nameEnd: Int, from: Int): Int {
        val b = e.buf
        val len = nameEnd - open
        var i = from
        while (true) {
            i = e.indexOfByte(LT, i)
            if (i < 0 || i + 1 >= e.len) return -1
            if (b[i + 1] == SLASH && i + 2 + len <= e.len) {
                var ok = true
                for (k in 0 until len) if (b[i + 2 + k] != b[open + k]) { ok = false; break }
                if (ok && (i + 2 + len >= e.len || isNameEnd(b[i + 2 + len]))) return i
            }
            // CDATA может содержать «</» — перескочить целиком
            if (e.startsWithAt(i, CDATA)) {
                val end = e.indexOf(CDATA_END, i)
                if (end < 0) return -1
                i = end + CDATA_END.size
                continue
            }
            i++
        }
    }

    /** '>' открывающего тега с учётом кавычек в значениях атрибутов */
    private fun tagEndOf(e: Bytes, from: Int): Int {
        val b = e.buf
        var q: Byte = 0
        var i = from
        while (i < e.len) {
            val c = b[i]
            if (q != 0.toByte()) {
                if (c == q) q = 0
            } else if (c == QUOT || c == APOS) q = c
            else if (c == GT_B) return i
            i++
        }
        return -1
    }

    private fun langOf(e: Bytes, nameEnd: Int, tagEnd: Int): String? = attrOf(e, nameEnd, tagEnd, "lang").ifEmpty { null }

    /** Диапазон значения атрибута [name] в теге [from, to): (start shl 32) or end, −1 — нет */
    private fun attrRange(e: Bytes, from: Int, to: Int, name: ByteArray): Long {
        val b = e.buf
        var i = from
        while (i < to) {
            while (i < to && isSpace(b[i])) i++
            val ns = i
            while (i < to && b[i] != EQ && !isSpace(b[i]) && b[i] != GT_B && b[i] != SLASH) i++
            val ne = i
            while (i < to && isSpace(b[i])) i++
            if (i >= to || b[i] != EQ) { i++; continue }
            i++
            while (i < to && isSpace(b[i])) i++
            if (i >= to) break
            val q = b[i]
            val vs: Int
            val ve: Int
            if (q == QUOT || q == APOS) {
                vs = i + 1
                var j = vs
                while (j < to && b[j] != q) j++
                ve = j
                i = j + 1
            } else {
                vs = i
                while (i < to && !isSpace(b[i]) && b[i] != GT_B) i++
                ve = i
            }
            if (ne - ns == name.size) {
                var ok = true
                for (k in name.indices) if (b[ns + k] != name[k]) { ok = false; break }
                if (ok) return (vs.toLong() shl 32) or ve.toLong()
            }
        }
        return -1
    }

    /** Значение атрибута [name] в теге [from, to) */
    private fun attrOf(e: Bytes, from: Int, to: Int, name: String): String {
        val b = e.buf
        var i = from
        while (i < to) {
            while (i < to && isSpace(b[i])) i++
            val ns = i
            while (i < to && b[i] != EQ && !isSpace(b[i]) && b[i] != GT_B && b[i] != SLASH) i++
            val ne = i
            while (i < to && isSpace(b[i])) i++
            if (i >= to || b[i] != EQ) { i++; continue }
            i++
            while (i < to && isSpace(b[i])) i++
            if (i >= to) break
            val q = b[i]
            val vs: Int
            val ve: Int
            if (q == QUOT || q == APOS) {
                vs = i + 1
                var j = vs
                while (j < to && b[j] != q) j++
                ve = j
                i = j + 1
            } else {
                vs = i
                while (i < to && !isSpace(b[i]) && b[i] != GT_B) i++
                ve = i
            }
            if (ne - ns == name.length) {
                var ok = true
                for (k in name.indices) if (b[ns + k] != name[k].code.toByte()) { ok = false; break }
                if (ok) return decode(b, vs, ve)
            }
        }
        return ""
    }

    /** Атрибуты открывающего тега верхнего уровня (programme, channel) */
    private fun readAttrs(t: Bytes, nameEnd: Int) {
        attrs.clear()
        for (k in TOP_ATTRS) {
            val v = attrOf(t, nameEnd, t.len, k)
            if (v.isNotEmpty()) attrs[k] = v
        }
    }

    /** Текст элемента: CDATA как есть, вложенные теги убираются, сущности раскрываются */
    private fun text(e: Bytes, from: Int, to: Int): String {
        val b = e.buf
        // быстрый путь: без '<' и '&'
        var plain = true
        for (i in from until to) if (b[i] == LT || b[i] == AMP) { plain = false; break }
        if (plain) return String(b, from, to - from, charset)
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            if (b[i] == LT) {
                if (e.startsWithAt(i, CDATA)) {
                    val s = i + CDATA.size
                    val end = e.indexOf(CDATA_END, s).let { if (it < 0 || it > to) to else it }
                    sb.append(String(b, s, end - s, charset))
                    i = end + CDATA_END.size
                    continue
                }
                // вложенный тег — пропустить
                val gt = e.indexOfByte(GT_B, i)
                i = if (gt < 0 || gt > to) to else gt + 1
                continue
            }
            var j = i
            while (j < to && b[j] != LT) j++
            sb.append(decode(b, i, j))
            i = j
        }
        return sb.toString()
    }

    /** Байты → строка с раскрытием сущностей (&amp; &lt; &gt; &quot; &apos; &#N; &#xH; &nbsp;) */
    private fun decode(b: ByteArray, from: Int, to: Int): String {
        var amp = -1
        for (i in from until to) if (b[i] == AMP) { amp = i; break }
        if (amp < 0) return String(b, from, to - from, charset)
        val s = String(b, from, to - from, charset)
        return unescape(s)
    }

    private fun isNameEnd(c: Byte) = c == GT_B || c == SLASH || isSpace(c)

    private fun nameEnd(t: Bytes): Int {
        var i = 0
        while (i < t.len && !isNameEnd(t.buf[i])) i++
        return i
    }

    /** Значение по языкам: сначала из [langs] по порядку, иначе первое (текст разбирается, только если подходит) */
    private class Pick(private val langs: List<String>) {
        var value = ""
            private set
        private var rank = Int.MAX_VALUE

        inline fun offer(lang: String?, text: () -> String) {
            val i = if (lang == null) langs.size else langs.indexOf(lang.lowercase()).let { if (it < 0) langs.size + 1 else it }
            if (i >= rank) return
            val t = text()
            if (t.isBlank()) return
            rank = i
            value = t
        }
    }

    // ------------------------------------------------------------------ байтовый ввод

    /** Растущий буфер байт */
    internal class Bytes(cap: Int) {
        var buf = ByteArray(cap)
        var len = 0

        fun clear() { len = 0 }

        fun add(src: ByteArray, off: Int, n: Int) {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            System.arraycopy(src, off, buf, len, n)
            len += n
        }

        fun selfClosed() = len > 0 && buf[len - 1] == SLASH

        fun equalsAt(from: Int, to: Int, name: ByteArray): Boolean {
            if (to - from != name.size) return false
            for (k in name.indices) if (buf[from + k] != name[k]) return false
            return true
        }

        fun startsWithAt(at: Int, p: ByteArray): Boolean {
            if (at + p.size > len) return false
            for (k in p.indices) if (buf[at + k] != p[k]) return false
            return true
        }

        fun indexOfByte(c: Byte, from: Int): Int {
            for (i in from until len) if (buf[i] == c) return i
            return -1
        }

        fun indexOf(p: ByteArray, from: Int): Int {
            var i = from
            val first = p[0]
            while (i <= len - p.size) {
                if (buf[i] == first) {
                    var ok = true
                    for (k in 1 until p.size) if (buf[i + k] != p[k]) { ok = false; break }
                    if (ok) return i
                }
                i++
            }
            return -1
        }
    }

    /** Чтение потока большими блоками с поиском по сырым байтам */
    internal class Scanner(private val input: InputStream) {
        private val buf = ByteArray(256 * 1024)
        private var pos = 0
        private var lim = 0
        private var eof = false

        /** Дочитать (сохранив непрочитанное); false — данных больше нет */
        private fun fill(): Boolean {
            if (eof) return false
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, lim - pos)
                lim -= pos
                pos = 0
            }
            val r = input.read(buf, lim, buf.size - lim)
            if (r <= 0) {
                eof = true
                return false
            }
            lim += r
            return true
        }

        private fun ensure(n: Int): Boolean {
            while (lim - pos < n) if (!fill()) return false
            return true
        }

        fun peek(): Int = if (ensure(1)) buf[pos].toInt() and 0xFF else -1

        fun startsWith(p: ByteArray): Boolean {
            if (!ensure(p.size)) return false
            for (k in p.indices) if (buf[pos + k] != p[k]) return false
            return true
        }

        /** Перейти за ближайший байт [c] */
        fun skipTo(c: Byte): Boolean {
            while (true) {
                val b = buf
                var i = pos
                val l = lim
                while (i < l) {
                    if (b[i] == c) {
                        pos = i + 1
                        return true
                    }
                    i++
                }
                pos = l
                if (!fill()) return false
            }
        }

        /** Перейти за образец [p] (поиск по первому байту, затем сравнение) */
        fun skipPast(p: ByteArray) {
            val first = p[0]
            while (true) {
                if (!skipTo(first)) return
                if (!ensure(p.size - 1)) return
                var ok = true
                for (k in 1 until p.size) if (buf[pos + k - 1] != p[k]) { ok = false; break }
                if (ok) {
                    pos += p.size - 1
                    return
                }
            }
        }

        /** Скопировать в [out] всё до образца [p] (сам образец пропускается) */
        fun copyUntil(p: ByteArray, out: Bytes) {
            val first = p[0]
            while (true) {
                var i = pos
                while (i < lim && buf[i] != first) i++
                out.add(buf, pos, i - pos)
                pos = i
                if (i >= lim) {
                    if (!fill()) return
                    continue
                }
                if (!ensure(p.size)) {
                    out.add(buf, pos, lim - pos)
                    pos = lim
                    return
                }
                var ok = true
                for (k in 1 until p.size) if (buf[pos + k] != p[k]) { ok = false; break }
                if (ok) {
                    pos += p.size
                    return
                }
                out.add(buf, pos, 1)
                pos++
            }
        }

        /** Открывающий тег после '<' до '>' (с учётом кавычек), без самого '>' */
        fun readTag(out: Bytes): Boolean {
            var q: Byte = 0
            while (true) {
                if (pos >= lim && !fill()) return false
                val start = pos
                var i = pos
                while (i < lim) {
                    val c = buf[i]
                    if (q != 0.toByte()) {
                        if (c == q) q = 0
                    } else if (c == QUOT || c == APOS) q = c
                    else if (c == GT_B) {
                        out.add(buf, start, i - start)
                        pos = i + 1
                        return true
                    }
                    i++
                }
                out.add(buf, start, i - start)
                pos = i
                if (out.len > 64 * 1024) return true   // испорченный тег — не копим бесконечно
            }
        }
    }

    companion object {
        private const val LT = '<'.code.toByte()
        private const val GT_B = '>'.code.toByte()
        private const val SLASH = '/'.code.toByte()
        private const val BANG = '!'.code.toByte()
        private const val QM = '?'.code.toByte()
        private const val QUOT = '"'.code.toByte()
        private const val APOS = '\''.code.toByte()
        private const val AMP = '&'.code.toByte()
        private const val EQ = '='.code.toByte()

        private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
        private val CHANNEL = b("channel")
        private val PROGRAMME = b("programme")
        private val CHANNEL_END = b("</channel>")
        private val PROGRAMME_END = b("</programme>")
        private val DISPLAY_NAME = b("display-name")
        private val ICON = b("icon")
        private val TITLE = b("title")
        private val SUB_TITLE = b("sub-title")
        private val DESC = b("desc")
        private val CATEGORY = b("category")
        private val COMMENT = b("<!--").copyOfRange(1, 4)   // после '<': «!--»
        private val COMMENT_END = b("-->")
        private val CDATA = b("<![CDATA[")
        private val CDATA_END = b("]]>")
        private val PI_END = b("?>")
        private val GT = b(">")
        private val TOP_ATTRS = arrayOf("id")
        private val CHANNEL_ATTR = b("channel")

        private fun isSpace(c: Byte) = c == ' '.code.toByte() || c == '\n'.code.toByte() || c == '\r'.code.toByte() || c == '\t'.code.toByte()

        fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '&') { sb.append(c); i++; continue }
                val semi = s.indexOf(';', i + 1)
                if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
                val ent = s.substring(i + 1, semi)
                val rep: String? = when {
                    ent == "amp" -> "&"
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent == "nbsp" -> "\u00A0"
                    ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                }
                if (rep == null) { sb.append(c); i++; continue }
                sb.append(rep)
                i = semi + 1
            }
            return sb.toString()
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
