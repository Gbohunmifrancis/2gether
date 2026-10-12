package app.heartpact.ui.games

import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.ICallOnState
import app.heartpact.data.MemoryState
import app.heartpact.data.QuizState
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
        GameType.CoupleQuiz -> "Same answer"
        GameType.MemoryMatch -> "Memory match"
        GameType.ICallOn -> "I call on"
        GameType.GuessMyAnswer -> "Guess my answer"
        GameType.WordDash -> "Word dash"
        GameType.Handwriting -> "Handwriting"
        GameType.TruthOrDare -> "Truth or dare"
    }

    fun blurb(type: GameType) = when (type) {
        GameType.CoupleQuiz -> "One question, two answers. A heart each time you both say the same thing."
        GameType.MemoryMatch -> "Twelve cards, six pairs, your turn then mine. Most pairs wins."
        GameType.ICallOn -> "A letter, four categories, 15 seconds. Judge each other's answers."
        else -> "Take turns answering."
    }

    fun outcome(s: GameSession, me: String, partner: String): String {
        if (s.status == GameStatus.Abandoned) return "Ended early"
        val scores = when (s.gameType) {
            GameType.MemoryMatch -> memory(s).scores
            GameType.ICallOn -> iCallOn(s).scores
            else -> return "${quiz(s).scores[me] ?: 0} matching answers"
        }
        val mine = scores[me] ?: 0
        val theirs = scores.entries.firstOrNull { it.key != me }?.value ?: 0
        return when {
            mine > theirs -> "You won, $mine to $theirs"
            theirs > mine -> "$partner won, $theirs to $mine"
            else -> "A draw, $mine each"
        }
    }

    /** Values 1 to 6 on memory cards become six stickers. */
    fun cardLabel(value: Int) = listOf("Heart", "Star", "Flower", "Sun", "Moon", "Cake").getOrElse(value - 1) { "?" }
}
