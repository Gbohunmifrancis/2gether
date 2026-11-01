package app.heartpact.ui.pair

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
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
import kotlinx.coroutines.delay
import java.time.Instant

/** What the pair screen can do. */
class PairActions(
    val code: (String) -> Unit = {},
    val startEntering: () -> Unit = {},
    val stopEntering: () -> Unit = {},
    val submitCode: () -> Unit = {},
    val retry: () -> Unit = {},
    val newCode: () -> Unit = {},
    val scan: () -> Unit = {},
    /** A link or code found on the clipboard, chosen from the paste offer. */
    val use: (InviteInput) -> Unit = {},
    val confirmLink: () -> Unit = {},
    val declineLink: () -> Unit = {},
    val celebrated: () -> Unit = {},
    val signOut: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PairScreen(
    state: PairState,
    actions: PairActions,
    /** Straight after signing up: look around first and connect later, from the banner at the top of the app. */
    onLater: (() -> Unit)? = null,
    /** Opened from Home ("Connect"): back to Home, and no sign-out here. */
    onBack: (() -> Unit)? = null,
    /** The clock for the countdown; screenshots pin it. */
    now: Instant? = null,
    /** What the clipboard offered last (screenshots set it; on a phone it is read when the code field is focused or tapped). */
    pasteOffer: InviteInput? = null,
) {
    state.celebrating?.let { LinkedCelebration(it, actions.celebrated); return }
    state.joining?.let { JoiningMoment(it); return }

    val colors = Heartpact.colors
    var clock by remember { mutableStateOf(now ?: Instant.now()) }
    // Ticks while a countdown shows (the last hour), otherwise a minute is plenty to notice the expiry.
    val ticking = state.invite?.let { InviteExpiry.ticking(it.expiresAtUtc, clock) } == true
    LaunchedEffect(now, ticking) {
        if (now != null) return@LaunchedEffect
        while (true) { delay(if (ticking) 15_000 else 60_000); clock = Instant.now() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(tr("Find your other half"), onBack = onBack, trailing = { LiveLogo(30.dp, LogoMood.Waiting, night = colors.isNight) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!WindowInsets.isImeVisible) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Character(Who.Left, Pose.Wave, 108.dp)
                // The partner's half waits as a dashed slot until they join.
                Box(Modifier.offset(x = (-30).dp).padding(bottom = 20.dp)) { HeartSticker(HalfState.Empty, HalfState.Slot, 98.dp) }
            }
            state.confirm?.let { ConfirmCard(it, actions) }
            if (state.entering) EnterCard(state, actions, pasteOffer)
            else {
                InviteCard(state, actions, clock)
                QrCard(state, actions, clock)
            }
            StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                CardRow(tr("You'll both see"), subtitle = tr("dares you set each other, your sticker page"), icon = Icons.Rounded.FavoriteBorder)
                CardRow(tr("You each choose"), subtitle = tr("location, cycle and food, all off until you share"), icon = Icons.Rounded.Tune, divider = true)
            }
            if (!state.entering) HeartButton(tr("I have a code from my partner"), actions.startEntering, Modifier.padding(top = 12.dp), kind = ButtonKind.Text, icon = Icons.Rounded.Keyboard)
            // At the end of the page rather than pinned: the room at the bottom is for "Connect later".
            if (onBack == null) HeartButton(tr("Sign out"), actions.signOut, Modifier.padding(top = 4.dp, bottom = 8.dp), kind = ButtonKind.Text)
        }
        if (!WindowInsets.isImeVisible) {
            onLater?.let {
                HeartButton(tr("Connect later"), it, Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), kind = ButtonKind.Outline)
                Text(tr("You can look around first. Your invite code stays at the top of the app until you connect."), style = Heartpact.type.small,
                    color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp))
            }
        }
    }
}

/** A link that opened the app while signed in: who it's from, and a yes before linking. */
@Composable
private fun ConfirmCard(confirm: LinkConfirm, actions: PairActions) {
    val colors = Heartpact.colors
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(confirm.name?.let { tr("Join {name}?", "name" to it) } ?: tr("Join your partner?"), style = Heartpact.type.title, color = colors.ink)
            Text(
                confirm.name?.let { tr("{name} sent you an invite. Linking puts you on one page: dares for each other, and your stickers.", "name" to it) }
                    ?: tr("You opened an invite link. Linking puts you on one page: dares for each other, and your stickers."),
                style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            HeartButton(confirm.name?.let { tr("Join {name}", "name" to it) } ?: tr("Join my partner"), actions.confirmLink)
            HeartButton(tr("Not now"), actions.declineLink, kind = ButtonKind.Text)
        }
    }
}

