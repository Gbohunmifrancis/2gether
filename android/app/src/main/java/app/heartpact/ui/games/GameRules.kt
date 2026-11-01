package app.heartpact.ui.games

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.play.blobPath
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** A game's rules in four short parts: what you're after, how a turn goes, how it's won or scored, and one tip. */
data class Rules(val goal: String, val turn: String, val win: String, val tip: String)

/**
 * "How to play" for every couple game (docs/games.md has the server's rules each line follows). Shown by itself the
 * first time each person opens a game on this phone, and from the "?" in the game's top bar any time.
 */
object GameRules {
    fun of(type: GameType): Rules? = when (type) {
        GameType.CoupleQuiz -> Rules(
            tr("Give the same answer about the two of you."),
            tr("Ten questions about you two. You both answer at once, in a word or two or by picking one of you, then both answers show."),
            tr("You play as a team: a heart each time your answers match. If two answers mean the same to you, tap \"That counts!\"."),
            tr("Keep answers short. Capitals, accents and a \"the\" in front don't matter."),
        )
        GameType.MemoryMatch -> Rules(
            tr("Find more pairs than your partner."),
            tr("Twelve cards, six pairs. On your turn, turn over two cards. A pair is yours and you go again; a miss passes the turn."),
            tr("Most pairs wins."),
            tr("Watch your partner's misses: both cards stay face up until the next flip."),
        )
        GameType.ICallOn -> Rules(
            tr("Fill four categories with words that start with the round's letter."),
            tr("Three rounds, a new letter each. You both type at once; once one of you sends, the other has 15 seconds."),
            tr("An answer only you gave scores 2, one you both gave scores 1 each. Answers the app can't check go to your partner to judge."),
            tr("Keep typing to the end: what you've typed still counts when time runs out."),
        )
        GameType.GuessMyAnswer -> Rules(
            tr("Know how your partner will answer."),
            tr("Eight rounds, taking turns. One of you answers a question about yourself from four choices, then the other guesses."),
            tr("A point for every right guess. Most points wins."),
            tr("Answer honestly, not with what you think they'll guess."),
        )
        GameType.WordDash -> Rules(
            tr("Keep the word chain going longer than your partner."),
            tr("Take turns: each word starts with the last letter of the one before. No repeats, 20 seconds a turn. A word that isn't accepted can be tried again while time lasts."),
            tr("Run out of time and your partner scores. First to five wins."),
            tr("End on a tricky letter, like X or Z, and your partner has to think fast."),
        )
        GameType.Handwriting -> Rules(
            tr("Draw a secret word so your partner can guess it."),
            tr("Six rounds, taking turns to draw. Your partner watches the drawing appear and has three guesses. The drawer can pass."),
            tr("You play as a team: a right guess is a heart for each of you."),
            tr("Draw the big shape first; details can come later."),
        )
        GameType.TruthOrDare -> Rules(
            tr("Get to know each other, one truth or dare at a time."),
            tr("Ten turns, taking turns. Pick truth or dare and get a prompt; your partner marks it done or skipped."),
            tr("A point for each one done. Most points wins."),
            tr("Nobody is pushed: you can swap a prompt once a turn, or pass on it."),
        )
        GameType.WouldYouRather -> Rules(
            tr("Pick the same as your partner."),
            tr("Ten rounds. You both choose one of two options at once, and the picks show together."),
            tr("You play as a team: a heart each time you pick the same."),
            tr("The ones you split on are the best to talk about."),
        )
        GameType.TicTacToe -> Rules(
            tr("Get three in a row."),
            tr("There are no turns: you both draw at once, and a square goes to whoever gets there first. Your pen takes a moment to refill after each mark."),
            tr("Three in a row wins a game; first to three games takes the match."),
            tr("A tap on a square is quicker than drawing your mark."),
        )
        GameType.ConnectFour -> Rules(
            tr("Line up four of your discs."),
            tr("Take turns dropping a disc into one of seven columns. Who starts swaps each game."),
            tr("Four in a row in any direction wins a game. First to two games takes the match."),
            tr("Block your partner's three in a row before building your own."),
        )
        GameType.Trivia -> Rules(
            tr("Answer more questions right than your partner."),
            tr("Ten questions with four answers each. You both answer at once, then the right answer shows."),
            tr("A point for each right answer. Most points wins."),
            tr("There's no clock, so take your time."),
        )
        GameType.SkillDuel -> Rules(
            tr("Beat your partner at five tiny tests of a steady hand."),
            tr("You both play the same five rounds, each on your own time. Each is scored 0 to 100, and a round's two scores show once you've both played it."),
            tr("The higher score wins the round's heart; a tie is a heart each. More hearts wins, and if the hearts are level, more points wins."),
            tr("Backing out of a round deals you a new one, so play each one for real."),
        )
        GameType.SpotTheDifference -> Rules(
            tr("Find the differences before your partner does."),
            tr("Three pictures, seven differences each, 90 seconds a picture. You both look at once; tap a difference first to claim it."),
            tr("A point for each one you spot first. Most spots wins."),
            tr("A wrong tap pauses you for a moment, so look before you tap."),
        )
        GameType.SnakesAndLadders -> Rules(
            tr("Be first to reach square 100."),
            tr("Take turns rolling the die. Ladders take you up, snakes slide you down, and heart squares swap, boost, shield or set you back. A 6 rolls again; three 6s in a row send you back."),
            tr("Land on 100 exactly to win. Roll too many and you bounce back."),
            tr("A hug shield saves you from one snake."),
        )
        GameType.Unknown -> null
    }

