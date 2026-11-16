package top.rootu.perfecttv.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.rootu.perfecttv.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обновление с GitHub: последний релиз (`releases/latest`: без черновиков и pre-release) → `.apk` из его файлов.
 * Релиз выпускает `.github/workflows/release.yml` по тегу `v<версия>`; текущая версия — `BuildConfig.VERSION_NAME`
 * (из `git describe`). Свой клиент OkHttp с обычной проверкой сертификатов — не [Net.client]: настройка «Принимать любые
 * сертификаты» относится к провайдерам IPTV, а не к загрузке программы. Подмена файла и без того не пройдёт: Android
 * ставит обновление только с той же подписью.
 */
object Updater {
    const val API = "https://api.github.com/repos/perfect-tv/perfect-tv-android/releases/latest"

    /** Не чаще раза в 6 часов при автоматической проверке */
    const val PERIOD_SECONDS = 6 * 3600L

    class Info(val version: String, val notes: String, val url: String, val size: Long)

    sealed class Result {
        class Found(val info: Info) : Result()
        object Latest : Result()
        object Failed : Result()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "updater").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ разбор и сравнение (чистые функции — тесты)

    /** Ответ `releases/latest` → [Info]; `null` — черновик, pre-release или нет `.apk` */
    fun parse(json: String): Info? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Info(o.getString("tag_name").removePrefix("v"), o.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /**
     * Новее ли [remote] ("1.2.3" или "v1.2.3"), чем [current] — версия сборки, у которой могут быть хвосты git describe
     * ("1.2.3-5-gabcdef", "1.2.3-dirty", "0.0.1-dev-abc"). Сравниваются числа до первого хвоста: сборка после тега
     * не старше своего тега, а откат на старый релиз не предлагается. Версия сборки без чисел (не разобрать) — новее.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = numbers(remote) ?: return false
        val c = numbers(current) ?: return true
        for (i in 0 until maxOf(r.size, c.size)) {
            val d = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun numbers(v: String): List<Int>? =
        Regex("""^\d+(\.\d+)*""").find(v.trim().removePrefix("v"))?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }

    /** Описание релиза без разметки Markdown: заголовки, ссылки, `**`, строка «Full Changelog»; не длиннее [max] знаков */
    fun cleanNotes(body: String, max: Int = 700): String {
        val lines = body.replace("\r", "").lines()
            .filterNot { it.contains("Full Changelog", ignoreCase = true) }
            .map { it.trim().removePrefix("#").trim().removePrefix("#").trim().removePrefix("#").trim() }
            .map { it.replace("**", "").replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1").replace(Regex("""https?://\S+"""), "").trim() }
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length > max) text.take(max).trimEnd() + "…" else text
    }

    fun toJson(i: Info): String = JSONObject().put("version", i.version).put("notes", i.notes).put("url", i.url).put("size", i.size).toString()

    fun fromJson(s: String): Info? = try {
        JSONObject(s).let { Info(it.getString("version"), it.optString("notes"), it.getString("url"), it.optLong("size")) }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ проверка

    private fun api() = if (BuildConfig.DEBUG && Prefs.updateApi.isNotEmpty()) Prefs.updateApi else API

    /** Сохранённая находка, если она всё ещё новее установленной версии */
    fun available(): Info? {
        val info = fromJson(Prefs.updateJson) ?: return null
        if (isNewer(info.version, BuildConfig.VERSION_NAME)) return info
        Prefs.updateJson = ""
        return null
    }

    /** Проверить в фоне; [done] — в главном потоке. Нет релизов (404) — «последняя версия», сбой сети — [Result.Failed] */
    fun check(done: (Result) -> Unit) {
        worker.execute {
            val r = try {
                val req = Request.Builder().url(api()).header("Accept", "application/vnd.github+json").header("User-Agent", "PerfectTV/" + BuildConfig.VERSION_NAME).build()
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> Result.Latest
                        !resp.isSuccessful -> Result.Failed
                        else -> {
                            val info = parse(resp.body!!.string())
                            if (info != null && isNewer(info.version, BuildConfig.VERSION_NAME)) Result.Found(info) else Result.Latest
                        }
                    }
                }
            } catch (_: Exception) {
                Result.Failed
            }
            if (r !is Result.Failed) {
                Prefs.updateLast = Clock.now()
                Prefs.updateJson = if (r is Result.Found) toJson(r.info) else ""
            }
            main.post { done(r) }
        }
    }

    // ------------------------------------------------------------------ загрузка и установка

    private fun file(a: Activity) = File(File(a.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    /** Скачивание в фоне: [progress] 0–100 и [done] (файл или `null` при сбое/отмене) — в главном потоке; [cancel] — отмена */
    fun download(a: Activity, info: Info, cancel: AtomicBoolean, progress: (Int) -> Unit, done: (File?) -> Unit) {
        val out = file(a)
        worker.execute {
            var ok = false
            try {
                val req = Request.Builder().url(info.url).header("User-Agent", "PerfectTV/" + BuildConfig.VERSION_NAME).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val total = resp.body!!.contentLength().takeIf { it > 0 } ?: info.size
                    resp.body!!.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(32 * 1024)
                            var sum = 0L
                            var last = -1
                            while (true) {
                                if (cancel.get()) throw IOException("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                sum += n
                                val p = if (total > 0) (sum * 100 / total).toInt().coerceIn(0, 100) else 0
                                if (p != last) { last = p; main.post { progress(p) } }
                            }
                            if (total > 0 && sum != total) throw IOException("incomplete $sum of $total")
                        }
                    }
                }
                // это наше приложение, а не страница ошибки
                @Suppress("DEPRECATION")
                ok = a.packageManager.getPackageArchiveInfo(out.path, 0)?.packageName == a.packageName
            } catch (_: Exception) {
            }
            if (!ok) out.delete()
            main.post { done(if (ok) out else null) }
        }
    }

    /** Результат [install] */
    enum class Install { STARTED, NEED_PERMISSION, FAILED }

    /** Передать APK установщику Android. Нет права «Установка из неизвестных источников» — открыть его настройку */
    fun install(a: Activity, apk: File): Install {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            return try {
                a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                Install.NEED_PERMISSION
            } catch (_: Exception) {
                Install.NEED_PERMISSION
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".updates", apk)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            Install.STARTED
        } catch (_: Exception) {
            Install.FAILED
        }
    }

    /** Удалить скачанный файл (после успешного обновления он не нужен) */
    fun cleanup(a: Activity) {
        file(a).delete()
    }
}
