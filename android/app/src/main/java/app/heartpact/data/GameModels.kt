package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/game and the couple hub's game events. State comes as a JSON string inside the session: parse it again. */

@Serializable
enum class GameType {
    @SerialName("coupleQuiz") CoupleQuiz, @SerialName("memoryMatch") MemoryMatch, @SerialName("iCallOn") ICallOn,
    @SerialName("guessMyAnswer") GuessMyAnswer, @SerialName("wordDash") WordDash, @SerialName("handwriting") Handwriting, @SerialName("truthOrDare") TruthOrDare,
    @SerialName("wouldYouRather") WouldYouRather, @SerialName("ticTacToe") TicTacToe, @SerialName("connectFour") ConnectFour, @SerialName("trivia") Trivia,
    /** Heart to heart: the same skill rounds for both, scores side by side (ui/play). */
    @SerialName("skillDuel") SkillDuel,
    /** Spot the difference: the same pictures for both, at the same time; most spots wins. */
    @SerialName("spotTheDifference") SpotTheDifference,
    /** Snakes and ladders: the server rolls the die; climb, slide, first to 100 wins. */
    @SerialName("snakesAndLadders") SnakesAndLadders,
    /** A game this version of the app doesn't know (a newer server's): listed and endable, never played, so nothing breaks. */
    @SerialName("unknown") Unknown,
}

@Serializable
enum class GameStatus { @SerialName("waitingForPartner") Waiting, @SerialName("inProgress") InProgress, @SerialName("completed") Completed, @SerialName("abandoned") Abandoned, @SerialName("paused") Paused }

@Serializable
data class GameSession(
    val id: String,
    val coupleId: String = "",
    /** A type this app doesn't know reads as [GameType.Unknown] (coerced to this default). */
    val gameType: GameType = GameType.Unknown,
    /** A status this app doesn't know (a newer server's) reads as ended, so the list still loads. */
    val status: GameStatus = GameStatus.Abandoned,
    val startedAtUtc: String? = null,
    val endedAtUtc: String? = null,
    val currentTurnUserId: String? = null,
    val deadlineUtc: String? = null,
    val stateVersion: Long = 0,
    val stateJson: String = "{}",
    val invitedByUserId: String? = null,
    val inviteAcceptedAtUtc: String? = null,
    val countdownEndsAtUtc: String? = null,
    val endedByUserId: String? = null,
    val setupJson: String = "{}",
) {
    val open: Boolean get() = status == GameStatus.Waiting || status == GameStatus.InProgress || status == GameStatus.Paused
}

@Serializable
data class CreateGameRequest(val gameType: GameType, val letter: String? = null)

@Serializable
data class GameActionRequest(val expectedStateVersion: Long, val action: String, val value: String? = null, val index: Int? = null)

/**
 * Same answer's state. [kind] is "who" for a prompt answered by picking one of you two (the answer is that partner's
 * id), else "text". [mine] is my own answer this round, [answered] who has answered; both answers show in [last] once
 * both are in, and every round played in [history].
 */
@Serializable
data class QuizState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 0,
    val turnIndex: Int = 0,
    val prompt: String = "",
    val kind: String? = null,
    val answerCount: Int = 0,
    val answered: List<String> = emptyList(),
    val mine: String? = null,
    val lastResult: String? = null,
    val last: QuizReveal? = null,
    val history: List<QuizReveal> = emptyList(),
)

/** A Same answer round once both are in: the prompt and each partner's answer (a partner's id for a "who" prompt). */
@Serializable
data class QuizReveal(
    val round: Int = 0,
    val prompt: String = "",
    val kind: String = "text",
    val answers: Map<String, String> = emptyMap(),
    val match: Boolean = false,
)

@Serializable
data class MemoryCardState(val index: Int, val value: Int? = null, val matched: Boolean = false)

@Serializable
data class MemoryFlip(val index: Int, val value: Int)

@Serializable
data class MemoryState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val cards: List<MemoryCardState> = emptyList(),
    val revealed: List<Int> = emptyList(),
    val lastResult: String? = null,
    val lastPair: List<MemoryFlip>? = null,
    /** The pictures on the cards: null for the classic six, "naija" for Nigerian couples. */
    val theme: String? = null,
)

@Serializable
data class VettingItem(val player: String, val judge: String, val category: String, val answer: String, val allowed: Boolean? = null)

