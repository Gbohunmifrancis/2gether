package app.heartpact.ui.food

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.FoodScanResult
import app.heartpact.data.MealType
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.album.CoilPhoto
import app.heartpact.ui.album.PhotoSlot
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Food scan (mockup 11): the photo above, the estimate as a range below, one tap to log it. */
@Composable
fun FoodScanScreen(
    ui: FoodScanUi,
    scansLeft: String?,
    onPortion: (Portion) -> Unit,
    onMeal: (MealType) -> Unit,
    onLog: () -> Unit,
    onRetry: () -> Unit,
    onLogByHand: () -> Unit,
    onBack: () -> Unit,
    image: PhotoSlot = CoilPhoto,
    /** Set when the couple is on Free: the daily limit then offers Plus. */
    onGetPlus: (() -> Unit)? = null,
) {
    val colors = Heartpact.colors
    Box(Modifier.fillMaxSize().background(colors.page2)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.46f).background(colors.sticker2)) {
            image(ui.photo, tr("Your meal"), Modifier.fillMaxSize(), true)
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(Shapes.Pill).background(Color.Black.copy(alpha = 0.35f))) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = Color.White)
                }
                Box(Modifier.weight(1f))
                (scansLeft ?: (ui.phase as? ScanPhase.Ready)?.let { trn(it.scan.scansRemainingToday, "{count} photo estimates left today", "{count} photo estimates left today") })?.let {
                    Text(it, style = Heartpact.type.labelSmall, color = Color.White,
                        modifier = Modifier.clip(Shapes.Pill).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.58f)
                .clip(Shapes.Sheet).background(colors.sticker).navigationBarsPadding(),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp).clip(Shapes.Pill).background(colors.rule2))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
                when (val phase = ui.phase) {
                    ScanPhase.Reading -> Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Heartpact.actor.action, strokeWidth = 3.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(tr("Reading your plate"), style = Heartpact.type.title, color = colors.ink)
                            Text(tr("A few seconds. The photo isn't kept."), style = Heartpact.type.body, color = colors.ink2)
                        }
                    }
                    is ScanPhase.Failed -> Failed(phase, onRetry, onLogByHand, onGetPlus)
                    is ScanPhase.Ready -> Estimate(phase.scan, ui, onPortion, onMeal)
                }
                ui.note?.let { NoteCard(it, Modifier.padding(top = 12.dp)) }
            }
            (ui.phase as? ScanPhase.Ready)?.let { ready ->
                val adjusted = FoodText.adjust(ready.scan, ui.portion)
                HeartButton(tr("Log {range} kcal to {meal}", "range" to FoodText.range(adjusted.low, adjusted.high), "meal" to ui.meal.label.lowercase()), onLog,
                    Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp), busy = ui.busy)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Estimate(scan: FoodScanResult, ui: FoodScanUi, onPortion: (Portion) -> Unit, onMeal: (MealType) -> Unit) {
    val colors = Heartpact.colors
    val adjusted = FoodText.adjust(scan, ui.portion)
    Text(scan.description, style = Heartpact.type.headline.copy(fontSize = 25.sp), color = colors.ink)
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(FoodText.range(adjusted.low, adjusted.high), style = Heartpact.type.displaySmall, color = colors.ink)
        Text(tr("kcal, about {calories}", "calories" to FoodText.n(adjusted.calories)), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
    }
    Text(tr("An estimate from your photo. Portions are hard to judge from above, so treat it as a range."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(tr("Protein") to adjusted.protein, tr("Carbs") to adjusted.carbs, tr("Fat") to adjusted.fat).forEach { (name, range) ->
            Text("$name ${FoodText.grams(range)}", style = Heartpact.type.labelSmall, color = colors.ink,
                modifier = Modifier.clip(Shapes.Pill).background(colors.sticker2).padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    Text(tr("Portion"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth().clip(Shapes.Pill).border(2.dp, colors.rule, Shapes.Pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Portion.entries.forEach { p ->
            val on = p == ui.portion
            Row(Modifier.weight(1f).clip(Shapes.Pill).background(if (on) Heartpact.actor.soft else colors.sticker).clickable { onPortion(p) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (on) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp).padding(end = 4.dp), tint = Heartpact.actor.onSoft)
                Text(p.label, style = Heartpact.type.label, color = if (on) Heartpact.actor.onSoft else colors.ink2)
            }
        }
    }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MealType.entries.forEach { HeartChip(it.label, it == ui.meal, { onMeal(it) }) }
    }
}

@Composable
private fun Failed(phase: ScanPhase.Failed, onRetry: () -> Unit, onLogByHand: () -> Unit, onGetPlus: (() -> Unit)?) {
    val colors = Heartpact.colors
    val title = when (phase.code) {
        "food.not_food" -> tr("That doesn't look like food")
        "food.scan_limit" -> tr("No photo estimates left today")
        "food.estimation_unavailable" -> tr("Photo estimates aren't set up")
        "offline" -> tr("You're offline")
        else -> tr("No estimate this time")
    }
    Text(title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 10.dp))
    Text(phase.message, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
    val canRetry = phase.code !in setOf("food.scan_limit", "food.estimation_unavailable", "food.not_food", "photo")
    val offerPlus = phase.code == "food.scan_limit" && onGetPlus != null
    if (canRetry) HeartButton(tr("Try again"), onRetry, Modifier.padding(top = 18.dp), kind = ButtonKind.Tonal)
    if (offerPlus) HeartButton(tr("Get more a day with Plus"), { onGetPlus?.invoke() }, Modifier.padding(top = 18.dp))
    HeartButton(tr("Log it by hand"), onLogByHand, Modifier.padding(top = 8.dp), kind = if (canRetry || offerPlus) ButtonKind.Text else ButtonKind.Filled)
}

/** Log a meal by hand, or change or delete one already logged. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodEntryScreen(ui: FoodEntryUi, onDescription: (String) -> Unit, onMeal: (MealType) -> Unit, onCalories: (String) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    val existing = ui.existing
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(if (existing == null) tr("Log a meal") else tr("Change this meal"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeartField(tr("What did you eat?"), ui.description, onDescription,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), error = if (ui.showErrors) ui.descriptionError else null)
            Text(tr("Meal"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach { HeartChip(it.label, it == ui.meal, { onMeal(it) }) }
            }
            HeartField(tr("Calories"), ui.calories, onCalories, Modifier.padding(top = 6.dp).width(200.dp),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), error = if (ui.showErrors) ui.caloriesError else null,
                hint = if (existing?.caloriesLow != null) tr("The photo said {range}", "range" to FoodText.range(existing.low, existing.high)) else tr("A packet or a menu usually says"))
            ui.note?.let { NoteCard(it) }
            if (existing != null) HeartButton(tr("Delete this meal"), onDelete, Modifier.offset(x = (-12).dp), kind = ButtonKind.Text, icon = Icons.Rounded.Delete, fillWidth = false, height = 44.dp)
        }
        HeartButton(if (existing == null) tr("Log it") else tr("Save"), onSave, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), busy = ui.busy)
    }
}
