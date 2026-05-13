package top.rootu.perfecttv

import android.app.Application
import top.rootu.perfecttv.core.Net
import top.rootu.perfecttv.core.Prefs
import top.rootu.perfecttv.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Net.init(this)
        repo = Repo(this)
    }

    companion object {
        lateinit var repo: Repo
            private set
    }
}
