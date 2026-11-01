package app.heartpact.ui.watch

import android.app.Application
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.ExerciseOccurrence
import app.heartpact.data.OccurrenceStatus
import app.heartpact.exercise.Cheer
import app.heartpact.exercise.CheerShown
import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Landmark
import app.heartpact.exercise.LiveCodec
import app.heartpact.exercise.LiveCues
import app.heartpact.exercise.LiveFrame
import app.heartpact.exercise.LivePhase
import app.heartpact.exercise.Pose
import app.heartpact.i18n.tr
import app.heartpact.live.LiveEvent
import app.heartpact.ui.camera.CameraText
import app.heartpact.ui.camera.CheerBurst
import app.heartpact.ui.camera.button
import app.heartpact.ui.camera.heart
import app.heartpact.ui.camera.stickFigure
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.components.rememberStill
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import app.heartpact.ui.graphics.Pose as CharacterPose

/** How the dare being watched ended, when it ended without being done. */
enum class WatchEnd { Missed, LetOff }

/** What the live view shows. Pure, so it can be rendered in tests. */
data class WatchUi(
    val partnerName: String,
    val partnerSide: Side = Side.Right,
    val type: ExerciseType? = null,
    val target: Int = 0,
    val count: Int = 0,
    /** The latest frame and the one before it: the figure glides from one to the other. */
    val frame: LiveFrame? = null,
    val previous: LiveFrame? = null,
    /** Bumped with each frame; [frameGapMs] is how long the glide takes (the time since the frame before). */
    val frameId: Long = 0,
    val frameGapMs: Long = 125,
    val connected: Boolean = false,
    /** The camera is on (the dare is in progress), from the server, before any frame arrives. */
    val cameraOn: Boolean = false,
    /** No frame for a while: the camera stopped, or the phone lost its connection. */
    val stale: Boolean = false,
    val done: Boolean = false,
    val ended: WatchEnd? = null,
    /** The latest cheer sent from here, for its little animation. */
    val sent: CheerShown? = null,
) {
    val phase: LivePhase? get() = if (done) LivePhase.Done else frame?.phase
}

/** The words over the figure: what's happening now, in a few words. */
object WatchText {
    fun title(ui: WatchUi): String = ui.type?.let { tr("{partner}'s {exercise}", "partner" to ui.partnerName, "exercise" to it.displayName.lowercase()) }
        ?: tr("{partner}'s dare", "partner" to ui.partnerName)

    fun status(ui: WatchUi): String = when {
        ui.done -> tr("Done!")
        ui.ended == WatchEnd.Missed -> tr("Time ran out")
        ui.ended == WatchEnd.LetOff -> tr("Called off for today")
        !ui.connected -> tr("Connecting…")
        ui.frame == null -> if (ui.cameraOn) tr("Waiting for the camera…") else tr("Waiting for {partner} to start the camera", "partner" to ui.partnerName)
        ui.stale -> tr("{partner}'s camera paused", "partner" to ui.partnerName)
        ui.phase == LivePhase.Setup -> tr("Getting set")
        ui.phase == LivePhase.Countdown -> LiveCues.countdownOf(ui.frame?.cue)?.let { tr("Starting in {seconds}", "seconds" to it) } ?: tr("Starting…")
        else -> tr("Counting")
    }

    /** What the coach is telling them now, in this phone's language. */
    fun cue(ui: WatchUi): String? = ui.type?.takeIf { !ui.done && ui.ended == null && !ui.stale }?.let { LiveCues.text(it, ui.frame?.cue) }
}

/**
 * The partner's live view (docs/couple-goals.md, Watching live): the stick figure, never video, the count, the phase,
 * the coach's cue, and cheers. Says it's open (and keeps saying so) while it is, so the camera shows who's watching.
 */
class WatchLiveViewModel(application: Application, private val occurrenceId: String, partnerName: String, partnerSide: Side) : AndroidViewModel(application) {
    private val app = (application as HeartpactApplication).container
    private val _ui = MutableStateFlow(WatchUi(partnerName, partnerSide))
    val ui: StateFlow<WatchUi> = _ui.asStateFlow()
    private var lastFrameAt = 0L
    private var lastCheerAt = 0L
    private var cheers = 0L

