package app.heartpact.data

import app.heartpact.exercise.ExerciseType
import app.heartpact.exercise.Measure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** api/ai (docs/ai.md): which AI features are on for this person, and what is left of today's caps. Off by default: a failed call hides them. */
@Serializable
data class AiStatus(
    val dareIdeas: Boolean = false,
    val coach: Boolean = false,
    val weeklyRecap: Boolean = false,
    val planner: Boolean = false,
    val dareIdeasLeft: Int = 0,
    val plansLeft: Int = 0,
)

@Serializable
data class DareIdeasRequest(val assignedUserId: String)

/** One suggested dare. An exercise this app doesn't know reads as null and is left out. */
@Serializable
data class DareIdea(
    val exerciseType: ExerciseType? = null,
    val target: Int,
    val measure: Measure = Measure.Reps,
    /** "HH:mm", 24-hour. */
    val scheduledTime: String,
    val reason: String,
)

@Serializable
data class DareIdeas(val ideas: List<DareIdea> = emptyList(), val left: Int = 0)

/** The coach's note on a finished dare: [state] is "ready" (with [text]), "pending" (being written) or "unavailable". */
@Serializable
data class CoachNote(val occurrenceId: String = "", val state: String = "unavailable", val text: String? = null, val userId: String = "")

@Serializable
data class WeeklyRecapStats(
    val heartsMade: Int = 0,
    val myDaresDone: Int = 0,
    val partnerDaresDone: Int = 0,
    val daresMissed: Int = 0,
    val myStreakDays: Int = 0,
    val partnerStreakDays: Int = 0,
    val gamesPlayed: Int = 0,
    val myGamesWon: Int = 0,
    val partnerGamesWon: Int = 0,
    val photosAdded: Int = 0,
    val questionsAnswered: Int = 0,
)

/** The couple's week, written for me in my language. */
@Serializable
data class WeeklyRecap(
    val id: String,
    /** "yyyy-MM-dd": the Sunday the week ended on. */
    val weekEnding: String,
    val periodStartUtc: String,
    val periodEndUtc: String,
    val title: String,
    val lines: List<String> = emptyList(),
    val suggestion: String = "",
    val stats: WeeklyRecapStats = WeeklyRecapStats(),
)

@Serializable
enum class PlanMood { @SerialName("cosy") Cosy, @SerialName("active") Active, @SerialName("adventurous") Adventurous, @SerialName("romantic") Romantic }

@Serializable
enum class PlanBudget { @SerialName("free") Free, @SerialName("low") Low, @SerialName("treat") Treat }

@Serializable
enum class PlanSetting { @SerialName("in") In, @SerialName("out") Out }

@Serializable
data class PlanRequest(val mood: PlanMood, val budget: PlanBudget, val setting: PlanSetting, val city: String? = null)

@Serializable
data class DateIdea(val title: String, val why: String)

/** A game to play tonight. One this app doesn't know reads as [GameType.Unknown] and is left out. */
@Serializable
data class GameIdea(val gameType: GameType = GameType.Unknown, val why: String)

@Serializable
data class DatePlan(val dates: List<DateIdea> = emptyList(), val games: List<GameIdea> = emptyList(), val left: Int = 0)

class AiRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun status(): ApiResult<AiStatus> = apiCall(json) { api.aiStatus() }

    suspend fun dareIdeas(assignedUserId: String): ApiResult<DareIdeas> = when (val result = apiCall(json) { api.dareIdeas(DareIdeasRequest(assignedUserId)) }) {
        is ApiResult.Ok -> ApiResult.Ok(result.value.copy(ideas = result.value.ideas.filter { it.exerciseType != null }))
        else -> result
    }

    suspend fun coach(occurrenceId: String): ApiResult<CoachNote> = apiCall(json) { api.coachNote(occurrenceId) }

    /** The latest recap, or null when there is none this week (the API answers 204). */
    suspend fun recap(): ApiResult<WeeklyRecap?> = when (val result = apiCall(json) { api.weeklyRecap() }) {
        // A 204 has no body; read the value without a cast to the type, which would fail on it.
        is ApiResult.Ok -> ApiResult.Ok((result as ApiResult.Ok<*>).value as? WeeklyRecap)
        is ApiResult.Failure -> result
        ApiResult.Offline -> ApiResult.Offline
    }

    suspend fun plan(request: PlanRequest): ApiResult<DatePlan> = when (val result = apiCall(json) { api.plan(request) }) {
        is ApiResult.Ok -> ApiResult.Ok(result.value.copy(games = result.value.games.filter { it.gameType != GameType.Unknown }))
        else -> result
    }
}
