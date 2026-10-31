package app.heartpact.ui.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.heartpact.data.DuelRound
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.SkillDuelState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.LocalStillLogo
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.play.Grade
import app.heartpact.ui.play.HeartBurst
import app.heartpact.ui.play.PlayInk
import app.heartpact.ui.play.Reaction
import app.heartpact.ui.play.ReactingCharacter
import app.heartpact.ui.play.ScoreReveal
import app.heartpact.ui.play.SkillIcon
import app.heartpact.ui.play.SkillKind
import app.heartpact.ui.play.SkillRound
import app.heartpact.ui.play.Stamp
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.play.penCircle
import app.heartpact.ui.play.rememberPlayHaptics
import app.heartpact.ui.play.rememberWobble
import app.heartpact.ui.play.unitHeart
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import kotlin.math.PI

/*
 * Heart to heart: five skill rounds, the same for both, each played on your own time. A partner's score stays a
 * sealed "?" until you've played that round too; then both count up side by side, the pen circles them, the round's
 * heart is stamped and Maya and Theo react. At the end the hearts decide the duel; the points only when the hearts are level.
 */

/** How long ScoreReveal takes to count [score] up (PlayKit's timing), so the stamp lands right after. */
private fun countMs(score: Int) = 650 + score * 4

/**
 * What this phone has already shown, so a reveal plays once and a reopened board doesn't replay every stamp.
 * Per round: 1 once my score has been shown, 2 once both have; the finished duel's result is index 5.
 */
private object DuelSeen {
    private val seen = mutableMapOf<String, IntArray>()

    /** Marks [what] for the round and says whether it was new (so it should animate). */
    fun first(session: String, round: Int, what: Int): Boolean = synchronized(seen) {
        val levels = seen.getOrPut(session) { IntArray(8) }
        if (round !in levels.indices || levels[round] >= what) return false
        levels[round] = what
        true
    }
}

/** A score on its way to the server: shown at once, resent if the partner's move got there first. */
private data class Sending(val score: Int, val version: Long, val tries: Int)

private data class RevealEvent(val round: Int, val winner: String?, val tie: Boolean)

/** The skill game a round names; null for one this app doesn't know yet. */
private fun DuelRound.skill(): SkillKind? = SkillKind.entries.firstOrNull { it.name == kind }

private fun scoreJson(score: Int) = """{"score":$score}"""

