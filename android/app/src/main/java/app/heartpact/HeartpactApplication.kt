package app.heartpact

import android.app.Application
import app.heartpact.data.AppContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.heartpact.push.Push

class HeartpactApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Push.init(this)
        // Live updates while the app is on screen; a location share holds the hub on its own.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.live.hold("app") }
            override fun onStop(owner: LifecycleOwner) { container.live.release("app") }
        })
    }
}
