package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/cycle. Only the partner who owns the cycle logs it; the other sees what they share. */

@Serializable
enum class CycleShare { @SerialName("none") None, @SerialName("predictionsOnly") PredictionsOnly, @SerialName("fullDetail") FullDetail }

@Serializable
enum class FlowLevel(val label: String) {
    @SerialName("none") None("No flow"),
    @SerialName("spotting") Spotting("Spotting"),
    @SerialName("light") Light("Light"),
    @SerialName("medium") Medium("Medium"),
    @SerialName("heavy") Heavy("Heavy"),
}

@Serializable
enum class CycleMood(val label: String) {
    @SerialName("happy") Happy("Happy"), @SerialName("calm") Calm("Calm"), @SerialName("sensitive") Sensitive("Sensitive"),
    @SerialName("sad") Sad("Sad"), @SerialName("anxious") Anxious("Anxious"), @SerialName("irritable") Irritable("Irritable"),
}

@Serializable
data class CycleProfile(
    val userId: String = "",
    val averageCycleLengthDays: Int = 28,
    val averagePeriodLengthDays: Int = 5,
    val shareLevel: CycleShare = CycleShare.PredictionsOnly,
    val lastPeriodStartDate: String? = null,
)

@Serializable
data class UpdateCycleProfile(val averageCycleLengthDays: Int, val averagePeriodLengthDays: Int, val lastPeriodStartDate: String?, val shareLevel: CycleShare)

@Serializable
data class CycleDayLog(
    val logDate: String,
    val flowLevel: FlowLevel = FlowLevel.None,
    val symptoms: List<String> = emptyList(),
    val mood: CycleMood? = null,
    val hadSex: Boolean? = null,
    val protectionUsed: Boolean? = null,
    val notes: String? = null,
)

@Serializable
data class CycleLogRequest(
    val flowLevel: FlowLevel,
    val symptoms: List<String> = emptyList(),
    val mood: CycleMood? = null,
    val hadSex: Boolean? = null,
    val protectionUsed: Boolean? = null,
    val notes: String? = null,
)

@Serializable
data class CyclePrediction(
    val nextPeriodStart: String? = null,
    val fertileWindowStart: String? = null,
    val periodEnd: String? = null,
    val fertileWindowEnd: String? = null,
    val cycleStart: String? = null,
    val cycleDay: Int? = null,
)

@Serializable
data class SharedCycle(val profile: CycleProfile? = null, val prediction: CyclePrediction? = null, val logs: List<CycleDayLog> = emptyList())