    /** Played against a clock: a sheet opening by itself mid-round would cost the person time. */
    fun timed(type: GameType) = type == GameType.ICallOn || type == GameType.WordDash || type == GameType.SpotTheDifference || type == GameType.TicTacToe

    /**
     * Opens by itself the first time each person opens a game: while it waits to start (invited, or inviting), or once
     * it's on in a game without a clock. A timed game started first leaves it for next time, and the "?" any time.
     */
    fun autoShow(type: GameType, status: GameStatus, seen: Boolean): Boolean =
        !seen && of(type) != null && (status == GameStatus.Waiting || (status == GameStatus.InProgress && !timed(type)))
}

/** Which games' rules this person has seen on this phone. */
interface RulesSeen {
    fun seen(type: GameType): Boolean
    fun markSeen(type: GameType)

    /** Every game seen: nothing opens by itself (previews, screenshots). */
    object All : RulesSeen {
        override fun seen(type: GameType) = true
        override fun markSeen(type: GameType) {}
    }
}

/** Kept per person and per game, so a partner signing in on this phone sees the rules for themselves. */
class PrefsRulesSeen(context: Context, private val userId: String) : RulesSeen {
    private val prefs = context.getSharedPreferences("game_rules", Context.MODE_PRIVATE)
    private fun key(type: GameType) = "$userId:${type.name}"
    override fun seen(type: GameType) = prefs.getBoolean(key(type), false)
    override fun markSeen(type: GameType) = prefs.edit { putBoolean(key(type), true) }
}

/** In memory, for tests. */
class MemoryRulesSeen(seen: Set<GameType> = emptySet()) : RulesSeen {
    private val games = seen.toMutableSet()
    override fun seen(type: GameType) = type in games
    override fun markSeen(type: GameType) { games += type }
}

@Composable
fun rememberRulesSeen(userId: String): RulesSeen {
    val context = LocalContext.current
    return remember(userId) { PrefsRulesSeen(context.applicationContext, userId) }
}

/** The rules in a sheet from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesSheet(type: GameType, region: String?, onDismiss: () -> Unit) {
    val rules = GameRules.of(type) ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Heartpact.colors.sticker, shape = Shapes.Sheet) {
        RulesSheetContent(type, region, rules, onDismiss)
    }
}

/** What the sheet holds: the game, then goal, a turn, winning and a tip, and a button to get going. */
@Composable
fun RulesSheetContent(type: GameType, region: String?, rules: Rules, onDone: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val blob = type.blob
            androidx.compose.foundation.layout.Box(
                Modifier.size(52.dp).drawBehind { drawPath(blobPath(type.ordinal * 7 + 3, size), blob.fill) },
                contentAlignment = Alignment.Center,
            ) { GameIcon(type, blob.ink, 26.dp) }
            Column(Modifier.weight(1f)) {
                Text(tr("How to play"), style = Heartpact.type.small, color = colors.ink2)
                Text(GamePlay.title(type, region), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.semantics { heading() })
            }
        }
        Part(tr("The goal"), rules.goal, Modifier.padding(top = 20.dp))
        Part(tr("How a turn goes"), rules.turn, Modifier.padding(top = 14.dp))
        Part(tr("Winning"), rules.win, Modifier.padding(top = 14.dp))
        Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Heartpact.actor.tint).padding(14.dp)) {
            Text(tr("Tip"), style = Heartpact.type.label, color = Heartpact.actor.onSoft)
            Text(rules.tip, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
        }
        HeartButton(tr("Got it"), onDone, Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun Part(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Heartpact.type.label, color = Heartpact.colors.ink)
        Text(text, style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 3.dp))
    }
}
