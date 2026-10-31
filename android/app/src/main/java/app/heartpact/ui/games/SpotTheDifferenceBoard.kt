package app.heartpact.ui.games

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.ApiResult
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.OFFLINE_MESSAGE
import app.heartpact.data.SpotTheDifferenceState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.penCircle
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.skills.rememberLevelSounds
import app.heartpact.ui.theme.Art
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant

/*
 * Spot the difference (docs/games.md): the same two pictures on both phones at once, the original on top and its
 * twin underneath (side by side on a wide screen). Tap a difference on either and it's yours if you're first: a pen
 * circle in your colour goes round it on both pictures, with your initial, and your partner sees it land too. A wrong
 * tap is a little x and a short pause. Three pictures; most spots wins.
 */

private val spotJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun GamePlay.spot(s: GameSession): SpotTheDifferenceState =
    runCatching { spotJson.decodeFromString(SpotTheDifferenceState.serializer(), s.stateJson) }.getOrDefault(SpotTheDifferenceState())

/** The history line: "You spotted 12, Theo 9". */
internal fun GamePlay.spotOutcome(g: SpotTheDifferenceState, me: String, partner: String): String =
    tr("You spotted {mine}, {partner} {theirs}", "mine" to (g.scores[me] ?: 0), "partner" to partner, "theirs" to (g.scores.entries.firstOrNull { it.key != me }?.value ?: 0))

/** How long a wrong tap pauses tapping. */
internal const val SPOT_PENALTY_MS = 1500L

/** How long a tapped difference waits for the server before it stops showing as yours. */
private const val CLAIM_TIMEOUT_MS = 5000L

/** The colour a finder circles in: the left half's indigo, or the right half's periwinkle deepened to carry a white initial. */
internal fun finderInk(side: Side): Color = if (side == Side.Left) Art.Left else lerp(Art.Right, Art.Left, 0.3f)

/**
 * Sends spots one at a time, at least [gap] ms apart (the server refuses spots closer than 300 ms). Both partners spot
 * at once, so a spot can meet `game.version_conflict`: then the session reloads and the spot goes again, as long as
 * that difference is still unfound in the same picture. A spot the partner beat you to, or one for a picture that has
 * moved on, is dropped quietly (the board says "{partner} got that one" itself); anything else shows its message.
 */
internal class SpotSender(
    private val scope: CoroutineScope,
    private val ui: MutableStateFlow<GamesUi>,
    private val send: suspend (GameSession, Int) -> ApiResult<GameSession>,
    private val load: suspend (String) -> ApiResult<GameSession>,
    private val gap: Long = 320L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Mutex()
    private var lastSent = 0L

    fun spot(session: GameSession, index: Int) {
        val scene = GamePlay.spot(session).scene
        scope.launch { lock.withLock { deliver(session.id, scene, index) } }
    }

    /** The difference is still there to claim, in the same picture. */
    private fun open(id: String, scene: Int, index: Int): GameSession? {
        val latest = ui.value.sessions.firstOrNull { it.id == id } ?: return null
        val g = GamePlay.spot(latest)
        return latest.takeIf { it.status == GameStatus.InProgress && g.scene == scene && g.phase == "spotting" && g.found.none { f -> f.index == index } }
    }

    private suspend fun deliver(id: String, scene: Int, index: Int) {
        var conflicts = 0
        var refusals = 0
        while (true) {
            val latest = open(id, scene, index) ?: return
            val wait = lastSent + gap - clock()
            if (wait > 0) pause(wait)
            lastSent = clock()
            when (val result = send(latest, index)) {
                is ApiResult.Ok -> { ui.update { it.withSession(result.value) }; return }
                is ApiResult.Failure -> {
                    reload(id)
                    if (result.code == GameMoveQueue.VERSION_CONFLICT && conflicts < GameMoveQueue.MAX_RETRIES) { conflicts++; continue }
                    val stillOpen = open(id, scene, index) != null
                    // Two spots can reach the server closer together than they left the phone: one more go, a gap later.
                    if (stillOpen && result.code != GameMoveQueue.VERSION_CONFLICT && refusals == 0) { refusals++; continue }
                    if (stillOpen && result.code != GameMoveQueue.VERSION_CONFLICT) ui.update { it.copy(note = result.message) }
                    return
                }
                ApiResult.Offline -> { ui.update { it.copy(note = OFFLINE_MESSAGE) }; return }
            }
        }
    }

    private suspend fun reload(id: String) {
        (load(id) as? ApiResult.Ok)?.value?.let { session -> ui.update { it.withSession(session) } }
    }
}

