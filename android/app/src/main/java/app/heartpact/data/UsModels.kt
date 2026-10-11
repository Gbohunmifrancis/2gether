package app.heartpact.data

import kotlinx.serialization.Serializable

/** api/questions (docs/daily-questions.md): one question a day; the partner's answer opens once mine is in. */
@Serializable
data class DailyAnswer(val userId: String, val userName: String = "", val body: String, val answeredAtUtc: String, val updatedAtUtc: String = answeredAtUtc)

@Serializable
data class DailyQuestion(
    val id: String,
    val localDate: String,
    val key: String = "",
    val category: String = "",
    val text: String,
    val myAnswer: DailyAnswer? = null,
    val partnerAnswered: Boolean = false,
    val partnerAnswer: DailyAnswer? = null,
    val streakDays: Int = 0,
)

@Serializable
data class DailyQuestionHistory(val days: Int = 0, val streakDays: Int = 0, val items: List<DailyQuestion> = emptyList())

@Serializable
data class AnswerRequest(val body: String)

/** api/notification/preferences (docs/push-notifications.md). The dare alarm ignores all of it. */
@Serializable
data class NotificationPreferences(
    val messages: Boolean = true,
    val games: Boolean = true,
    val exercise: Boolean = true,
    val cycle: Boolean = true,
    val pregnancy: Boolean = true,
    val dailyQuestions: Boolean = true,
    val album: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietStart: String = "22:00",
    val quietEnd: String = "07:00",
    val timeZoneId: String = "UTC",
    val quietNow: Boolean = false,
)

@Serializable
data class DeleteAccountRequest(val password: String? = null, val confirmEmail: String? = null, val acknowledgeActiveSubscription: Boolean = false)
