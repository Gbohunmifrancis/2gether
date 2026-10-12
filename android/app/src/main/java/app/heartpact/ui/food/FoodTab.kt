package app.heartpact.ui.food

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.DinnerDining
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FreeBreakfast
import androidx.compose.material.icons.rounded.LunchDining
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import app.heartpact.data.FoodDay
import app.heartpact.data.FoodEntry
import app.heartpact.data.FoodPartnerDay
import app.heartpact.data.FoodShareLevel
import app.heartpact.data.MealType
import app.heartpact.data.utc
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.SectionHeader
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Food (mockup 10): today's range, what was logged, and what the partner shares. */
@Composable
fun FoodTab(
    ui: FoodUi,
    partnerName: String,
    onSnap: () -> Unit,
    onChoosePhoto: () -> Unit,
    onLogByHand: () -> Unit,
    onOpenEntry: (FoodEntry) -> Unit,
    onSaveTargets: (Int, FoodShareLevel) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val colors = Heartpact.colors
    var editingAim by remember { mutableStateOf(false) }
    val day = ui.day
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 104.dp)) {
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                Text("Food", style = Heartpact.type.headline, color = colors.ink)
                Text("${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} · photo estimates run about ±30%", style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
            }
            ui.note?.let { NoteCard(it, Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) }
            if (day != null) {
                Summary(day) { editingAim = true }
                SectionHeader("Logged today", Modifier.padding(horizontal = 8.dp).padding(top = 14.dp))
                if (day.entries.isEmpty()) {
                    StickerCard(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        Text("Nothing yet. Snap your next meal, or log it by hand.", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(18.dp))
                    }
                } else {
                    StickerCard(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        day.entries.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 0.dp), color = colors.rule)
                            EntryRow(entry) { onOpenEntry(entry) }
                        }
                    }
                }
                day.partner?.let { PartnerCard(it, partnerName) }
                Row(Modifier.padding(horizontal = 12.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeartButton("Choose a photo", onChoosePhoto, kind = ButtonKind.Text, icon = Icons.Rounded.PhotoLibrary, fillWidth = false, height = 44.dp)
                    HeartButton("Log by hand", onLogByHand, kind = ButtonKind.Text, icon = Icons.Rounded.Edit, fillWidth = false, height = 44.dp)
                }
                FoodText.scansLeft(day.scanLimitPerDay - day.scansUsedToday, day.scanLimitPerDay)?.let {
                    Text(it, style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        }
        HeartButton("Snap a meal", onSnap, Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = Icons.Rounded.PhotoCamera, fillWidth = false)
    }
    if (editingAim && day != null) AimDialog(day, partnerName, onDismiss = { editingAim = false }, onSave = { cal, share -> onSaveTargets(cal, share); editingAim = false })
}

