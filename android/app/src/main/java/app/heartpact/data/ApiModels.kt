package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contracts of the Heartpact API: camelCase fields, enums as camelCase strings. */

@Serializable
enum class Gender {
    @SerialName("female") Female,
    @SerialName("male") Male,
    @SerialName("nonBinary") NonBinary,
    @SerialName("preferNotToSay") PreferNotToSay,
}

@Serializable
enum class CoupleStatus {
    @SerialName("pendingInvite") PendingInvite,
    @SerialName("active") Active,
    @SerialName("dissolved") Dissolved,
}

@Serializable
enum class CoupleSide {
    @SerialName("left") Left,
    @SerialName("right") Right,
}

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String, val dateOfBirth: String, val gender: Gender)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class AuthUser(
    val id: String,
    val email: String,
    val displayName: String,
    val coupleId: String? = null,
    val timeZoneId: String = "UTC",
    val avatarUrl: String? = null,
    val mapColor: String? = null,
    val cycleOwner: Boolean = false,
    val gender: Gender = Gender.PreferNotToSay,
)

@Serializable
data class AuthResponse(val accessToken: String, val user: AuthUser)

@Serializable
data class CoupleMember(val userId: String, val displayName: String, val avatarUrl: String? = null, val side: CoupleSide = CoupleSide.Left)

@Serializable
data class Couple(val id: String, val status: CoupleStatus, val linkedAtUtc: String? = null, val members: List<CoupleMember> = emptyList())

@Serializable
data class PartnerInvite(val id: String, val code: String, val token: String, val expiresAtUtc: String, val accessToken: String? = null)

@Serializable
data class AcceptInviteRequest(val code: String? = null, val token: String? = null)

/** Every expected failure from the API: a stable code and a sentence fit to show. */
@Serializable
data class ApiErrorBody(val code: String = "unknown", val message: String = "")
