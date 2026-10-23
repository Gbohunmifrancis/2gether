package app.heartpact.ui.today

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.ReportGmailerrorred
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.utc
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.PeeledHeart
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun time(instant: Instant) = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Today (mockup 03): my dare first, my partner's next, then the week on my page. */
@Composable
fun TodayTab(
    ui: TodayUi,
    partnerName: String,
    onStartCamera: (MyDare) -> Unit,
    onDoItNow: (MyDare) -> Unit = {},
    onCallOff: (String) -> Unit,
    onKeep: (String) -> Unit,
    onNewDare: () -> Unit,
    now: Instant = Instant.now(),
) {
    val colors = Heartpact.colors
    val view = ui.view
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Text("Today", style = Heartpact.type.headline, color = colors.ink)
            val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
            Text(if (view != null) "$date · ${view.hearts} ${if (view.hearts == 1) "heart" else "hearts"} this week" else date,
                style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
        }
        ui.note?.let { NoteCard(it, Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) }
        if (!ui.readiness.ready) Readiness(ui)
        view?.partnerRinging?.takeIf { it.callOffRequestedAtUtc != null && it.id !in ui.kept }?.let { CallOffRequest(it, partnerName, ui.busy, onCallOff, onKeep) }
        when {
            view?.mine != null -> DareCard(view.mine, partnerName, now, onStartCamera, onDoItNow)
            view != null -> StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp).fillMaxWidth(), shape = Shapes.Dare) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Character(Who.Both, Pose.Wave, 100.dp)
                    Text("No dare for you yet", style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 10.dp))
                    Text("When $partnerName sets you one, their half of the heart lands here first.", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        view?.let { Week(it) }
        view?.let { v ->
            StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                val ringing = v.partnerRinging
                val next = v.partnerNext
                val done = v.partnerDone
                when {
                    ringing != null -> CardRow("$partnerName's ${ringing.title.lowercase()} is ringing",
                        subtitle = "${ringing.achievedCount} of ${ringing.target} counted · rings until ${time(utc(ringing.completeByUtc ?: ringing.deadlineUtc))}", icon = Icons.Rounded.Alarm)
                    done != null -> PartnerDone(done, partnerName)
                    next != null -> CardRow("Your dare for $partnerName", subtitle = "${next.target} ${next.exerciseType.displayName.lowercase()} · rings at ${time(utc(next.nextDueAtUtc))}", icon = Icons.Rounded.Alarm)
                    else -> CardRow("No dare for $partnerName yet", subtitle = "Set one and stick your half on first", icon = Icons.Rounded.Alarm)
                }
            }
        }
        HeartButton("New dare for $partnerName", onNewDare, Modifier.padding(horizontal = 12.dp).padding(top = 14.dp), kind = ButtonKind.Tonal, icon = Icons.Rounded.Add)
    }
}

