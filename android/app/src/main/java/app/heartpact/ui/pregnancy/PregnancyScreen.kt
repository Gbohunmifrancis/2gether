package app.heartpact.ui.pregnancy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.Appointment
import app.heartpact.data.Pregnancy
import app.heartpact.data.utc
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class PregnancyActions(
    val onStart: (LocalDate?, LocalDate?, Boolean) -> Unit = { _, _, _ -> },
    val onShare: (Boolean) -> Unit = {},
    val onEnd: () -> Unit = {},
    val onAddAppointment: (String, LocalDateTime, String?) -> Unit = { _, _, _ -> },
    val onDeleteAppointment: (String) -> Unit = {},
    val onStartKicks: () -> Unit = {},
    val onKick: () -> Unit = {},
    val onFinishKicks: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Pregnancy mode (mockup 15): the week, the due date, appointments and kick counts. */
@Composable
fun PregnancyScreen(ui: PregnancyUi, myId: String, partnerName: String, actions: PregnancyActions, today: LocalDate = LocalDate.now()) {
    val colors = Heartpact.colors
    var adding by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val p = ui.pregnancy
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TopBar(tr("Pregnancy"), actions.onBack) }
            if (p?.isMine == true) TextButton(onClick = { ending = true }) { Text(tr("End"), color = colors.ink2) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ui.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            when {
                !ui.loaded -> Text(tr("Opening…"), style = Heartpact.type.body, color = colors.ink2)
                p == null -> Start(ui.busy, partnerName, actions.onStart, today)
                else -> Active(p, ui, myId, partnerName, actions, onAdd = { adding = true }, today = today)
            }
        }
    }
    if (adding) AddAppointment(today, onDismiss = { adding = false }, onSave = { t, at, loc -> actions.onAddAppointment(t, at, loc); adding = false })
    if (ending) AlertDialog(
        onDismissRequest = { ending = false },
        title = { Text(tr("End pregnancy mode?")) },
        text = { Text(tr("Weeks and kick counts stop, and cycle predictions come back. Your appointments and history stay.")) },
        confirmButton = { TextButton(onClick = { ending = false; actions.onEnd() }) { Text(tr("End it")) } },
        dismissButton = { TextButton(onClick = { ending = false }) { Text(tr("Keep it on")) } },
    )
}

@Composable
private fun Active(p: Pregnancy, ui: PregnancyUi, myId: String, partnerName: String, actions: PregnancyActions, onAdd: () -> Unit, today: LocalDate) {
    val colors = Heartpact.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Week {week}", "week" to p.week), style = Heartpact.type.display.copy(fontSize = 64.sp, lineHeight = 60.sp), color = colors.ink, maxLines = 1, softWrap = false)
            Text(if (p.isMine) tr("{due} · cycle predictions paused", "due" to PregnancyText.due(p)) else PregnancyText.due(p) + " · ${p.userName.ifBlank { partnerName }}",
                style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 12.dp))
        }
        Character(Who.Both, Pose.Hug, 84.dp)
    }
    Weeks(p.week)
    if (p.isMine) StickerCard(Modifier.padding(top = 18.dp)) {
        CardRow(if (p.shareWithPartner) tr("Shared with {partner}", "partner" to partnerName) else tr("Just for you"),
            subtitle = if (p.shareWithPartner) tr("{partner} can see this and add appointments", "partner" to partnerName) else tr("{partner} doesn't see pregnancy mode", "partner" to partnerName),
            icon = Icons.Outlined.Visibility, onClick = { actions.onShare(!p.shareWithPartner) }) {
            Switch(p.shareWithPartner, actions.onShare, colors = SwitchDefaults.colors(checkedTrackColor = Heartpact.actor.action))
        }
    }
    Row(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(tr("Appointments"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.weight(1f))
        Text(tr("Add"), style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.clickable(onClick = onAdd).padding(4.dp))
    }
    val upcoming = ui.appointments.filter { utc(it.atUtc).atZone(ZoneId.systemDefault()).toLocalDate() >= today }
    if (upcoming.isEmpty()) {
        StickerCard(Modifier.fillMaxWidth()) { Text(tr("Nothing booked. Add scans, check-ups and classes so you both know."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(16.dp)) }
    } else StickerCard(Modifier.fillMaxWidth()) {
        upcoming.forEachIndexed { i, a -> AppointmentRow(a, myId, partnerName, divider = i > 0, onDelete = { actions.onDeleteAppointment(a.id) }) }
    }
    if (p.isMine) {
        val counting = ui.counting
        StickerCard(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            if (counting == null) {
                CardRow(tr("Kick count"), subtitle = PregnancyText.lastKicks(ui.kicks, today), icon = Icons.Outlined.TouchApp) {
                    HeartButton(tr("Start"), actions.onStartKicks, kind = ButtonKind.Tonal, fillWidth = false, height = 44.dp)
                }
            } else Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ui.kickCount}", style = Heartpact.type.display, color = colors.ink)
                Text(trn(ui.kickCount, "kick", "kicks"), style = Heartpact.type.body, color = colors.ink2)
                HeartButton(tr("Felt a kick"), actions.onKick, Modifier.padding(top = 14.dp))
                HeartButton(tr("Done counting"), actions.onFinishKicks, kind = ButtonKind.Text)
            }
        }
    }
}

