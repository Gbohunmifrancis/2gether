package app.heartpact.ui.play.skills

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.Grade
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.ReactingCharacter
import app.heartpact.ui.play.ScoreReveal
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import app.heartpact.ui.play.dottedPage
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.delay

/*
 * The frame every skill round shares: what to do at the top (and, once it's over, the note), the game in the middle,
 * then the score counted up, circled and stamped while Maya or Theo reacts. Each game is a SkillPlay: its own state
 * and board, and the score it hands over when the round ends.
 */

/**
 * One round of one game: its state, its board and (for some) its buttons. Created per round from the seed and the
 * [difficulty] (Levels.kt), which the game reads for its shapes, speeds and how strictly it scores.
 */
internal abstract class SkillPlay(val kind: SkillKind, val seed: Long, val difficulty: Difficulty = Difficulty.Start) {
    /** The score, once the round is played; the frame reveals it [revealAfterMs] later, once the board has settled. */
    var scored by mutableStateOf<Scored?>(null)
        private set
    var revealAfterMs = 0L
        private set

    /** The score, revealed. */
    var result by mutableStateOf<SkillResult?>(null)
        internal set

    /** A live line under the instruction while playing ("Keep going..."), or empty. */
    var hint by mutableStateOf("")

    protected fun finish(s: Scored, afterMs: Long) {
        if (scored != null) return
        revealAfterMs = afterMs
        scored = s
    }

    @Composable abstract fun Board(modifier: Modifier)

    /** The game's buttons under the board while playing (Clear and Submit, Start, Hold to pour), or nothing. */
    @Composable open fun Controls(modifier: Modifier) {}

    open val hasControls: Boolean get() = false
}

internal fun newPlay(kind: SkillKind, seed: Long, difficulty: Difficulty = Difficulty.Start): SkillPlay = when (kind) {
    SkillKind.FairShare -> FairSharePlay(seed, difficulty)
    SkillKind.FillTheHeart -> FillPlay(seed, difficulty)
    SkillKind.OtherHalf -> OtherHalfPlay(seed, difficulty)
    SkillKind.Heartbeat -> HeartbeatPlay(seed, difficulty)
    SkillKind.KissTheCenter -> KissPlay(seed, difficulty)
    SkillKind.PromiseRing -> RingPlay(seed, difficulty)
}

/** How long the score reveal takes to count, circle and stamp (ScoreReveal's own timings). */
internal fun revealMs(score: Int): Long = 650L + score * 4 + 380 + 420

@Composable
internal fun SkillRoundFrame(play: SkillPlay, onDone: (SkillResult) -> Unit, modifier: Modifier = Modifier, onAgain: (() -> Unit)? = null) {
    val colors = Heartpact.colors
    val done by rememberUpdatedState(onDone)
    val scored = play.scored
    LaunchedEffect(scored) {
        if (scored == null) return@LaunchedEffect
        delay(play.revealAfterMs)
        val r = SkillResult(play.kind, scored.score, scored.note)
        play.result = r
        delay(revealMs(r.score) + 300)
        done(r)
    }
    val result = play.result
    Column(modifier.fillMaxSize().dottedPage(colors.rule2)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp)) {
            Text(play.kind.instruction, style = Heartpact.type.body, color = colors.ink2)
            val line = result?.note ?: play.hint
            AnimatedContent(line, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "line") { text ->
                Text(
                    text,
                    style = Heartpact.type.label,
                    color = if (result != null) noteInk() else Heartpact.actor.onSoft,
                    modifier = Modifier.heightIn(min = 22.dp).padding(top = 3.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { play.Board(Modifier.fillMaxSize()) }
        AnimatedVisibility(result != null, enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 260f)) + fadeIn(tween(200))) {
            result?.let { ResultPanel(it) }
        }
        Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 18.dp).height(56.dp), contentAlignment = Alignment.Center) {
            when {
                result != null && onAgain != null -> HeartButton(tr("New round"), onAgain)
                result == null && scored == null && play.hasControls -> play.Controls(Modifier.fillMaxWidth())
                result == null && scored == null && onAgain != null -> HeartButton(tr("New round"), onAgain, kind = ButtonKind.Outline)
            }
        }
    }
}

