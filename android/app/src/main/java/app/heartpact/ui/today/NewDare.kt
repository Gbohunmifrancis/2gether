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
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class NewDareState(
    val type: ExerciseType = ExerciseType.PushUp,
    val target: Int = ExerciseType.PushUp.defaultTarget,
    val time: LocalTime = LocalTime.of(18, 30),
    val window: Int = 30,
    val busy: Boolean = false,
    val note: String? = null,
)

class NewDareViewModel(private val exercise: ExerciseRepository, private val partnerId: String) : ViewModel() {
    private val _state = MutableStateFlow(NewDareState())
    val state: StateFlow<NewDareState> = _state.asStateFlow()

    fun type(type: ExerciseType) = _state.update { it.copy(type = type, target = type.defaultTarget) }
    fun step(delta: Int) = _state.update { it.copy(target = (it.target + delta).coerceIn(it.type.minTarget, it.type.maxTarget)) }
    fun time(time: LocalTime) = _state.update { it.copy(time = time) }
    fun window(minutes: Int) = _state.update { it.copy(window = minutes) }

    /** The dare rings on the partner's phone daily at this local time, in this phone's time zone. */
    fun send(onDone: () -> Unit) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val request = CreateGoalRequest(partnerId, s.type, null, s.target, s.time.format(DateTimeFormatter.ofPattern("HH:mm")), ZoneId.systemDefault().id, s.window)
            when (val result = exercise.createGoal(request)) {
                is ApiResult.Ok -> onDone()
                is ApiResult.Failure -> _state.update { it.copy(busy = false, note = result.message) }
                ApiResult.Offline -> _state.update { it.copy(busy = false, note = OFFLINE_MESSAGE) }
            }
        }
    }
}

private val STEP = mapOf(Measure.Reps to 5, Measure.Seconds to 10)

/** New dare (mockup 04): what, how many, when it rings, how long it may ring. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NewDareScreen(state: NewDareState, partnerName: String, onType: (ExerciseType) -> Unit, onStep: (Int) -> Unit, onTime: (LocalTime) -> Unit, onWindow: (Int) -> Unit, onSend: () -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    var picking by remember { mutableStateOf(false) }
    val step = STEP.getValue(state.type.measure)
    val end = state.time.plusMinutes(state.window.toLong())
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("New dare for $partnerName", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Exercise", style = Heartpact.type.label, color = colors.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseType.entries.forEach { HeartChip(it.displayName, state.type == it, { onType(it) }) }
            }
            Text(if (state.type.measure == Measure.Seconds) "Seconds" else "Reps", style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconButton(onClick = { onStep(-step) }, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Remove, "Fewer", tint = colors.ink) }
                Text("${state.target}", style = Heartpact.type.displaySmall, color = colors.ink)
                IconButton(onClick = { onStep(step) }, modifier = Modifier.clip(Shapes.Pill).background(colors.sticker)) { Icon(Icons.Rounded.Add, "More", tint = colors.ink) }
            }
            Text("Rings every day at", style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            HeartButton(state.time.format(fmt), { picking = true }, kind = app.heartpact.ui.components.ButtonKind.Outline, icon = Icons.Rounded.Schedule, fillWidth = false)
            Text("Rings for up to", style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30).forEach { HeartChip("$it min", state.window == it, { onWindow(it) }) }
            }
            Text("If it isn't done by ${state.time.format(fmt)}, $partnerName's phone rings until the camera counts ${state.target}, you both call it off, or ${end.format(fmt)} passes. $partnerName can always silence it on their phone.",
                style = Heartpact.type.small, color = colors.ink2)
            StickerCard(Modifier.padding(top = 6.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (LocalActor.current == Side.Left) HeartSticker(HalfState.Stuck, HalfState.Slot, 40.dp) else HeartSticker(HalfState.Slot, HalfState.Stuck, 40.dp)
                    Text("Your half sticks when you send it. $partnerName's sticks when the camera counts ${state.target}.", style = Heartpact.type.body.copy(fontSize = 13.sp), color = colors.ink)
                }
            }
            state.note?.let { NoteCard(it) }
        }
        HeartButton("Stick my half and send", onSend, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), busy = state.busy)
    }
    if (picking) {
        val picker = rememberTimePickerState(state.time.hour, state.time.minute)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { onTime(LocalTime.of(picker.hour, picker.minute)); picking = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(picker) },
        )
    }
}

