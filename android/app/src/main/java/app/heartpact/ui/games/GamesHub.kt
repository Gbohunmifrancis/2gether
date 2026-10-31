package app.heartpact.ui.games

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameType
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TwoPaneFrom
import app.heartpact.ui.play.BlobColour
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.SkillBests
import app.heartpact.ui.play.SkillIcon
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillResult
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.play.popSpring
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.rememberWobble
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/*
 * The games hub: the two of you up top with how the last game went, then two grids of wobbly pastel blobs, live
 * games to play together and Heart skills to practice alone. Tapping a blob picks it (a dashed pen ring, a boing),
 * and what it is and the button to start it show under its grid.
 */

/** No best scores kept: the default until a store is handed in. */
internal object NoSkillBests : SkillBests {
    override fun best(kind: SkillKind): Int? = null
    override fun record(result: SkillResult) {}
}

/** The games to invite your partner to: Heart to heart first, then the rest. */
internal val coupleGames: List<GameType> get() = (listOf(GameType.SkillDuel) + GamePlay.offered).distinct()

@Composable
internal fun GamesHub(ui: GamesUi, me: String, partnerName: String, actions: GameActions, bests: SkillBests, onPractice: (SkillKind) -> Unit) {
    val region = ui.region ?: ui.sessions.firstOrNull()?.let(GamePlay::region)
    HubHeader(ui.finished, me, partnerName)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Side by side on a wide screen; one under the other on a phone.
        if (maxWidth >= TwoPaneFrom) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1.3f)) { CoupleSection(ui, region, partnerName, actions) }
                Column(Modifier.weight(1f)) { SkillSection(bests, startAt = 4, onPractice) }
            }
        } else {
            Column {
                CoupleSection(ui, region, partnerName, actions)
                SkillSection(bests, startAt = 10, onPractice, Modifier.padding(top = 34.dp))
            }
        }
    }
    if (region == "ng") AfterRoundCard(Modifier.padding(top = 28.dp))
    Recent(ui, me, partnerName)
}

