package app.heartpact.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.LogoMark
import app.heartpact.ui.theme.Heartpact

@Composable
fun SignInScreen(
    state: SignInState,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgot: () -> Unit,
    onCreateAccount: () -> Unit,
    onBack: () -> Unit,
    onGoogle: (() -> Unit)? = null,
    googleBusy: Boolean = false,
) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            LogoMark(44.dp, night = colors.isNight, modifier = Modifier.padding(top = 8.dp))
            Text(tr("Welcome back"), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 18.dp))
            Text(tr("Sign in to pick up where you and your partner left off."), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeartField(tr("Email"), state.email, onEmail, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                HeartField(tr("Password"), state.password, onPassword, password = true, error = state.error,
                    keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { onSubmit() }))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                HeartButton(tr("Forgot password?"), onForgot, kind = ButtonKind.Text, fillWidth = false, height = 40.dp)
            }
            state.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            HeartButton(tr("Sign in"), onSubmit, Modifier.padding(top = 6.dp), busy = state.busy)
            onGoogle?.let { HeartButton(tr("Continue with Google"), it, Modifier.padding(top = 8.dp), kind = ButtonKind.Outline, busy = googleBusy) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                buildAnnotatedString {
                    append(tr("New to Heartpact?"))
                    append(" ")
                    withStyle(SpanStyle(color = Heartpact.actor.onSoft, fontWeight = FontWeight.Bold)) { append(tr("Create an account")) }
                },
                style = Heartpact.type.body, color = colors.ink2,
                modifier = Modifier.clickable(onClick = onCreateAccount).padding(12.dp),
            )
        }
    }
}