@Serializable
data class RevealEntry(val player: String, val category: String, val answer: String = "", val counted: Boolean = false, val points: Int = 0)

@Serializable
data class RoundReveal(val round: Int = 0, val letter: String = "", val entries: List<RevealEntry> = emptyList(), val roundScores: Map<String, Int> = emptyMap())

@Serializable
data class ICallOnState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 3,
    val letter: String = "",
    val categories: List<String> = emptyList(),
    val phase: String = "answering",
    val submitted: List<String> = emptyList(),
    val pendingVetting: List<VettingItem> = emptyList(),
    val reveal: RoundReveal? = null,
    val roundWinnerUserId: String? = null,
    val winnerUserId: String? = null,
    val lastResult: String? = null,
)

@Serializable
data class VetVerdict(val player: String, val category: String, val allow: Boolean)

@Serializable
data class TruthOrDareOutcome(
    val chooser: String,
    val choice: String,
    val prompt: String = "",
    val result: String = "done",
    /** The chooser passed on the prompt themselves (result "skipped"). */
    val passed: Boolean = false,
)

@Serializable
data class TruthOrDareState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val turn: Int = 0,
    val totalTurns: Int = 10,
    /** "choosing", "judging" or "completed". */
    val phase: String = "choosing",
    val chooser: String? = null,
    val judge: String? = null,
    /** "truth" or "dare" once chosen. */
    val choice: String? = null,
    val prompt: String? = null,
    /** The chooser has swapped this turn's prompt (once a turn). */
    val swapped: Boolean = false,
    val last: TruthOrDareOutcome? = null,
)

@Serializable
data class WordLink(val player: String, val word: String)

@Serializable
data class WordDashState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    /** The letter the next word must start with; null once the game is over. */
    val letter: String? = null,
    val startLetter: String = "",
    val chain: List<WordLink> = emptyList(),
    val chainNumber: Int = 1,
    val maxChains: Int = 15,
    val target: Int = 5,
    val turnSeconds: Int = 20,
    val phase: String = "playing",
    /** "timeout" right after a chain broke. */
    val lastResult: String? = null,
    val timedOut: String? = null,
    val lastChain: List<String>? = null,
    val winnerUserId: String? = null,
)

/** Guess my answer's last round, shown to both once the guess is in. */
@Serializable
data class GuessReveal(
    val round: Int = 0,
    val subject: String = "",
    val guesser: String = "",
    val question: String = "",
    val choices: List<String> = emptyList(),
    val answer: Int = 0,
    val guess: Int = 0,
    val right: Boolean = false,
)

@Serializable
data class GuessMyAnswerState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 8,
    val phase: String = "answering",
    val subject: String? = null,
    val guesser: String? = null,
    val question: String? = null,
    val choices: List<String> = emptyList(),
    val answered: Boolean = false,
    /** The subject's own answer while their partner guesses; null for the guesser. */
    val answer: Int? = null,
    val last: GuessReveal? = null,
    /** Every round played so far, for the recap at the end. */
    val history: List<GuessReveal> = emptyList(),
)

/** Would you rather's last round: both picks (0 for a, 1 for b), shown once both are in. */
@Serializable
data class RatherReveal(val round: Int = 0, val a: String = "", val b: String = "", val picks: Map<String, Int> = emptyMap(), val match: Boolean = false)

@Serializable
data class WouldYouRatherState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 10,
    val phase: String = "picking",
    val a: String? = null,
    val b: String? = null,
    val picked: List<String> = emptyList(),
    val myPick: Int? = null,
    val last: RatherReveal? = null,
    /** Every round played so far, for the recap at the end. */
    val history: List<RatherReveal> = emptyList(),
)

/**
 * Connect four (and tic-tac-toe's scores, which read the same): a short match of games on a grid. [cells] go row by row from the top and hold the
 * player id that owns each one, or null. [phase] is "playing", "gameOver" (the finished board, with its [line], until
 * the next starter moves) or "matchOver". [results] holds each finished game's winner, null for a draw.
 */
@Serializable
data class GridMatchState(
    val players: List<String> = emptyList(),
    val columns: Int = 3,
    val rows: Int = 3,
    val cells: List<String?> = emptyList(),
    val game: Int = 0,
    val totalGames: Int = 1,
    val winsNeeded: Int = 1,
    val phase: String = "playing",
    val lastMove: Int? = null,
    val line: List<Int>? = null,
    val scores: Map<String, Int> = emptyMap(),
    val draws: Int = 0,
    val results: List<String?> = emptyList(),
    val winnerUserId: String? = null,
)

