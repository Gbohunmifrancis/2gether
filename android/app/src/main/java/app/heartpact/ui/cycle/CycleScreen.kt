package app.heartpact.ui.cycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import app.heartpact.data.CyclePrediction
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.ui.draw.alpha
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
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Owner(ui: CycleUi, partnerName: String, actions: CycleActions, today: LocalDate) {
    val colors = Heartpact.colors
    var more by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val day = ui.day ?: today
    val todayLog = ui.logFor(day)
    Forecast(ui.prediction, ui.logs, ui.profile.averagePeriodLengthDays, today, CyclePlan.summary(ui.prediction, ui.profile.averageCycleLengthDays),
        selected = day, onPick = { if (CyclePlan.canLog(it, today)) actions.onPickDay(it) })
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
    // Only what's shared: at "predictions" the server sends no logs, so only the forecast is drawn.
    Forecast(prediction, if (profile.shareLevel == CycleShare.FullDetail) shared.logs else emptyList(), profile.averagePeriodLengthDays, today,
        CyclePlan.partnerSummary(prediction, profile.averageCycleLengthDays, partnerName))
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

/** The colours a period tracker is read by: a period red, a fertile teal, both a shade deeper at night for the dark page. */
private data class CycleInk(val period: Color, val periodSoft: Color, val fertile: Color, val fertileSoft: Color, val ovulation: Color, val onMark: Color, val plain: Color)

@Composable
private fun cycleInk(): CycleInk {
    val colors = Heartpact.colors
    return if (colors.isNight) CycleInk(Color(0xFFFF6B93), Color(0xFF4A2338), Color(0xFF3FC7B4), Color(0xFF1D5450), Color(0xFF7FE3D3), Color(0xFF10132B), colors.sticker2)
    else CycleInk(Color(0xFFD23B68), Color(0xFFFADCE6), Color(0xFF1E9C8C), Color(0xFFD3F1EC), Color(0xFF0B7366), Color.White, colors.sticker2)
}

/**
 * The forecast as a period tracker draws it: a dial of the cycle's days with the period, the predicted fertile window,
 * ovulation and today, the words in the middle, then the month calendar with the same marks and a legend. [onPick]
 * (the owner) logs a tapped day; [selected] is the day being logged.
 */
@Composable
private fun Forecast(prediction: CyclePrediction, logs: List<CycleDayLog>, periodLength: Int, today: LocalDate, summary: String?,
                     selected: LocalDate? = null, onPick: ((LocalDate) -> Unit)? = null) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val ring = CyclePlan.ring(prediction, logs, periodLength, today, clockwise = !rtl)
    val text = CyclePlan.dial(prediction, ring, periodLength, today)
    if (ring.isNotEmpty() && text != null) Dial(ring, text, Modifier.padding(top = 4.dp))
    summary?.let { Text(it, style = Heartpact.type.body, color = Heartpact.colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) }
    val marks = CyclePlan.marks(prediction, logs, periodLength, today)
    MonthCalendar(marks, today, selected, onPick, Modifier.padding(top = 18.dp))
}

