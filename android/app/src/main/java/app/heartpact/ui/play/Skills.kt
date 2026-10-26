package app.heartpact.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.theme.Heartpact

/*
 * Heart skills: tiny tests of precision, each round a few seconds, scored 0 to 100 with one honest note on what to fix.
 * Played alone any time (practice, best scores kept on the phone) or as a Heart to heart duel, where both partners
 * play the same rounds (the same shapes, from one seed) and the scores show side by side once both have played.
 *
 * This file is the contract between the games, the duel and the picker; the games themselves live in skills/.
 */

/** The skill games. The name is stable: it travels in duels (`kind` in the duel's state) and keys best scores. */
enum class SkillKind(val colour: BlobColour) {
    /** One swipe splits a heart cookie (or a chocolate bar, a pizza, an odd blob) into two halves. Score: how even. */
    FairShare(BlobColour.Rose),
    /** Hold to pour pink love into a heart jar; the line to stop at hides once it starts pouring. Score: how close. */
    FillTheHeart(BlobColour.Sky),
    /** Half a shape is drawn; draw its other half across the fold. Score: how mirrored. */
    OtherHalf(BlobColour.Mint),
    /** The heart beats a few times, then goes quiet; tap on the beat it would land on. Score: how on time. */
    Heartbeat(BlobColour.Peach),
    /** Drop a kiss dead centre of an odd shape (its centre of area). Score: how close. */
    KissTheCenter(BlobColour.Butter),
    /** Draw a ring in one stroke. Score: round, closed, even. */
    PromiseRing(BlobColour.Lilac),
    ;

    val title: String get() = when (this) {
        FairShare -> tr("Fair share")
        FillTheHeart -> tr("Fill the heart")
        OtherHalf -> tr("Other half")
        Heartbeat -> tr("Heartbeat")
        KissTheCenter -> tr("Kiss the center")
        PromiseRing -> tr("Promise ring")
    }

    /** One line under the title in the picker. */
    val blurb: String get() = when (this) {
        FairShare -> tr("One swipe. Split it dead even.")
        FillTheHeart -> tr("Pour to the line. Not a drop more.")
        OtherHalf -> tr("Draw the other half across the fold.")
        Heartbeat -> tr("Feel the beat. Tap when it would land.")
        KissTheCenter -> tr("Drop the kiss dead center.")
        PromiseRing -> tr("One stroke. As round as your promise.")
    }

    /** What to do, at the top of a round. */
    val instruction: String get() = when (this) {
        FairShare -> tr("Cut it in two equal halves. Straight through, one swipe.")
        FillTheHeart -> tr("Hold to pour. The line hides once the love runs.")
        OtherHalf -> tr("Finish the other half across the fold. One stroke or a few.")
        Heartbeat -> tr("Count the beats. When it goes quiet, tap on beat eight.")
        KissTheCenter -> tr("Tap the exact middle of the shape.")
        PromiseRing -> tr("Draw a circle in one stroke, as round as you can.")
    }
}

/** One finished round: the score (0 to 100) and the one thing to fix, in the person's language. */
data class SkillResult(val kind: SkillKind, val score: Int, val note: String)

/**
 * One round of [kind], from first touch to the score: the instruction, the play, then the score revealed (counted up,
 * circled, stamped) with its note. [seed] picks the shape or target, so both partners in a duel get the same round.
 * [onDone] gets the result once the reveal has finished; [onAgain] (practice) offers a new round.
 */
@Composable
fun SkillRound(kind: SkillKind, seed: Long, onDone: (SkillResult) -> Unit, modifier: Modifier = Modifier, onAgain: (() -> Unit)? = null) {
    // Placeholder until the games land (skills/): a button that scores a round, so the duel and picker can be built.
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(kind.instruction, style = Heartpact.type.bodyLarge, color = Heartpact.colors.ink2)
        HeartButton(tr("Play"), { onDone(SkillResult(kind, (seed % 101).toInt().coerceAtLeast(0), "")) }, Modifier.padding(top = 16.dp))
    }
}

/** The game's picture on its picker blob: a simple line icon in [tint]. */
@Composable
fun SkillIcon(kind: SkillKind, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    // Placeholder until the games land: a heart for every game.
    Canvas(modifier.size(size)) { drawPath(unitHeart(this.size.minDimension * 0.7f).apply { translate(center) }, tint) }
}

/**
 * Practice: rounds of one skill game, alone, as many as you like. Keeps the best score on the phone.
 * [onBack] leaves for the picker.
 */
@Composable
fun SkillPracticeScreen(kind: SkillKind, onBack: () -> Unit) {
    // Placeholder until the games land.
    SkillRound(kind, System.nanoTime(), onDone = {}, onAgain = {})
}

/** Best practice scores, kept on the phone. */
interface SkillBests {
    fun best(kind: SkillKind): Int?
    fun record(result: SkillResult)
}