@Composable
internal fun SkillDuelBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val d = GamePlay.skillDuel(s)
    val partnerId = d.players.firstOrNull { it != me }
    // With animations off (or a screenshot holding things still) the board shows end states and nothing loops.
    val still = motionOff() || LocalStillLogo.current
    val haptics = rememberPlayHaptics()
    val sending = remember(s.id) { mutableStateMapOf<Int, Sending>() }
    var playing by remember(s.id) { mutableStateOf<Int?>(null) }
    var event by remember(s.id) { mutableStateOf<RevealEvent?>(null) }

    fun send(round: Int, score: Int, tries: Int) {
        sending[round] = Sending(score, s.stateVersion, tries)
        actions.onAct(s, "score", scoreJson(score), round)
    }

    // Scores sent at the same moment by both meet a version conflict: once the board has the newer state, send again.
    LaunchedEffect(s.stateVersion, busy) {
        if (busy) return@LaunchedEffect
        sending.entries.toList().forEach { (round, out) ->
            when {
                d.rounds.getOrNull(round)?.mine != null -> sending.remove(round)
                out.version != s.stateVersion && out.tries < 3 -> send(round, out.score, out.tries + 1)
            }
        }
    }
    // Not busy and still at the version it went out on: it didn't arrive (offline, say). A tap sends it again.
    fun failed(out: Sending?) = out != null && !busy && (out.version == s.stateVersion || out.tries >= 3)

    // What this phone shows: the server's rounds, with scores on their way filled in.
    val rounds = d.rounds.map { r -> sending[r.index]?.takeIf { r.mine == null }?.let { r.copy(mine = it.score) } ?: r }
    val finished = d.finished || s.status == GameStatus.Completed && d.totals != null

    DuelHeader(d, rounds, me, partnerId, partnerName, event, finished, still)

    if (finished) {
        DuelResult(s.id, d, rounds, me, partnerId, partnerName, still)
        return
    }

    val myLeft = rounds.count { it.mine == null }
    val theirsWaiting = rounds.count { it.mine == null && it.played }
    val partnerLeft = rounds.count { !it.played }
    Text(
        when {
            rounds.none { it.mine != null || it.played } -> tr("Five rounds, the same for you both. Play them in any order; the scores show once you've both played a round.")
            theirsWaiting > 0 -> trn(theirsWaiting, "{partner} played {count} round you haven't. Play it to see both scores.",
                "{partner} played {count} rounds you haven't. Play them to see both scores.", "partner" to partnerName)
            myLeft == 0 -> trn(partnerLeft, "All five played. {partner} has {count} round to go.", "All five played. {partner} has {count} rounds to go.", "partner" to partnerName)
            else -> trn(myLeft, "{count} round left to play", "{count} rounds left to play")
        },
        style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )

    val next = rounds.firstOrNull { it.mine == null && it.skill() != null }?.index
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rounds.forEach { round ->
            val out = sending[round.index]
            val canPlay = s.status == GameStatus.InProgress && round.mine == null && round.skill() != null && !busy
            RoundCard(
                session = s.id, round = round, me = me, partnerName = partnerName, next = round.index == next, still = still,
                sendingState = out, failed = failed(out),
                enabled = canPlay || failed(out),
                onTap = {
                    haptics.tick()
                    if (out != null) send(round.index, out.score, 1) else playing = round.index
                },
                onReveal = { event = RevealEvent(round.index, round.winner, round.tie) },
            )
        }
    }

    val round = playing?.let { d.rounds.getOrNull(it) }
    val kind = round?.skill()
    if (round != null && kind != null) {
        val index = round.index
        // A score counts the moment it's made: closing the round while its score is still counting up sends it all the
        // same, so a round can't be left and played again for a better one.
        var made by remember(s.id, index) { mutableStateOf<Int?>(null) }
        fun close() {
            playing = null
            made?.let { if (sending[index] == null) send(index, it, 1) }
        }
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
            // The round draws its own page; the host (here) keeps it clear of the status and navigation bars.
            Column(Modifier.fillMaxSize().background(colors.page).statusBarsPadding().navigationBarsPadding()) {
                TopBar(tr("Round {round} of {rounds} · {game}", "round" to index + 1, "rounds" to d.totalRounds, "game" to kind.title), ::close)
                SkillRound(kind, round.seed, onDone = { close() }, Modifier.weight(1f), onScored = { made = it.score.coerceIn(0, 100) })
            }
        }
    }
}

/** You against your partner: each with their half and their hearts so far, the five rounds as pips between. */
@Composable
private fun DuelHeader(d: SkillDuelState, rounds: List<DuelRound>, me: String, partnerId: String?, partnerName: String, event: RevealEvent?, finished: Boolean, still: Boolean) {
    val colors = Heartpact.colors
    val mySide = LocalActor.current
    val theirSide = if (mySide == Side.Left) Side.Right else Side.Left
    fun reaction(player: String?): Reaction = when {
        event != null -> when {
            event.tie || event.winner == player -> Reaction.Cheer
            else -> Reaction.Sad
        }
        finished -> if (d.tie || d.winner == player) Reaction.Cheer else Reaction.Idle
        else -> Reaction.Idle
    }
    Row(
        Modifier.fillMaxWidth().shadow(8.dp, Shapes.Card, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
            .background(colors.sticker, Shapes.Card).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Duelist(tr("You"), mySide, d.hearts[me] ?: 0, reaction(me), event, still, Modifier.weight(1f))
        Column(Modifier.padding(bottom = 6.dp).widthIn(min = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("vs"), style = Heartpact.type.headline.copy(fontSize = 24.sp), color = colors.ink4)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                rounds.forEach { RoundPip(it, me, partnerName) }
            }
        }
        Duelist(partnerName, theirSide, partnerId?.let { d.hearts[it] } ?: 0, reaction(partnerId), event, still, Modifier.weight(1f))
    }
}