    init {
        app.live.hold(HOLD)
        viewModelScope.launch { load() }
        viewModelScope.launch { app.live.events.collect(::onEvent) }
        viewModelScope.launch {
            app.live.connected.collect { connected ->
                _ui.update { it.copy(connected = connected) }
                if (connected) app.live.watchExercise(occurrenceId, true)
            }
        }
        // "Watching" again every few seconds, so the camera knows the view is still open; and spot a camera gone quiet.
        viewModelScope.launch {
            var beat = 0
            while (isActive) {
                delay(1_000)
                if (++beat % HEARTBEAT_S == 0 && !finished()) app.live.watchExercise(occurrenceId, true)
                val stale = lastFrameAt > 0 && SystemClock.elapsedRealtime() - lastFrameAt > STALE_MS
                if (stale != _ui.value.stale) _ui.update { it.copy(stale = stale) }
            }
        }
    }

    private fun finished() = _ui.value.done || _ui.value.ended != null

    private suspend fun load() {
        val today = (app.exercise.today() as? ApiResult.Ok)?.value ?: return
        today.occurrences.firstOrNull { it.id == occurrenceId }?.let(::apply)
    }

    private fun apply(occurrence: ExerciseOccurrence) = _ui.update {
        it.copy(
            type = occurrence.exerciseType, target = occurrence.target, count = maxOf(it.count, occurrence.achievedCount),
            cameraOn = occurrence.status == OccurrenceStatus.InProgress,
            done = it.done || occurrence.status == OccurrenceStatus.Completed,
            ended = when (occurrence.status) { OccurrenceStatus.Missed -> WatchEnd.Missed; OccurrenceStatus.Cancelled -> WatchEnd.LetOff; else -> it.ended },
        )
    }

    private fun onEvent(event: LiveEvent) {
        when (event.name) {
            "ExerciseLive" -> {
                val dto = runCatching { app.json.decodeFromString(LiveDto.serializer(), event.json) }.getOrNull() ?: return
                if (dto.occurrenceId != occurrenceId) return
                frame(dto)
            }
            "ExerciseStarted", "ExerciseCompleted", "ExerciseMissed", "ExerciseCancelled" -> {
                val occurrence = runCatching { app.json.decodeFromString(ExerciseOccurrence.serializer(), event.json) }.getOrNull() ?: return
                if (occurrence.id == occurrenceId) apply(occurrence)
            }
            "ExerciseProgress" -> {
                val progress = runCatching { app.json.decodeFromString(Progress.serializer(), event.json) }.getOrNull() ?: return
                if (progress.occurrenceId == occurrenceId) _ui.update { it.copy(count = maxOf(it.count, progress.achievedCount)) }
            }
        }
    }

    private fun frame(dto: LiveDto) {
        val phase = LivePhase.of(dto.phase) ?: return
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastFrameAt == 0L) 125L else (now - lastFrameAt).coerceIn(60, 300)
        lastFrameAt = now
        val frame = LiveFrame(dto.count, phase, dto.cue?.takeIf { it.isNotBlank() }, dto.aspect / LiveCodec.SCALE.toFloat(), LiveCodec.decode(dto.joints))
        _ui.update {
            it.copy(
                type = dto.exerciseType ?: it.type, target = dto.target.takeIf { t -> t > 0 } ?: it.target,
                // A restarted camera counts from zero again.
                count = if (phase == LivePhase.Counting || phase == LivePhase.Done) dto.count else it.count,
                previous = it.frame, frame = frame, frameId = it.frameId + 1, frameGapMs = gap,
                cameraOn = true, stale = false, done = it.done || phase == LivePhase.Done,
            )
        }
    }

    /** A cheer to the camera; one a second at most (the server drops more). */
    fun cheer(cheer: Cheer) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheerAt < CHEER_GAP_MS || finished()) return
        lastCheerAt = now
        app.live.cheerExercise(occurrenceId, cheer.wire)
        _ui.update { it.copy(sent = CheerShown(cheer, "", ++cheers)) }
    }

    override fun onCleared() {
        app.live.watchExercise(occurrenceId, false)
        app.live.release(HOLD)
    }

    @Serializable
    private data class LiveDto(
        val occurrenceId: String, val userId: String = "", val exerciseType: ExerciseType? = null, val target: Int = 0, val count: Int = 0,
        val phase: String = "", val cue: String? = null, val aspect: Int = 750, val joints: IntArray = IntArray(0),
    )

    @Serializable
    private data class Progress(val occurrenceId: String, val achievedCount: Int = 0)

    private companion object {
        const val HOLD = "watch"
        const val HEARTBEAT_S = 10
        const val STALE_MS = 5_000L
        const val CHEER_GAP_MS = 1_000L
    }
}