/** The score, the stamp, and a character reacting once the stamp lands. Read out whole for TalkBack. */
@Composable
private fun ResultPanel(result: SkillResult) {
    val who = if (LocalActor.current == Side.Left) Who.Left else Who.Right
    var reaction by remember(result) { mutableStateOf(Reaction.Thinking) }
    LaunchedEffect(result) {
        delay(revealMs(result.score) - 300)
        reaction = when {
            result.score >= 85 -> Reaction.Cheer
            result.score < 40 -> Reaction.Sad
            else -> Reaction.Idle
        }
    }
    val said = tr("Score {score} of 100. {grade}. {note}", "score" to result.score, "grade" to Grade.of(result.score).label, "note" to result.note)
    Box(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).semantics(mergeDescendants = true) {
            contentDescription = said
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        ScoreReveal(result.score, Modifier.clearAndSetSemantics {}, key = result)
        ReactingCharacter(who, reaction, 70.dp, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp).clearAndSetSemantics {}, key = reaction)
    }
}

/** The colour of the note under the instruction: the pen pink, deep enough to read on the page. */
@Composable
internal fun noteInk(): Color = if (Heartpact.colors.isNight) PlayInk.PenSoft else BlobColour.Rose.ink

/** With animations off in the system settings (or in a preview), things land without moving. */
@Composable
internal fun rememberMotionOff(): Boolean {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    return remember { !preview && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** [spec], or a jump to the end with motion off. */
@Composable
internal fun <T> moving(spec: AnimationSpec<T>): AnimationSpec<T> = if (rememberMotionOff()) snap() else spec

/** A unit box (or any box of [unitW] by [unitH]) fitted and centred in a canvas of [size], with [pad] px around. */
internal class Fit(val scale: Float, val origin: Offset) {
    fun px(u: Offset): Offset = origin + u * scale
    fun px(v: Float): Float = v * scale
    fun unit(p: Offset): Offset = (p - origin) / scale

    fun path(poly: List<Offset>, close: Boolean = true, shift: Offset = Offset.Zero): Path = Path().apply {
        poly.forEachIndexed { i, u ->
            val p = px(u) + shift
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        if (close) close()
    }

    companion object {
        fun of(size: Size, unitW: Float, unitH: Float, pad: Float, centreY: Float = 0.5f): Fit {
            val s = minOf((size.width - 2 * pad) / unitW, (size.height - 2 * pad) / unitH).coerceAtLeast(0.01f)
            val x = (size.width - unitW * s) / 2
            val y = (size.height - unitH * s) * centreY
            return Fit(s, Offset(x, y))
        }
    }
}

/**
 * A filled pill that acts on touch down, not on release (a pour starts the instant you press; a beat lands when your
 * finger does). [onDown] gets the touch's time on the frame clock.
 */
@Composable
internal fun PressPill(text: String, enabled: Boolean, modifier: Modifier = Modifier, onDown: (Long) -> Unit, onUp: () -> Unit = {}) {
    val actor = Heartpact.actor
    val colors = Heartpact.colors
    var pressed by remember { mutableStateOf(false) }
    val squash by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, spring(dampingRatio = 0.45f, stiffness = 800f), label = "squash")
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = squash; scaleY = squash }
            .shadow(if (enabled) 10.dp else 0.dp, Shapes.Pill, ambientColor = actor.action, spotColor = actor.action)
            .clip(Shapes.Pill)
            .background(if (enabled) actor.action else colors.ink4)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    pressed = true
                    down(d.uptimeMillis)
                    waitForUpOrCancellation()
                    pressed = false
                    up()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Heartpact.type.button, color = if (enabled) colors.onAction else colors.sticker)
    }
}
