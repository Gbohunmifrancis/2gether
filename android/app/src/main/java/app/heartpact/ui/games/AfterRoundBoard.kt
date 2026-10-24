package app.heartpact.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.AfterRoundReveal
import app.heartpact.data.AfterRoundState
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.SlapDuelState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import java.time.Instant

/** The live After Round, for couples who want to play it with friends too. */
private const val AFTER_ROUND_URL = "https://afterround1.tech"

/** The phone counts 3, 2, 1 before the duel's needle starts. */
private const val DUEL_COUNT_IN_MS = 3000L

/**
 * After Round (docs/games.md): call a number, lay 0 to 5 fingers, whoever called the total takes the round. First to
 * five, then the winner slaps in a slap duel.
 */
@Composable
internal fun AfterRoundBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions, now: () -> Instant) {
    val colors = Heartpact.colors
    val a = GamePlay.afterRound(s)
    val playing = s.status == GameStatus.InProgress
    val left = secondsUntil(s.deadlineUtc, now)
    Scores(a.scores, me, partnerName) { trn(it, "{count} round", "{count} rounds") }
    if (playing && a.phase != "duel") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (a.phase == "calling") tr("Call a number") else tr("Lay your fingers"), style = Heartpact.type.headline, color = colors.ink)
                Text(tr("Round {round} · first to {target}", "round" to a.round + 1, "target" to a.target), style = Heartpact.type.small, color = colors.ink2)
            }
            Text(tr("{seconds}s", "seconds" to left), style = Heartpact.type.headline, color = if (left <= 3) colors.error else colors.ink2)
        }
    }
    a.last?.takeIf { a.phase != "duel" || s.status != GameStatus.InProgress }?.let { LastRound(it, me, partnerName, Modifier.padding(top = 12.dp)) }
    when {
        !playing -> Finished(s, a, me, partnerName)
        a.phase == "calling" -> Calling(s, a, me, partnerName, busy, actions)
        a.phase == "laying" -> Laying(s, a, me, partnerName, busy, actions)
        a.phase == "duel" && a.duel != null -> Duel(s, a.duel, me, partnerName, actions)
    }
}

