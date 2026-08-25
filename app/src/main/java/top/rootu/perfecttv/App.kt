package top.rootu.perfecttv

import android.app.Application
import top.rootu.perfecttv.core.Net
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        top.rootu.perfecttv.core.Lang.init(this)
        Net.init(this)
        repo = Repo(this)
        top.rootu.perfecttv.ui.Logos.init(this)
        // баннеры карточек главного экрана, которые давно не публиковались, больше не нужны
        Thread { top.rootu.perfecttv.data.Posters.expire(this) }.start()
    }

    companion object {
        lateinit var repo: Repo
            private set

        /** TvActivity на экране — напоминания показывает она, а не уведомления */
        @Volatile var foreground = false
    }
}
