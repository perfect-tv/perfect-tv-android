package top.rootu.perfecttv.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import top.rootu.perfecttv.BuildConfig
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Clock
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.core.Updater
import top.rootu.perfecttv.core.str
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Окно «Доступна версия X»: что нового, «Отмена» и «Обновить» (в фокусе). «Обновить» качает APK с прогрессом и передаёт
 * его установщику Android; «Отмена» во время загрузки прерывает её. При запуске приложения окно по версии показывается
 * один раз ([auto]); в Настройки → О программе обновиться можно всегда.
 */
object UpdateDialog {
    /** При запуске: окно о ранее найденной версии и (не чаще раза в 6 часов) новая проверка. В debug — только со своим [Prefs.updateApi] */
    fun auto(a: Activity) {
        if (!Prefs.updateCheck || (BuildConfig.DEBUG && Prefs.updateApi.isEmpty())) return
        Updater.available()?.let { showOnce(a, it) }
        if (Clock.now() - Prefs.updateLast < Updater.PERIOD_SECONDS) return
        Updater.check { r -> if (r is Updater.Result.Found) showOnce(a, r.info) }
    }

    private fun showOnce(a: Activity, info: Updater.Info) {
        if (a.isFinishing || a.isDestroyed || Prefs.updateSeen == info.version) return
        Prefs.updateSeen = info.version
        show(a, info)
    }

    fun show(a: Activity, info: Updater.Info) {
        val v = a.layoutInflater.inflate(R.layout.update_dialog, null)
        v.findViewById<TextView>(R.id.update_title).text = str(R.string.update_title_x, info.version)
        v.findViewById<TextView>(R.id.update_current).text = str(R.string.update_current_x, BuildConfig.VERSION_NAME)
        val notes = Updater.cleanNotes(info.notes)
        v.findViewById<TextView>(R.id.update_notes).apply { text = notes; visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE }
        val bar = v.findViewById<ProgressBar>(R.id.update_progress)
        val cancel = v.findViewById<TextView>(R.id.update_cancel)
        val ok = v.findViewById<TextView>(R.id.update_ok)
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(v)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val stop = AtomicBoolean(false)
        var busy = false
        var apk: File? = null
        d.setOnDismissListener { stop.set(true) }
        cancel.setOnClickListener { d.dismiss() }

        fun install(f: File) {
            when (Updater.install(a, f)) {
                Updater.Install.STARTED -> d.dismiss()
                Updater.Install.NEED_PERMISSION -> {
                    Toast.makeText(a, str(R.string.update_allow_install), Toast.LENGTH_LONG).show()
                    ok.text = str(R.string.update_install)
                }
                Updater.Install.FAILED -> Toast.makeText(a, str(R.string.update_install_failed), Toast.LENGTH_LONG).show()
            }
        }
        ok.setOnClickListener {
            if (busy) return@setOnClickListener
            apk?.let { return@setOnClickListener install(it) }
            busy = true
            ok.isEnabled = false
            bar.visibility = View.VISIBLE
            ok.text = str(R.string.update_downloading_x, 0)
            Updater.download(a, info, stop, { p ->
                bar.progress = p
                ok.text = str(R.string.update_downloading_x, p)
            }) { f ->
                busy = false
                ok.isEnabled = true
                bar.visibility = View.GONE
                if (f == null) {
                    if (!stop.get()) Toast.makeText(a, str(R.string.update_download_failed), Toast.LENGTH_LONG).show()
                    ok.text = str(R.string.update_do)
                } else if (!stop.get()) {
                    apk = f
                    install(f)
                }
            }
        }
        d.show()
        // фокус на «Обновить»: окно само ставит его на первую кнопку уже после show()
        ok.post { ok.requestFocus() }
    }

    /** Ручная проверка из настроек: найдена новая версия — окно, иначе сообщение. [done] — когда проверка закончилась */
    fun checkNow(a: Activity, done: () -> Unit = {}) {
        Toast.makeText(a, str(R.string.update_checking), Toast.LENGTH_SHORT).show()
        Updater.check { r ->
            done()
            if (a.isFinishing || a.isDestroyed) return@check
            when (r) {
                is Updater.Result.Found -> { Prefs.updateSeen = r.info.version; show(a, r.info) }
                Updater.Result.Latest -> Toast.makeText(a, str(R.string.update_latest), Toast.LENGTH_LONG).show()
                Updater.Result.Failed -> Toast.makeText(a, str(R.string.update_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
