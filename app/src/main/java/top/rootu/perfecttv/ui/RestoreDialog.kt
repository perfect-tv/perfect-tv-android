package top.rootu.perfecttv.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import top.rootu.perfecttv.App
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.str
import top.rootu.perfecttv.data.Backup

/**
 * Восстановление из копии с подтверждением: «Восстановить копию от <дата из файла>?», кнопки «Отмена» (в фокусе —
 * заменяет плейлисты и данные, случайное OK ничего не ломает) и «Восстановить». Не копия Perfect TV — сообщение.
 * Общий для настроек и для открытия файла `*.perfecttv` ([RestoreActivity]).
 */
object RestoreDialog {
    fun show(a: Activity, json: String, onClosed: () -> Unit = {}) {
        val created = Backup.created(json)
        if (created == null) {
            Toast.makeText(a, str(R.string.backup_not_a_backup), Toast.LENGTH_LONG).show()
            return onClosed()
        }
        val v = a.layoutInflater.inflate(R.layout.restore_confirm, null)
        val date = if (created > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, top.rootu.perfecttv.core.Lang.locale)
            .format(java.util.Date(created * 1000)) else str(R.string.backup_unknown_date)
        v.findViewById<TextView>(R.id.restore_message).text = str(R.string.backup_restore_confirm_x, date)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(v)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val cancel = v.findViewById<TextView>(R.id.restore_cancel)
        val ok = v.findViewById<TextView>(R.id.restore_ok)
        var done = false
        d.setOnDismissListener { if (!done) onClosed() }
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener { done = true; d.dismiss(); restoreNow(a, json) }
        d.show()
        cancel.requestFocus()
    }

    /** Заменить данные копией и перезапустить приложение: экран ТВ и каталог строятся заново */
    fun restoreNow(a: Activity, json: String) {
        try {
            Backup.restore(App.repo.db.writableDatabase, json)
        } catch (e: Exception) {
            Toast.makeText(a, str(R.string.backup_failed_x, e.message ?: e.toString()), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(a, str(R.string.backup_restored), Toast.LENGTH_LONG).show()
        val launch = a.packageManager.getLeanbackLaunchIntentForPackage(a.packageName) ?: a.packageManager.getLaunchIntentForPackage(a.packageName)
        launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) a.startActivity(launch)
        a.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
