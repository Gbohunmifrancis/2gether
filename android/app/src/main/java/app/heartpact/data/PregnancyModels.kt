package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/pregnancy (docs/pregnancy.md). The person who started it decides whether the partner sees it. */

@Serializable
enum class PregnancyStatus { @SerialName("active") Active, @SerialName("ended") Ended }

@Serializable
data class Appointment(val id: String, val pregnancyId: String = "", val title: String, val atUtc: String, val location: String? = null, val notes: String? = null, val createdByUserId: String = "")

@Serializable
data class KickSession(val id: String, val pregnancyId: String = "", val startedAtUtc: String, val endedAtUtc: String? = null, val count: Int = 0, val durationSeconds: Int? = null)

@Serializable
data class Pregnancy(
    val id: String,
    val userId: String = "",
    val userName: String = "",
    val isMine: Boolean = false,
    val dueDate: String,
    val status: PregnancyStatus = PregnancyStatus.Active,
    val shareWithPartner: Boolean = true,
    val week: Int = 0,
    val day: Int = 0,
    val trimester: Int = 1,
    val daysToGo: Int = 0,
    val progress: Double = 0.0,
    val nextAppointment: Appointment? = null,
    val createdAtUtc: String = "",
    val endedAtUtc: String? = null,
)

@Serializable
data class StartPregnancyRequest(val dueDate: String? = null, val lastPeriodStart: String? = null, val shareWithPartner: Boolean = true)

@Serializable
data class UpdatePregnancyRequest(val dueDate: String, val shareWithPartner: Boolean)

@Serializable
data class AppointmentRequest(val title: String, val atUtc: String, val location: String? = null, val notes: String? = null)

@Serializable
data class KickUpdate(val count: Int, val end: Boolean)