/** The cycle dial: a day per segment from day 1 at the top, the period filled, predictions dotted, today a knob on the ring. */
@Composable
private fun Dial(ring: List<RingDay>, text: DialText, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val ink = cycleInk()
    val measurer = rememberTextMeasurer()
    val numbers = Heartpact.type.labelSmall.copy(color = colors.ink3, fontSize = 10.5.sp)
    val described = listOfNotNull(text.big, text.line, text.note).joinToString(". ")
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, 300.dp)
        Box(Modifier.size(side), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize().semantics { contentDescription = described }) {
                val band = size.minDimension * 0.095f
                val radius = size.minDimension / 2f - band / 2f - 10.dp.toPx()
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arc = Size(radius * 2, radius * 2)
                ring.forEach { d ->
                    val fill = when (d.mark) {
                        CycleMark.Period -> ink.period
                        CycleMark.PredictedPeriod -> ink.periodSoft
                        CycleMark.Fertile -> ink.fertileSoft
                        CycleMark.Ovulation -> ink.fertile
                        CycleMark.None -> ink.plain
                    }
                    drawArc(fill, d.startDegrees, d.sweepDegrees, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(band, cap = StrokeCap.Butt))
                    val mid = Math.toRadians(d.midDegrees.toDouble())
                    val at = Offset(center.x + radius * cos(mid).toFloat(), center.y + radius * sin(mid).toFloat())
                    when (d.mark) {
                        // A predicted period is dotted: a rose dot in each of its days.
                        CycleMark.PredictedPeriod -> drawCircle(ink.period, band * 0.13f, at)
                        // The fertile window's days carry a small teal dot; ovulation a white egg with a teal centre.
                        CycleMark.Fertile -> drawCircle(ink.fertile, band * 0.11f, at)
                        CycleMark.Ovulation -> {
                            drawCircle(ink.onMark, band * 0.24f, at)
                            drawCircle(ink.fertile, band * 0.12f, at)
                        }
                        else -> Unit
                    }
                    // Day numbers inside the ring: day 1, every seventh, and today.
                    if ((d.day == 1 || d.day % 7 == 0) && !d.today) {
                        val label = measurer.measure(d.day.toString(), numbers)
                        val r = radius - band / 2f - 11.dp.toPx()
                        drawText(label, topLeft = Offset(center.x + r * cos(mid).toFloat() - label.size.width / 2f, center.y + r * sin(mid).toFloat() - label.size.height / 2f))
                    }
                }
                // Today: a knob riding on the ring.
                ring.firstOrNull { it.today }?.let { d ->
                    val mid = Math.toRadians(d.midDegrees.toDouble())
                    val at = Offset(center.x + radius * cos(mid).toFloat(), center.y + radius * sin(mid).toFloat())
                    drawCircle(colors.left.art.copy(alpha = 0.18f), band * 0.78f, at + Offset(0f, 2.dp.toPx()))
                    drawCircle(colors.sticker, band * 0.7f, at)
                    drawCircle(colors.ink, band * 0.7f, at, style = Stroke(2.5.dp.toPx()))
                    // Today's day number sits in the knob.
                    val label = measurer.measure(d.day.toString(), numbers.copy(color = colors.ink, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                    drawText(label, topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f))
                }
            }
            Column(Modifier.padding(horizontal = side * 0.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                val big = text.big
                val fontSize = when {
                    big.length <= 7 -> 46.sp
                    big.length <= 11 -> 36.sp
                    big.length <= 15 -> 30.sp
                    else -> 25.sp
                }
                Text(big, style = Heartpact.type.display.copy(fontSize = fontSize, lineHeight = fontSize * 1.02f), color = colors.ink, textAlign = TextAlign.Center, maxLines = 2)
                text.line?.let { Text(it, style = Heartpact.type.label, color = if (text.period) ink.period else colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
                text.note?.let {
                    Text(it, style = Heartpact.type.labelSmall, color = ink.ovulation, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).clip(Shapes.Pill).background(ink.fertileSoft).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

/** The month, with the dial's marks; the owner taps a day (up to two months back) to log it. */
@Composable
private fun MonthCalendar(marks: Map<LocalDate, CycleMark>, today: LocalDate, selected: LocalDate?, onPick: ((LocalDate) -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val ink = cycleInk()
    val range = CyclePlan.months(marks, today)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val locale = Locale.getDefault()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    StickerCard(modifier.fillMaxWidth(), shape = Shapes.Dare) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > range.start) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, tr("Previous month"), tint = if (month > range.start) colors.ink else colors.ink4)
                }
                Text(month.atDay(1).format(Dates.formatter("MMMM yyyy")).replaceFirstChar { it.titlecase(locale) }, style = Heartpact.type.titleSmall, color = colors.ink,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < range.endInclusive) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Next month"), tint = if (month < range.endInclusive) colors.ink else colors.ink4)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                (0 until 7).map { firstDay.plus(it.toLong()) }.forEach { d ->
                    Text(d.getDisplayName(TextStyle.NARROW, locale), style = Heartpact.type.labelSmall, color = colors.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            CyclePlan.month(month, marks, today, firstDay).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            DayCell(cell, cell.date == selected && selected != today, ink,
                                onPick?.takeIf { cell.inMonth && CyclePlan.canLog(cell.date, today) }?.let { pick -> { pick(cell.date) } },
                                Modifier.alpha(if (cell.inMonth) 1f else 0.38f))
                        }
                    }
                }
            }
            Legend(ink, Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
        }
    }
}

@Composable
private fun DayCell(cell: CalendarDay, selected: Boolean, ink: CycleInk, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val fmt = Dates.formatter("EEEE d MMMM")
    val what = when (cell.mark) {
        CycleMark.Period -> tr("Period")
        CycleMark.PredictedPeriod -> tr("Predicted period")
        CycleMark.Fertile -> tr("Fertile window, predicted")
        CycleMark.Ovulation -> tr("Ovulation, predicted")
        CycleMark.None -> null
    }
    val said = listOfNotNull(cell.date.format(fmt), if (cell.today) tr("Today") else null, what).joinToString(", ")
    val number = when (cell.mark) {
        CycleMark.Period, CycleMark.Ovulation -> ink.onMark
        CycleMark.PredictedPeriod -> ink.period
        CycleMark.Fertile -> ink.ovulation
        CycleMark.None -> colors.ink
    }
    Box(
        modifier.fillMaxSize().padding(3.dp)
            .then(if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .drawBehind {
                val r = size.minDimension / 2f - 1.dp.toPx()
                when (cell.mark) {
                    CycleMark.Period -> drawCircle(ink.period, r)
                    CycleMark.PredictedPeriod -> {
                        drawCircle(ink.periodSoft, r)
                        drawCircle(ink.period, r - 1.dp.toPx(), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.6.dp.toPx()))))
                    }
                    CycleMark.Fertile -> drawCircle(ink.fertileSoft, r)
                    CycleMark.Ovulation -> drawCircle(ink.fertile, r)
                    CycleMark.None -> Unit
                }
                if (cell.today) drawCircle(colors.ink, r + 0.5.dp.toPx(), style = Stroke(2.2.dp.toPx()))
                else if (selected) drawCircle(colors.ink3, r + 0.5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(cell.date.dayOfMonth.toString(), style = if (cell.today) Heartpact.type.label else Heartpact.type.body, color = number)
        if (cell.mark == CycleMark.Ovulation) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape).background(ink.onMark))
    }
}

/** What each mark means; every one but a logged period is a prediction, and says so. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(ink: CycleInk, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Key(tr("Period")) { drawCircle(ink.period) }
        Key(tr("Predicted period")) {
            drawCircle(ink.periodSoft)
            drawCircle(ink.period, size.minDimension / 2f - 0.8.dp.toPx(), style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4.dp.toPx(), 2.dp.toPx()))))
        }
        Key(tr("Fertile window, predicted")) { drawCircle(ink.fertileSoft) }
        Key(tr("Ovulation, predicted")) {
            drawCircle(ink.fertile)
            drawCircle(ink.onMark, size.minDimension * 0.16f)
        }
        Key(tr("Today")) { drawCircle(colors.ink, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
    }
}

@Composable
private fun Key(label: String, swatch: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(14.dp), onDraw = swatch)
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
