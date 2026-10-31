package app.heartpact.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.data.ApiResult
import app.heartpact.data.CreateGoalRequest
import app.heartpact.data.ExerciseRepository
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.PlusRepository
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

data class NewDareState(
    val type: ExerciseType = ExerciseType.PushUp,
    val target: Int = ExerciseType.PushUp.defaultTarget,
    val time: LocalTime = LocalTime.of(18, 30),
    val window: Int = 30,
    val busy: Boolean = false,
    val note: String? = null,
    /** The partner's own time zone, when the server knows it: the time is read there, since it rings on their phone. */
    val partnerZone: String? = null,
    /** The partner already has as many active dares as the plan allows (Free: one each): pause one or get Plus. */
    val atLimit: Boolean = false,
)

/** The partner's zone when it is known and keeps a different clock from this phone's right now; else null (nothing to say). */
fun otherZone(partnerZone: String?, mine: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): ZoneId? {
    val zone = partnerZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
    return zone.takeIf { it.rules.getOffset(now) != mine.rules.getOffset(now) }
}

class NewDareViewModel(private val exercise: ExerciseRepository, private val partnerId: String, private val plus: PlusRepository? = null) : ViewModel() {
    private val _state = MutableStateFlow(NewDareState())
    val state: StateFlow<NewDareState> = _state.asStateFlow()

    init {
        // Only to say which clock the time is on; the server picks the zone either way.
        viewModelScope.launch { (exercise.today() as? ApiResult.Ok)?.value?.partnerTimeZoneId?.let { zone -> _state.update { it.copy(partnerZone = zone) } } }
        // Say so before the form is filled in: the partner's free daily dare is already taken.
        plus?.let { repository ->
            viewModelScope.launch {
                val limit = (repository.entitlement() as? ApiResult.Ok)?.value?.limits?.activeDaresPerPerson ?: return@launch
                val goals = (exercise.goals() as? ApiResult.Ok)?.value ?: return@launch
                if (goals.count { it.assignedUserId == partnerId && it.isActive } >= limit) _state.update { it.copy(atLimit = true) }
            }
        }
    }

    fun type(type: ExerciseType) = _state.update { it.copy(type = type, target = type.defaultTarget) }
    fun step(delta: Int) = _state.update { it.copy(target = (it.target + delta).coerceIn(it.type.minTarget, it.type.maxTarget)) }
    fun time(time: LocalTime) = _state.update { it.copy(time = time) }
    fun window(minutes: Int) = _state.update { it.copy(window = minutes) }

    /**
     * The dare rings on the partner's phone daily at this local time, in the partner's time zone when the server
     * has it (it uses theirs anyway), else this phone's.
     */
    fun send(onDone: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val zone = s.partnerZone?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: ZoneId.systemDefault().id
            val request = CreateGoalRequest(partnerId, s.type, null, s.target, s.time.format(DateTimeFormatter.ofPattern("HH:mm")), zone, s.window)
            when (val result = exercise.createGoal(request)) {
                is ApiResult.Ok -> onDone()
                is ApiResult.Failure -> _state.update { it.copy(busy = false, note = result.message, atLimit = it.atLimit || result.code == DARE_LIMIT) }
                ApiResult.Offline -> _state.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }

    companion object {
        const val DARE_LIMIT = "exercise.dare_limit"
    }
}

private val STEP = mapOf(Measure.Reps to 5, Measure.Seconds to 10)

/** New dare (mockup 04): what, how many, when it rings, how long it may ring. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NewDareScreen(state: NewDareState, partnerName: String, onType: (ExerciseType) -> Unit, onStep: (Int) -> Unit, onTime: (LocalTime) -> Unit, onWindow: (Int) -> Unit, onSend: () -> Unit, onBack: () -> Unit,
                  onGetPlus: (() -> Unit)? = null) {
    val colors = Heartpact.colors
    var picking by remember { mutableStateOf(false) }
    val step = STEP.getValue(state.type.measure)
    val end = state.time.plusMinutes(state.window.toLong())
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("New dare for {partner}", "partner" to partnerName), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Exercise"), style = Heartpact.type.label, color = colors.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseType.entries.forEach { HeartChip(it.displayName, state.type == it, { onType(it) }) }
            }
            Text(if (state.type.measure == Measure.Seconds) tr("Seconds") else tr("Reps"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconButton(onClick = { onStep(-step) }, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Remove, tr("Fewer"), tint = colors.ink) }
                Text("${state.target}", style = Heartpact.type.displaySmall, color = colors.ink)
                IconButton(onClick = { onStep(step) }, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Add, tr("More"), tint = colors.ink) }
            }
            Text(tr("Rings every day at"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            HeartButton(state.time.format(fmt), { picking = true }, kind = app.heartpact.ui.components.ButtonKind.Outline, icon = Icons.Rounded.Schedule, fillWidth = false)
            otherZone(state.partnerZone)?.let { zone ->
                Text(tr("That's {partner}'s time ({zone}), where it's {time} now.", "partner" to partnerName,
                    "zone" to zone.getDisplayName(TextStyle.FULL, Locale.getDefault()), "time" to LocalTime.now(zone).format(fmt)),
                    style = Heartpact.type.small, color = colors.ink2)
            }
            Text(tr("Rings for up to"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30).forEach { HeartChip(tr("{minutes} min", "minutes" to it), state.window == it, { onWindow(it) }) }
            }
            Text(tr("If it isn't done by {start}, {partner}'s phone rings until the camera counts {target}, you both call it off, or {end} passes. {partner} can always silence it on their phone.",
                "start" to state.time.format(fmt), "partner" to partnerName, "target" to state.target, "end" to end.format(fmt)),
                style = Heartpact.type.small, color = colors.ink2)
            StickerCard(Modifier.padding(top = 6.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (LocalActor.current == Side.Left) HeartSticker(HalfState.Stuck, HalfState.Slot, 40.dp) else HeartSticker(HalfState.Slot, HalfState.Stuck, 40.dp)
                    Text(tr("Your half sticks when you send it. {partner}'s sticks when the camera counts {target}.", "partner" to partnerName, "target" to state.target), style = Heartpact.type.body.copy(fontSize = 13.sp), color = colors.ink)
                }
            }
            when {
                state.note != null -> NoteCard(state.note)
                state.atLimit -> NoteCard(tr("{partner} already has their free daily dare. Pause or stop it first, or get Heartpact Plus for unlimited dares.", "partner" to partnerName))
            }
            if (state.atLimit && onGetPlus != null) HeartButton(tr("See Heartpact Plus"), onGetPlus, kind = ButtonKind.Tonal)
        }
        HeartButton(tr("Stick my half and send"), onSend, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), busy = state.busy, enabled = !state.atLimit)
    }
    if (picking) {
        val picker = rememberTimePickerState(state.time.hour, state.time.minute)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { onTime(LocalTime.of(picker.hour, picker.minute)); picking = false }) { Text(tr("Done")) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Cancel")) } },
            text = { TimePicker(picker) },
        )
    }
}