/** A ring on a picture: a difference found (in its finder's ink, with their initial), claimed and on its way, or shown when time ran out. */
private class Ring(val spot: Spot, val ink: Color, val initial: String?, val fresh: Boolean, val revealed: Boolean = false)

/** A wrong tap: where (page units) and on which picture; [key] tells two apart. */
private class Miss(val x: Float, val y: Float, val changed: Boolean, val key: Long)

@Composable
internal fun SpotTheDifferenceBoard(s: GameSession, me: String, myName: String, partnerName: String, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val g = GamePlay.spot(s)
    val mine = g.scores[me] ?: 0
    val theirs = g.scores.entries.firstOrNull { it.key != me }?.value ?: 0
    val over = s.status != GameStatus.InProgress || g.finished
    // A finished game's result card already holds the scores.
    if (s.status != GameStatus.Completed) Scoreboard(mine, theirs, partnerName, if (over) Turn.None else Turn.Both, { trn(it, "{count} spot", "{count} spots") })
    if (over) {
        SpotSummary(g, me, partnerName)
        return
    }
    val myInitial = initialOf(myName.ifBlank { tr("You") })
    val theirInitial = initialOf(partnerName)
    val meSide = LocalActor.current
    val theirSide = if (meSide == Side.Left) Side.Right else Side.Left
    val still = motionOff()
    val sounds = rememberLevelSounds()
    val haptics = rememberPlayHaptics()

    val left = (g.differences - g.found.size).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Picture {number} of {total}", "number" to g.scene + 1, "total" to g.totalScenes), style = Heartpact.type.label, color = colors.ink2)
            Text(if (g.phase == "between") tr("Picture done") else trn(left, "{count} difference left", "{count} differences left"), style = Heartpact.type.titleSmall, color = colors.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (g.phase == "spotting") TimerBadge(secondsUntil(s.deadlineUtc, now), g.sceneSeconds)
    }

    // Whatever the phone holder has tapped and is on its way, and how the last wrong tap went.
    val claims = remember(s.id, g.scene) { mutableStateListOf<Int>() }
    var pausedUntil by remember(s.id) { mutableLongStateOf(0L) }
    var miss by remember(s.id, g.scene) { mutableStateOf<Miss?>(null) }
    var kindNote by remember(s.id, g.scene) { mutableStateOf<String?>(null) }
    val seenAtStart = remember(s.id, g.scene) { g.found.map { it.index }.toSet() }
    val known = remember(s.id, g.scene) { mutableStateListOf<Int>().apply { addAll(seenAtStart) } }

    // A find lands: a soft note for it, and a claim the partner got first becomes a kind word, not an error.
    LaunchedEffect(g.found) {
        g.found.filter { it.index !in known }.forEach { find ->
            known += find.index
            if (find.by == me) sounds?.star(0) else sounds?.star(1)
            if (find.by != me && find.index in claims) kindNote = tr("{partner} got that one", "partner" to partnerName)
            claims.remove(find.index)
        }
    }
    // A claim the server never took (offline, refused) stops showing as yours after a few seconds.
    val foundNow by rememberUpdatedState(g.found)
    LaunchedEffect(claims.size) {
        if (claims.isEmpty()) return@LaunchedEffect
        delay(CLAIM_TIMEOUT_MS)
        claims.removeAll { index -> foundNow.none { it.index == index } }
    }
    // A page turning, but not for the picture the board opened on.
    val openedOn = remember(s.id) { g.scene }
    LaunchedEffect(g.scene) { if (g.scene != openedOn) sounds?.whoosh() }
    LaunchedEffect(kindNote) { if (kindNote != null) { delay(2600); kindNote = null } }

    val spotting = g.phase == "spotting" && s.status == GameStatus.InProgress
    val tap = rememberUpdatedState { x: Float, y: Float, scene: SpotScene, changed: Boolean ->
        val clock = System.currentTimeMillis()
        if (spotting && clock >= pausedUntil) {
            when (val index = scene.hit(x, y)) {
                null -> {
                    pausedUntil = clock + SPOT_PENALTY_MS
                    miss = Miss(x, y, changed, clock)
                    haptics.tick()
                }
                else -> {
                    val found = g.found.firstOrNull { it.index == index }
                    when {
                        found == null && index !in claims -> { claims += index; actions.onAct(s, "spot", null, index) }
                        found != null && found.by != me -> kindNote = tr("{partner} got that one", "partner" to partnerName)
                    }
                }
            }
        }
    }

    AnimatedContent(
        g.scene,
        transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(260, delayMillis = 240)) + scaleIn(tween(420, delayMillis = 240), initialScale = 0.92f)) togetherWith fadeOut(tween(200, delayMillis = 300)) using SizeTransform(clip = false)
        },
        label = "page",
    ) { index ->
        // The old picture turns away like a page from its left edge; the new one settles in under it.
        val turn by transition.animateFloat({ tween(520) }, label = "turn") { if (it == EnterExitState.PostExit) 1f else 0f }
        val picture = g.scenes.getOrNull(index) ?: return@AnimatedContent
        val kind = SceneKind.of(picture.kind)
        if (kind == null) {
            NoteCard(tr("This picture needs a newer Heartpact. Update the app to play it."))
            return@AnimatedContent
        }
        val scene = remember(kind, picture.seed, g.differences) { SpotScenes.build(kind, picture.seed, g.differences) }
        val current = index == g.scene
        val rings = scene.spots.mapNotNull { spot ->
            val find = picture.found.firstOrNull { it.index == spot.index }
            when {
                find != null -> {
                    val side = if (find.by == me) meSide else theirSide
                    Ring(spot, finderInk(side), if (find.by == me) myInitial else theirInitial, fresh = current && find.index !in seenAtStart)
                }
                current && spot.index in claims -> Ring(spot, finderInk(meSide), myInitial, fresh = true)
                picture.timedOut -> Ring(spot, colors.ink3, null, fresh = !still, revealed = true)
                else -> null
            }
        }
        Box(Modifier.graphicsLayer {
            rotationY = -96f * turn
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = 18f * density
            alpha = if (turn > 0.95f) 0f else 1f
        }) {
            ScenePair(scene, rings, if (current) miss else null, pausedUntil, enabled = current && spotting) { x, y, changed -> tap.value(x, y, scene, changed) }
        }
    }

    when {
        g.phase == "between" -> BetweenPictures(g, s, me, partnerName, now, Modifier.padding(top = 14.dp))
        kindNote != null -> Text(kindNote.orEmpty(), style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        else -> Text(tr("Tap a difference on either picture. First to tap it gets it."), style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

private fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/** The two pictures: stacked on a phone, side by side on a wide screen. */
@Composable
private fun ScenePair(scene: SpotScene, rings: List<Ring>, miss: Miss?, pausedUntil: Long, enabled: Boolean, onTap: (Float, Float, Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Picture(scene, false, rings, miss, pausedUntil, enabled, onTap, Modifier.weight(1f))
                Picture(scene, true, rings, miss, pausedUntil, enabled, onTap, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Picture(scene, false, rings, miss, pausedUntil, enabled, onTap)
                Picture(scene, true, rings, miss, pausedUntil, enabled, onTap)
            }
        }
    }
}

/** One picture, a die-cut sticker on the page, with its rings, a wrong tap's x, and the grey of the pause after one. */
@Composable
private fun Picture(scene: SpotScene, changed: Boolean, rings: List<Ring>, miss: Miss?, pausedUntil: Long, enabled: Boolean, onTap: (Float, Float, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val still = motionOff()
    val shape = RoundedCornerShape(20.dp)
    val inner = RoundedCornerShape(16.dp)
    val label = if (changed) tr("The changed picture") else tr("The original picture")
    // The pause after a wrong tap: the pictures grey a little, then clear.
    val veil = remember { Animatable(0f) }
    LaunchedEffect(pausedUntil) {
        val left = pausedUntil - System.currentTimeMillis()
        if (left <= 0) return@LaunchedEffect
        veil.snapTo(1f)
        if (still) { delay(left); veil.snapTo(0f) } else veil.animateTo(0f, tween(left.toInt(), easing = LinearEasing))
    }
    Box(
        modifier.fillMaxWidth()
            .shadow(5.dp, shape, ambientColor = Art.Shadow.copy(alpha = 0.5f), spotColor = Art.Shadow.copy(alpha = 0.5f))
            .background(Color.White, shape)
            .padding(4.dp)
            .clip(inner)
            .semantics { contentDescription = label },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(SpotScenes.WIDTH / SpotScenes.HEIGHT)) {
            val unit: Dp = maxWidth / SpotScenes.WIDTH
            Canvas(Modifier.fillMaxSize()) { drawSpotScene(scene, changed) }
            rings.forEach { ring -> RingMark(ring, unit, showPop = changed) }
            if (miss != null && miss.changed == changed) MissMark(miss, unit)
            if (veil.value > 0f) Box(Modifier.fillMaxSize().background(colors.ink.copy(alpha = 0.22f * veil.value)))
            Box(Modifier.fillMaxSize().pointerInput(enabled, changed) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onTap(o.x / size.width * SpotScenes.WIDTH, o.y / size.height * SpotScenes.HEIGHT, changed) }
            })
        }
    }
}

