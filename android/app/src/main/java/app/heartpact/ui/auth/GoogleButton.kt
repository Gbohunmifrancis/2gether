package app.heartpact.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.R
import app.heartpact.i18n.tr
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Which of Google's approved button texts a screen uses. */
enum class GoogleAction { Continue, SignUp, SignIn }

/**
 * Google's own sign-in button (developers.google.com/identity/branding-guidelines): the official "G" from Google's
 * asset pack, untouched; white with a grey outline by day and the dark theme at night; Roboto Medium, the phone's own
 * font; a pill, one of the two shapes Google allows.
 */
@Composable
fun GoogleButton(onClick: () -> Unit, modifier: Modifier = Modifier, action: GoogleAction = GoogleAction.Continue, busy: Boolean = false) {
    val night = Heartpact.colors.isNight
    val fill = if (night) Color(0xFF131314) else Color.White
    val outline = if (night) Color(0xFF8E918F) else Color(0xFF747775)
    val ink = if (night) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    val label = when (action) {
        GoogleAction.Continue -> tr("Continue with Google")
        GoogleAction.SignUp -> tr("Sign up with Google")
        GoogleAction.SignIn -> tr("Sign in with Google")
    }
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(Shapes.Pill).background(fill).border(BorderStroke(1.dp, outline), Shapes.Pill)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.google_g), null, Modifier.size(20.dp))
            Text(label, style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp), color = ink)
        }
    }
}