/** Joint [i] of [Pose.FIGURE]'s list by its pose index. */
private fun List<Landmark?>.joint(poseIndex: Int): Landmark? = Pose.FIGURE.indexOf(poseIndex).takeIf { it >= 0 }?.let { getOrNull(it) }

/**
 * The live view: the partner's stick figure gliding between frames on a soft stage, how far along they are, the coach's
 * cue, and cheers. When the dare is done it celebrates; when it ends otherwise it says so. Animations off: the figure
 * jumps from frame to frame and the hearts hold still.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchLiveScreen(ui: WatchUi, onCheer: (Cheer) -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(WatchText.title(ui), onBack, trailing = { LiveChip(ui) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.done) Celebrate(ui, onBack)
            else {
                Stage(ui, Modifier.fillMaxWidth().widthIn(max = 520.dp))
                WatchText.cue(ui)?.let { cue ->
                    Text(tr("Coach: “{cue}”", "cue" to cue), style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (ui.ended != null) {
                    Text(if (ui.ended == WatchEnd.Missed) tr("The dare ended before the count was reached.") else tr("You both called it off for today."),
                        style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                    HeartButton(tr("Back to Today"), onBack, Modifier.padding(top = 14.dp), kind = ButtonKind.Tonal)
                } else Cheers(ui, onCheer)
            }
            Row(Modifier.padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = colors.ink3)
                Text(tr("Only the stick figure is shared, never the camera"), style = Heartpact.type.small, color = colors.ink3, textAlign = TextAlign.Center)
            }
        }
    }
}

/** "Live" with a dot while frames arrive; the connection's state otherwise. */
@Composable
private fun LiveChip(ui: WatchUi) {
    val colors = Heartpact.colors
    val live = ui.connected && ui.frame != null && !ui.stale && !ui.done && ui.ended == null
    val text = when {
        ui.done || ui.ended != null -> return
        !ui.connected -> tr("Connecting…")
        live -> tr("Live")
        else -> tr("Waiting")
    }
    Row(Modifier.clip(Shapes.Pill).background(if (live) colors.partner(ui.partnerSide).soft else colors.sticker2).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(Shapes.Pill).background(if (live) Color(0xFFE5484D) else colors.ink4))
        Text(text, style = Heartpact.type.labelSmall, color = colors.ink)
    }
}

