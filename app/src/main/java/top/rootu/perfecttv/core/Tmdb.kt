package top.rootu.perfecttv.core

import top.rootu.perfecttv.R

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Поиск передачи на TMDB: баннер (backdrop), постер и описание.
 *
 * Запрос — очищенное название (без серий, сезонов, возраста), год — из «(2019)» в названии
 * или начала описания EPG. Совпадение — по сходству названий (Левенштейн) с бонусом за год.
 * Без года — только однозначный поиск с почти точным названием (новости «Сегодня» ≠ шоу «Today»).
 *
 * Ключ — в настройках: v3 (api_key, 32 символа) или v4 (токен чтения — заголовок Bearer).
 * Адреса API и картинок можно заменить (прокси, если TMDB недоступен).
 */
object Tmdb {

    class Result(
        val title: String,
        val original: String,
        val year: String,
        val overview: String,
        val backdrop: String,
        val poster: String,
        val vote: Double,
        /** поиск дал ровно один результат — описанию можно доверять */
        val unique: Boolean
    )

    /** Кандидат из ответа поиска (для [pick]) */
    class Hit(
        val title: String,
        val original: String,
        val date: String,
        val backdropPath: String,
        val posterPath: String,
        val overview: String,
        val vote: Double,
        val mediaType: String
    )

