package top.rootu.perfecttv.player

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Cookie провайдера на потоках (HLS-плейлист → ключи → сегменты): часть провайдеров выдаёт cookie на первом
 * запросе и без неё отказывает во всех следующих. Хранилище лёгкое и снисходительное: на хост ответа, по имени;
 * Domain, Path и Secure игнорируются (cookie провайдера нередко выдают с чужим Domain или с Secure на http-ссылке,
 * строгий разбор их бы отбросил). Живёт одного запуска канала — сбрасывается в [clear] при каждом `Playback.play`.
 * Сетевой перехватчик видит каждый шаг редиректа, поэтому cookie редиректа доходит до следующего запроса.
 */
class Cookies : Interceptor {
    private val byHost = HashMap<String, LinkedHashMap<String, String>>()

    @Synchronized fun clear() = byHost.clear()

    @Synchronized fun header(host: String): String? =
        byHost[host]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    /** Принять заголовки Set-Cookie ответа хоста [host] */
    @Synchronized fun store(host: String, setCookies: List<String>) {
        for (line in setCookies) {
            val c = parse(line) ?: continue
            val m = byHost.getOrPut(host) { LinkedHashMap() }
            if (c.second == null) m.remove(c.first) else m[c.first] = c.second!!
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var req = chain.request()
        val host = req.url.host
        // Cookie, заданный в заголовках канала, важнее
        if (req.header("Cookie") == null) header(host)?.let { req = req.newBuilder().header("Cookie", it).build() }
        val resp = chain.proceed(req)
        val set = resp.headers("Set-Cookie")
        if (set.isNotEmpty()) store(host, set)
        return resp
    }

    companion object {
        /**
         * Одна строка Set-Cookie → (имя, значение); значение null — cookie удаляется (Max-Age ≤ 0 или Expires
         * в прошлом не проверяется, только Max-Age: часы у приставок бывают неверные). Пустое имя — null.
         */
        fun parse(line: String): Pair<String, String?>? {
            val parts = line.split(';')
            val nv = parts[0]
            val eq = nv.indexOf('=')
            if (eq <= 0) return null
            val name = nv.substring(0, eq).trim()
            if (name.isEmpty()) return null
            val value = nv.substring(eq + 1).trim().removeSurrounding("\"")
            for (a in parts.drop(1)) {
                val i = a.indexOf('=')
                if (i > 0 && a.substring(0, i).trim().equals("max-age", true)) {
                    val age = a.substring(i + 1).trim().toLongOrNull()
                    if (age != null && age <= 0) return name to null
                }
            }
            return name to value
        }
    }
}
