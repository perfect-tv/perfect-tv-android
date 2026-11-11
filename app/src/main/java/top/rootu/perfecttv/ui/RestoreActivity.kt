package top.rootu.perfecttv.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import top.rootu.perfecttv.R
import top.rootu.perfecttv.core.Lang
import top.rootu.perfecttv.core.str

/**
 * Открытие файла `*.perfecttv` (файловый менеджер, «Загрузки», мессенджер): прозрачный экран с подтверждением
 * восстановления ([RestoreDialog]). Файл читается целиком (копия — небольшой JSON).
 */
class RestoreActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        U.init(this)
        val uri = intent?.data
        val json = try { uri?.let { contentResolver.openInputStream(it)?.use { s -> s.readBytes().take(8 shl 20).toByteArray().toString(Charsets.UTF_8) } } } catch (_: Exception) { null }
        if (json == null) {
            Toast.makeText(this, str(R.string.backup_failed_x, uri?.toString() ?: ""), Toast.LENGTH_LONG).show()
            return finish()
        }
        RestoreDialog.show(this, json) { finish() }
    }
}
