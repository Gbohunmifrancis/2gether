package app.heartpact.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.RatherReveal
import app.heartpact.data.WouldYouRatherState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.serialization.json.Json

private val ratherJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

fun GamePlay.wouldYouRather(s: GameSession): WouldYouRatherState =
    runCatching { ratherJson.decodeFromString(WouldYouRatherState.serializer(), s.stateJson) }.getOrDefault(WouldYouRatherState())

/** Would you rather: both pick A or B at once; the picks show together. A heart each when they match. */
@Composable
internal fun WouldYouRatherBoard(s: GameSession, me: String, partnerName: String, busy: Boolean, actions: GameActions) {
    val colors = Heartpact.colors
    val w = GamePlay.wouldYouRather(s)
    val finished = s.status == GameStatus.Completed
    if (!finished) TogetherScore(w.scores[me] ?: 0, tr("Round {round} of {rounds}", "round" to minOf(w.round + 1, w.totalRounds), "rounds" to w.totalRounds),
        if (s.status == GameStatus.InProgress) waitingOn(w.players, me, w.picked) else Turn.None)
    w.last?.let { RatherRevealCard(it, me, partnerName, Modifier.padding(top = 12.dp)) }

    val a = w.a
    val b = w.b
    if (finished) return
    if (a == null || b == null) {
        Text(tr("You matched {matched} of {rounds}", "matched" to (w.scores[me] ?: 0), "rounds" to w.totalRounds), style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
        return
    }
    val canPick = s.status == GameStatus.InProgress && w.myPick == null && !busy
    val partnerPicked = w.picked.any { it != me }
    Text(tr("Would you rather…"), style = Heartpact.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp), color = colors.ink, modifier = Modifier.padding(top = 20.dp, bottom = 14.dp))
    // The two choices are dealt in, one after the other, each round.
    DealIn(w.round to 0) { RatherOption(a, selected = w.myPick == 0, faded = w.myPick == 1, enabled = canPick) { actions.onAct(s, "pick", null, 0) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(2.dp).background(colors.rule2))
        Text(tr("or"), style = Heartpact.type.label, color = colors.ink3)
        Box(Modifier.weight(1f).height(2.dp).background(colors.rule2))
    }
    DealIn(w.round to 1, delayMs = 90) { RatherOption(b, selected = w.myPick == 1, faded = w.myPick == 0, enabled = canPick) { actions.onAct(s, "pick", null, 1) } }
    when {
        w.myPick != null -> Text(tr("Picked. Waiting for {partner}…", "partner" to partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 18.dp))
        partnerPicked -> NoteCard(tr("{partner} has picked. Your turn!", "partner" to partnerName), Modifier.padding(top = 16.dp))
        else -> Text(tr("Pick one. You'll see {partner}'s pick once you've both chosen.", "partner" to partnerName), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun RatherOption(text: String, selected: Boolean, faded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val shape = Shapes.Card
    val pick = pickedFlip(selected)
    var m = Modifier.fillMaxWidth().heightIn(min = 112.dp).graphicsLayer { scaleX = pick.bump; scaleY = pick.bump }
    if (!faded) m = m.shadow(8.dp, shape, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
    m = m.clip(shape).background(if (selected) actor.soft else colors.sticker)
    if (selected) m = m.border(2.dp, actor.action, shape)
    m = m.alpha(if (faded) 0.55f else 1f).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Box(m.padding(horizontal = 22.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Heartpact.type.title.copy(fontSize = 21.sp, lineHeight = 26.sp), color = if (selected) actor.onSoft else colors.ink, textAlign = TextAlign.Center)
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).size(26.dp).graphicsLayer { rotationY = pick.turn }.clip(CircleShape).background(actor.action), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.onAction)
            }
        }
    }
}

/** Both picks from the last round, side by side once both were in. */
@Composable
private fun RatherRevealCard(last: RatherReveal, me: String, partnerName: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val option = { pick: Int? -> if (pick == 1) last.b else last.a }
    val mine = last.picks[me]
    val theirs = last.picks.entries.firstOrNull { it.key != me }?.value
    RoundReveal(last.round, if (last.match) tr("Match!") else null, hearts = last.match, modifier) {
        StickerCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (last.match) Heartpact.actor.soft else colors.sticker2), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(22.dp), tint = if (last.match) Heartpact.actor.onSoft else colors.ink4)
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Last round"), style = Heartpact.type.small, color = colors.ink2)
                    Text(
                        if (last.match) tr("You both picked: {choice}. A heart each.", "choice" to option(mine))
                        else tr("You picked: {mine}. {partner} picked: {theirs}.", "mine" to option(mine), "partner" to partnerName, "theirs" to option(theirs)),
                        style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
