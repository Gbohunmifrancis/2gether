package app.heartpact.ui.games

import app.heartpact.data.DrawAndGuessState
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.GridMatchState
import app.heartpact.data.ICallOnState
import app.heartpact.data.MemoryState
import app.heartpact.data.QuizState
import app.heartpact.data.TruthOrDareState
import app.heartpact.data.WordDashState
import app.heartpact.data.TriviaState
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure: reading a session's state and saying what it means for the person holding the phone. */
object GamePlay {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun quiz(s: GameSession): QuizState = runCatching { json.decodeFromString(QuizState.serializer(), s.stateJson) }.getOrDefault(QuizState())
    fun memory(s: GameSession): MemoryState = runCatching { json.decodeFromString(MemoryState.serializer(), s.stateJson) }.getOrDefault(MemoryState())
    fun iCallOn(s: GameSession): ICallOnState = runCatching { json.decodeFromString(ICallOnState.serializer(), s.stateJson) }.getOrDefault(ICallOnState())
    fun truthOrDare(s: GameSession): TruthOrDareState = runCatching { json.decodeFromString(TruthOrDareState.serializer(), s.stateJson) }.getOrDefault(TruthOrDareState())
    fun wordDash(s: GameSession): WordDashState = runCatching { json.decodeFromString(WordDashState.serializer(), s.stateJson) }.getOrDefault(WordDashState())
    fun grid(s: GameSession): GridMatchState = runCatching { json.decodeFromString(GridMatchState.serializer(), s.stateJson) }.getOrDefault(GridMatchState())
    fun drawAndGuess(s: GameSession): DrawAndGuessState = runCatching { json.decodeFromString(DrawAndGuessState.serializer(), s.stateJson) }.getOrDefault(DrawAndGuessState())
    fun trivia(s: GameSession): TriviaState = runCatching { json.decodeFromString(TriviaState.serializer(), s.stateJson) }.getOrDefault(TriviaState())
    /** The session's content region from its setup: "ng" for Nigerian couples, else "global". */
    fun region(s: GameSession): String = runCatching { json.parseToJsonElement(s.setupJson).jsonObject["region"]?.jsonPrimitive?.content }.getOrNull() ?: "global"

    /** The games the app offers (docs/games.md). */
    val offered = listOf(GameType.CoupleQuiz, GameType.MemoryMatch, GameType.ICallOn, GameType.Trivia, GameType.GuessMyAnswer, GameType.WouldYouRather,
        GameType.TruthOrDare, GameType.WordDash, GameType.Handwriting, GameType.TicTacToe, GameType.ConnectFour)

    /** A session's name; Nigerian couples' trivia is Naija trivia. */
    fun title(s: GameSession) = title(s.gameType, region(s))

    fun title(type: GameType, region: String? = null) = when (type) {
        GameType.CoupleQuiz -> tr("Same answer")
        GameType.MemoryMatch -> tr("Memory match")
        GameType.ICallOn -> tr("I call on")
        GameType.GuessMyAnswer -> tr("Guess my answer")
        GameType.WordDash -> tr("Word dash")
        GameType.Handwriting -> tr("Draw and guess")
        GameType.TruthOrDare -> tr("Truth or dare")
        GameType.WouldYouRather -> tr("Would you rather")
        GameType.TicTacToe -> tr("Tic-tac-toe")
        GameType.ConnectFour -> tr("Connect four")
        GameType.Trivia -> if (region == "ng") tr("Naija trivia") else tr("Trivia")
    }

    /** The button that accepts an invite: one whole phrase per game. */
    fun play(type: GameType, region: String? = null) = when (type) {
        GameType.CoupleQuiz -> tr("Play Same answer")
        GameType.MemoryMatch -> tr("Play Memory match")
        GameType.ICallOn -> tr("Play I call on")
        GameType.GuessMyAnswer -> tr("Play Guess my answer")
        GameType.WordDash -> tr("Play Word dash")
        GameType.Handwriting -> tr("Play Draw and guess")
        GameType.TruthOrDare -> tr("Play Truth or dare")
        GameType.WouldYouRather -> tr("Play Would you rather")
        GameType.TicTacToe -> tr("Play Tic-tac-toe")
        GameType.ConnectFour -> tr("Play Connect four")
        GameType.Trivia -> if (region == "ng") tr("Play Naija trivia") else tr("Play Trivia")
    }

    fun blurb(type: GameType, region: String? = null) = when (type) {
        GameType.CoupleQuiz -> tr("One question, two answers. A heart each time you both say the same thing.")
        GameType.MemoryMatch -> tr("Twelve cards, six pairs, your turn then mine. Most pairs wins.")
        GameType.ICallOn -> tr("A letter, four categories, 15 seconds. Judge each other's answers.")
        GameType.TruthOrDare -> tr("Ten turns. Pick truth or dare, and your partner says if you did it. A point for each one done.")
        GameType.WordDash -> tr("A word chain against the clock: each word starts with the last letter of the one before. 20 seconds a turn, first to five.")
        GameType.GuessMyAnswer -> tr("Answer about yourself, then guess your partner's answers. A point for every right guess.")
        GameType.WouldYouRather -> tr("Two choices, you both pick at once. A heart each time you pick the same.")
        GameType.TicTacToe -> tr("Three in a row, your half of the heart against mine. First to win three games takes the match.")
        GameType.ConnectFour -> tr("Drop your discs and line up four. First to win two games takes the match.")
        GameType.Handwriting -> tr("One draws a secret word, the other has three guesses. Swap each round, six rounds.")
        GameType.Trivia -> if (region == "ng") tr("Ten questions on Naija music, Nollywood, food and more. You both answer, then see who got it.")
            else tr("Ten questions, four choices each. You both answer, then the right one shows.")
        else -> tr("Take turns answering.")
    }

    fun outcome(s: GameSession, me: String, partner: String): String {
        if (s.status == GameStatus.Abandoned) return tr("Ended early")
        val scores = when (s.gameType) {
            GameType.MemoryMatch -> memory(s).scores
            GameType.ICallOn -> iCallOn(s).scores
            GameType.TruthOrDare -> truthOrDare(s).scores
            GameType.WordDash -> wordDash(s).scores
            GameType.GuessMyAnswer -> guessMyAnswer(s).scores
            GameType.WouldYouRather -> return trn(wouldYouRather(s).scores[me] ?: 0, "{count} matching pick", "{count} matching picks")
            GameType.TicTacToe, GameType.ConnectFour -> grid(s).scores
            GameType.Handwriting -> return trn(drawAndGuess(s).scores[me] ?: 0, "{count} drawing guessed", "{count} drawings guessed")
            GameType.Trivia -> trivia(s).scores
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

    /** Values 1 to 6 on memory cards become six stickers; Nigerian couples' cards (theme "naija") show Naija ones. */
    fun cardLabel(value: Int, theme: String? = null) = (if (theme == "naija") listOf(tr("Jollof"), tr("Suya"), tr("Talking drum"), tr("Danfo"), tr("Palm tree"), tr("Mortar and pestle"))
        else listOf(tr("Heart"), tr("Star"), tr("Flower"), tr("Sun"), tr("Moon"), tr("Cake"))).getOrElse(value - 1) { "?" }
}