@Composable
private fun Weeks(week: Int) {
    val done = Heartpact.colors.right.art.copy(alpha = 0.55f)
    val now = Heartpact.colors.ink
    val rest = Heartpact.colors.sticker2
    Canvas(Modifier.padding(top = 22.dp).fillMaxWidth().height(40.dp).semantics { contentDescription = tr("Week {week} of 40", "week" to week) }) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 39) / 40
        for (i in 1..40) {
            val x = (i - 1) * (w + gap)
            val h = when { i == week -> size.height; i < week -> size.height * 0.62f; else -> size.height * 0.38f }
            drawRoundRect(when { i == week -> now; i < week -> done; else -> rest }, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
    Row(Modifier.padding(top = 6.dp).fillMaxWidth()) {
        Text(tr("Week {week}", "week" to 1), style = Heartpact.type.small, color = Heartpact.colors.ink2, modifier = Modifier.weight(1f))
        Text(tr("Week {week}", "week" to 40), style = Heartpact.type.small, color = Heartpact.colors.ink2)
    }
}

@Composable
private fun AppointmentRow(a: Appointment, myId: String, partnerName: String, divider: Boolean, onDelete: () -> Unit) {
    val at = utc(a.atUtc).atZone(ZoneId.systemDefault())
    val line = listOfNotNull(at.format(Dates.formatter("EEE d MMM · HH:mm")), a.location).joinToString(" · ")
    var confirm by remember { mutableStateOf(false) }
    CardRow(a.title, subtitle = line, icon = Icons.Outlined.Event, divider = divider, onClick = { confirm = true }) {
        Text(if (a.createdByUserId == myId) tr("You") else tr("{partner} added", "partner" to partnerName), style = Heartpact.type.labelSmall, color = Heartpact.actor.onSoft,
            modifier = Modifier.clip(Shapes.Pill).background(Heartpact.actor.tint).padding(horizontal = 12.dp, vertical = 7.dp))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(a.title) },
        text = { Text(line + (a.notes?.let { "\n\n$it" } ?: "")) },
        confirmButton = { TextButton(onClick = { confirm = false }) { Text(tr("Close")) } },
        dismissButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(tr("Delete")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Start(busy: Boolean, partnerName: String, onStart: (LocalDate?, LocalDate?, Boolean) -> Unit, today: LocalDate) {
    val colors = Heartpact.colors
    var byDue by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var share by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Character(Who.Both, Pose.Hug, 120.dp) }
    Text(tr("Pregnancy mode"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 14.dp))
    Text(tr("Counts the weeks, keeps appointments in one place, and pauses cycle predictions while it's on."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeartChip(tr("I know the due date"), byDue, { byDue = true; date = null })
        HeartChip(tr("From my last period"), !byDue, { byDue = false; date = null })
    }
    HeartButton(date?.format(Dates.formatter("EEEE d MMMM yyyy")) ?: if (byDue) tr("Choose the due date") else tr("Choose the first day of your last period"),
        { picking = true }, Modifier.padding(top = 12.dp), kind = ButtonKind.Outline, icon = Icons.Outlined.Event)
    StickerCard(Modifier.padding(top = 14.dp)) {
        CardRow(tr("Share with {partner}", "partner" to partnerName), subtitle = tr("You can change this any time"), onClick = { share = !share }) {
            Switch(share, { share = it }, colors = SwitchDefaults.colors(checkedTrackColor = Heartpact.actor.action))
        }
    }
    HeartButton(tr("Turn on pregnancy mode"), { onStart(date.takeIf { byDue }, date.takeIf { !byDue }, share) }, Modifier.padding(top = 18.dp), enabled = date != null, busy = busy)
    if (picking) {
        val state = rememberDatePickerState(today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; picking = false }) { Text(tr("Set")) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Cancel")) } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppointment(today: LocalDate, onDismiss: () -> Unit, onSave: (String, LocalDateTime, String?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(today.plusDays(1)) }
    var time by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var pickDay by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add an appointment")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HeartField(tr("What"), title, { title = it.take(120) }, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeartButton(day.format(Dates.formatter("EEE d MMM")), { pickDay = true }, kind = ButtonKind.Outline, fillWidth = false, height = 44.dp)
                    HeartButton(time.format(DateTimeFormatter.ofPattern("HH:mm")), { pickTime = true }, kind = ButtonKind.Outline, fillWidth = false, height = 44.dp)
                }
                HeartField(tr("Where (optional)"), location, { location = it.take(200) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, LocalDateTime.of(day, time), location) }, enabled = title.isNotBlank()) { Text(tr("Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (pickDay) {
        val state = rememberDatePickerState(day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDay = false }) { Text(tr("Set")) } },
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text(tr("Cancel")) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text(tr("Set")) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(tr("Cancel")) } },
        )
    }
}
