package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/game and the couple hub's game events. State comes as a JSON string inside the session: parse it again. */

@Serializable
enum class GameType {
    @SerialName("coupleQuiz") CoupleQuiz, @SerialName("memoryMatch") MemoryMatch, @SerialName("iCallOn") ICallOn,
    @SerialName("guessMyAnswer") GuessMyAnswer, @SerialName("wordDash") WordDash, @SerialName("handwriting") Handwriting, @SerialName("truthOrDare") TruthOrDare,
    @SerialName("wouldYouRather") WouldYouRather, @SerialName("ticTacToe") TicTacToe, @SerialName("connectFour") ConnectFour, @SerialName("trivia") Trivia,
}

@Serializable
enum class GameStatus { @SerialName("waitingForPartner") Waiting, @SerialName("inProgress") InProgress, @SerialName("completed") Completed, @SerialName("abandoned") Abandoned, @SerialName("paused") Paused }

@Serializable
data class GameSession(
    val id: String,
    val coupleId: String = "",
    val gameType: GameType,
    val status: GameStatus,
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

@Serializable
data class QuizState(
    val players: List<String> = emptyList(),
    val scores: Map<String, Int> = emptyMap(),
    val round: Int = 0,
    val totalRounds: Int = 0,
    val turnIndex: Int = 0,
    val prompt: String = "",
    val answerCount: Int = 0,
    val lastResult: String? = null,
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