@Composable
private fun Summary(day: FoodDay, onAim: () -> Unit) {
    val colors = Heartpact.colors
    val totals = day.totals
    val aim = day.targets.dailyCalories
    StickerCard(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), shape = Shapes.Dare) {
        Column(Modifier.padding(20.dp)) {
            Text(FoodText.headline(day.entries, totals), style = Heartpact.type.title.copy(fontSize = 22.sp, lineHeight = 27.sp), color = colors.ink)
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RangeBar(day.entries, totals.low, totals.high, aim, Modifier.weight(1f).height(26.dp))
                Column(Modifier.padding(start = 10.dp).clickable(onClick = onAim).semantics { contentDescription = "Daily aim, about ${FoodText.n(aim)} kcal. Change it." }) {
                    Text("~${FoodText.n(aim)}", style = Heartpact.type.labelSmall, color = colors.ink2)
                    Text("aim", style = Heartpact.type.labelSmall, color = colors.ink2)
                }
            }
            Text(FoodText.caption(totals, aim), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** One segment per meal, then a dashed box from the low end of the day to the high end. */
@Composable
private fun RangeBar(entries: List<FoodEntry>, low: Int, high: Int, aim: Int, modifier: Modifier) {
    val colors = Heartpact.colors
    val strong = Heartpact.actor.action
    val soft = colors.right.art
    val ink = colors.ink
    val track = colors.sticker2
    val scale = maxOf(aim * 1.1f, high * 1.05f, 1f)
    Canvas(modifier.semantics { contentDescription = "About ${FoodText.range(low, high)} of ${FoodText.n(aim)} kcal" }) {
        val barH = size.height * 0.5f
        val top = (size.height - barH) / 2
        val r = CornerRadius(barH / 2, barH / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, barH), r)
        var x = 0f
        entries.forEachIndexed { i, e ->
            val w = size.width * e.calories / scale
            drawRoundRect(if (i % 2 == 0) strong else soft, Offset(x, top), Size(w.coerceAtLeast(barH), barH), r)
            x += w
        }
        if (high > low) {
            val from = size.width * low / scale
            val to = size.width * high / scale
            drawRoundRect(ink, Offset(from, 1.5f), Size(to - from, size.height - 3f), CornerRadius(size.height / 2, size.height / 2),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }
    }
}

private fun MealType.icon(): ImageVector = when (this) {
    MealType.Breakfast -> Icons.Rounded.FreeBreakfast
    MealType.Lunch -> Icons.Rounded.LunchDining
    MealType.Dinner -> Icons.Rounded.DinnerDining
    MealType.Snack -> Icons.Rounded.BakeryDining
}

@Composable
private fun EntryRow(entry: FoodEntry, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val time = utc(entry.loggedAtUtc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(Shapes.Tile).background(Heartpact.actor.tint), contentAlignment = Alignment.Center) {
            Icon(entry.mealType.icon(), null, Modifier.size(26.dp), tint = Heartpact.actor.onSoft)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.description, style = Heartpact.type.titleSmall, color = colors.ink, maxLines = 2)
            Text(FoodText.entryLine(entry, time), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (entry.low != entry.high) "~${FoodText.n(entry.calories)}" else FoodText.n(entry.calories), style = Heartpact.type.label, color = colors.ink)
            if (entry.low != entry.high) Text(FoodText.range(entry.low, entry.high), style = Heartpact.type.small, color = colors.ink2)
            else Text("kcal", style = Heartpact.type.small, color = colors.ink2)
        }
    }
}

@Composable
private fun PartnerCard(partner: FoodPartnerDay, partnerName: String) {
    val colors = Heartpact.colors
    val theirSide = if (LocalActor.current == Side.Left) Side.Right else Side.Left
    val totals = partner.totals
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp).fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (theirSide == Side.Right) HeartSticker(HalfState.Empty, HalfState.Stuck, 34.dp) else HeartSticker(HalfState.Stuck, HalfState.Empty, 34.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(partner.userName.ifBlank { partnerName }, style = Heartpact.type.titleSmall, color = colors.ink)
                val estimate = totals != null && totals.low != totals.high
                Text(FoodText.partnerLine(partner.shareLevel) + if (estimate) " · estimate" else "", style = Heartpact.type.small, color = colors.ink2)
            }
            if (totals != null) Column(horizontalAlignment = Alignment.End) {
                Text(if (totals.entryCount == 0) "nothing yet" else FoodText.range(totals.low, totals.high), style = Heartpact.type.label, color = colors.ink)
                if (totals.entryCount > 0) Text("kcal", style = Heartpact.type.small, color = colors.ink2)
            }
        }
        if (partner.shareLevel == FoodShareLevel.FullDetail && partner.entries.isNotEmpty()) {
            Column(Modifier.padding(start = 62.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                partner.entries.forEach { e ->
                    Row {
                        Text("${e.mealType.label}: ${e.description}", style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(FoodText.range(e.low, e.high), style = Heartpact.type.small, color = colors.ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun AimDialog(day: FoodDay, partnerName: String, onDismiss: () -> Unit, onSave: (Int, FoodShareLevel) -> Unit) {
    var calories by remember { mutableStateOf(day.targets.dailyCalories.toString()) }
    var share by remember { mutableStateOf(day.targets.shareLevel) }
    val value = calories.toIntOrNull()?.takeIf { it in 800..6000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your aim and sharing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeartField("Daily aim, kcal", calories, { calories = it.filter(Char::isDigit).take(4) },
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), error = if (value == null) "Between 800 and 6,000" else null)
                Text("What $partnerName sees", style = Heartpact.type.label, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 6.dp))
                FoodShareLevel.entries.forEach { level ->
                    Row(Modifier.fillMaxWidth().clickable { share = level }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = share == level, onClick = { share = level })
                        Text(FoodText.shareChoice(level, partnerName), style = Heartpact.type.body, color = Heartpact.colors.ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let { onSave(it, share) } }, enabled = value != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
