package top.rootu.perfecttv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import top.rootu.perfecttv.App
import top.rootu.perfecttv.core.Alarms
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.player.Playback

/**
 * Единственный экран просмотра: видео во весь экран и слои интерфейса над ним ([Tv]).
 * Без плейлистов сразу открываются настройки (мастер первого плейлиста).
 */
class TvActivity : Activity() {

    private var tv: Tv? = null
    private var playback: Playback? = null
    private lateinit var root: FrameLayout

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        U.init(this)
        Logos.init(this)
        Icons.init(this)
        Anim.on = Prefs.animations
        root = FrameLayout(this)
        root.setBackgroundColor(U.BLACK)
        val pb = Playback(this)
        playback = pb
        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(U.BLACK)
            setKeepContentOnPlayerReset(true)
            player = pb.player
        }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        tv = Tv(this, root, pb)
        reminderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reminderFromIntent(intent)
    }

    // уведомление о напоминании: включить передачу, когда плейлист откроется
    private var pendingReminder: String? = null

    private fun reminderFromIntent(i: Intent?) {
        pendingReminder = i?.getStringExtra(Alarms.EXTRA_REMINDER) ?: return
        i.removeExtra(Alarms.EXTRA_REMINDER)
    }

    override fun onResume() {
        super.onResume()
        App.foreground = true
        pendingReminder?.let { id ->
            pendingReminder = null
            root.post { tv?.launchReminder(id) }
        }
    }

    override fun onPause() {
        App.foreground = false
        Alarms.schedule(this)
        super.onPause()
    }

    /** Разрешение на уведомления (Android 13+) — чтобы напоминание дошло, когда приложение закрыто */
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val t = tv ?: return
        val list = App.repo.playlists()
        if (list.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FIRST, true))
            return
        }
        val p = list.firstOrNull { it.id == Prefs.lastPlaylist } ?: list[0]
        if (t.playlist?.id != p.id || t.catalog == null) t.switchPlaylist(p)
        else {
            // вернулись из настроек: плейлист мог измениться
            val fresh = App.repo.playlist(p.id)
            if (fresh != null && (fresh.url != t.playlist?.url || fresh.epgUrls() != t.playlist?.epgUrls())) t.switchPlaylist(fresh)
            else t.state.channel?.let { if (playback?.player?.isPlaying != true) t.playChannel(it, t.state.groupKey, reload = true) }
        }
    }

    override fun onStop() {
        super.onStop()
        playback?.stop()
    }

    override fun onDestroy() {
        tv?.destroy()
        playback?.release()
        super.onDestroy()
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Клавиши пульта — сразу в [Tv], мимо View: иначе стрелки уводят фокус Android на
     * кликабельные элементы окон, и OK «кликает» по ним вместо нашего действия.
     * Тач и мышь по-прежнему идут к View (клики по строкам и кнопкам).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val t = tv ?: return super.dispatchKeyEvent(event)
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> t.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> t.onKeyUp(event.keyCode)
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }
}
