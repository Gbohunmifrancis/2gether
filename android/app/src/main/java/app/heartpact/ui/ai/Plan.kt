package app.heartpact.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.AiRepository
import app.heartpact.data.ApiResult
import app.heartpact.data.DateIdea
import app.heartpact.data.DatePlan
import app.heartpact.data.GameIdea
import app.heartpact.data.GameType
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.PlanBudget
import app.heartpact.data.PlanMood
import app.heartpact.data.PlanRequest
import app.heartpact.data.PlanSetting
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.games.GameIcon
import app.heartpact.ui.games.GamePlay
import app.heartpact.ui.games.blob
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Plan something: what you feel like, what it may cost, in or out, maybe a city; then ideas. */
data class PlanUi(
    val mood: PlanMood = PlanMood.Cosy,
    val budget: PlanBudget = PlanBudget.Free,
    val setting: PlanSetting = PlanSetting.In,
    val city: String = "",
    val busy: Boolean = false,
    val plan: DatePlan? = null,
    val note: String? = null,
    /** Plans left today; null until the server says. */
    val left: Int? = null,
)

/** Pure: the request the form makes. A city goes only with going out, trimmed, and never empty. */
object PlanForm {
    const val MAX_CITY = 60

    fun request(ui: PlanUi): PlanRequest =
        PlanRequest(ui.mood, ui.budget, ui.setting, ui.city.trim().takeIf { ui.setting == PlanSetting.Out && it.isNotEmpty() })
}

class PlanViewModel(private val ai: AiRepository) : ViewModel() {
    private val _ui = MutableStateFlow(PlanUi())
    val ui: StateFlow<PlanUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { (ai.status() as? ApiResult.Ok)?.value?.let { status -> _ui.update { it.copy(left = status.plansLeft) } } }
    }

    fun mood(mood: PlanMood) = _ui.update { it.copy(mood = mood) }
    fun budget(budget: PlanBudget) = _ui.update { it.copy(budget = budget) }
    fun setting(setting: PlanSetting) = _ui.update { it.copy(setting = setting) }
    fun city(city: String) = _ui.update { it.copy(city = city.take(PlanForm.MAX_CITY)) }

    fun suggest() {
        val state = _ui.value
        if (state.busy) return
        _ui.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            when (val result = ai.plan(PlanForm.request(state))) {
                is ApiResult.Ok -> _ui.update { it.copy(busy = false, plan = result.value, left = result.value.left) }
                is ApiResult.Failure -> _ui.update { it.copy(busy = false, note = result.message, left = if (result.status == 429) 0 else it.left) }
                ApiResult.Offline -> _ui.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }
}

private val PlanMood.label: String get() = when (this) {
    PlanMood.Cosy -> tr("Cosy")
    PlanMood.Active -> tr("Active")
    PlanMood.Adventurous -> tr("Adventurous")
    PlanMood.Romantic -> tr("Romantic")
}

private val PlanBudget.label: String get() = when (this) {
    PlanBudget.Free -> tr("Free")
    PlanBudget.Low -> tr("Low cost")
    PlanBudget.Treat -> tr("A treat")
}

private val PlanSetting.label: String get() = when (this) {
    PlanSetting.In -> tr("Stay in")
    PlanSetting.Out -> tr("Go out")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(
    ui: PlanUi,
    partnerName: String,
    onMood: (PlanMood) -> Unit,
    onBudget: (PlanBudget) -> Unit,
    onSetting: (PlanSetting) -> Unit,
    onCity: (String) -> Unit,
    onSuggest: () -> Unit,
    onGame: (GameType) -> Unit,
    onBack: () -> Unit,
) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("Plan something"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Pick a mood and a budget, and get three date ideas and two games for tonight with {partner}.", "partner" to partnerName),
                style = Heartpact.type.body, color = colors.ink2)
            Label(tr("Mood"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanMood.entries.forEach { HeartChip(it.label, ui.mood == it, { onMood(it) }) }
            }
            Label(tr("Budget"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanBudget.entries.forEach { HeartChip(it.label, ui.budget == it, { onBudget(it) }) }
            }
            Label(tr("Where"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanSetting.entries.forEach { HeartChip(it.label, ui.setting == it, { onSetting(it) }) }
            }
            if (ui.setting == PlanSetting.Out) {
                HeartField(tr("City (optional)"), ui.city, onCity, Modifier.padding(top = 4.dp), maxLength = PlanForm.MAX_CITY,
                    hint = tr("Only the city is sent, never where you are."),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done))
            }
            HeartButton(if (ui.plan == null) tr("Suggest") else tr("Suggest again"), onSuggest, Modifier.padding(top = 8.dp), icon = Icons.Rounded.Lightbulb,
                busy = ui.busy, enabled = ui.left != 0)
            ui.left?.let {
                Text(if (it == 0) tr("That's all the plans for today.") else trn(it, "{count} plan left today", "{count} plans left today"),
                    style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            ui.note?.let { NoteCard(it) }
            when {
                ui.busy -> PlanPlaceholder()
                ui.plan != null -> PlanResult(ui.plan, onGame)
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = Heartpact.type.label, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun PlanResult(plan: DatePlan, onGame: (GameType) -> Unit) {
    val colors = Heartpact.colors
    Text(tr("Date ideas"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
    StickerCard {
        plan.dates.forEachIndexed { index, idea -> DateRow(idea, divider = index > 0) }
    }
    if (plan.games.isNotEmpty()) {
        Text(tr("Play tonight"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 12.dp))
        StickerCard {
            plan.games.forEachIndexed { index, idea -> GameRow(idea, divider = index > 0) { onGame(idea.gameType) } }
        }
    }
}

@Composable
private fun DateRow(idea: DateIdea, divider: Boolean) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxWidth().perforated(divider, colors.rule)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(idea.title, style = Heartpact.type.titleSmall, color = colors.ink)
            Text(idea.why, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun GameRow(idea: GameIdea, divider: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxWidth().perforated(divider, colors.rule)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val blob = idea.gameType.blob
            Box(Modifier.size(44.dp).clip(CircleShape).background(blob.fill), contentAlignment = Alignment.Center) { GameIcon(idea.gameType, blob.ink, 26.dp) }
            Column(Modifier.weight(1f)) {
                Text(GamePlay.title(idea.gameType), style = Heartpact.type.titleSmall, color = colors.ink)
                Text(idea.why, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Open the games"), tint = colors.ink2)
        }
    }
}

/** The dashed tear line a card's later rows have, as on CardRow. */
private fun Modifier.perforated(on: Boolean, color: androidx.compose.ui.graphics.Color): Modifier = if (!on) this else drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
}

@Composable
private fun PlanPlaceholder() {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(Shapes.Card).background(Heartpact.colors.sticker).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerLine(0.5f, height = 14.dp)
                ShimmerLine(0.85f)
            }
        }
    }
}