/**
 * Tic-tac-toe, played at once (docs/games.md): the grid match's fields, plus each square's hand-drawn mark ([marks],
 * null for an empty square or one placed before marks were drawn), when the next game starts while a finished board
 * is up ([nextGameAtUtc]), and how long a pen takes to refill after its own mark ([cooldownMs]).
 */
@Serializable
data class TicTacToeState(
    val players: List<String> = emptyList(),
    val cells: List<String?> = emptyList(),
    val game: Int = 0,
    val totalGames: Int = 5,
    val winsNeeded: Int = 3,
    val phase: String = "playing",
    val lastMove: Int? = null,
    val line: List<Int>? = null,
    val scores: Map<String, Int> = emptyMap(),
    val draws: Int = 0,
    val results: List<String?> = emptyList(),
    val winnerUserId: String? = null,
    val marks: List<TicTacToeMark?> = emptyList(),
    val nextGameAtUtc: String? = null,
    val cooldownMs: Int = 650,
)

/** A hand-drawn mark: up to three strokes, each point `[x, y]` as fractions of its square. */
@Serializable
data class TicTacToeMark(val strokes: List<TicTacToeStroke> = emptyList())

@Serializable
data class TicTacToeStroke(val points: List<List<Double>> = emptyList())

/** Draw and guess (`handwriting` on the wire): one stroke, coordinates as fractions of the canvas (0 to 1). */
@Serializable
data class DrawStroke(val points: List<List<Double>> = emptyList(), val width: Double = 0.012)

/** A finished round, shown to both: the word, how it went and the drawing. */
@Serializable
data class DrawRound(
    val round: Int = 0,
    val drawer: String = "",
    val word: String = "",
    /** "guessed", "missed" or "passed". */
    val outcome: String = "",
    val guess: String? = null,
    val strokes: List<DrawStroke> = emptyList(),
    val wrongGuesses: List<String> = emptyList(),
)

@Serializable
data class DrawAndGuessState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 6,
    /** "drawing" or "completed". */
    val phase: String = "drawing",
    val drawer: String? = null,
    val guesser: String? = null,
    /** The word to draw: only the drawer gets it. */
    val word: String? = null,
    val strokes: List<DrawStroke> = emptyList(),
    val wrongGuesses: List<String> = emptyList(),
    val guessesLeft: Int = 3,
    val maxGuesses: Int = 3,
    val lastRound: DrawRound? = null,
)

/** A trivia question as shown: text and four options, never the answer until both have picked. */
@Serializable
data class TriviaQuestionView(val text: String = "", val options: List<String> = emptyList())

/** The last question both answered: the right option and what each partner picked. */
@Serializable
data class TriviaReveal(val round: Int = 0, val question: TriviaQuestionView = TriviaQuestionView(), val answer: Int = 0, val picks: Map<String, Int> = emptyMap())

@Serializable
data class TriviaState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 10,
    val question: TriviaQuestionView? = null,
    /** Who has answered the current question; their picks stay hidden until both have. */
    val answered: List<String> = emptyList(),
    val myAnswer: Int? = null,
    val reveal: TriviaReveal? = null,
    /** Every question both answered so far, for the recap at the end. */
    val history: List<TriviaReveal> = emptyList(),
    val completed: Boolean = false,
)

/**
 * One Heart to heart round as the person holding the phone sees it: which skill game and its seed (the same for both),
 * their own score, and the partner's only once they've played it too ([played] says the partner has).
 */
@Serializable
data class DuelRound(
    val index: Int = 0,
    /** A `SkillKind` name (ui/play). */
    val kind: String = "",
    val seed: Long = 0,
    val mine: Int? = null,
    val partner: Int? = null,
    val played: Boolean = false,
    /** Whoever scored higher, once both have played; null for a tie. */
    val winner: String? = null,
    val tie: Boolean = false,
) {
    /** Both scores are in and showing. */
    val revealed: Boolean get() = mine != null && partner != null
}