@Composable
private fun EnterCard(state: PairState, actions: PairActions, initialOffer: InviteInput?) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    var offer by remember { mutableStateOf(initialOffer) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up with the boxes; focusing them is also when the clipboard is looked at.
    LaunchedEffect(Unit) { if (initialOffer == null) runCatching { focus.requestFocus() } }
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(tr("Enter your partner's code"), style = Heartpact.type.title, color = colors.ink)
            Text(tr("It's the 6 digits on their invite screen."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            CodeBoxes(state.code, actions.code, error = state.codeError, focusRequester = focus, onDone = actions.submitCode,
                onCheckClipboard = { offer = ClipboardInvite.read(context)?.takeIf { it != InviteInput.Code(state.code) } })
            AnimatedVisibility(offer != null && !state.accepting) {
                val shown = offer
                HeartButton(
                    when (shown) {
                        is InviteInput.Code -> tr("Paste {code}", "code" to InviteParsing.format(shown.digits))
                        else -> tr("Use the invite link you copied")
                    },
                    { shown?.let(actions.use); offer = null },
                    Modifier.padding(top = 14.dp), kind = ButtonKind.Tonal, icon = Icons.Rounded.ContentPaste, height = 48.dp,
                )
            }
            if (state.accepting) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                Spinner(app.heartpact.ui.components.rememberStill())
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                HeartButton(tr("Scan their code"), actions.scan, kind = ButtonKind.Text, fillWidth = false, icon = Icons.Rounded.QrCodeScanner)
                HeartButton(tr("Show my code"), actions.stopEntering, kind = ButtonKind.Text, fillWidth = false)
            }
        }
    }
}

@Composable
private fun InviteCard(state: PairState, actions: PairActions, now: Instant) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(tr("Your invite code"), style = Heartpact.type.labelSmall, color = colors.ink2)
            val invite = state.invite
            when {
                state.loadingInvite -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.CenterStart) { CircularProgressIndicator(Modifier.size(28.dp), color = Heartpact.actor.action) }
                invite == null -> {
                    NoteCard(state.inviteError ?: tr("Couldn't make an invite."), Modifier.padding(top = 10.dp))
                    HeartButton(tr("Try again"), actions.retry, Modifier.padding(top = 12.dp), kind = ButtonKind.Tonal)
                }
                else -> {
                    val expired = InviteExpiry.expired(invite.expiresAtUtc, now)
                    Text(InviteParsing.format(invite.code), style = Heartpact.type.displaySmall.copy(letterSpacing = 0.06.em),
                        color = if (expired) colors.ink3 else colors.ink, modifier = Modifier.padding(top = 10.dp))
                    Text(InviteExpiry.text(invite.expiresAtUtc, now), style = Heartpact.type.small,
                        color = if (expired || InviteExpiry.ticking(invite.expiresAtUtc, now)) colors.error else colors.ink2, modifier = Modifier.padding(top = 8.dp))
                    state.inviteError?.let { NoteCard(it, Modifier.padding(top = 10.dp)) }
                    if (expired) {
                        HeartButton(tr("Get a new code"), actions.newCode, Modifier.padding(top = 18.dp), busy = state.renewing, icon = Icons.Rounded.Refresh)
                    } else {
                        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeartButton(tr("Share"), {
                                val text = InviteShare.message(invite.token, invite.code)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Share your invite")))
                            }, Modifier.weight(1f), icon = Icons.Rounded.Share)
                            HeartButton(if (copied) tr("Copied") else tr("Copy code"), { clipboard.setText(AnnotatedString(invite.code)); copied = true },
                                Modifier.weight(1f), kind = ButtonKind.Tonal, icon = Icons.Rounded.ContentCopy)
                        }
                        HeartButton(tr("Get a new code"), actions.newCode, Modifier.padding(top = 4.dp), kind = ButtonKind.Text, fillWidth = false, busy = state.renewing,
                            icon = Icons.Rounded.Refresh, height = 40.dp)
                    }
                }
            }
        }
    }
}

/** Together in the same room: the invite link as a QR code, and the camera to read theirs. */
@Composable
private fun QrCard(state: PairState, actions: PairActions, now: Instant) {
    val invite = state.invite ?: return
    if (InviteExpiry.expired(invite.expiresAtUtc, now)) return
    val colors = Heartpact.colors
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QrCode(InviteParsing.link(invite.token), 120.dp, description = tr("QR code of your invite link"))
                Column(Modifier.weight(1f)) {
                    Text(tr("Together right now?"), style = Heartpact.type.title, color = colors.ink)
                    Text(tr("Let them scan this with Heartpact, or scan theirs."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                }
            }
            HeartButton(tr("Scan their code"), actions.scan, Modifier.padding(top = 14.dp), kind = ButtonKind.Tonal, icon = Icons.Rounded.QrCodeScanner, height = 48.dp)
        }
    }
}