@Composable
private fun DareCard(dare: MyDare, partnerName: String, now: Instant, onStartCamera: (MyDare) -> Unit, onDoItNow: (MyDare) -> Unit) {
    val colors = Heartpact.colors
    val mySide = LocalActor.current
    val occurrence = dare.occurrence
    Box(Modifier.padding(horizontal = 12.dp).padding(top = 30.dp)) {
        StickerCard(Modifier.fillMaxWidth(), shape = Shapes.Dare, lift = 14.dp) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
                Text("${dare.goal.target} ${dare.goal.exerciseType.displayName.lowercase()} by ${time(dare.dueAt)}", style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(end = 70.dp))
                Text(if (dare.setByPartner) "$partnerName's dare · your phone rings if you don't" else "Your own dare · your phone rings if you don't", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (occurrence != null) {
                        val left = Duration.between(now, utc(occurrence.completeByUtc ?: occurrence.deadlineUtc)).toMinutes().coerceAtLeast(0)
                        Text("$left", style = Heartpact.type.display, color = colors.ink)
                        Text("minutes left\n${occurrence.achievedCount} of ${occurrence.target} counted", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(bottom = 4.dp))
                    } else {
                        val until = Duration.between(now, dare.dueAt)
                        val label = when {
                            until.toMinutes() < 60 -> "Rings in ${until.toMinutes().coerceAtLeast(1)} minutes"
                            dare.dueAt.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now() -> "Rings today at ${time(dare.dueAt)}"
                            else -> "Rings tomorrow at ${time(dare.dueAt)}"
                        }
                        Text(label, style = Heartpact.type.titleSmall, color = colors.ink)
                    }
                }
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(Shapes.Well).background(colors.sticker2).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val partnerHalf = if (dare.setByPartner) HalfState.Stuck else HalfState.Slot
                    if (mySide == Side.Left) HeartSticker(HalfState.Slot, partnerHalf, 46.dp) else HeartSticker(partnerHalf, HalfState.Slot, 46.dp)
                    Text(if (dare.setByPartner) "$partnerName stuck their half on. Do the reps and the camera sticks yours." else "Do the reps and the camera sticks your half.", style = Heartpact.type.body.copy(fontSize = 13.sp), color = colors.ink)
                }
                val dueToday = dare.dueAt.atZone(ZoneId.systemDefault()).toLocalDate() == now.atZone(ZoneId.systemDefault()).toLocalDate()
                when {
                    dare.ringing -> HeartButton("Start on camera", { onStartCamera(dare) }, Modifier.padding(top = 14.dp), icon = Icons.Rounded.PhotoCamera)
                    dueToday && dare.dueAt.isAfter(now) -> {
                        HeartButton("Do it now", { onDoItNow(dare) }, Modifier.padding(top = 14.dp), kind = ButtonKind.Tonal, icon = Icons.Rounded.PhotoCamera)
                        Text("Done before ${time(dare.dueAt)}, it won't ring today.", style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    else -> Text("You can do it any time on the day, or ask $partnerName to call it off once it rings.", style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        if (dare.setByPartner) Character(if (mySide == Side.Left) Who.Right else Who.Left, Pose.Wave, 72.dp, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-40).dp))
    }
}

/** My partner's finished dare: when, and what the camera noticed, flags first. */
@Composable
private fun PartnerDone(done: ExerciseOccurrence, partnerName: String) {
    val checks = done.checks
    val line = checks?.partnerLine()
    val flagged = checks?.flagged == true
    CardRow(
        "$partnerName finished ${done.achievedCount} ${done.exerciseType.displayName.lowercase()}",
        subtitle = listOfNotNull(done.completedAtUtc?.let { "at ${time(utc(it))}" }, line).joinToString(" · "),
        icon = if (flagged) Icons.Rounded.ReportGmailerrorred else Icons.Rounded.Favorite,
    )
}

@Composable
private fun Week(view: TodayView) {
    val colors = Heartpact.colors
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${view.week.first().first.format(DateTimeFormatter.ofPattern("d"))}–${view.week.last().first.format(fmt)}", style = Heartpact.type.label, color = colors.ink, modifier = Modifier.weight(1f))
            view.week.forEach { (_, sticker) ->
                when (sticker) {
                    DaySticker.Heart -> HeartSticker(HalfState.Stuck, HalfState.Stuck, 26.dp)
                    DaySticker.Peeled -> PeeledHeart(26.dp)
                    DaySticker.Pending -> if (LocalActor.current == Side.Left) HeartSticker(HalfState.Slot, HalfState.Stuck, 26.dp) else HeartSticker(HalfState.Stuck, HalfState.Slot, 26.dp)
                    DaySticker.Missed -> Box(Modifier.size(24.dp, 26.dp).clip(Shapes.Tile).background(colors.sticker2).border(2.dp, colors.rule2, Shapes.Tile))
                    DaySticker.Empty -> Box(Modifier.size(24.dp, 26.dp).clip(Shapes.Tile).background(colors.page))
                }
            }
        }
    }
}

@Composable
private fun CallOffRequest(occurrence: ExerciseOccurrence, partnerName: String, busy: Boolean, onCallOff: (String) -> Unit, onKeep: (String) -> Unit) {
    val colors = Heartpact.colors
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 6.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Character(if (LocalActor.current == Side.Left) Who.Right else Who.Left, Pose.Stand, 54.dp)
                Text("$partnerName asks to call off today's ${occurrence.title.lowercase()}", style = Heartpact.type.title, color = colors.ink)
            }
            occurrence.callOffNote?.let {
                Text("“$it”", style = Heartpact.type.body, color = colors.right.onSoft, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(Shapes.Well).background(colors.right.tint).padding(12.dp))
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PeeledHeart(30.dp)
                Text("If you agree, the alarm stops and today goes on both your pages as a peeled heart.", style = Heartpact.type.small, color = colors.ink2)
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeartButton("Keep it", { onKeep(occurrence.id) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                HeartButton("Call it off together", { onCallOff(occurrence.id) }, Modifier.weight(1f), busy = busy)
            }
        }
    }
}

@Composable
private fun Readiness(ui: TodayUi) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val r = ui.readiness
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
        Text("Get your alarm ready", style = Heartpact.type.titleSmall, color = Heartpact.colors.ink, modifier = Modifier.padding(start = 18.dp, top = 14.dp))
        if (!r.notifications) CardRow("Allow notifications", subtitle = "So a dare can ring at all", icon = Icons.Rounded.Notifications, onClick = {
            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
        if (!r.exactAlarms) CardRow("Ring on time", subtitle = "Allow exact alarms, or it may ring a few minutes late", icon = Icons.Rounded.Alarm, divider = !r.notifications, onClick = {
            app.heartpact.alarm.AlarmReadiness.exactAlarmSettings(context)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        if (!r.fullScreen) CardRow("Ring over the lock screen", subtitle = "Allow full-screen alarms, or it shows as a notification", icon = Icons.Rounded.FullscreenExit, divider = true, onClick = {
            app.heartpact.alarm.AlarmReadiness.fullScreenSettings(context)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
}
