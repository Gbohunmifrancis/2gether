package app.heartpact.alarm

import app.heartpact.ui.graphics.StickingHeart
import app.heartpact.ui.graphics.StickTimeline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.PeeledHeart
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Ink = Color(0xFF161A3D)
private val OnInk = Color.White

/** The alarm's big clock: the digits large, AM/PM (when the phone uses it) set small beside them. */
private fun bigClock(isoUtc: String, is24Hour: Boolean): Pair<String, String?> {
    val local = utc(isoUtc).atZone(ZoneId.systemDefault())
    return if (is24Hour) local.format(DateTimeFormatter.ofPattern("H:mm")) to null
    else local.format(DateTimeFormatter.ofPattern("h:mm")) to local.format(DateTimeFormatter.ofPattern("a"))
}

private fun localTime(isoUtc: String) = utc(isoUtc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/**
 * The ringing alarm (mockup 05). Grounded on ink: saturated colour only on what you can press.
 * The way out is always on screen: ask your partner to call it off, or silence it on this phone.
 */
@Composable
fun AlarmRinging(
    occurrence: ExerciseOccurrence,
    partnerName: String,
    setByPartner: Boolean,
    mySide: Side,
    busy: Boolean,
    note: String?,
    onStart: () -> Unit,
    onAskOff: (String?) -> Unit,
    onSilence: () -> Unit,
    now: () -> Instant = { Instant.now() },
) {
    var clock by remember { mutableLongStateOf(now().toEpochMilli()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); clock = now().toEpochMilli() } }
    var asking by remember { mutableStateOf(false) }
    val deadline = utc(occurrence.completeByUtc ?: occurrence.deadlineUtc)
    val left = Duration.ofMillis((deadline.toEpochMilli() - clock).coerceAtLeast(0))
    val asked = occurrence.callOffRequestedAtUtc != null
    val me = if (mySide == Side.Left) Who.Left else Who.Right
    val is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val (clockFace, meridiem) = bigClock(occurrence.scheduledAtUtc, is24Hour)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clockFace, style = Heartpact.type.display.copy(fontSize = 84.sp, lineHeight = 80.sp), color = OnInk, maxLines = 1, softWrap = false)
                    meridiem?.let { Text(it, style = Heartpact.type.title, color = OnInk, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 12.dp)) }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Alarm, null, tint = OnInk, modifier = Modifier.size(20.dp))
                    Text(if (setByPartner) tr("{partner}'s dare is ringing", "partner" to partnerName) else tr("Your dare is ringing"), style = Heartpact.type.bodyLarge, color = OnInk)
                }
            }
            Character(me, Pose.Stand, 104.dp, mood = Mood.Worried)
        }
        Spacer(Modifier.weight(1f))
        StickerCard(shape = Shapes.Dare) {
            Column(Modifier.padding(20.dp)) {
                Text(occurrence.title, style = Heartpact.type.title, color = Heartpact.colors.ink)
                Text(tr("{achieved} of {target} counted · stops at {target}", "achieved" to occurrence.achievedCount, "target" to occurrence.target), style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(Shapes.Well).background(Heartpact.colors.sticker2).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (mySide == Side.Left) HeartSticker(HalfState.Slot, HalfState.Stuck, 42.dp) else HeartSticker(HalfState.Stuck, HalfState.Slot, 42.dp)
                    Text(if (setByPartner) tr("{partner}'s half is on. Yours sticks at {target}.", "partner" to partnerName, "target" to occurrence.target) else tr("Your half sticks at {target}.", "target" to occurrence.target), style = Heartpact.type.body, color = Heartpact.colors.ink)
                }
                HeartButton(tr("Start on camera"), onStart, Modifier.padding(top = 14.dp), icon = Icons.Rounded.PhotoCamera, busy = busy)
            }
        }
        Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(Shapes.Well).background(Color.White.copy(alpha = 0.07f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (setByPartner) Character(if (me == Who.Left) Who.Right else Who.Left, Pose.Stand, 52.dp, mood = Mood.Worried)
            Column {
                Text(if (asked) tr("You asked {partner} to call it off", "partner" to partnerName) else tr("{partner} can see it's ringing", "partner" to partnerName), style = Heartpact.type.label, color = OnInk)
                Text(if (asked) tr("If {partner} agrees, it stops for both of you.", "partner" to partnerName) else tr("Your count goes to {partner} when you finish.", "partner" to partnerName), style = Heartpact.type.small, color = OnInk.copy(alpha = 0.8f))
            }
        }
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("%d:%02d".format(left.toMinutes(), left.seconds % 60), style = Heartpact.type.displaySmall.copy(fontSize = 52.sp), color = OnInk)
            Text(tr("left to ring"), style = Heartpact.type.body, color = OnInk.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 8.dp))
        }
        note?.let { Text(it, style = Heartpact.type.small, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.weight(1f))
        if (setByPartner && !asked) {
            Box(Modifier.fillMaxWidth().clip(Shapes.Pill).background(Color.White.copy(alpha = 0.12f))) {
                HeartButton(tr("Ask {partner} to call it off", "partner" to partnerName), { asking = true }, kind = ButtonKind.Text, height = 56.dp, contentColor = OnInk)
            }
        }
        HeartButton(tr("Silence on this phone"), onSilence, kind = ButtonKind.Text, icon = Icons.Rounded.NotificationsOff, contentColor = OnInk)
        Text(tr("Silencing keeps the dare open until {time}.", "time" to localTime(deadline.toString())), style = Heartpact.type.small, color = OnInk.copy(alpha = 0.8f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
    }
    if (asking) AskOffDialog(partnerName, onSend = { asking = false; onAskOff(it) }, onDismiss = { asking = false })
}

@Composable
private fun AskOffDialog(partnerName: String, onSend: (String?) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ask {partner} to call it off?", "partner" to partnerName)) },
        text = {
            Column {
                Text(tr("If {partner} agrees, the alarm stops and today goes on your page as a peeled heart.", "partner" to partnerName), style = Heartpact.type.body)
                HeartField(tr("Add a note (optional)"), note, { note = it.take(140) }, Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSend(note) }) { Text(tr("Ask {partner}", "partner" to partnerName)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}

/** Heart complete (mockup 07): both halves stuck, both characters cheer. */
@Composable
fun HeartComplete(occurrence: ExerciseOccurrence, partnerName: String, onDone: () -> Unit, mySide: Side = Side.Left, frozenAtMs: Float? = null) {
    val colors = Heartpact.colors
    val done = occurrence.completedAtUtc?.let { utc(it) }
    val scheduled = utc(occurrence.scheduledAtUtc)
    val exercise = occurrence.exerciseType.displayName.lowercase()
    val at = done?.let { localTime(it.toString()) } ?: ""
    val summary = when {
        done == null -> tr("{achieved} of {target} {exercise} at {time},", "achieved" to occurrence.achievedCount, "target" to occurrence.target, "exercise" to exercise, "time" to at) + "\n"
        done.isBefore(scheduled) -> {
            val early = Duration.between(done, scheduled).toMinutes().coerceAtLeast(1)
            trn(early, "{achieved} of {target} {exercise} at {time},\n{count} minute before it would ring.", "{achieved} of {target} {exercise} at {time},\n{count} minutes before it would ring.",
                "achieved" to occurrence.achievedCount, "target" to occurrence.target, "exercise" to exercise, "time" to at)
        }
        else -> tr("{achieved} of {target} {exercise} at {time},\nwhile it was ringing.", "achieved" to occurrence.achievedCount, "target" to occurrence.target, "exercise" to exercise, "time" to at)
    }
    Column(Modifier.fillMaxSize().background(colors.page).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
        StickerCard(Modifier.fillMaxWidth().weight(1f), shape = Shapes.Dare) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // Mockup 20: your half sticks next to the one your partner set, then you both cheer.
                var whole by remember { mutableStateOf(frozenAtMs != null && frozenAtMs >= StickTimeline.END_MS) }
                val cheer by animateFloatAsState(if (whole) 1f else 0f, tween(if (frozenAtMs != null) 0 else 220, easing = LinearOutSlowInEasing), label = "cheer")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StickingHeart(mySide, 104.dp, frozenAtMs = frozenAtMs, onWhole = { whole = true })
                    Character(Who.Both, Pose.Cheer, 150.dp, Modifier.graphicsLayer { alpha = cheer; scaleX = 0.8f + 0.2f * cheer; scaleY = 0.8f + 0.2f * cheer })
                }
                Text(tr("Heart complete"), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
                Text(summary,
                    style = Heartpact.type.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Text(tr("The same heart goes on {partner}'s page.", "partner" to partnerName), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
            }
        }
        HeartButton(tr("Done"), onDone, Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 12.dp))
    }
}

@Composable
fun AlarmEnded(reason: EndReason, partnerName: String, onDone: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().background(colors.page).statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (reason) {
            EndReason.LetOff -> PeeledHeart(96.dp)
            EndReason.Missed -> HeartSticker(HalfState.Slot, HalfState.Slot, 96.dp)
            EndReason.Gone -> Character(Who.Both, Pose.Stand, 120.dp)
        }
        Text(when (reason) {
            EndReason.LetOff -> tr("Called off together")
            EndReason.Missed -> tr("The window closed")
            EndReason.Gone -> tr("Nothing is ringing")
        }, style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 18.dp))
        Text(when (reason) {
            EndReason.LetOff -> tr("{partner} agreed. Today goes on your page as a peeled heart.", "partner" to partnerName)
            EndReason.Missed -> tr("Today's dare wasn't done in time. Tomorrow is a new page.")
            EndReason.Gone -> tr("This dare was changed or already finished.")
        }, style = Heartpact.type.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        HeartButton(tr("Done"), onDone, Modifier.padding(top = 28.dp))
    }
}

@Composable
fun AlarmLoading(note: String?) {
    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = OnInk)
        Text(note ?: tr("Checking today's dare…"), style = Heartpact.type.body, color = OnInk, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}
