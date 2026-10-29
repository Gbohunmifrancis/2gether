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
 * Practice: one skill game, alone, round after round. The header keeps the best score; beating it bursts hearts
 * and stamps "New best".
 */

/** Best scores in the phone's preferences, one per game, keyed by the game's stable name. */
internal class PhoneSkillBests(context: Context) : SkillBests {
    private val prefs = context.getSharedPreferences("skill_bests", Context.MODE_PRIVATE)

    override fun best(kind: SkillKind): Int? = if (prefs.contains(kind.name)) prefs.getInt(kind.name, 0) else null

    override fun record(result: SkillResult) {
        val was = best(result.kind)
        if (was == null || result.score > was) prefs.edit().putInt(result.kind.name, result.score).apply()
    }
}

/**
 * The practice screen. [bests] and [firstSeed] are for tests; [initial] shows a round already set up (a frozen state).
 */
@Composable
internal fun PracticeScreen(kind: SkillKind, onBack: () -> Unit, bests: SkillBests, firstSeed: Long = Random.nextLong(), initial: SkillPlay? = null) {
    val colors = Heartpact.colors
    var seed by rememberSaveable { mutableStateOf(firstSeed) }
    var best by remember { mutableStateOf(bests.best(kind)) }
    /** The seed of the round that set a new best, if the showing round did. */
    var newBest by remember { mutableStateOf<Long?>(null) }
    Box(Modifier.fillMaxSize().background(colors.page)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = colors.ink2) }
                Text(kind.title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(start = 4.dp).weight(1f))
                best?.let { BestTag(kind, it, Modifier.padding(end = 16.dp)) }
            }
            Box(Modifier.weight(1f)) {
                key(seed) {
                    val play = remember { initial?.takeIf { it.seed == seed } ?: newPlay(kind, seed) }
                    SkillRoundFrame(
                        play,
                        onDone = { r ->
                            val was = best
                            bests.record(r)
                            if (r.score > 0 && (was == null || r.score > was)) {
                                best = r.score
                                newBest = seed
                            }
                        },
                        onAgain = { newBest = null; seed = Random.nextLong() },
                    )
                }
                if (newBest == seed) Stamp(tr("New best"), Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 18.dp), tilt = 9f, key = seed)
            }
        }
        HeartBurst(newBest?.takeIf { it == seed }, Modifier.fillMaxSize(), count = 26, spread = 190.dp)
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
