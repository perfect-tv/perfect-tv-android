package top.rootu.perfecttv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Crash
import top.rootu.perfecttv.core.Lang
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.core.str

/**
 * Экран фатальной ошибки (процесс `:crash`, [Crash]): что случилось одной строкой, «Перезапустить», «Подробности»
 * ([ReportActivity]: отчёт, копирование, выгрузка с QR), «Выйти». Назад — выйти.
 */
class CrashActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        U.init(this)
        setContentView(R.layout.activity_crash)
        // в отчёте после «--- Crash ---» идёт трассировка: первая строка — класс и сообщение исключения
        val first = Crash.last(this)?.substringAfter("--- Crash ---\n", "")?.lineSequence()?.firstOrNull().orEmpty()
        findViewById<TextView>(R.id.crash_message).text = str(R.string.crash_message) + if (first.isNotEmpty()) "\n\n" + first.take(160) else ""
        findViewById<TextView>(R.id.crash_restart).setOnClickListener { restart() }
        findViewById<TextView>(R.id.crash_details).setOnClickListener { startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_CRASH, true)) }
        findViewById<TextView>(R.id.crash_exit).setOnClickListener { exit() }
        findViewById<TextView>(R.id.crash_restart).requestFocus()
    }

    private fun restart() {
        val i = packageManager.getLaunchIntentForPackage(packageName)
        if (i != null) startActivity(Intent.makeRestartActivityTask(i.component))
        exit()
    }

    private fun exit() {
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = exit()
}
