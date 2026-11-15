package top.rootu.perfecttv.data

/** Нормализация названий каналов: ключ канала для пользовательских данных, короткое имя и транслит для сопоставления с EPG (docs/TZ.md, §6) */
object Names {

    private val KEY_RE = Regex("[\\s!-/:-@\\[-`\\{-~]+")

    /** Ключ канала для избранного, истории, скрытого и напоминаний: без пробелов и знаков, в нижнем регистре */
    fun key(name: String): String = name.lowercase().replace(KEY_RE, "")

    private val TR = mapOf(
        'ё' to "e", 'у' to "y", 'к' to "k", 'е' to "e", 'н' to "h", 'ш' to "w", 'з' to "3", 'х' to "x",
        'ы' to "bl", 'в' to "b", 'а' to "a", 'р' to "p", 'о' to "o", 'ч' to "4", 'с' to "c", 'м' to "m",
        'т' to "t", 'ь' to "b", 'б' to "6"
    )

    private val R_ARCHIVE = Regex("\\s+\\(архив\\)$")
    private val R_PLUS = Regex("\\s+\\((\\+\\d+)\\)")
    private val R_TELE = Regex("^телеканал\\s+")
    private val R_SIGNS = Regex("([!\\s.,()–-]+|ⓢ|ⓖ|ⓥ|ⓞ|Ⓢ|Ⓖ|Ⓥ|Ⓞ)")
    private val R_KANAL = Regex("\\s(канал|тв)(\\s.+|\\s*)$")
    private val R_ORIG = Regex("\\s(50|orig|original)$")
    private val R_NUM = Regex("\\s(\\d+)")

    /** «Короткое имя» для сопоставления с EPG */
    fun short(name: String): String = name.lowercase()
        .replace(R_ARCHIVE, "")
        .replace(R_PLUS, " $1")
        .replace(R_TELE, "")
        .replace(R_SIGNS, " ").trim()
        .replace(R_KANAL, "$2")
        .replace(R_ORIG, "")
        .replace(R_NUM, "$1")

    /** Похожие кириллические буквы → латиница/цифры («нтв» и «HTB» совпадут) */
    fun translit(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(TR[c] ?: c)
        return sb.toString()
    }

    /**
     * Монограмма канала для плашки без логотипа: до трёх знаков. Одно слово — целиком, если оно
     * не длиннее трёх знаков («НТВ»), иначе две первые буквы («КА»); несколько слов — первые буквы,
     * число — целиком, пока есть место («Россия 24» → «Р24», «Матч! ТВ» → «МТ»).
     */
    fun monogram(name: String): String {
        val sb = StringBuilder(3)
        var words = 0
        var first = ""
        var i = 0
        val n = name.length
        while (i < n) {
            while (i < n && !name[i].isLetterOrDigit()) i++
            val s = i
            while (i < n && name[i].isLetterOrDigit()) i++
            if (i == s) break
            if (words++ == 0) first = name.substring(s, i)
            if (sb.length >= 3) continue
            if (name[s].isDigit()) sb.append(name, s, minOf(i, s + 3 - sb.length)) else sb.append(name[s])
        }
        return when (words) {
            0 -> "?"
            1 -> (if (first.length <= 3) first else first.substring(0, 2)).uppercase()
            else -> sb.toString().uppercase()
        }
    }

    /** Ключ сравнения: короткое имя с транслитом */
    fun matchKey(name: String): String = translit(short(name))
}
