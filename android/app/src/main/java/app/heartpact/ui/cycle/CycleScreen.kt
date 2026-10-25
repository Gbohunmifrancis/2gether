package app.heartpact.ui.cycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.CycleDayLog
import app.heartpact.data.CycleMood
import app.heartpact.data.CycleShare
import app.heartpact.data.FlowLevel
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CycleActions(
    val onSetup: (LocalDate, Int, Int, CycleShare) -> Unit = { _, _, _, _ -> },
    val onEdit: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onPickDay: (LocalDate) -> Unit = {},
    val onShare: (CycleShare) -> Unit = {},
    val onFlow: (FlowLevel) -> Unit = {},
    val onSymptom: (String) -> Unit = {},
    val onMood: (CycleMood?) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Your cycle (mockup 14), or what your partner shares of theirs. */
@Composable
fun CycleScreen(ui: CycleUi, partnerName: String, actions: CycleActions, today: LocalDate = LocalDate.now()) {
    val colors = Heartpact.colors
    val title = if (ui.mode == CycleMode.Partner) tr("{partner}'s cycle", "partner" to partnerName) else tr("Your cycle")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(title, actions.onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ui.note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            when (ui.mode) {
                CycleMode.Loading -> Text(tr("Opening your calendar…"), style = Heartpact.type.body, color = colors.ink2)
                CycleMode.Setup -> Setup(ui, partnerName, actions, today)
                CycleMode.Unavailable -> Text(tr("{partner} hasn't shared a cycle with you. If they track one here, they choose what you see.", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2)
                CycleMode.Owner -> Owner(ui, partnerName, actions, today)
                CycleMode.Partner -> Partner(ui, partnerName, today)
            }
        }
    }
}

@Composable
private fun Big(day: Int?, line: String?) {
    val mine = LocalActor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(day?.let { tr("Day {day}", "day" to it) } ?: tr("No date yet"), style = Heartpact.type.display.copy(fontSize = 72.sp, lineHeight = 66.sp), color = Heartpact.colors.ink, maxLines = 1, softWrap = false)
            line?.let { Text(it, style = Heartpact.type.bodyLarge, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 12.dp)) }
        }
        Character(if (mine == Side.Left) Who.Left else Who.Right, Pose.Rest, 84.dp, mood = Mood.Sleep)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Owner(ui: CycleUi, partnerName: String, actions: CycleActions, today: LocalDate) {
    val colors = Heartpact.colors
    var more by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val day = ui.day ?: today
    val todayLog = ui.logFor(day)
    Big(ui.prediction.cycleDay, CyclePlan.summary(ui.prediction, ui.profile.averageCycleLengthDays))
    Strip(CyclePlan.strip(ui.prediction, ui.logs, ui.profile.averagePeriodLengthDays, today))
    Row(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(CyclePlan.dayTitle(day, today), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.weight(1f))
        Text(tr("Another day"), style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.clickable { picking = true })
        Text(if (more) tr("Less") else tr("More"), style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.clickable { more = !more })
    }
    if (picking) PickDay(day, { CyclePlan.canLog(it, today) }, onDismiss = { picking = false }) { actions.onPickDay(it); picking = false }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(FlowLevel.None, FlowLevel.Spotting, FlowLevel.Light, FlowLevel.Medium, FlowLevel.Heavy).forEach { HeartChip(it.label, todayLog.flowLevel == it, { actions.onFlow(it) }) }
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (if (more) CyclePlan.symptoms else CyclePlan.symptoms.take(4)).forEach { HeartChip(CyclePlan.symptomLabel(it), it in todayLog.symptoms, { actions.onSymptom(it) }) }
    }
    if (more) {
        Text(tr("Mood"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CycleMood.entries.forEach { m -> HeartChip(m.label, todayLog.mood == m, { actions.onMood(if (todayLog.mood == m) null else m) }) }
        }
        HeartField(if (ui.profile.shareLevel == CycleShare.FullDetail) tr("Notes, just for you and {partner}", "partner" to partnerName) else tr("Notes, just for you"), todayLog.notes.orEmpty(), actions.onNotes,
            Modifier.padding(top = 14.dp), minLines = 3, maxLength = 2000, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    }
    Text(tr("What {partner} sees", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
    Segmented(listOf(CycleShare.None to tr("Nothing"), CycleShare.PredictionsOnly to tr("Predictions"), CycleShare.FullDetail to tr("Everything")), ui.profile.shareLevel, actions.onShare)
    Text(CyclePlan.shareLine(ui.profile.shareLevel, partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
    HeartButton(tr("Cycle settings"), actions.onEdit, Modifier.padding(top = 18.dp), kind = ButtonKind.Outline, fillWidth = false, height = 44.dp)
    Text(tr("Predictions are calendar estimates from your averages, not medical advice or contraception."), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 18.dp))
}

/** A day picker limited to the days [selectable] allows (dates are UTC midnights, as the picker counts them). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDay(initial: LocalDate, selectable: (LocalDate) -> Boolean, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = selectable(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate())
        })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text(tr("Set")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    ) { DatePicker(state) }
}

@Composable
private fun Partner(ui: CycleUi, partnerName: String, today: LocalDate) {
    val colors = Heartpact.colors
    val shared = ui.shared ?: return
    val profile = shared.profile ?: return
    val prediction = shared.prediction
    if (prediction?.cycleDay == null) {
        Text(tr("{partner} shares their cycle with you, but there's nothing to predict right now.", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2)
        return
    }
    Big(prediction.cycleDay, CyclePlan.summary(prediction, profile.averageCycleLengthDays))
    Strip(CyclePlan.strip(prediction, shared.logs, profile.averagePeriodLengthDays, today))
    val todayLog = shared.logs.firstOrNull { it.logDate == today.toString() }
    if (profile.shareLevel == CycleShare.FullDetail && todayLog != null) {
        StickerCard(Modifier.padding(top = 18.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Today"), style = Heartpact.type.label, color = colors.ink)
                Text(listOfNotNull(todayLog.flowLevel.label, todayLog.mood?.label, todayLog.symptoms.joinToString { CyclePlan.symptomLabel(it) }.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                todayLog.notes?.let { Text(it, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
    Text(if (profile.shareLevel == CycleShare.FullDetail) tr("{partner} shares everything they log.", "partner" to partnerName) else tr("{partner} shares predictions only, not what they log.", "partner" to partnerName),
        style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 16.dp))
    // The fertile strip is the part most easily read as "safe days", so the partner gets the same warning.
    Text(tr("Predictions are calendar estimates from {partner}'s averages, not medical advice or contraception.", "partner" to partnerName),
        style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 18.dp))
}

/** One bar per day: period dark, fertile light, today outlined, the predicted next period dashed. */
@Composable
private fun Strip(days: List<StripDay>) {
    if (days.isEmpty()) return
    val colors = Heartpact.colors
    val period = Heartpact.actor.action
    val fertile = colors.left.soft
    val plain = colors.sticker2
    val ink = colors.ink
    val fmt = Dates.formatter("d MMM")
    StickerCard(Modifier.padding(top = 20.dp).fillMaxWidth(), shape = Shapes.Dare) {
        Column(Modifier.padding(18.dp)) {
            Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = tr("Cycle from {from} to {to}", "from" to days.first().date.format(fmt), "to" to days.last().date.format(fmt)) }) {
                val gap = 2.dp.toPx()
                val w = (size.width - gap * (days.size - 1)) / days.size
                days.forEachIndexed { i, d ->
                    val x = i * (w + gap)
                    val tall = d.kind == DayKind.Period || d.kind == DayKind.Predicted || d.today
                    val h = if (tall) size.height else size.height * 0.38f
                    val top = size.height - h
                    val r = CornerRadius(w / 2, w / 2)
                    when (d.kind) {
                        DayKind.Predicted -> drawRoundRect(ink, Offset(x, top), Size(w, h), r, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        else -> drawRoundRect(if (d.kind == DayKind.Period) period else if (d.kind == DayKind.Fertile) fertile else plain, Offset(x, top), Size(w, h), r)
                    }
                    if (d.today && d.kind != DayKind.Predicted) drawRoundRect(ink, Offset(x, top), Size(w, h), r, style = Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Text(days.first().date.format(fmt), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.weight(1f))
                days.firstOrNull { it.kind == DayKind.Predicted }?.let { Text(it.date.format(fmt), style = Heartpact.type.small, color = colors.ink2) }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(tr("period")) { Box(Modifier.size(12.dp).clip(Shapes.Tile).background(period)) }
                Legend(tr("fertile, estimated")) { Box(Modifier.size(12.dp).clip(Shapes.Tile).background(fertile)) }
                Legend(tr("predicted")) { Box(Modifier.size(12.dp).border(1.5.dp, ink, Shapes.Tile)) }
            }
        }
    }
}

@Composable
private fun Legend(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swatch()
        Text(label, style = Heartpact.type.small, color = Heartpact.colors.ink2)
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = Heartpact.colors
    Row(Modifier.fillMaxWidth().clip(Shapes.Pill).background(colors.sticker).border(2.dp, colors.rule, Shapes.Pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Row(Modifier.weight(1f).clip(Shapes.Pill).background(if (on) Heartpact.actor.soft else colors.sticker).clickable { onSelect(value) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (on) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp).padding(end = 4.dp), tint = Heartpact.actor.onSoft)
                Text(label, style = Heartpact.type.label, color = if (on) Heartpact.actor.onSoft else colors.ink2)
            }
        }
    }
}

/** First use, and the settings later: last period, usual lengths and what the partner sees. */
@Composable
private fun Setup(ui: CycleUi, partnerName: String, actions: CycleActions, today: LocalDate) {
    val colors = Heartpact.colors
    var start by remember { mutableStateOf(CyclePlan.lastStartForEditing(ui.prediction, ui.profile.lastPeriodStartDate)?.let(LocalDate::parse) ?: today.minusDays(7)) }
    var cycle by remember { mutableIntStateOf(ui.profile.averageCycleLengthDays) }
    var period by remember { mutableIntStateOf(ui.profile.averagePeriodLengthDays) }
    var share by remember { mutableStateOf(ui.profile.shareLevel) }
    var picking by remember { mutableStateOf(false) }
    Text(tr("When did your last period start?"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
    HeartButton(start.format(Dates.formatter("EEEE d MMMM")), { picking = true }, Modifier.padding(top = 10.dp), kind = ButtonKind.Outline, fillWidth = false)
    Stepper(tr("Usual cycle length"), trn(cycle, "{count} day", "{count} days"), { cycle = (cycle - 1).coerceAtLeast(15) }, { cycle = (cycle + 1).coerceAtMost(60) })
    Stepper(tr("Usual period length"), trn(period, "{count} day", "{count} days"), { period = (period - 1).coerceAtLeast(1) }, { period = (period + 1).coerceAtMost(14) })
    Text(tr("Not sure? 28 and 5 are common starting points. Log your period when it starts and predictions count from that day; you can change these any time."),
        style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
    Text(tr("What {partner} sees", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
    Segmented(listOf(CycleShare.None to tr("Nothing"), CycleShare.PredictionsOnly to tr("Predictions"), CycleShare.FullDetail to tr("Everything")), share) { share = it }
    Text(CyclePlan.shareLine(share, partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
    HeartButton(tr("Save"), { actions.onSetup(start, cycle, period, share) }, Modifier.padding(top = 22.dp), busy = ui.busy)
    if (ui.editing) HeartButton(tr("Cancel"), actions.onCancelEdit, Modifier.padding(top = 8.dp), kind = ButtonKind.Text)
    if (picking) PickDay(start, { !it.isAfter(today) && !it.isBefore(today.minusYears(1)) }, onDismiss = { picking = false }) { start = it; picking = false }
}

@Composable
private fun Stepper(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit) {
    val colors = Heartpact.colors
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Heartpact.type.label, color = colors.ink, modifier = Modifier.weight(1f))
        IconButton(onClick = onLess, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Remove, tr("Fewer days"), tint = colors.ink) }
        Text(value, style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(horizontal = 14.dp))
        IconButton(onClick = onMore, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Add, tr("More days"), tint = colors.ink) }
    }
}
