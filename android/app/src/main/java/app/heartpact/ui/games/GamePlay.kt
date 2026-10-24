package app.heartpact.ui.games

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.ICallOnState
import app.heartpact.data.MemoryState
import app.heartpact.data.QuizState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.serialization.json.Json

/** Pure: reading a session's state and saying what it means for the person holding the phone. */
object GamePlay {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun quiz(s: GameSession): QuizState = runCatching { json.decodeFromString(QuizState.serializer(), s.stateJson) }.getOrDefault(QuizState())
    fun memory(s: GameSession): MemoryState = runCatching { json.decodeFromString(MemoryState.serializer(), s.stateJson) }.getOrDefault(MemoryState())
    fun iCallOn(s: GameSession): ICallOnState = runCatching { json.decodeFromString(ICallOnState.serializer(), s.stateJson) }.getOrDefault(ICallOnState())

    /** The three games the app offers; the server's other types play like the quiz. */
    val offered = listOf(GameType.CoupleQuiz, GameType.MemoryMatch, GameType.ICallOn)

    fun title(type: GameType) = when (type) {
        GameType.CoupleQuiz -> tr("Same answer")
        GameType.MemoryMatch -> tr("Memory match")
        GameType.ICallOn -> tr("I call on")
        GameType.GuessMyAnswer -> tr("Guess my answer")
        GameType.WordDash -> tr("Word dash")
        GameType.Handwriting -> tr("Handwriting")
        GameType.TruthOrDare -> tr("Truth or dare")
        GameType.WouldYouRather -> tr("Would you rather")
        GameType.TicTacToe -> tr("Tic-tac-toe")
        GameType.ConnectFour -> tr("Connect four")
        GameType.Trivia -> tr("Trivia")
    }

    /** The button that accepts an invite: one whole phrase per game. */
    fun play(type: GameType) = when (type) {
        GameType.CoupleQuiz -> tr("Play Same answer")
        GameType.MemoryMatch -> tr("Play Memory match")
        GameType.ICallOn -> tr("Play I call on")
        GameType.GuessMyAnswer -> tr("Play Guess my answer")
        GameType.WordDash -> tr("Play Word dash")
        GameType.Handwriting -> tr("Play Handwriting")
        GameType.TruthOrDare -> tr("Play Truth or dare")
        GameType.WouldYouRather -> tr("Play Would you rather")
        GameType.TicTacToe -> tr("Play Tic-tac-toe")
        GameType.ConnectFour -> tr("Play Connect four")
        GameType.Trivia -> tr("Play Trivia")
    }

    fun blurb(type: GameType) = when (type) {
        GameType.CoupleQuiz -> tr("One question, two answers. A heart each time you both say the same thing.")
        GameType.MemoryMatch -> tr("Twelve cards, six pairs, your turn then mine. Most pairs wins.")
        GameType.ICallOn -> tr("A letter, four categories, 15 seconds. Judge each other's answers.")
        else -> tr("Take turns answering.")
    }

    fun outcome(s: GameSession, me: String, partner: String): String {
        if (s.status == GameStatus.Abandoned) return tr("Ended early")
        val scores = when (s.gameType) {
            GameType.MemoryMatch -> memory(s).scores
            GameType.ICallOn -> iCallOn(s).scores
            else -> return trn(quiz(s).scores[me] ?: 0, "{count} matching answer", "{count} matching answers")
        }
        val mine = scores[me] ?: 0
        val theirs = scores.entries.firstOrNull { it.key != me }?.value ?: 0
        return when {
            mine > theirs -> tr("You won, {mine} to {theirs}", "mine" to mine, "theirs" to theirs)
            theirs > mine -> tr("{partner} won, {theirs} to {mine}", "partner" to partner, "theirs" to theirs, "mine" to mine)
            else -> tr("A draw, {score} each", "score" to mine)
        }
    }

    /** Values 1 to 6 on memory cards become six stickers. */
    fun cardLabel(value: Int) = listOf(tr("Heart"), tr("Star"), tr("Flower"), tr("Sun"), tr("Moon"), tr("Cake")).getOrElse(value - 1) { "?" }
}
