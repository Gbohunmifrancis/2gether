package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** api/location and the couple hub (docs/location-sharing.md). Mutual or nothing; only the latest point exists. */

@Serializable
enum class SharingStatus { @SerialName("off") Off, @SerialName("sharing") Sharing, @SerialName("paused") Paused }

@Serializable
data class LocationPoint(
    val userId: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val mapColor: String = "",
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double = 0.0,
    val updatedAtUtc: String,
    val isCurrentUser: Boolean = false,
)

@Serializable
data class SharingState(val userId: String, val displayName: String = "", val status: SharingStatus = SharingStatus.Off, val pausedUntilUtc: String? = null, val lastUpdatedAtUtc: String? = null)

@Serializable
data class SharingStatusView(val me: SharingState, val partner: SharingState? = null, val mutual: Boolean = false)

@Serializable
data class PauseRequest(val minutes: Int)
