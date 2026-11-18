package top.rootu.perfecttv.player

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookie провайдера на потоках (HLS-плейлист → ключи → сегменты): часть провайдеров выдаёт cookie на первом
 * запросе и без неё отказывает во всех следующих. Обычное хранилище по RFC 6265 (разбор — `okhttp3.Cookie`:
 * Domain, Path, Secure, Max-Age / Expires; cookie с чужим Domain отбрасывается). Только в памяти и на время
 * одного запуска канала — сбрасывается в [clear] при каждом `Playback.play`. Cookie, заданный в заголовках
 * канала, перекрывается, если провайдер выдал свои (OkHttp ставит заголовок из хранилища поверх).
 */
class Cookies : CookieJar {
    private val list = ArrayList<Cookie>()

    @Synchronized fun clear() = list.clear()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) list.add(c)
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        list.removeAll { it.expiresAt <= now }
        return list.filter { it.matches(url) }
    }
}