/** A ring drawn on with the pen round a difference, with the finder's initial; "+1" floats up off a new one. */
@Composable
private fun RingMark(ring: Ring, unit: Dp, showPop: Boolean) {
    val still = motionOff()
    val pen = remember(ring.spot.index, ring.ink) { Animatable(if (ring.fresh && !still) 0f else 1f) }
    val pop = remember(ring.spot.index, ring.ink) { Animatable(if (ring.fresh && !still && !ring.revealed) 0f else 1f) }
    LaunchedEffect(ring.spot.index, ring.ink) {
        if (pen.value < 1f) launch { pen.animateTo(1f, tween(if (ring.revealed) 700 else 460, easing = LinearEasing)) }
        if (pop.value < 1f) pop.animateTo(1f, tween(900, easing = LinearEasing))
    }
    val r = ring.spot.r
    val box = unit * (r * 2f + 10f)
    Box(Modifier.offset(unit * (ring.spot.x - r - 5f), unit * (ring.spot.y - r - 5f)).size(box)) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = unit.toPx() * 5f
            val around = Rect(pad, pad, size.width - pad, size.height - pad)
            if (ring.revealed) {
                drawCircle(Color.White.copy(alpha = 0.85f), around.width / 2f, around.center, style = Stroke(5.dp.toPx()))
                drawArc(ring.ink, -90f, 360f * pen.value, false, around.topLeft, around.size,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            } else {
                penCircle(pen.value, Color.White, 6.5.dp.toPx(), seed = ring.spot.index, around = around)
                penCircle(pen.value, ring.ink, 3.dp.toPx(), seed = ring.spot.index, around = around)
            }
        }
        if (ring.initial != null) {
            val disc = 18.dp
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(disc)
                    .graphicsLayer { val p = pen.value.coerceIn(0f, 1f); scaleX = p; scaleY = p }
                    .shadow(2.dp, RoundedCornerShape(50))
                    .background(Color.White, RoundedCornerShape(50))
                    .padding(1.5.dp)
                    .background(ring.ink, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ring.initial, style = Heartpact.type.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp), color = Color.White)
            }
        }
        if (showPop && !ring.revealed && pop.value < 1f) {
            val p = pop.value
            Text("+1", style = Heartpact.type.titleSmall, color = ring.ink, modifier = Modifier.align(Alignment.Center)
                .graphicsLayer {
                    translationY = -(18.dp.toPx() + 26.dp.toPx() * p)
                    alpha = (if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val s = 0.8f + 0.4f * (if (p < 0.25f) p / 0.25f else 1f)
                    scaleX = s; scaleY = s
                })
        }
    }
}