@Composable
private fun Calling(s: GameSession, a: AfterRoundState, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val mine = a.calls[me]
    val theirs = a.calls.entries.firstOrNull { it.key != me }?.value
    when {
        mine != null -> Text(tr("You called {number}. Waiting for {partner}…", "number" to mine, "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 14.dp))
        theirs != null -> NoteCard(tr("{partner} called {number}. Your call!", "partner" to partnerName, "number" to theirs), Modifier.padding(top = 14.dp))
        else -> Text(tr("Call the total you think both hands will make. Each number goes once: first come, first served."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 14.dp))
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..10).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { n ->
                    val state = when {
                        mine == n -> Token.Mine
                        theirs == n -> Token.Theirs
                        n !in a.reachable -> Token.Impossible
                        else -> Token.Open
                    }
                    NumberToken(n, state, Modifier.weight(1f), enabled = mine == null && state == Token.Open && !busy) { actions.onAct(s, "call", null, n) }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
    if (a.reachable.size < 11) Text(tr("Faded numbers can't be made this round: nobody may lay the same fingers twice in a row."), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Laying(s: GameSession, a: AfterRoundState, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val mine = a.calls[me]
    val theirs = a.calls.entries.firstOrNull { it.key != me }?.value
    if (mine != null && theirs != null) {
        Text(tr("You called {mine}, {partner} called {theirs}.", "mine" to mine, "partner" to partnerName, "theirs" to theirs), style = Heartpact.type.bodyLarge, color = colors.ink, modifier = Modifier.padding(top = 14.dp))
    }
    val laid = a.myLay
    if (laid != null) {
        Text(trn(laid, "Your hand is down: {count} finger. Waiting for {partner}…", "Your hand is down: {count} fingers. Waiting for {partner}…", "partner" to partnerName),
            style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 14.dp))
        return
    }
    if (a.laid.any { it != me }) NoteCard(tr("{partner}'s hand is down. Lay yours!", "partner" to partnerName), Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..5).forEach { n ->
            val allowed = n in a.myFingers
            FingerTile(n, allowed, Modifier.weight(1f), enabled = allowed && !busy) { actions.onAct(s, "lay", null, n) }
        }
    }
    a.lastFingers[me]?.let { Text(trn(it, "You laid {count} last round, so not {count} again.", "You laid {count} last round, so not {count} again."), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 10.dp)) }
}

/**
 * The slap duel. The needle sweeps across the gauge; press as close to the middle of the mark as you can. The slapper's
 * press lands the slap, the defender's dodges it: the closer press wins.
 */
@Composable
private fun Duel(s: GameSession, d: SlapDuelState, me: String, partnerName: String, actions: GameActions) {
    val colors = Heartpact.colors
    val slapping = d.slapper == me
    // The gauge runs on this phone's clock from when the duel showed up; the press is sent as time since it started.
    var shownAt by remember(s.id, d.slapper, d.zone) { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember(s.id, d.zone) { mutableLongStateOf(0L) }
    var pressedAt by remember(s.id, d.zone) { mutableStateOf<Long?>(null) }
    val pressed = pressedAt != null || me in d.pressed
    LaunchedEffect(s.id, d.zone, pressed) {
        while (!pressed) {
            withFrameMillis { elapsed = System.currentTimeMillis() - shownAt }
            if (elapsed > DUEL_COUNT_IN_MS + d.windowMs) break
        }
    }
    Text(if (slapping) tr("Slap duel! You won, so you slap.") else tr("Slap duel! {partner} won. Dodge the slap!", "partner" to partnerName),
        style = Heartpact.type.headline, color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    Text(tr("Stop the needle on the middle of the mark. The closer press wins."), style = Heartpact.type.body, color = colors.ink2,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    val gaugeMs = (pressedAt ?: elapsed) - DUEL_COUNT_IN_MS
    if (gaugeMs < 0 && !pressed) {
        Text("${(-gaugeMs + 999) / 1000}", style = Heartpact.type.display.copy(fontSize = 96.sp, lineHeight = 90.sp), color = colors.ink,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        return
    }
    val live = !pressed && gaugeMs <= d.windowMs
    Gauge(position(gaugeMs.coerceIn(0, d.windowMs.toLong()), d.periodMs), d.zone, Modifier.padding(top = 24.dp))
    when {
        pressed -> Text(tr("Pressed. Waiting for {partner}…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        live -> HeartButton(if (slapping) tr("Slap!") else tr("Dodge!"), {
            val at = System.currentTimeMillis() - shownAt
            pressedAt = at
            actions.onAct(s, "press", null, (at - DUEL_COUNT_IN_MS).toInt().coerceIn(0, d.windowMs))
        }, Modifier.padding(top = 18.dp).height(72.dp), height = 72.dp)
        else -> Text(tr("Too late! Waiting for the result…"), style = Heartpact.type.bodyLarge, color = colors.ink2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
    }
}

/** Where the needle is after [ms]: one sweep across per [periodMs], then back. The same sweep the server scores. */
internal fun position(ms: Long, periodMs: Int): Double {
    val phase = ms.toDouble() / periodMs % 2
    return if (phase < 1) phase else 2 - phase
}

@Composable
private fun Gauge(needle: Double, zone: Double, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    Canvas(modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = tr("Slap gauge") }) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(colors.sticker2, cornerRadius = r)
        // The mark: good, great and perfect bands, darker towards the middle.
        listOf(0.16 to actor.tint, 0.08 to actor.soft, 0.03 to actor.action).forEach { (band, color) ->
            val from = ((zone - band).coerceAtLeast(0.0) * size.width).toFloat()
            val to = ((zone + band).coerceAtMost(1.0) * size.width).toFloat()
            drawRect(color, Offset(from, 0f), Size(to - from, size.height))
        }
        drawRoundRect(colors.rule, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        val x = (needle * size.width).toFloat()
        drawLine(colors.ink, Offset(x, -6.dp.toPx()), Offset(x, size.height + 6.dp.toPx()), strokeWidth = 4.dp.toPx())
    }
}

@Composable
private fun Finished(s: GameSession, a: AfterRoundState, me: String, partnerName: String) {
    val colors = Heartpact.colors
    a.duel?.result?.let { result ->
        val slapper = a.duel.slapper == me
        val text = when (result.outcome) {
            "dodged" -> if (slapper) tr("{partner} dodged your slap!", "partner" to partnerName) else tr("You dodged {partner}'s slap!", "partner" to partnerName)
            "glancing" -> if (slapper) tr("Your slap glanced off {partner}.", "partner" to partnerName) else tr("{partner}'s slap only glanced off you.", "partner" to partnerName)
            else -> if (slapper) tr("Your slap landed on {partner}!", "partner" to partnerName) else tr("{partner}'s slap landed!", "partner" to partnerName)
        }
        val mine = if (slapper) result.slapper else result.defender
        StickerCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Slap duel"), style = Heartpact.type.small, color = colors.ink2)
                Text(text, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
                Text(tr("Your press: {rating}", "rating" to rating(mine.rating)), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Text(GamePlay.outcome(s, me, partnerName), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
    if (a.ended == "idle") Text(tr("The game stopped: nobody moved for two rounds."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
    if (s.status == GameStatus.Completed) {
        val uri = LocalUriHandler.current
        StickerCard(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Play After Round with your friends"), style = Heartpact.type.titleSmall, color = colors.ink)
                Text(tr("Group tables, story mode and more slap duels at afterround1.tech."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                HeartButton(tr("Open After Round"), { runCatching { uri.openUri(AFTER_ROUND_URL) } }, Modifier.padding(top = 10.dp), kind = ButtonKind.Tonal)
            }
        }
    }
}

private fun rating(rating: String) = when (rating) {
    "perfect" -> tr("Perfect")
    "great" -> tr("Great")
    "good" -> tr("Good")
    "weak" -> tr("Weak")
    else -> tr("No press")
}

/** Both calls, both hands and the total, once both hands were down. */
@Composable
private fun LastRound(last: AfterRoundReveal, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val mineFingers = last.fingers[me] ?: 0
    val theirFingers = last.fingers.entries.firstOrNull { it.key != me }?.value ?: 0
    val mineCall = last.calls[me]
    val theirCall = last.calls.entries.firstOrNull { it.key != me }?.value
    StickerCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("Last round: {mine} + {theirs} = {total}", "mine" to mineFingers, "theirs" to theirFingers, "total" to last.total), style = Heartpact.type.label, color = colors.ink)
            Text(when (last.winner) {
                me -> tr("You called {total}. The round is yours!", "total" to last.total)
                null -> tr("Nobody called {total}. You called {mine}, {partner} called {theirs}.", "total" to last.total, "mine" to (mineCall ?: "—"), "partner" to partnerName, "theirs" to (theirCall ?: "—"))
                else -> tr("{partner} called {total} and takes the round.", "partner" to partnerName, "total" to last.total)
            }, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
            if (me in last.auto) Text(tr("Your time ran out, so a move was made for you."), style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private enum class Token { Open, Mine, Theirs, Impossible }

@Composable
private fun NumberToken(n: Int, state: Token, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val mine = Heartpact.actor
    val theirs = colors.partner(if (LocalActor.current == Side.Left) Side.Right else Side.Left)
    val (fill, ink) = when (state) {
        Token.Mine -> mine.action to colors.onAction
        Token.Theirs -> theirs.soft to theirs.onSoft
        else -> colors.sticker to colors.ink
    }
    val label = when (state) {
        Token.Mine -> tr("{number}, your call", "number" to n)
        Token.Theirs -> tr("{number}, taken by your partner", "number" to n)
        Token.Impossible -> tr("{number}, can't be made this round", "number" to n)
        Token.Open -> "$n"
    }
    Box(
        modifier.aspectRatio(1f).clip(Shapes.Tile).background(fill).border(2.dp, if (state == Token.Theirs) theirs.action else colors.rule, Shapes.Tile)
            .alpha(if (state == Token.Impossible) 0.35f else 1f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = Heartpact.type.headline.copy(fontSize = 28.sp), color = ink)
    }
}

/** A hand of [n] fingers: the number, with a fist for zero. */
@Composable
private fun FingerTile(n: Int, allowed: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    Column(
        modifier.clip(Shapes.Tile).background(colors.sticker).border(2.dp, colors.rule, Shapes.Tile).alpha(if (allowed) 1f else 0.35f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (n == 0) tr("A fist, no fingers") else trn(n, "{count} finger", "{count} fingers") }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (n == 0) "✊" else "$n", style = Heartpact.type.headline.copy(fontSize = 26.sp), color = colors.ink)
        Box(Modifier.size(4.dp))
        Text(if (n == 0) tr("Fist") else trn(n, "finger", "fingers"), style = Heartpact.type.small, color = colors.ink2)
    }
}
