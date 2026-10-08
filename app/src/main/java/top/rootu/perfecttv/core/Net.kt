package top.rootu.perfecttv.core

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Общий OkHttp: таймауты, кэш, редиректы; поправка часов по Date. HTTPS — с любыми сертификатами
 * (просроченные, самоподписанные, чужое имя узла): у провайдеров IPTV это обычное дело.
 */
object Net {
    const val USER_AGENT = "PerfectTV/1.0 (Linux; Android) ExoPlayer"

    lateinit var client: OkHttpClient
        private set

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun init(context: Context) {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        client = OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
            .addNetworkInterceptor { chain ->
                val r = chain.proceed(chain.request())
                Clock.syncWith(r.headers.getDate("Date"))
                r
            }
            .build()
    }

    class HttpError(val code: Int) : IOException("HTTP $code")

    /**
     * GET с условными заголовками. 304 возвращается как есть (тело пустое),
     * прочие не-2xx — [HttpError]. Ответ нужно закрыть.
     */
    fun get(url: String, etag: String = "", lastModified: String = "", userAgent: String = USER_AGENT): Response {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        if (etag.isNotEmpty()) b.header("If-None-Match", etag)
        if (lastModified.isNotEmpty()) b.header("If-Modified-Since", lastModified)
        // большие файлы читаем потоком, HTTP-кэш OkHttp для них не нужен
        b.cacheControl(okhttp3.CacheControl.Builder().noStore().build())
        val r = client.newCall(b.build()).execute()
        if (r.code == 304 || r.isSuccessful) return r
        r.close()
        throw HttpError(r.code)
    }
}
