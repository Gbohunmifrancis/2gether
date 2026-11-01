package app.heartpact.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.autofill.ContentType
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
import app.heartpact.ui.graphics.LiveLogo
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.theme.Heartpact

/**
 * Signing in: Google first for accounts made with it, "or", then email and password. The Sign in button sits above the
 * keyboard; Done on the password signs in.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    /** A partner's invite link is waiting: "You'll be linked with Maya". */
    invitedBy: String? = null,
) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // It says hello, and looks worried when something went wrong.
            LiveLogo(44.dp, if (state.error != null || state.note != null) LogoMood.Worried else LogoMood.Hello, night = colors.isNight, modifier = Modifier.padding(top = 8.dp))
            Text(tr("Welcome back"), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 14.dp))
            Text(tr("Sign in to pick up where you and your partner left off."), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            invitedBy?.let { InvitedCard(tr("You'll be linked with {name} as soon as you're signed in.", "name" to it), Modifier.padding(bottom = 16.dp)) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                onGoogle?.let {
                    GoogleButton(it, action = GoogleAction.Continue, busy = googleBusy)
                    OrDivider(tr("or"))
                }
                HeartField(tr("Email"), state.email, { onEmail(it.trim()) },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    contentType = ContentType.Username + ContentType.EmailAddress)
                HeartField(tr("Password"), state.password, onPassword, password = true, error = state.error,
                    keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { onSubmit() }), contentType = ContentType.Password)
            }
            // Wrong email or password: the server can't say an account uses Google without revealing who has an
            // account, so this is for everyone who might have signed up that way.
            if (state.wrongPassword && onGoogle != null && state.error != null) {
                Text(tr("Signed up with Google? Use Continue with Google above."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                HeartButton(tr("Forgot password?"), onForgot, kind = ButtonKind.Text, fillWidth = false, height = 40.dp)
            }
            state.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp)) {
            HeartButton(tr("Sign in"), onSubmit, busy = state.busy)
        }
        if (!WindowInsets.isImeVisible) Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
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