@Serializable
data class SkillDuelState(
    val players: List<String> = emptyList(),
    val phase: String = "playing",
    val totalRounds: Int = 5,
    val rounds: List<DuelRound> = emptyList(),
    /** Rounds won over the rounds both have played; a tie is a heart each. */
    val hearts: Map<String, Int> = emptyMap(),
    /** Each partner's points, once both have played every round. */
    val totals: Map<String, Int>? = null,
    /** More hearts, or the higher total when the hearts are level; null until the end, and for a perfect tie. */
    val winner: String? = null,
    /** Level on hearts and on points. */
    val tie: Boolean = false,
    /** What settled it: "hearts", or "points" when the hearts were level; null for a tie and until the end. */
    val decidedBy: String? = null,
    /** The difference in hearts, at the end. */
    val margin: Int? = null,
    /** The difference in points, at the end. */
    val pointMargin: Int? = null,
) {
    val finished: Boolean get() = totals != null
}

/** Spot the difference: one difference found, and by whom. */
@Serializable
data class SpotFind(val index: Int = 0, val by: String = "")

/** One picture of a Spot the difference game: its kind and seed (the phone draws it), who found what, and whether time ran out. */
@Serializable
data class SpotPicture(val index: Int = 0, val kind: String = "", val seed: Int = 0, val found: List<SpotFind> = emptyList(), val timedOut: Boolean = false)

@Serializable
data class SpotTheDifferenceState(
    val players: List<String> = emptyList(),
    /** "spotting", "between" (a short beat before the next picture) or "completed". */
    val phase: String = "spotting",
    val scene: Int = 0,
    val totalScenes: Int = 3,
    val differences: Int = 7,
    val sceneSeconds: Int = 90,
    val beatSeconds: Int = 3,
    val kind: String? = null,
    val seed: Int = 0,
    val found: List<SpotFind> = emptyList(),
    /** The pictures so far, the one in play last. */
    val scenes: List<SpotPicture> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    /** More spots wins; null for a tie and until the end. */
    val winner: String? = null,
    val tie: Boolean = false,
) {
    val finished: Boolean get() = phase == "completed"
}

/** Snakes and ladders: a ladder (bottom to top) or a snake (head to tail). */
@Serializable
data class SnakesLink(val from: Int = 0, val to: Int = 0)

/** A heart square: "swap" (kiss & swap), "boost" (+3), "shield" (hug shield) or "heartbreak" (back 5). */
@Serializable
data class SnakesHeart(val square: Int = 0, val kind: String = "")

@Serializable
data class SnakesBoardLayout(val ladders: List<SnakesLink> = emptyList(), val snakes: List<SnakesLink> = emptyList(), val hearts: List<SnakesHeart> = emptyList())

/**
 * One thing a roll did, in order: "ladder", "snake", "shield" (a snake cancelled), "hug" (a shield gained), "boost",
 * "heartbreak", "swap" (you go to [to], your partner to [from]), "kiss", "bounce" (off 100), "threeSixes", "win".
 */
@Serializable
data class SnakesEvent(val kind: String = "", val from: Int = 0, val to: Int = 0)

/** The last roll in full, so both phones replay it: the squares hopped one by one, then the events. */
@Serializable
data class SnakesMove(
    val move: Int = 0,
    val player: String = "",
    val roll: Int = 1,
    val from: Int = 0,
    val steps: List<Int> = emptyList(),
    val events: List<SnakesEvent> = emptyList(),
    val to: Int = 0,
    val extraTurn: Boolean = false,
    val sixes: Int = 0,
)

@Serializable
data class SnakesStats(val rolls: Int = 0, val ladders: Int = 0, val snakes: Int = 0, val sixes: Int = 0, val swaps: Int = 0, val saves: Int = 0)

@Serializable
data class SnakesState(
    val players: List<String> = emptyList(),
    /** "playing" or "completed". */
    val phase: String = "playing",
    val goal: Int = 100,
    val first: String? = null,
    /** Whose roll it is; null once it's over. */
    val turn: String? = null,
    val board: SnakesBoardLayout = SnakesBoardLayout(),
    /** Each player's square: 0 is off the board, before square 1. */
    val positions: Map<String, Int> = emptyMap(),
    val shields: Map<String, Boolean> = emptyMap(),
    val stats: Map<String, SnakesStats> = emptyMap(),
    val sixes: Int = 0,
    val moves: Int = 0,
    val last: SnakesMove? = null,
    val winner: String? = null,
) {
    val finished: Boolean get() = phase == "completed"
}

/** Whose versions of the games the couple plays: "ng" for Nigerian couples (Naija content), else "global". */
@Serializable
data class GameRegionView(val region: String = "global")