@Composable
private fun Duelist(name: String, side: Side, hearts: Int, reaction: Reaction, event: RevealEvent?, still: Boolean, modifier: Modifier) {
    val colors = Heartpact.colors
    val partner = colors.partner(side)
    // The count pops when a heart lands.
    val pop = remember { Animatable(1f) }
    var last by remember { mutableIntStateOf(hearts) }
    LaunchedEffect(hearts) {
        if (hearts != last) {
            last = hearts
            pop.snapTo(1.45f)
            pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 420f))
        }
    }
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        val who = if (side == Side.Left) Who.Left else Who.Right
        if (still) Character(who, when (reaction) { Reaction.Cheer -> Pose.Cheer; Reaction.Sad -> Pose.Rest; else -> Pose.Wave }, 70.dp,
            mood = when (reaction) { Reaction.Cheer -> Mood.Cheer; Reaction.Sad -> Mood.Worried; else -> Mood.Smile })
        else ReactingCharacter(who, reaction, 70.dp, key = event ?: reaction)
        Text(name, style = Heartpact.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Row(
            Modifier.padding(top = 4.dp).background(partner.soft, Shapes.Pill).padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(Modifier.size(13.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
                translate(size.width / 2, size.height * 0.55f) { drawPath(unitHeart(size.minDimension * 1.25f), PlayInk.Pen) }
            }
            Text(trn(hearts, "{count} heart", "{count} hearts"), style = Heartpact.type.labelSmall, color = partner.onSoft, maxLines = 1)
        }
    }
}

/** One round in the header: the winner's half, a whole heart for a tie, else a small dot in the game's colour. */
@Composable
private fun RoundPip(round: DuelRound, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val label = when {
        !round.revealed -> null
        round.tie -> tr("Round {round}: a heart each", "round" to round.index + 1)
        round.winner == me -> tr("Round {round}: your heart", "round" to round.index + 1)
        else -> tr("Round {round}: {partner}'s heart", "round" to round.index + 1, "partner" to partnerName)
    }
    Box(Modifier.size(16.dp).then(label?.let { Modifier.semantics { contentDescription = it } } ?: Modifier), contentAlignment = Alignment.Center) {
        when {
            round.revealed && round.tie -> HeartSticker(HalfState.Stuck, HalfState.Stuck, 16.dp)
            round.revealed && round.winner != null -> HalfHeartPiece(sideOf(round.winner, me), 17.dp)
            else -> {
                val fill = round.skill()?.colour?.fill ?: colors.rule2
                val mine = round.mine != null
                Canvas(Modifier.size(9.dp)) {
                    drawCircle(fill)
                    if (mine) drawCircle(colors.ink3, style = Stroke(1.2.dp.toPx()))
                }
            }
        }
    }
}

