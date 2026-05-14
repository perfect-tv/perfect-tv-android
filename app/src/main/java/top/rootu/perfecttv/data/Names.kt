package top.rootu.perfecttv.data

/** Нормализация названий каналов (из tv_plugin: favId в core/utils.js, shortName/translit в playlist/epgmap.js) */
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

    /** Ключ сравнения: короткое имя с транслитом */
    fun matchKey(name: String): String = translit(short(name))
}
