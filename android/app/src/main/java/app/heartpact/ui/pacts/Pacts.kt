package app.heartpact.ui.pacts

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.utc
import app.heartpact.exercise.Measure
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.AdaptivePage
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardColumns
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
import app.heartpact.ui.today.DaySticker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class PactsUi(val view: PactsView? = null, val note: String? = null, val busy: Boolean = false)

class PactsViewModel(application: Application, private val me: String) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(PactsUi())
    val ui: StateFlow<PactsUi> = _ui.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val goals = app.exercise.goals()
        val history = (app.exercise.history(30) as? ApiResult.Ok)?.value
        when (goals) {
            is ApiResult.Ok -> _ui.update { it.copy(view = PactsBuilder.build(goals.value, history, me, Instant.now()), note = null) }
            ApiResult.Offline -> _ui.update { it.copy(note = OFFLINE_MESSAGE) }
            is ApiResult.Failure -> _ui.update { it.copy(note = goals.message) }
        }
    }

    fun stop(goalId: String) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = app.exercise.stopGoal(goalId)) {
                is ApiResult.Ok -> refresh()
                is ApiResult.Failure -> _ui.update { it.copy(note = result.message) }
                ApiResult.Offline -> _ui.update { it.copy(note = OFFLINE_MESSAGE) }
            }
            _ui.update { it.copy(busy = false) }
        }
    }
}

/** Pacts (mockup 09): every dare on the sticker page, split by who it's for. */
@Composable
fun PactsTab(ui: PactsUi, partnerName: String, onStop: (String) -> Unit, onNewDare: () -> Unit, initialForMe: Boolean = true) {
    val colors = Heartpact.colors
    var forMe by remember { mutableStateOf(initialForMe) }
    var stopping by remember { mutableStateOf<PactCard?>(null) }
    // With room, the dares lay out in columns, like a sticker book.
    AdaptivePage(header = {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
            Text(tr("Pacts"), style = Heartpact.type.headline, color = colors.ink)
            Text(trn(ui.view?.hearts ?: 0, "Your sticker page · {count} heart in 30 days", "Your sticker page · {count} hearts in 30 days"), style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
        }
    }, second = null, wide = true) {
        Segmented(listOf(tr("Dares for you"), tr("Dares you set")), if (forMe) 0 else 1) { forMe = it == 0 }
        ui.note?.let { NoteCard(it, Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) }
        val cards = ui.view?.let { if (forMe) it.forMe else it.bySet }
        if (cards != null && cards.isEmpty()) {
            StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp).fillMaxWidth(), shape = Shapes.Dare) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Character(if (forMe) Who.Both else Who.Right, Pose.Stand, 96.dp)
                    Text(if (forMe) tr("No dares for you yet") else tr("You haven't dared {partner} yet", "partner" to partnerName), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 10.dp))
                    Text(if (forMe) tr("When {partner} sets one, it shows up here with every heart you keep.", "partner" to partnerName) else tr("Set one and your half goes on first."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        CardColumns(cards.orEmpty(), most = 2) { card -> PactCardView(card, partnerName, forMe, onStopRequest = { stopping = card }) }
        Legend()
        HeartButton(tr("New dare for {partner}", "partner" to partnerName), onNewDare, Modifier.padding(horizontal = 12.dp).padding(top = 14.dp), kind = ButtonKind.Tonal, icon = Icons.Rounded.Add)
    }
    stopping?.let { card ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(tr("Stop this dare?")) },
            text = { Text(tr("{dare} won't ring for {partner} any more. The hearts already kept stay on both pages.", "dare" to card.goal.exerciseType.label(card.goal.target).replaceFirstChar { it.uppercase() }, "partner" to partnerName)) },
            confirmButton = { TextButton(onClick = { onStop(card.goal.id); stopping = null }) { Text(tr("Stop it")) } },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text(tr("Keep it")) } },
        )
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = Heartpact.colors
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(Shapes.Pill).background(colors.sticker).border(2.dp, colors.rule, Shapes.Pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Row(
                Modifier.weight(1f).clip(Shapes.Pill).background(if (on) Heartpact.actor.soft else colors.sticker).clickable { onSelect(index) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp).padding(end = 4.dp), tint = Heartpact.actor.onSoft)
                Text(label, style = Heartpact.type.label, color = if (on) Heartpact.actor.onSoft else colors.ink2)
            }
        }
    }
}

