package app.heartpact.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.DareIdea
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.today.NewDareState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Suggest a dare" on the New dare screen: three ideas from the server, one tap puts one in the form. */
data class DareIdeasUi(
    /** The server offers ideas: it has a key and the admin switch is on. Otherwise nothing shows. */
    val available: Boolean = false,
    val busy: Boolean = false,
    val ideas: List<DareIdea> = emptyList(),
    /** The idea last tapped, now in the form. */
    val picked: Int? = null,
    val note: String? = null,
    /** What is left of today's ideas; null until the server says. */
    val left: Int? = null,
)

/** Pure: what tapping an idea does to the form. */
object DareIdeaPick {
    /** The exercise, the target kept within the exercise's limits, and the time; the ringing window stays as chosen. */
    fun apply(state: NewDareState, idea: DareIdea): NewDareState {
        val type = idea.exerciseType ?: return state
        val time = runCatching { LocalTime.parse(idea.scheduledTime) }.getOrNull() ?: state.time
        return state.copy(type = type, target = idea.target.coerceIn(type.minTarget, type.maxTarget), time = time)
    }
}

@Composable
fun DareIdeasSection(ui: DareIdeasUi, partnerName: String, onSuggest: () -> Unit, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (!ui.available) return
    val colors = Heartpact.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            ui.busy -> {
                Text(tr("Thinking up three dares for {partner}…", "partner" to partnerName), style = Heartpact.type.label, color = colors.ink)
                repeat(3) { IdeaPlaceholder() }
            }
            ui.ideas.isNotEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Ideas for {partner}", "partner" to partnerName), style = Heartpact.type.label, color = colors.ink)
                        Text(tr("Tap one to put it in the dare."), style = Heartpact.type.small, color = colors.ink2)
                    }
                    if (ui.left != 0) HeartButton(tr("New ideas"), onSuggest, kind = ButtonKind.Text, fillWidth = false, height = 40.dp)
                }
                ui.ideas.forEachIndexed { index, idea -> IdeaCard(idea, ui.picked == index) { onPick(index) } }
                ui.left?.let {
                    Text(if (it == 0) tr("That's all the ideas for today.") else trn(it, "{count} more round of ideas today", "{count} more rounds of ideas today"),
                        style = Heartpact.type.small, color = colors.ink2)
                }
            }
            else -> {
                HeartButton(tr("Suggest a dare"), onSuggest, kind = ButtonKind.Tonal, icon = Icons.Rounded.Lightbulb, enabled = ui.left != 0)
                Text(if (ui.left == 0) tr("That's all the ideas for today.") else tr("Three ideas from {partner}'s recent dares and streak.", "partner" to partnerName),
                    style = Heartpact.type.small, color = colors.ink2)
            }
        }
        ui.note?.let { NoteCard(it) }
    }
}

@Composable
private fun IdeaCard(idea: DareIdea, picked: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val type = idea.exerciseType ?: return
    val time = runCatching { LocalTime.parse(idea.scheduledTime).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }.getOrDefault(idea.scheduledTime)
    val label = type.label(idea.target).replaceFirstChar { it.uppercase() }
    val pickedWords = tr("In the dare")
    Row(
        Modifier.fillMaxWidth().clip(Shapes.Well)
            .background(if (picked) actor.tint else colors.sticker2)
            .border(2.dp, if (picked) actor.action else colors.sticker2, Shapes.Well)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (picked) contentDescription = "$label, $time. $pickedWords" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("{dare} at {time}", "dare" to label, "time" to time), style = Heartpact.type.titleSmall, color = colors.ink)
            Text(idea.reason, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
        if (picked) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(24.dp), tint = actor.action)
    }
}

@Composable
private fun IdeaPlaceholder() {
    Column(Modifier.fillMaxWidth().clip(Shapes.Well).background(Heartpact.colors.sticker2.copy(alpha = 0.45f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShimmerLine(0.45f, height = 14.dp)
        ShimmerLine(0.9f)
    }
}
