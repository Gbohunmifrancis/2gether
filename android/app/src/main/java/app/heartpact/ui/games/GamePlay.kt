package app.heartpact.ui.games

import app.heartpact.data.DrawAndGuessState
import app.heartpact.data.GameSession
import app.heartpact.data.GameStatus
import app.heartpact.data.GameType
import app.heartpact.data.GridMatchState
import app.heartpact.data.ICallOnState
import app.heartpact.data.MemoryState
import app.heartpact.data.QuizState
import app.heartpact.data.SkillDuelState
import app.heartpact.data.TicTacToeState
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
    fun ticTacToe(s: GameSession): TicTacToeState = runCatching { json.decodeFromString(TicTacToeState.serializer(), s.stateJson) }.getOrDefault(TicTacToeState())
    fun grid(s: GameSession): GridMatchState = runCatching { json.decodeFromString(GridMatchState.serializer(), s.stateJson) }.getOrDefault(GridMatchState())
    fun drawAndGuess(s: GameSession): DrawAndGuessState = runCatching { json.decodeFromString(DrawAndGuessState.serializer(), s.stateJson) }.getOrDefault(DrawAndGuessState())
    fun trivia(s: GameSession): TriviaState = runCatching { json.decodeFromString(TriviaState.serializer(), s.stateJson) }.getOrDefault(TriviaState())
    fun skillDuel(s: GameSession): SkillDuelState = runCatching { json.decodeFromString(SkillDuelState.serializer(), s.stateJson) }.getOrDefault(SkillDuelState())
    /** The session's content region from its setup: "ng" for Nigerian couples, else "global". */
    fun region(s: GameSession): String = runCatching { json.parseToJsonElement(s.setupJson).jsonObject["region"]?.jsonPrimitive?.content }.getOrNull() ?: "global"

    /** The games the app offers (docs/games.md). */
    val offered = listOf(GameType.SkillDuel, GameType.SpotTheDifference, GameType.SnakesAndLadders, GameType.CoupleQuiz, GameType.MemoryMatch, GameType.ICallOn, GameType.Trivia, GameType.GuessMyAnswer, GameType.WouldYouRather,
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
        GameType.SkillDuel -> tr("Heart to heart")
        GameType.SpotTheDifference -> tr("Spot the difference")
        GameType.SnakesAndLadders -> tr("Snakes and ladders")
        GameType.Unknown -> tr("A new game")
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
        GameType.SkillDuel -> tr("Play Heart to heart")
        GameType.SpotTheDifference -> tr("Play Spot the difference")
        GameType.SnakesAndLadders -> tr("Play Snakes and ladders")
        GameType.Unknown -> tr("Join the game")
    }

    fun blurb(type: GameType, region: String? = null) = when (type) {
        GameType.CoupleQuiz -> tr("One question, two answers. A heart each time you both say the same thing.")
        GameType.MemoryMatch -> tr("Twelve cards, six pairs, your turn then mine. Most pairs wins.")
        GameType.ICallOn -> tr("A letter, four categories, 15 seconds. Judge each other's answers.")
        GameType.TruthOrDare -> tr("Ten turns. Pick truth or dare, and your partner says if you did it. A point for each one done.")
        GameType.WordDash -> tr("A word chain against the clock: each word starts with the last letter of the one before. 20 seconds a turn, first to five.")
        GameType.GuessMyAnswer -> tr("Answer about yourself, then guess your partner's answers. A point for every right guess.")
        GameType.WouldYouRather -> tr("Two choices, you both pick at once. A heart each time you pick the same.")
        GameType.TicTacToe -> tr("No turns: you both draw at once, and a square goes to whoever gets there first. Three in a row wins; first to three games takes the match.")
        GameType.ConnectFour -> tr("Drop your discs and line up four. First to win two games takes the match.")
        GameType.Handwriting -> tr("One draws a secret word, the other has three guesses. Swap each round, six rounds.")
        GameType.Trivia -> if (region == "ng") tr("Ten questions on Naija music, Nollywood, food and more. You both answer, then see who got it.")
            else tr("Ten questions, four choices each. You both answer, then the right one shows.")
        GameType.SkillDuel -> tr("The same five skill rounds for both of you, each on your own time. Your scores show side by side.")
        GameType.SpotTheDifference -> tr("Two pictures, seven differences, both of you hunting at once. Tap one first to claim it; most spots wins.")
        GameType.SnakesAndLadders -> tr("Roll, climb, slide. First to 100 wins.")
        GameType.Unknown -> tr("This game needs a newer Heartpact. Update the app to play it.")
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
            GameType.SpotTheDifference -> return spotOutcome(spot(s), me, partner)
            GameType.SnakesAndLadders -> return snakesOutcome(snakes(s), me, partner)
            GameType.SkillDuel -> skillDuel(s).let { d -> if (d.totals != null) return duelOutcome(d, me, partner) else d.hearts }
            // A newer game's scores can't be read here: say only that it was played.
            GameType.Unknown -> return tr("Played")
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

    /** How a finished duel was settled: on hearts, on points when the hearts were level, or a tie on both. */
    enum class DuelDecision { Hearts, Points, Tie }

    fun duelDecision(d: SkillDuelState): DuelDecision = when {
        d.winner == null -> DuelDecision.Tie
        d.decidedBy == "points" -> DuelDecision.Points
        d.decidedBy == null && d.hearts.values.distinct().size == 1 -> DuelDecision.Points
        else -> DuelDecision.Hearts
    }

    /** A finished duel is won on hearts; the points speak only when the hearts were level. Before the end, the hearts so far. */
    private fun duelOutcome(d: SkillDuelState, me: String, partner: String): String {
        val mine = d.hearts[me] ?: 0
        val theirs = d.hearts.entries.firstOrNull { it.key != me }?.value ?: 0
        val by = d.margin ?: kotlin.math.abs(mine - theirs)
        val iWon = d.winner == me
        return when (duelDecision(d)) {
            DuelDecision.Hearts -> if (iWon) trn(by, "You won the duel by {count} heart", "You won the duel by {count} hearts")
                else trn(by, "{partner} won the duel by {count} heart", "{partner} won the duel by {count} hearts", "partner" to partner)
            DuelDecision.Points -> if (iWon) tr("Level on hearts, you won on points") else tr("Level on hearts, {partner} won on points", "partner" to partner)
            DuelDecision.Tie -> trn(mine, "A perfect tie, {count} heart each", "A perfect tie, {count} hearts each")
        }
    }

    /** The line under the result's headline: by how many hearts or, only when the hearts were level, by how many points. */
    fun duelMargin(d: SkillDuelState, me: String): String {
        val mine = d.hearts[me] ?: 0
        val theirs = d.hearts.entries.firstOrNull { it.key != me }?.value ?: 0
        val points = d.totals.orEmpty()
        val pointGap = d.pointMargin ?: kotlin.math.abs((points[me] ?: 0) - (points.entries.firstOrNull { it.key != me }?.value ?: 0))
        return when (duelDecision(d)) {
            DuelDecision.Hearts -> trn(d.margin ?: kotlin.math.abs(mine - theirs), "by {count} heart", "by {count} hearts")
            DuelDecision.Points -> trn(pointGap, "Level on hearts, won by {count} point", "Level on hearts, won by {count} points")
            DuelDecision.Tie -> tr("Level on hearts and on points")
        }
    }

    /**
     * I call on's categories arrive as English keys ("Name", "Place", "Thing", "Food", "Animal") and go back to the
     * server unchanged; what's shown is one whole phrase per category, so each language can word it its own way.
     */
    fun categoryLabel(category: String) = when (category) {
        "Name" -> tr("name")
        "Place" -> tr("place")
        "Thing" -> tr("thing")
        "Food" -> tr("food")
        "Animal" -> tr("animal")
        else -> category
    }

    fun categoryField(category: String, letter: String) = when (category) {
        "Name" -> tr("A name starting with {letter}", "letter" to letter)
        "Place" -> tr("A place starting with {letter}", "letter" to letter)
        "Thing" -> tr("A thing starting with {letter}", "letter" to letter)
        "Food" -> tr("A food starting with {letter}", "letter" to letter)
        "Animal" -> tr("An animal starting with {letter}", "letter" to letter)
        else -> tr("{category} starting with {letter}", "category" to category, "letter" to letter)
    }

    fun categoryVet(category: String, partner: String, answer: String) = when (category) {
        "Name" -> tr("{partner} said \"{answer}\" for a name", "partner" to partner, "answer" to answer)
        "Place" -> tr("{partner} said \"{answer}\" for a place", "partner" to partner, "answer" to answer)
        "Thing" -> tr("{partner} said \"{answer}\" for a thing", "partner" to partner, "answer" to answer)
        "Food" -> tr("{partner} said \"{answer}\" for a food", "partner" to partner, "answer" to answer)
        "Animal" -> tr("{partner} said \"{answer}\" for an animal", "partner" to partner, "answer" to answer)
        else -> tr("{partner} said \"{answer}\" for {category}", "partner" to partner, "answer" to answer, "category" to category)
    }

    /** Values 1 to 6 on memory cards become six stickers; Nigerian couples' cards (theme "naija") show Naija ones. */
    fun cardLabel(value: Int, theme: String? = null) = (if (theme == "naija") listOf(tr("Jollof"), tr("Suya"), tr("Talking drum"), tr("Danfo"), tr("Palm tree"), tr("Mortar and pestle"))
        else listOf(tr("Heart"), tr("Star"), tr("Flower"), tr("Sun"), tr("Moon"), tr("Cake"))).getOrElse(value - 1) { "?" }
}
