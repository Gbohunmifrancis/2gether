package app.heartpact

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.heartpact.album.AlbumNotices
import app.heartpact.i18n.Tr
import app.heartpact.push.AppForeground
import app.heartpact.push.PushNotifier
import app.heartpact.ui.AppViewModel
import app.heartpact.ui.HeartpactApp
import app.heartpact.ui.graphics.ProvideRive

class MainActivity : ComponentActivity() {
    private val container get() = (application as HeartpactApplication).container
    private val appModel: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(container) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash is a plain indigo frame; the walk-together scene starts as soon as Compose draws.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Tr.load(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        // One Rive file (the 3D icons and the splash) for the whole app.
        setContent { ProvideRive { HeartpactApp(container, appModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppForeground.resumed = true
    }

    override fun onPause() {
        AppForeground.resumed = false
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(AlbumNotices.EXTRA_OPEN_TAB)?.let { appModel.openTab(it) }
        // A screen over Home ("watch/{occurrenceId}" for a dare going live); one this app doesn't have opens the tab.
        intent?.getStringExtra(PushNotifier.EXTRA_OPEN_ROUTE)?.let { appModel.openRoute(it) }
        intent?.takeIf { it.hasExtra(PushNotifier.EXTRA_CLEAR) }?.let { PushNotifier.dismiss(this, it.getIntExtra(PushNotifier.EXTRA_CLEAR, 0)) }
        handleInvite(intent)
    }

    /** https://heartpact.me/invite/{token}: kept until signed in (through signing up), then pairing joins with it. */
    private fun handleInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        app.heartpact.ui.pair.InviteParsing.tokenFromPath(uri.host, uri.pathSegments)?.let { appModel.openedInvite(it) }
    }
}
