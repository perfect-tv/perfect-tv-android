package top.rootu.perfecttv.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Diagnostics
import top.rootu.perfecttv.core.Lang
import top.rootu.perfecttv.core.PhoneInput
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.core.str
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Диагностика (Настройки → Данные) и подробности фатальной ошибки: текст отчёта ([Diagnostics]), копирование в буфер
 * и выгрузка на termbin.com со ссылкой и QR-кодом (QR рисуется на устройстве). Работает в процессе `:crash`.
 * Выгрузка — только по кнопке: текст уходит на внешний сервис, ссылку видит любой, у кого она есть
 * (адреса из лога сокращены до хоста).
 */
class ReportActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var text = ""

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        U.init(this)
        setContentView(R.layout.activity_report)
        findViewById<TextView>(R.id.report_title).text = str(if (intent.getBooleanExtra(EXTRA_CRASH, false)) R.string.report_title_crash else R.string.report_title)
        val tv = findViewById<TextView>(R.id.report_text)
        tv.text = "…"
        // список декодеров и лог читаются медленно — не в главном потоке
        Thread {
            val t = Diagnostics.text(Diagnostics.collect(applicationContext))
            main.post { text = t; tv.text = t }
        }.start()
        findViewById<View>(R.id.report_copy).setOnClickListener { copy() }
        findViewById<View>(R.id.report_upload).setOnClickListener { upload() }
        findViewById<View>(R.id.report_close).setOnClickListener { finish() }
        findViewById<View>(R.id.report_upload).requestFocus()
    }

    private fun copy() {
        if (text.isEmpty()) return
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", text))
            Toast.makeText(this, str(R.string.report_copied), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private var uploading = false

    private fun upload() {
        if (text.isEmpty() || uploading) return
        uploading = true
        val b = findViewById<TextView>(R.id.report_upload)
        b.text = str(R.string.report_uploading)
        val data = text
        Thread {
            val url = try {
                Socket("termbin.com", 9999).use { s ->
                    s.soTimeout = 10000
                    OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).also { it.write(data); it.flush() }
                    s.getInputStream().bufferedReader().readLine()?.trim()?.replace("\u0000", "")
                }
            } catch (e: Exception) { null }
            main.post {
                uploading = false
                if (url.isNullOrBlank() || !url.startsWith("http")) {
                    b.text = str(R.string.report_upload_retry)
                    Toast.makeText(this, str(R.string.report_upload_failed), Toast.LENGTH_LONG).show()
                } else {
                    b.visibility = View.GONE
                    findViewById<View>(R.id.report_hint).visibility = View.GONE
                    findViewById<View>(R.id.report_result).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.report_url).text = url
                    findViewById<ImageView>(R.id.report_qr).setImageBitmap(PhoneInput.qr(url, 450))
                    findViewById<View>(R.id.report_copy).requestFocus()
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_CRASH = "crash"
    }
}