/** A wrong tap: a small x that wobbles "no". */
@Composable
private fun MissMark(miss: Miss, unit: Dp) {
    val still = motionOff()
    val wobble = remember(miss.key) { Animatable(0f) }
    LaunchedEffect(miss.key) {
        if (still) return@LaunchedEffect
        wobble.animateTo(0f, keyframes { durationMillis = 420; -18f at 70; 15f at 150; -9f at 230; 4f at 310; 0f at 420 })
    }
    val fade = remember(miss.key) { Animatable(1f) }
    LaunchedEffect(miss.key) { delay(SPOT_PENALTY_MS - 300); fade.animateTo(0f, tween(300)) }
    val size = 26.dp
    Canvas(Modifier.offset(unit * miss.x - size / 2, unit * miss.y - size / 2).size(size).graphicsLayer { rotationZ = wobble.value; alpha = fade.value }) {
        val a = this.size.width * 0.28f
        val c = center
        fun cross(colour: Color, width: Float) {
            drawLine(colour, Offset(c.x - a, c.y - a), Offset(c.x + a, c.y + a), width, StrokeCap.Round)
            drawLine(colour, Offset(c.x + a, c.y - a), Offset(c.x - a, c.y + a), width, StrokeCap.Round)
        }
        cross(Color.White, 7.dp.toPx())
        cross(PlayInk.Pen, 3.5.dp.toPx())
    }
}