/** The figure on its stage, the count and the phase over it. */
@Composable
private fun Stage(ui: WatchUi, modifier: Modifier) {
    val colors = Heartpact.colors
    val them = colors.partner(ui.partnerSide)
    val still = rememberStill()
    val glide = remember { Animatable(1f) }
    LaunchedEffect(ui.frameId) {
        if (still) glide.snapTo(1f) else { glide.snapTo(0f); glide.animateTo(1f, tween(ui.frameGapMs.toInt(), easing = LinearEasing)) }
    }
    StickerCard(modifier, shape = RoundedCornerShape(30.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(24.dp)).background(them.tint)) {
            val height = (maxWidth * 1.05f).coerceAtMost(520.dp)
            Box(Modifier.fillMaxWidth().size(maxWidth, height)) {
                val frame = ui.frame
                if (frame != null && frame.hasBody) {
                    val previous = ui.previous
                    val dim = ui.stale || ui.ended != null
                    val bone = if (dim) colors.ink4 else them.action
                    Canvas(Modifier.fillMaxSize().semantics { contentDescription = tr("{partner}'s stick figure", "partner" to ui.partnerName) }) {
                        // The picture's shape, fitted to the stage and centred.
                        val aspect = frame.aspect.coerceIn(0.3f, 3f)
                        val (w, h) = if (size.width / size.height > aspect) size.height * aspect to size.height else size.width to size.width / aspect
                        val left = (size.width - w) / 2; val top = (size.height - h) / 2
                        // Floor: a soft line under the lowest ankle.
                        val t = glide.value
                        fun place(index: Int): Offset? {
                            val to = frame.joints.joint(index) ?: return null
                            val from = previous?.joints?.joint(index) ?: to
                            return Offset(left + (from.x + (to.x - from.x) * t) * w, top + (from.y + (to.y - from.y) * t) * h)
                        }
                        val feet = listOfNotNull(place(Pose.L_ANKLE), place(Pose.R_ANKLE)).maxOfOrNull { it.y }
                        feet?.let { drawLine(lerp(them.tint, colors.ink4, 0.45f), Offset(left + w * 0.08f, it + 14.dp.toPx()), Offset(left + w * 0.92f, it + 14.dp.toPx()), 3.dp.toPx()) }
                        stickFigure(::place, bone, bone, fill = colors.sticker, width = 6.dp, dot = 6.dp, head = true)
                    }
                } else {
                    // Nothing yet (or nobody in the picture): the partner's character, waiting.
                    Character(if (ui.partnerSide == Side.Left) Who.Left else Who.Right, CharacterPose.Stand, 120.dp, Modifier.align(Alignment.Center))
                }
                // The count, top start; the phase, top end.
                Column(Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(20.dp)).background(colors.sticker).padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    val type = ui.type
                    Text("${ui.count}", style = Heartpact.type.displaySmall, color = them.onSoft)
                    if (type != null && ui.target > 0) Text(CameraText.of(type, ui.target), style = Heartpact.type.labelSmall, color = them.onSoft)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(14.dp).clip(Shapes.Pill).background(colors.sticker).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(WatchText.status(ui), style = Heartpact.type.labelSmall, color = colors.ink)
                }
                // A cheer just sent floats up the stage, clear of the buttons.
                CheerBurst(ui.sent, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp), burst = 110.dp, pill = false)
            }
        }
    }
}

/** A heart and two lines of encouragement; each one sent floats a few hearts up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Cheers(ui: WatchUi, onCheer: (Cheer) -> Unit) {
    val colors = Heartpact.colors
    val them = colors.partner(ui.partnerSide)
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Cheer {partner} on", "partner" to ui.partnerName), style = Heartpact.type.label, color = colors.ink)
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { onCheer(Cheer.Heart) }, modifier = Modifier.size(52.dp).clip(Shapes.Pill).background(them.soft)) {
                    Icon(Icons.Rounded.Favorite, Cheer.Heart.button, tint = Color(0xFFE5484D))
                }
                HeartButton(Cheer.GotThis.button, { onCheer(Cheer.GotThis) }, kind = ButtonKind.Tonal, height = 52.dp, fillWidth = false)
                HeartButton(Cheer.AlmostThere.button, { onCheer(Cheer.AlmostThere) }, kind = ButtonKind.Tonal, height = 52.dp, fillWidth = false)
            }
        }
    }
}

/** Done: the heart they made, and back to Today. */
@Composable
private fun Celebrate(ui: WatchUi, onBack: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(230.dp)) {
                listOf(Offset(0.16f, 0.22f) to 26f, Offset(0.84f, 0.18f) to 20f, Offset(0.1f, 0.7f) to 18f, Offset(0.9f, 0.66f) to 24f, Offset(0.5f, 0.06f) to 16f).forEachIndexed { i, (at, s) ->
                    heart(Offset(at.x * size.width, at.y * size.height), s.dp.toPx(), if (i % 2 == 0) colors.left.action else colors.right.action, alpha = 0.85f)
                }
            }
            HeartSticker(HalfState.Stuck, HalfState.Stuck, 150.dp)
        }
        Text(tr("{partner} did it!", "partner" to ui.partnerName), style = Heartpact.type.headline, color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        ui.type?.let { type ->
            Text(type.label(maxOf(ui.count, ui.target)).replaceFirstChar { it.uppercase() }, style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
        }
        HeartButton(tr("Back to Today"), onBack, Modifier.padding(top = 20.dp))
    }
}