/** Maya and Theo, and a line on how the last game went. */
@Composable
private fun HubHeader(last: GameSession?, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val score = last?.let { GamePlay.score(it, me) }
    val mine = when {
        score == null -> Reaction.Idle
        score.lost -> Reaction.Waiting
        else -> Reaction.Cheer
    }
    val theirs = when {
        score == null -> Reaction.Idle
        score.won -> Reaction.Waiting
        else -> Reaction.Cheer
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Couple(74.dp, mine, theirs, key = "hub")
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(last?.let { GamePlay.lastLine(it, me, partnerName) } ?: tr("What shall we play?"), style = Heartpact.type.title.copy(lineBreak = LineBreak.Heading), color = colors.ink)
            Text(tr("Pick a game and {partner} gets an invite. It starts when they join.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A section's name with a pen underline, and one line on what it is. */
@Composable
private fun SectionHead(title: String, line: String?, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    Column(modifier.padding(bottom = 14.dp)) {
        Text(title, style = Heartpact.type.headline.copy(fontSize = 22.sp, lineHeight = 26.sp), color = colors.ink, modifier = Modifier.drawBehind {
            // A quick pen stroke under the name, slightly longer than the word.
            val y = size.height + 3.dp.toPx()
            val path = Path().apply {
                moveTo(0f, y)
                quadraticTo(size.width * 0.5f, y + 4.dp.toPx(), size.width * 1.02f, y - 1.dp.toPx())
            }
            drawPath(path, PlayInk.Pen, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        })
        if (line != null) Text(line, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoupleSection(ui: GamesUi, region: String?, partnerName: String, actions: GameActions) {
    val colors = Heartpact.colors
    val games = coupleGames
    var picked by rememberSaveable { mutableStateOf(ui.finished?.gameType?.takeIf { it in games } ?: games.first()) }
    var letter by rememberSaveable { mutableStateOf<String?>(null) }
    SectionHead(tr("Play together"), tr("Live, the two of you, each on your own phone."))
    BlobGrid(games, picked, { picked = it }, startAt = 0, counts = listOf(6, 4, 3), label = { GamePlay.title(it, region) }, colour = { it.blob }) { type, tint, size ->
        GameIcon(type, tint, size)
    }
    Detail(picked) { type ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(GamePlay.title(type, region), style = Heartpact.type.title, color = colors.ink, textAlign = TextAlign.Center)
            Text(GamePlay.blurb(type, region), style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            if (type == GameType.ICallOn) {
                Text(tr("First letter"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeartChip(tr("Surprise me"), letter == null, { letter = null })
                    listOf("A", "B", "M", "S", "T").forEach { l -> HeartChip(l, letter == l, { letter = l }) }
                }
            }
            HeartButton(tr("Invite {partner}", "partner" to partnerName), { actions.onInvite(type, if (type == GameType.ICallOn) letter else null) },
                Modifier.padding(top = 14.dp).widthIn(max = 420.dp), enabled = !ui.busy)
        }
    }
}

@Composable
private fun SkillSection(bests: SkillBests, startAt: Int, onPractice: (SkillKind) -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    var picked by rememberSaveable { mutableStateOf(SkillKind.entries.first()) }
    Column(modifier) {
        SectionHead(tr("Heart skills"), tr("Tiny tests of a steady hand. Practice alone and beat your best."))
        BlobGrid(SkillKind.entries, picked, { picked = it }, startAt = startAt, counts = listOf(9, 3), label = { it.title }, colour = { it.colour }) { kind, tint, size ->
            SkillIcon(kind, tint, size)
        }
        Detail(picked) { kind ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kind.title, style = Heartpact.type.title, color = colors.ink)
                    bests.best(kind)?.let { Tag(tr("best {score}", "score" to it), kind.colour.fill, kind.colour.ink) }
                }
                Text(kind.blurb, style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                HeartButton(tr("Play"), { onPractice(kind) }, Modifier.padding(top = 14.dp).widthIn(max = 420.dp))
            }
        }
    }
}

/** What's picked, under its grid: it slides up into place when the pick changes. */
@Composable
private fun <T> Detail(picked: T, content: @Composable (T) -> Unit) {
    val still = motionOff()
    // A new pick scrolls its button into view, so a tap low on the grid isn't left wondering.
    val view = remember { BringIntoViewRequester() }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(picked) {
        if (opened) { delay(if (still) 0 else 220); view.bringIntoView() }
        opened = true
    }
    AnimatedContent(
        picked,
        Modifier.fillMaxWidth().padding(top = 16.dp).bringIntoViewRequester(view),
        transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.7f, stiffness = 500f)) { it / 5 }) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false)
        },
        label = "picked",
    ) { content(it) }
}

/**
 * Blobs in rows, as many to a row as fit from [counts] (so rows come out even), each [startAt] + its place in the
 * stagger as they pop in. The picked one wobbles inside a dashed pen ring; the rest breathe, each to its own beat.
 */
@Composable
private fun <T> BlobGrid(
    items: List<T>, picked: T, onPick: (T) -> Unit, startAt: Int, counts: List<Int>,
    label: (T) -> String, colour: (T) -> BlobColour, icon: @Composable (T, Color, Dp) -> Unit,
) {
    val still = motionOff()
    val breath = if (motionOff()) null else rememberInfiniteTransition(label = "breathe").animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "breath")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val columns = counts.firstOrNull { n -> 76.dp * n + gap * (n - 1) <= maxWidth } ?: counts.last()
        val cell = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { c, item ->
                        val index = items.indexOf(item)
                        PickBlob(label(item), colour(item), index * 7 + 3, item == picked, startAt + r * columns + c, breath, { onPick(item) }, Modifier.width(cell)) { tint, size ->
                            icon(item, tint, size)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickBlob(
    label: String, colour: BlobColour, seed: Int, selected: Boolean, order: Int, breath: State<Float>?, onPick: () -> Unit, modifier: Modifier,
    icon: @Composable (Color, Dp) -> Unit,
) {
    val colors = Heartpact.colors
    val still = motionOff()
    val haptics = rememberPlayHaptics()
    val scope = rememberCoroutineScope()
    // Pop in, one after another, when the hub opens.
    val appear = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) { delay(40L + order * 32L); appear.animateTo(1f, popSpring()) } }
    // A boing when picked: squash one way, stretch the other, settling.
    val boing = remember { Animatable(0f) }
    val ring by animateFloatAsState(if (selected) 1f else 0f, gentle(spring(dampingRatio = 0.55f, stiffness = 380f)), label = "ring")
    val wobble = if (selected && !motionOff()) rememberWobble(2800) else 0f
    val phase = wobble * 2f * PI.toFloat()
    val beat = order * 0.17f
    Column(modifier.graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    haptics.tick()
                    onPick()
                    if (!still) scope.launch { boing.snapTo(0f); boing.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 420f), initialVelocity = 10f) }
                }
                .semantics { this.selected = selected; contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val blob = min(maxWidth * 0.84f, 98.dp)
            Box(
                Modifier.size(blob + 16.dp)
                    .graphicsLayer {
                        val a = appear.value
                        val b = breath?.value?.let { sin((it + beat) * 2f * PI.toFloat()) } ?: 0f
                        val breathe = if (selected) 0f else 0.022f * b
                        scaleX = a * (1f + breathe + 0.09f * boing.value)
                        scaleY = a * (1f + breathe - 0.09f * boing.value)
                        rotationZ = if (selected) 5f * sin(phase) else 0f
                    }
                    .drawWithCache {
                        val inset = 8.dp.toPx()
                        val blobSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                        val rest = blobPath(seed, blobSize)
                        val keyline = 3.dp.toPx()
                        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()), phase * 6.dp.toPx())
                        onDrawBehind {
                            val path = if (selected) blobPath(seed, blobSize, 1.7f, phase) else rest
                            translate(inset, inset + 3.dp.toPx()) { drawPath(path, Art.Shadow, alpha = if (colors.isNight) 0.35f else 0.12f) }
                            translate(inset, inset) {
                                drawPath(path, Art.Keyline, style = Stroke(keyline * 2))
                                drawPath(path, colour.fill)
                            }
                            if (ring > 0.01f) {
                                val r = size.minDimension / 2 - 1.5.dp.toPx()
                                drawCircle(PlayInk.Pen, r * (0.9f + 0.1f * ring), center, alpha = ring.coerceIn(0f, 1f),
                                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { icon(colour.ink, blob * 0.44f) }
        }
        Text(label, style = Heartpact.type.labelSmall, color = if (selected) colors.ink else colors.ink2, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.padding(top = 2.dp))
    }
}

/** The games played lately, after the last one. */
@Composable
private fun Recent(ui: GamesUi, me: String, partnerName: String) {
    if (ui.history.size <= 1) return
    val colors = Heartpact.colors
    SectionHead(tr("Recent"), null, Modifier.padding(top = 32.dp))
    StickerCard(Modifier.fillMaxWidth()) {
        ui.history.drop(1).forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(colors.rule))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).drawWithCache {
                    val path = blobPath(i * 7 + 3, size)
                    onDrawBehind { drawPath(path, s.gameType.blob.fill) }
                }, contentAlignment = Alignment.Center) { GameIcon(s.gameType, s.gameType.blob.ink, 20.dp) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(GamePlay.title(s), style = Heartpact.type.label, color = colors.ink)
                    Text(GamePlay.outcome(s, me, partnerName), style = Heartpact.type.small, color = colors.ink2)
                }
            }
        }
    }
}

/** After Round 1 on Google Play: a Naija finger-count game for playing with friends, shown to Nigerian couples. */
private const val AFTER_ROUND_PLAY_URL = "https://play.google.com/store/apps/details?id=com.afterround1.com"

@Composable
private fun AfterRoundCard(modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val uri = LocalUriHandler.current
    StickerCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("After Round 1"), style = Heartpact.type.title, color = colors.ink)
            Text(tr("The Naija finger-count game: call a number, lay your fingers, and the winner slaps. Play it with friends."),
                style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
            HeartButton(tr("Get it on Google Play"), { runCatching { uri.openUri(AFTER_ROUND_PLAY_URL) } }, Modifier.padding(top = 12.dp), kind = ButtonKind.Tonal)
        }
    }
}

