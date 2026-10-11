package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.HeartpactTheme
import com.github.takahirom.roborazzi.captureRoboImage

/** Screenshots land in android/app/build/screens and are compared against the PNG mockups by eye. */
fun shot(name: String, night: Boolean = false, content: @Composable () -> Unit) {
    captureRoboImage("build/screens/$name.png") {
        HeartpactTheme(night = night) {
            Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) { content() }
        }
    }
}
