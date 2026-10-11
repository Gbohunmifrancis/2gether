package app.heartpact.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.LogoMark
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogoMark(28.dp, night = colors.isNight)
            Text("heartpact", style = Heartpact.type.wordmark, color = colors.ink)
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Character(Who.Both, Pose.Wave, 200.dp)
        }
        Text("Keep your promises to each other.", style = Heartpact.type.headline.copy(fontSize = 34.sp, lineHeight = 37.sp), color = colors.ink)
        Text(
            "Dare each other to move. The camera counts the reps, and every promise you keep becomes a heart on your page.",
            style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
        )
        HeartButton("Create an account", onCreateAccount)
        HeartButton("I already have an account", onSignIn, Modifier.padding(top = 8.dp), kind = ButtonKind.Outline)
        Text(
            "Got an invite code from your partner? You'll enter it after you sign up.",
            style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        )
    }
}
