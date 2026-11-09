package top.rootu.perfecttv.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import top.rootu.perfecttv.ui.CrashActivity
import kotlin.system.exitProcess

/**
 * Перехват фатальной ошибки: отчёт (устройство, исключение, хвост лога приложения) пишется в файл
 * `crash_last.txt`, показывается экраном [CrashActivity] в отдельном процессе `:crash` (основной уже мёртв),
 * потом доступен в Настройки → Данные → «Диагностика». Адреса в отчёте сокращены до хоста ([Diagnostics.redact]).
 */
object Crash {
    private const val FILE = "crash_last.txt"

    fun file(c: Context) = java.io.File(c.filesDir, FILE)

    /** Отчёт о последней ошибке или null */
    fun last(c: Context): String? = try { file(c).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    fun init(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sb = StringBuilder()
                sb.append("Time: ").append(java.util.Date()).append("\nThread: ").append(thread.name).append("\n\n")
                sb.append(Diagnostics.device(app).joinToString("\n")).append("\n\n--- Crash ---\n").append(e.stackTraceToString())
                sb.append("\n\n--- App log ---\n").append(Diagnostics.appLog(400).joinToString("\n"))
                file(app).writeText(Diagnostics.redact(sb.toString()))
                app.startActivity(Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, e)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** Процесс экрана ошибки и диагностики (`:crash`): тяжёлую инициализацию приложения там пропускаем */
    fun isCrashProcess(app: Application): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().trim('\u0000') } catch (_: Exception) { "" }
        return name.endsWith(":crash")
    }
}
