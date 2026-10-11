package app.heartpact

import android.app.Application
import app.heartpact.data.AppContainer
import app.heartpact.push.Push

class HeartpactApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Push.init(this)
    }
}