@Composable
private fun PactCardView(card: PactCard, partnerName: String, forMe: Boolean, onStopRequest: () -> Unit) {
    val colors = Heartpact.colors
    val goal = card.goal
    val time = utc(goal.nextDueAtUtc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val amount = if (goal.exerciseType.measure == Measure.Seconds) trn(goal.target, "{exercise}, {count} second", "{exercise}, {count} seconds", "exercise" to goal.exerciseType.displayName)
        else goal.exerciseType.label(goal.target)
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp), shape = Shapes.Dare) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(amount.replaceFirstChar { it.uppercase() }, style = Heartpact.type.titleSmall.copy(fontSize = 18.sp), color = colors.ink)
                    Text(if (forMe) tr("Daily {time} · {partner}'s dare · {minutes} min ring", "time" to time, "partner" to partnerName, "minutes" to goal.graceMinutes)
                        else tr("Daily {time} · for {partner} · {minutes} min ring", "time" to time, "partner" to partnerName, "minutes" to goal.graceMinutes), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 3.dp))
                }
                StatusTag(card.status)
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                card.days.forEach { (_, s) -> DayStickerView(s, forMe) }
            }
            Row(Modifier.padding(top = 6.dp).fillMaxWidth()) {
                Text(card.days.first().first.format(Dates.formatter("d MMM")), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.weight(1f))
                Text(tr("today, {date}", "date" to card.days.last().first.format(Dates.formatter("d MMM"))), style = Heartpact.type.small, color = colors.ink3)
            }
            if (!forMe) HeartButton(tr("Stop this dare"), onStopRequest, Modifier.padding(top = 6.dp), kind = ButtonKind.Text, height = 40.dp, fillWidth = false)
        }
    }
}

@Composable
private fun StatusTag(status: DareStatus) {
    val colors = Heartpact.colors
    val strong = status == DareStatus.DoneToday || status == DareStatus.Ringing
    Text(status.label, style = Heartpact.type.labelSmall.copy(fontSize = 12.sp), color = if (strong) colors.left.onSoft else colors.ink2,
        modifier = Modifier.clip(Shapes.Pill).background(if (strong) colors.left.tint else colors.sticker2).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun DayStickerView(sticker: DaySticker, forMe: Boolean) {
    val colors = Heartpact.colors
    val mine = LocalActor.current
    when (sticker) {
        DaySticker.Heart -> HeartSticker(HalfState.Stuck, HalfState.Stuck, 30.dp)
        DaySticker.Peeled -> PeeledHeart(30.dp)
        // For my dares the partner's half is on first; for theirs, mine is.
        DaySticker.Pending -> if ((mine == Side.Left) == forMe) HeartSticker(HalfState.Slot, HalfState.Stuck, 30.dp) else HeartSticker(HalfState.Stuck, HalfState.Slot, 30.dp)
        DaySticker.Missed -> Box(Modifier.size(28.dp, 30.dp).clip(Shapes.Tile).background(colors.sticker2).border(2.dp, colors.rule2, Shapes.Tile))
        DaySticker.Empty -> Box(Modifier.size(28.dp, 30.dp).clip(Shapes.Tile).background(colors.page))
    }
}

@Composable
private fun Legend() {
    val colors = Heartpact.colors
    Column(Modifier.padding(horizontal = 22.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(tr("done together")) { HeartSticker(HalfState.Stuck, HalfState.Stuck, 16.dp) }
            LegendItem(tr("one half on")) { HeartSticker(HalfState.Slot, HalfState.Stuck, 16.dp) }
            LegendItem(tr("called off together")) { PeeledHeart(16.dp) }
        }
        LegendItem(tr("missed")) { Box(Modifier.size(15.dp, 16.dp).clip(Shapes.Tile).background(colors.sticker2).border(2.dp, colors.rule2, Shapes.Tile)) }
    }
}

@Composable
private fun LegendItem(label: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon()
        Text(label, style = Heartpact.type.small, color = Heartpact.colors.ink2)
    }
}
