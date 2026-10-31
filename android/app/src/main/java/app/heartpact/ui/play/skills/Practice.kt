package app.heartpact.ui.play.skills

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.popSpring
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlin.random.Random

/*
 * Practice: one skill game, alone, a level at a time. Each round shows its level and the score that clears it.
 * Clear it and a card celebrates (a stamp, stars, hearts, a chime), then a heart-shaped wipe carries you to the next,
 * harder level; miss it and you try the level again with a new shape. The header keeps the best score (beating it
 * bursts hearts and stamps "New best") and the level, which opens the levels reached so far.
 */

/** Best scores and levels in the phone's preferences, one each per game, keyed by the game's stable name. */
internal class PhoneSkillBests(context: Context) : SkillBests {
    private val prefs = context.getSharedPreferences("skill_bests", Context.MODE_PRIVATE)

    override fun best(kind: SkillKind): Int? = if (prefs.contains(kind.name)) prefs.getInt(kind.name, 0) else null

    override fun record(result: SkillResult) {
        val was = best(result.kind)
        if (was == null || result.score > was) prefs.edit().putInt(result.kind.name, result.score).apply()
    }

    override fun level(kind: SkillKind): Int = prefs.getInt("level_${kind.name}", 1).coerceAtLeast(1)

    override fun reach(kind: SkillKind, level: Int) {
        if (level > level(kind)) prefs.edit().putInt("level_${kind.name}", level).apply()
    }
}

/**
 * The practice screen. [bests] and [firstSeed] are for tests; [initial] shows a round already set up (a frozen state);
 * [startLevel] starts at a level other than the one reached.
 */
@Composable
internal fun PracticeScreen(
    kind: SkillKind,
    onBack: () -> Unit,
    bests: SkillBests,
    firstSeed: Long = Random.nextLong(),
    initial: SkillPlay? = null,
    startLevel: Int? = null,
) {
    val colors = Heartpact.colors
    val sounds = rememberLevelSounds()
    var seed by rememberSaveable { mutableStateOf(firstSeed) }
    var level by rememberSaveable { mutableIntStateOf(startLevel ?: initial?.difficulty?.level ?: bests.level(kind)) }
    var reached by remember { mutableIntStateOf(maxOf(bests.level(kind), level)) }
    var best by remember { mutableStateOf(bests.best(kind)) }
    /** The seed of the round that set a new best, if the showing round did. */
    var newBest by remember { mutableStateOf<Long?>(null) }
    /** The level just cleared, while its card is up. */
    var cleared by remember { mutableStateOf<Cleared?>(null) }
    /** The level the heart wipe is carrying us to, while it runs. */
    var wipeTo by remember { mutableStateOf<Int?>(null) }
    /** Whether the showing round fell short of its level's pass mark. */
    var short by remember { mutableStateOf(false) }
    val difficulty = Difficulty(level)
    Box(Modifier.fillMaxSize().background(colors.page)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = colors.ink2) }
                Text(kind.title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(start = 4.dp).weight(1f), maxLines = 1)
                LevelChip(kind, level, reached, onPick = { pick -> level = pick; short = false; newBest = null; seed = Random.nextLong() },
                    Modifier.padding(end = 8.dp))
                best?.let { BestTag(kind, it, Modifier.padding(end = 16.dp)) }
            }
            Text(
                if (short) tr("Not quite. Score {pass} or more to clear level {n}.", "pass" to difficulty.passMark, "n" to level)
                else tr("Score {pass} or more to clear this level.", "pass" to difficulty.passMark),
                style = Heartpact.type.small,
                color = if (short) noteInk() else colors.ink3,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            )
            Box(Modifier.weight(1f)) {
                key(seed, level) {
                    val play = remember { initial?.takeIf { it.seed == seed && it.difficulty == difficulty } ?: newPlay(kind, seed, difficulty) }
                    SkillRoundFrame(
                        play,
                        onDone = { r ->
                            val was = best
                            bests.record(r)
                            if (r.score > 0 && (was == null || r.score > was)) {
                                best = r.score
                                // Stamped only when an earlier best is beaten: a first round is a best by default.
                                if (was != null) newBest = seed
                            }
                            val stars = difficulty.stars(r.score)
                            if (stars > 0) {
                                bests.reach(kind, level + 1)
                                reached = maxOf(reached, level + 1)
                                cleared = Cleared(level, r.score, difficulty.passMark, stars)
                            } else {
                                short = true
                                sounds?.notYet()
                            }
                        },
                        onAgain = { newBest = null; short = false; seed = Random.nextLong() },
                        againLabel = if (short) tr("Try again") else null,
                    )
                }
                if (newBest == seed && cleared == null) Stamp(tr("New best"), Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 18.dp), tilt = 9f, key = seed)
            }
        }
        HeartBurst(newBest?.takeIf { it == seed && cleared == null }, Modifier.fillMaxSize(), count = 26, spread = 190.dp)
        cleared?.let { done ->
            LevelClearedCard(
                done, kind, sounds,
                onNext = { cleared = null; wipeTo = done.level + 1 },
                onReplay = { cleared = null; newBest = null; short = false; seed = Random.nextLong() },
            )
        }
        wipeTo?.let { next ->
            HeartWipe(
                next, kind, sounds,
                onClosed = { level = next; newBest = null; short = false; seed = Random.nextLong() },
                onDone = { wipeTo = null },
            )
        }
    }
}

/** "best 88" on the game's pastel, popping when it changes. */
@Composable
internal fun BestTag(kind: SkillKind, best: Int, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(1f) }
    val first = remember { best }
    LaunchedEffect(best) {
        if (best == first) return@LaunchedEffect
        pop.snapTo(1.35f)
        pop.animateTo(1f, popSpring())
    }
    Text(
        tr("best {score}", "score" to best),
        style = Heartpact.type.labelSmall,
        color = kind.colour.ink,
        modifier = modifier
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(Shapes.Pill)
            .background(kind.colour.fill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