/** Between two pictures: how this one went, stamped, and the next coming up. */
@Composable
private fun BetweenPictures(g: SpotTheDifferenceState, s: GameSession, me: String, partnerName: String, now: () -> Instant, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val picture = g.scenes.getOrNull(g.scene)
    val found = picture?.found.orEmpty()
    val mine = found.count { it.by == me }
    val theirs = found.count { it.by != me }
    val next = secondsUntil(s.deadlineUtc, now)
    StickerCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Stamp(if (picture?.timedOut == true) tr("Time's up!") else tr("All found!"), key = g.scene)
            Text(tr("This picture: you {mine}, {partner} {theirs}", "mine" to mine, "partner" to partnerName, "theirs" to theirs),
                style = Heartpact.type.bodyLarge, color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            if (picture?.timedOut == true) Text(tr("The ones nobody found are circled."), style = Heartpact.type.small, color = colors.ink3, textAlign = TextAlign.Center)
            Text(if (next > 0) tr("Next picture in {seconds}", "seconds" to next) else tr("Next picture coming up"), style = Heartpact.type.label, color = colors.ink2,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** The end: each picture small, every difference circled in the colour of whoever found it, and the count under it. */
@Composable
private fun SpotSummary(g: SpotTheDifferenceState, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val meSide = LocalActor.current
    val theirSide = if (meSide == Side.Left) Side.Right else Side.Left
    if (g.scenes.isEmpty()) return
    Text(tr("Picture by picture"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        g.scenes.forEach { picture ->
            val kind = SceneKind.of(picture.kind) ?: return@forEach
            val scene = remember(kind, picture.seed, g.differences) { SpotScenes.build(kind, picture.seed, g.differences) }
            val mine = picture.found.count { it.by == me }
            val theirs = picture.found.count { it.by != me }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val myInk = finderInk(meSide)
                val theirInk = finderInk(theirSide)
                Box(Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(12.dp)).background(Color.White, RoundedCornerShape(12.dp)).padding(2.5.dp).clip(RoundedCornerShape(10.dp))
                    .semantics { contentDescription = tr("You found {mine}, {partner} found {theirs}", "mine" to mine, "partner" to partnerName, "theirs" to theirs) }) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(SpotScenes.WIDTH / SpotScenes.HEIGHT)) {
                        drawSpotScene(scene, changed = true)
                        scene.spots.forEach { spot ->
                            val find = picture.found.firstOrNull { it.index == spot.index }
                            drawRing(spot, when { find == null -> colors.ink3; find.by == me -> myInk; else -> theirInk }, dashed = find == null)
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Dot(finderInk(meSide)); Text("$mine", style = Heartpact.type.label, color = colors.ink)
                    Text("·", style = Heartpact.type.label, color = colors.ink3)
                    Dot(finderInk(theirSide)); Text("$theirs", style = Heartpact.type.label, color = colors.ink)
                }
            }
        }
    }
}

@Composable
private fun Dot(colour: Color) = Box(Modifier.size(9.dp).background(colour, RoundedCornerShape(50)))

/** A still ring for the small pictures at the end. */
private fun DrawScope.drawRing(spot: Spot, colour: Color, dashed: Boolean) {
    val unit = size.width / SpotScenes.WIDTH
    val c = Offset(spot.x * unit, spot.y * unit)
    val r = spot.r * unit
    drawCircle(Color.White, r, c, style = Stroke(3.5.dp.toPx()))
    drawCircle(colour, r, c, style = Stroke(1.8.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null))
}
