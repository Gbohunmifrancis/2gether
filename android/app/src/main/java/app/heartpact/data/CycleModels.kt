package app.heartpact.data

import app.heartpact.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/cycle. Only the partner who owns the cycle logs it; the other sees what they share. */

@Serializable
enum class CycleShare { @SerialName("none") None, @SerialName("predictionsOnly") PredictionsOnly, @SerialName("fullDetail") FullDetail }

@Serializable
enum class FlowLevel {
    @SerialName("none") None,
    @SerialName("spotting") Spotting,
    @SerialName("light") Light,
    @SerialName("medium") Medium,
    @SerialName("heavy") Heavy;

    val label: String get() = when (this) {
        None -> tr("No flow")
        Spotting -> tr("Spotting")
        Light -> tr("Light")
        Medium -> tr("Medium")
        Heavy -> tr("Heavy")
    }
}

@Serializable
enum class CycleMood {
    @SerialName("happy") Happy, @SerialName("calm") Calm, @SerialName("sensitive") Sensitive,
    @SerialName("sad") Sad, @SerialName("anxious") Anxious, @SerialName("irritable") Irritable;

    val label: String get() = when (this) {
        Happy -> tr("Happy")
        Calm -> tr("Calm")
        Sensitive -> tr("Sensitive")
        Sad -> tr("Sad")
        Anxious -> tr("Anxious")
        Irritable -> tr("Irritable")
    }
}

@Serializable
data class CycleProfile(
    val userId: String = "",
    val averageCycleLengthDays: Int = 28,
    val averagePeriodLengthDays: Int = 5,
    val shareLevel: CycleShare = CycleShare.None,
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
    /** When the last period really started (logged flow, else the profile). cycleStart differs when cycles were rolled forward. */
    val lastPeriodStart: String? = null,
    /** Set while a due period hasn't been logged: 0 on the day it's due. */
    val daysLate: Int? = null,
)

@Serializable
data class SharedCycle(val profile: CycleProfile? = null, val prediction: CyclePrediction? = null, val logs: List<CycleDayLog> = emptyList())
