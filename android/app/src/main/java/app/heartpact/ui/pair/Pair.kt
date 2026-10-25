package app.heartpact.ui.pair

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.LiveLogo
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PairScreen(
    state: PairState,
    onCode: (String) -> Unit,
    onStartEntering: () -> Unit,
    onStopEntering: () -> Unit,
    onSubmitCode: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(tr("Find your other half"), trailing = { LiveLogo(30.dp, LogoMood.Waiting, night = colors.isNight) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Character(Who.Left, Pose.Wave, 124.dp)
                // The partner's half waits as a dashed slot until they join.
                Box(Modifier.offset(x = (-34).dp).padding(bottom = 22.dp)) { HeartSticker(HalfState.Empty, HalfState.Slot, 112.dp) }
            }
            if (state.entering) {
                StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(tr("Enter your partner's code"), style = Heartpact.type.title, color = colors.ink)
                        Text(tr("It's the 6 digits on their invite screen."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                        HeartField(tr("Invite code"), state.code, onCode, error = state.codeError,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { onSubmitCode() }))
                        HeartButton(tr("Join my partner"), onSubmitCode, Modifier.padding(top = 16.dp), busy = state.accepting)
                        HeartButton(tr("Show my code instead"), onStopEntering, kind = ButtonKind.Text)
                    }
                }
            } else {
                StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(tr("Your invite code"), style = Heartpact.type.labelSmall, color = colors.ink2)
                        val invite = state.invite
                        when {
                            state.loadingInvite -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.CenterStart) { CircularProgressIndicator(Modifier.size(28.dp), color = Heartpact.actor.action) }
                            invite == null -> {
                                NoteCard(state.inviteError ?: tr("Couldn't make an invite."), Modifier.padding(top = 10.dp))
                                HeartButton(tr("Try again"), onRetry, Modifier.padding(top = 12.dp), kind = ButtonKind.Tonal)
                            }
                            else -> {
                                Text(PairViewModel.formatCode(invite.code), style = Heartpact.type.displaySmall.copy(letterSpacing = 0.06.em), color = colors.ink, modifier = Modifier.padding(top = 10.dp))
                                Text(tr("Works once · expires {when}", "when" to expiry(invite.expiresAtUtc)), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
                                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HeartButton(tr("Share link"), {
                                        val text = tr("Join me on Heartpact: {link}\nOr enter the code {code} in the app.", "link" to PairViewModel.inviteLink(invite.token), "code" to PairViewModel.formatCode(invite.code))
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Share your invite")))
                                    }, Modifier.weight(1f), icon = Icons.Rounded.Share)
                                    HeartButton(tr("Copy code"), { clipboard.setText(AnnotatedString(invite.code)) }, Modifier.weight(1f), kind = ButtonKind.Tonal, icon = Icons.Rounded.ContentCopy)
                                }
                            }
                        }
                    }
                }
            }
            StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                CardRow(tr("You'll both see"), subtitle = tr("dares you set each other, your sticker page"), icon = Icons.Rounded.FavoriteBorder)
                CardRow(tr("You each choose"), subtitle = tr("location, cycle and food, all off until you share"), icon = Icons.Rounded.Tune, divider = true)
            }
            if (!state.entering) HeartButton(tr("I have a code from my partner"), onStartEntering, Modifier.padding(top = 12.dp), kind = ButtonKind.Text, icon = Icons.Rounded.Keyboard)
        }
        HeartButton(tr("Sign out"), onSignOut, Modifier.padding(bottom = 8.dp), kind = ButtonKind.Text)
    }
}

private fun expiry(isoUtc: String): String = runCatching {
    val local = Instant.parse(if (isoUtc.endsWith("Z") || isoUtc.contains('+')) isoUtc else "${isoUtc}Z").atZone(ZoneId.systemDefault())
    tr("{day} at {time}", "day" to local.format(Dates.formatter("EEEE")), "time" to local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
}.getOrDefault(tr("soon"))