/** A round: the game's blob and name, and once anyone has played, the two scores side by side. */
@Composable
private fun RoundCard(
    session: String, round: DuelRound, me: String, partnerName: String, next: Boolean, still: Boolean,
    sendingState: Sending?, failed: Boolean, enabled: Boolean, onTap: () -> Unit, onReveal: () -> Unit,
) {
    val colors = Heartpact.colors
    val kind = round.skill()
    val mySide = LocalActor.current
    val theirSide = if (mySide == Side.Left) Side.Right else Side.Left
    // Decided once per change: a reveal this phone hasn't shown yet plays out; one it has stays put.
    val revealNow = remember(round.revealed) { round.revealed && DuelSeen.first(session, round.index, 2) && !still }
    val mineNow = remember(round.mine != null) { round.mine != null && DuelSeen.first(session, round.index, 1) && !still }
    var stamped by remember(round.revealed) { mutableStateOf(round.revealed && !revealNow) }
    val landAt = countMs(maxOf(round.mine ?: 0, round.partner ?: 0)) + 520
    LaunchedEffect(revealNow) {
        if (revealNow) {
            delay(landAt.toLong())
            stamped = true
            onReveal()
        }
    }
    val title = kind?.title ?: round.kind
    val stampText = when {
        round.tie -> tr("A heart each")
        round.winner == me -> tr("Your heart")
        else -> tr("{partner}'s heart", "partner" to partnerName)
    }
    val status = when {
        kind == null -> tr("Update the app to play this round")
        failed && round.mine != null ->tr("Not sent. Tap to send it again")
        sendingState != null && round.mine != null -> tr("Sending…")
        round.revealed -> null
        round.mine == null && round.played -> tr("{partner} played. Play yours to see", "partner" to partnerName)
        round.mine == null -> kind.blurb
        else -> tr("Waiting for {partner}", "partner" to partnerName)
    }
    val description = buildString {
        append(tr("Round {round}: {game}", "round" to round.index + 1, "game" to title)).append(". ")
        round.mine?.let { append(tr("You scored {score}", "score" to it)).append(". ") }
        when {
            round.partner != null -> append(tr("{partner} scored {score}", "partner" to partnerName, "score" to round.partner)).append(". ")
            round.played -> append(tr("{partner} has played it", "partner" to partnerName)).append(". ")
        }
        if (round.revealed) append(stampText) else status?.let { append(it) }
    }

    val shape = Shapes.Card
    Box(Modifier.zIndex(if (revealNow) 1f else 0f)) {
        Column(
            Modifier.fillMaxWidth()
                .shadow(if (next) 12.dp else 6.dp, shape, clip = false, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
                .background(colors.sticker, shape)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (sendingState != null) tr("Send again") else tr("Play"), onClick = onTap)
                .clearAndSetSemantics { contentDescription = description }
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBlob(kind, round.index, 56.dp, ringed = next, moving = !still)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text(tr("Round {round}", "round" to round.index + 1), style = Heartpact.type.labelSmall, color = colors.ink3)
                    Text(title, style = Heartpact.type.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (round.revealed && status == null) {
                        Box(Modifier.padding(top = 8.dp, start = 2.dp).heightIn(min = 36.dp)) {
                            if (stamped) {
                                if (revealNow) Stamp(stampText, key = round.index) else StillStamp(stampText)
                            }
                        }
                    } else if (status != null) {
                        Text(status, style = Heartpact.type.small, color = if (failed) colors.error else colors.ink2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (enabled && round.mine == null) PlayPill(next)
            }
            if (round.mine != null || round.played) {
                Dashes(Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreSide(tr("You"), mySide, round.mine, hidden = false, played = round.mine != null, animate = revealNow || mineNow,
                        won = round.revealed && (round.tie || round.winner == me), burst = stamped && revealNow, end = false, modifier = Modifier.weight(1f))
                    Text(tr("vs"), style = Heartpact.type.labelSmall, color = colors.ink4, modifier = Modifier.padding(horizontal = 8.dp))
                    ScoreSide(partnerName, theirSide, round.partner, hidden = round.partner == null && round.played, played = round.played, animate = revealNow,
                        won = round.revealed && (round.tie || round.winner != me), burst = stamped && revealNow, end = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** One side of a round's score line: the half, the name, and the score (counted up), a sealed "?" or a dash. */
@Composable
private fun ScoreSide(name: String, side: Side, score: Int?, hidden: Boolean, played: Boolean, animate: Boolean, won: Boolean, burst: Boolean, end: Boolean, modifier: Modifier) {
    val colors = Heartpact.colors
    val partner = colors.partner(side)
    val number: @Composable () -> Unit = {
        Box(Modifier.width(76.dp).height(52.dp), contentAlignment = Alignment.Center) {
            when {
                score != null && animate -> ScoreReveal(score, big = false, key = score, stamp = null, colour = if (won) colors.ink else colors.ink3)
                score != null -> StillScore(score, if (won) colors.ink else colors.ink3)
                hidden -> Sealed(partner.action)
                else -> Text("–", style = Heartpact.type.headline, color = colors.ink4)
            }
            if (burst && won) HeartBurst(true, Modifier.size(1.dp), count = 14, spread = 90.dp)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        if (end) number()
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 6.dp), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
            Text(name, style = Heartpact.type.label, color = partner.onSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val caption = when {
                hidden -> tr("played")
                !played -> tr("not yet")
                score != null -> Grade.of(score).label
                else -> null
            }
            caption?.let { Text(it, style = Heartpact.type.small, color = colors.ink3, maxLines = 1) }
        }
        if (!end) number()
    }
}

/** A score already shown once: the number, circled. */
@Composable
private fun StillScore(score: Int, colour: Color, big: Boolean = false) {
    val numberSize = if (big) 40.sp else 34.sp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.drawBehind {
        val grow = 10.dp.toPx()
        penCircle(1f, width = 2.5.dp.toPx(), seed = score % 5, around = Rect(-grow, -grow / 1.6f, size.width + grow, size.height + grow / 1.6f))
    }) {
        Text("$score", style = Heartpact.type.display.copy(fontSize = numberSize, lineHeight = numberSize), color = colour, textAlign = TextAlign.Center)
    }
}

/** The partner's score, sealed until you've played the round: a dashed card with a question mark. */
@Composable
private fun Sealed(colour: Color) {
    Box(
        Modifier.size(46.dp, 40.dp).drawBehind {
            drawRoundRect(colour.copy(alpha = 0.12f), cornerRadius = CornerRadius(12.dp.toPx()))
            drawRoundRect(colour, cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = Heartpact.type.headline.copy(fontSize = 24.sp), color = colour)
    }
}

/**
 * The stamp of a round already revealed, at rest: [Stamp] slams (with a buzz) every time it appears, which a board
 * reopened with five stamps on it shouldn't do.
 */
@Composable
private fun StillStamp(text: String, colour: Color = PlayInk.Pen) {
    Box(
        Modifier.graphicsLayer { rotationZ = -8f }
            .drawBehind {
                val r = 10.dp.toPx()
                drawRoundRect(colour, cornerRadius = CornerRadius(r), style = Stroke(2.5.dp.toPx()))
                val inset = 5.dp.toPx()
                drawRoundRect(colour.copy(alpha = 0.7f), topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(r - inset / 2), style = Stroke(1.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text.uppercase(), style = Heartpact.type.label.copy(fontFamily = app.heartpact.ui.theme.Sora, fontSize = 15.sp, letterSpacing = 0.08.sp), color = colour)
    }
}

/** The game's sticker: a pastel blob with a white keyline and its line icon; the round to play next wobbles in a dashed ring. */
@Composable
private fun KindBlob(kind: SkillKind?, seed: Int, size: Dp, ringed: Boolean, moving: Boolean = false) {
    val colors = Heartpact.colors
    val phase = if (ringed && moving) rememberWobble() else 0f
    val fill = kind?.colour?.fill ?: colors.rule2
    val ink = kind?.colour?.ink ?: colors.ink3
    val ring = Heartpact.actor.action
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val p = phase * 2f * PI.toFloat()
            if (ringed) rotate(phase * 360f) {
                drawCircle(ring, this.size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            }
            val inset = if (ringed) 6.dp.toPx() else 2.dp.toPx()
            val box = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            translate(inset, inset + 1.dp.toPx()) { drawPath(blobPath(seed, box, phase = p), app.heartpact.ui.theme.Art.Shadow, alpha = 0.1f) }
            translate(inset, inset) {
                drawPath(blobPath(seed, box, phase = p), Color.White)
                val keyline = 2.5.dp.toPx()
                translate(keyline, keyline) { drawPath(blobPath(seed, Size(box.width - keyline * 2, box.height - keyline * 2), phase = p), fill) }
            }
        }
        if (kind != null) SkillIcon(kind, ink, size * 0.42f)
    }
}

/** The "play" pill on a round you haven't played: filled on the next one, soft on the rest. Not its own button. */
@Composable
private fun PlayPill(next: Boolean) {
    val actor = Heartpact.actor
    val colors = Heartpact.colors
    Box(Modifier.background(if (next) actor.action else actor.soft, Shapes.Pill).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(tr("Play"), style = Heartpact.type.label, color = if (next) colors.onAction else actor.onSoft)
    }
}

/** A dashed perforation, as between the rows of a card. */
@Composable
private fun Dashes(modifier: Modifier = Modifier) {
    val rule = Heartpact.colors.rule2
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
    }
}

/** The end of the duel: both hearts circled, who won and by how much (the points only when they broke a tie), a stamp, and the five rounds at a glance. */
@Composable
private fun DuelResult(session: String, d: SkillDuelState, rounds: List<DuelRound>, me: String, partnerId: String?, partnerName: String, still: Boolean) {
    val colors = Heartpact.colors
    val mySide = LocalActor.current
    val theirSide = if (mySide == Side.Left) Side.Right else Side.Left
    val mine = d.hearts[me] ?: 0
    val theirs = partnerId?.let { d.hearts[it] } ?: 0
    val decision = GamePlay.duelDecision(d)
    val iWon = d.winner == me
    val tie = decision == GamePlay.DuelDecision.Tie
    // The points show only when the hearts were level: they broke the tie, or they were level too.
    val points = if (decision == GamePlay.DuelDecision.Hearts) null else (d.totals?.get(me) ?: 0) to (partnerId?.let { d.totals?.get(it) } ?: 0)
    val animate = remember { DuelSeen.first(session, 5, 2) && !still }
    var landed by remember { mutableStateOf(!animate) }
    LaunchedEffect(animate) {
        if (animate) { delay(countMs(maxOf(mine, theirs)).toLong() + 520); landed = true }
    }
    val headline = when {
        tie -> tr("A perfect tie")
        iWon -> tr("You won the duel")
        else -> tr("{partner} won", "partner" to partnerName)
    }
    val by = GamePlay.duelMargin(d, me)
    val stampText = when {
        tie -> tr("Perfect tie!")
        iWon -> tr("Champion!")
        else -> tr("Well played!")
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .shadow(8.dp, Shapes.Card, clip = false, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
            .background(colors.sticker, Shapes.Card).padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(headline, style = Heartpact.type.headline, color = colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "$headline, $by" })
        Text(by, style = Heartpact.type.bodyLarge, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Total(tr("You"), mySide, mine, points?.first, won = tie || iWon, animate = animate, burst = landed && animate, modifier = Modifier.weight(1f))
            Total(partnerName, theirSide, theirs, points?.second, won = tie || !iWon, animate = animate, burst = landed && animate, modifier = Modifier.weight(1f))
        }
        Box(Modifier.padding(top = 20.dp).heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            if (landed) { if (animate) Stamp(stampText, key = session) else StillStamp(stampText) }
        }
        Dashes(Modifier.padding(top = 18.dp, bottom = 6.dp))
        rounds.forEach { round -> RoundLine(round, me, partnerName) }
    }
}

@Composable
private fun Total(name: String, side: Side, hearts: Int, points: Int?, won: Boolean, animate: Boolean, burst: Boolean, modifier: Modifier) {
    val colors = Heartpact.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HalfHeartPiece(side, 20.dp)
            Text(name, style = Heartpact.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(top = 14.dp).height(60.dp), contentAlignment = Alignment.Center) {
            if (animate) ScoreReveal(hearts, big = false, key = hearts, stamp = null, colour = if (won) colors.ink else colors.ink3)
            else StillScore(hearts, if (won) colors.ink else colors.ink3)
            if (burst && won) HeartBurst(true, Modifier.size(1.dp), count = 22, spread = 140.dp)
        }
        Text(trn(hearts, "heart", "hearts"), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 4.dp))
        if (points != null) Text(trn(points, "{count} point", "{count} points"), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
    }
}

/** One round in the result: its game, both scores, and the round's heart. */
@Composable
private fun RoundLine(round: DuelRound, me: String, partnerName: String) {
    val colors = Heartpact.colors
    val kind = round.skill()
    val mine = round.mine ?: 0
    val theirs = round.partner ?: 0
    val heart = when {
        round.tie -> tr("A heart each")
        round.winner == me -> tr("Your heart")
        else -> tr("{partner}'s heart", "partner" to partnerName)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics {
            contentDescription = tr("Round {round}: {game}", "round" to round.index + 1, "game" to (kind?.title ?: round.kind)) + ". " +
                tr("You {mine}, {partner} {theirs}", "mine" to mine, "partner" to partnerName, "theirs" to theirs) + ". " + heart
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindBlob(kind, round.index, 34.dp, ringed = false)
        Text(kind?.title ?: round.kind, style = Heartpact.type.label, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
        Text("$mine", style = Heartpact.type.titleSmall, color = if (round.tie || round.winner == me) colors.ink else colors.ink3,
            textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            when {
                round.tie -> HeartSticker(HalfState.Stuck, HalfState.Stuck, 18.dp)
                round.winner != null -> HalfHeartPiece(sideOf(round.winner, me), 19.dp)
                else -> Text("·", color = colors.ink4)
            }
        }
        Text("$theirs", style = Heartpact.type.titleSmall, color = if (round.tie || round.winner != me) colors.ink else colors.ink3,
            textAlign = TextAlign.Start, modifier = Modifier.width(34.dp))
    }
}