    private const val MIN_SIMILARITY = 0.72
    private val cache = HashMap<String, Result?>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "tmdb").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    val enabled get() = Prefs.tmdbKey.isNotBlank()

    // ------------------------------------------------------------------ текст

    private val R_AGE = Regex("\\s*[\\[(]?(\\d|1\\d|2[0-5])\\+[\\])]?[.\\s]*$")
    private val R_QUOTES = Regex("^«([^»]+)».*$")
    private val R_DQUOTES = Regex("^\"([^\"]+)\".*$")
    private val R_YEAR = Regex("\\s*\\((19\\d\\d|20[0-4]\\d)\\)\\s*(\\*\\s.+)?$")
    private val R_EPISODE_TAIL = Regex("[.,]\\s*(\\d+(-й)?\\s+(с-н|сезон)|(с-н|сезон)\\s+\\d+|[s][-.\\s]*\\d+(-й)?)?[-.,\\s]*(\\d+(-\\d+|-я)?\\s*(сери.|эпизоды?|episode|[cс]|ep?)\\.?|(сери.|эпизоды?|episode|[cс]|ep?)[-.]?\\s*\\d+).*$", RegexOption.IGNORE_CASE)
    private val R_EPISODE = Regex("\\s*(\\d+(-й)?\\s+(с-н|сезон)|(с-н|сезон)\\s+\\d+|[s][-.\\s]*\\d+(-й)?)?[-.,\\s]*(\\d+(-\\d+|-я)?\\s*(сери.|эпизоды?|episode|[cс]|ep?)\\.?|(сери.|эпизоды?|episode|[cс]|ep?)[-.]?\\s*\\d+)\\.?", RegexOption.IGNORE_CASE)
    private val R_DIGEST = Regex("\\.\\s+Дайджест\\s*$", RegexOption.IGNORE_CASE)
    private val R_EP_BRACKETS = Regex("\\.?\\s*\\(([cCсСeE](ерия|pisode)?[-.]?\\s*\\d+|\\d+(-[^)\\s]+)?\\s+[Сс]ерия)\\)")
    private val R_PART = Regex("\\.[^.:]+:\\s*[Чч](асть|\\.)\\s+\\d+\\S*$")
    private val R_COLLECTION = Regex("\\.\\s*Сборник\\s+\\d+\\S*\\s*$", RegexOption.IGNORE_CASE)
    private val R_EMPTY = Regex("\\s*(\\(\\)|\\[\\])")

    /** Очистка названия передачи для поиска (серии, сезоны, возраст) */
    fun searchQuery(title: String): String = title
        .replace(R_AGE, "")
        .replace(R_QUOTES, "$1")
        .replace(R_DQUOTES, "$1")
        .replace(R_YEAR, "")
        .replace(R_EPISODE_TAIL, "")
        .replaceFirst(R_EPISODE, "")
        .replace(R_DIGEST, "")
        .replaceFirst(R_EP_BRACKETS, "")
        .replace(R_PART, "")
        .replace(R_COLLECTION, "")
        .replace(R_AGE, "")
        .replaceFirst(R_EMPTY, "")
        .trim()

    private val R_NORM = Regex("[^a-zа-я0-9]+", RegexOption.IGNORE_CASE)
    private val R_SPACES = Regex("\\s+")

    fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace(R_NORM, " ").replace(R_SPACES, " ").trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Сходство 0..1 */
    fun similarity(a0: String, b0: String): Double {
        val a = normalize(a0)
        val b = normalize(b0)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private val R_TITLE_YEAR = Regex("\\((19\\d\\d|20\\d\\d)\\)")
    private val R_DESC_YEAR = Regex("(?:^|[\\s,(])((?:19|20)\\d\\d)(?:\\s*г\\.?)?(?=[\\s,.)]|$)")

    /** Год выпуска из названия или начала описания EPG (0 — нет) */
    fun guessYear(title: String, desc: String): Int {
        R_TITLE_YEAR.find(title)?.let { return it.groupValues[1].toInt() }
        return R_DESC_YEAR.find(desc.take(200))?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** Лучший результат поиска или null */
    fun pick(results: List<Hit>, query: String, year: Int): Hit? {
        val list = results.filter { it.mediaType.isEmpty() || it.mediaType == "movie" || it.mediaType == "tv" }
        fun sim(r: Hit) = maxOf(similarity(query, r.title), similarity(query, r.original))
        if (year == 0) {
            if (list.size != 1) return null
            return list[0].takeIf { sim(it) >= 0.9 }
        }
        var best: Hit? = null
        var bestScore = 0.0
        for (r in list) {
            val s = sim(r)
            val ry = r.date.take(4).toIntOrNull() ?: 0
            val bonus = if (ry == 0) 0.0 else if (ry == year) 0.15 else if (Math.abs(ry - year) <= 1) 0.05 else -0.1
            if (s < MIN_SIMILARITY && !(s >= 0.6 && ry == year)) continue
            val score = s + bonus + if (r.backdropPath.isNotEmpty()) 0.02 else 0.0
            if (score > bestScore) {
                best = r
                bestScore = score
            }
        }
        return best
    }

    // ------------------------------------------------------------------ сеть

    /**
     * Найти передачу; [done] — в главном потоке (null — не нашлось, ключа нет или ошибка).
     * Кэш — в памяти на время работы приложения.
     */
    fun find(title: String, desc: String, done: (Result?) -> Unit) {
        val key = Prefs.tmdbKey.trim()
        val query = searchQuery(title)
        if (key.isEmpty() || normalize(query).length < 3) return done(null)
        val year = guessYear(title, desc)
        val ck = normalize(query) + "|" + year
        if (cache.containsKey(ck)) return done(cache[ck])
        pool.execute {
            val r = try { search(key, query, year) } catch (e: Exception) {
                android.util.Log.w("IPTV", "tmdb: $e")
                main.post { done(null) }
                return@execute
            }
            main.post {
                cache[ck] = r
                done(r)
            }
        }
    }

    private fun search(key: String, query: String, year: Int): Result? {
        val lang = if (Lang.isEn) "en-US" else "ru-RU"
        val v4 = key.length > 40
        val api = Prefs.tmdbApi.trimEnd('/')
        var url = "$api/search/multi?query=" + URLEncoder.encode(query, "UTF-8") + "&language=$lang&include_adult=false"
        if (!v4) url += "&api_key=" + URLEncoder.encode(key, "UTF-8")
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (v4) b.header("Authorization", "Bearer $key")
        Net.client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Net.HttpError(resp.code)
            val json = JSONObject(resp.body?.string() ?: "{}")
            val arr = json.optJSONArray("results") ?: return null
            val hits = ArrayList<Hit>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = o.optString("media_type")
                if (type == "person") continue
                hits.add(Hit(
                    title = o.optString("title").ifEmpty { o.optString("name") },
                    original = o.optString("original_title").ifEmpty { o.optString("original_name") },
                    date = o.optString("release_date").ifEmpty { o.optString("first_air_date") },
                    backdropPath = o.optString("backdrop_path").takeIf { it != "null" } ?: "",
                    posterPath = o.optString("poster_path").takeIf { it != "null" } ?: "",
                    overview = o.optString("overview"),
                    vote = o.optDouble("vote_average", 0.0),
                    mediaType = type
                ))
            }
            val r = pick(hits, query, year) ?: return null
            val img = Prefs.tmdbImages.trimEnd('/')
            return Result(
                title = r.title, original = r.original, year = r.date.take(4), overview = r.overview,
                backdrop = if (r.backdropPath.isNotEmpty()) "$img/t/p/w780${r.backdropPath}" else "",
                poster = if (r.posterPath.isNotEmpty()) "$img/t/p/w300${r.posterPath}" else "",
                vote = Math.round(r.vote * 10) / 10.0,
                unique = hits.size == 1
            )
        }
    }

    /** Проверка ключа из настроек: [done] в главном потоке, null — работает, иначе текст ошибки */
    fun test(done: (String?) -> Unit) {
        val key = Prefs.tmdbKey.trim()
        if (key.isEmpty()) return done(str(R.string.no_key))
        pool.execute {
            val err = try {
                search(key, "Ирония судьбы", 1975)
                null
            } catch (e: Net.HttpError) {
                if (e.code == 401) str(R.string.invalid_key) else str(R.string.error_x, e.code)
            } catch (e: Exception) {
                str(R.string.no_connection_x, e.message)
            }
            main.post { done(err) }
        }
    }
}
