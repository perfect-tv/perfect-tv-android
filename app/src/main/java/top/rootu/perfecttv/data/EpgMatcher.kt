package top.rootu.perfecttv.data

/**
 * Сопоставление каналов плейлиста с каналами XMLTV одного источника:
 * tvg-id == id → tvg-name/имя == display-name (короткое имя с транслитом).
 */
class EpgMatcher {
    private val ids = HashSet<String>()
    private val idsLower = HashMap<String, String>()
    private val byName = HashMap<String, String>()

    fun addChannel(c: EpgChannelRaw) {
        ids.add(c.id)
        idsLower.getOrPut(c.id.lowercase()) { c.id }
        for (n in c.names) {
            val k = Names.matchKey(n)
            if (k.isNotEmpty()) byName.getOrPut(k) { c.id }
        }
    }

    val size get() = ids.size

    /** xml_id канала или null */
    fun match(tvgId: String, tvgName: String, name: String): String? {
        if (tvgId.isNotEmpty()) {
            if (tvgId in ids) return tvgId
            idsLower[tvgId.lowercase()]?.let { return it }
        }
        if (tvgName.isNotEmpty()) byName[Names.matchKey(tvgName)]?.let { return it }
        return byName[Names.matchKey(name)]
    }
}
