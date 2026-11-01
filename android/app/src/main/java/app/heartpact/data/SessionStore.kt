package app.heartpact.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Who is signed in. [coupleStatus] and [side] are the last known values, for starting offline. */
data class Session(val token: String, val user: AuthUser, val coupleStatus: CoupleStatus?, val side: CoupleSide)

private val Context.store: DataStore<Preferences> by preferencesDataStore("session")

/**
 * The signed-in session, kept in app-private DataStore. The token also lives in memory so the
 * HTTP interceptor can read it without suspending.
 */
class SessionStore(private val dataStore: DataStore<Preferences>, private val json: Json) {
    constructor(context: Context, json: Json) : this(context.store, json)

    @Volatile var token: String? = null
        private set

    val session: Flow<Session?> = dataStore.data.map { it.toSession() }
    val onboardingSeen: Flow<Boolean> = dataStore.data.map { it[ONBOARDING] == true }

    suspend fun load(): Session? = session.first().also { token = it?.token }

    suspend fun save(token: String, user: AuthUser) {
        this.token = token
        dataStore.edit {
            it[TOKEN] = token
            it[USER] = json.encodeToString(AuthUser.serializer(), user)
        }
    }

    suspend fun saveToken(token: String) {
        this.token = token
        dataStore.edit { it[TOKEN] = token }
    }

    suspend fun saveCouple(couple: Couple, userId: String) = dataStore.edit {
        it[COUPLE_STATUS] = json.encodeToString(CoupleStatus.serializer(), couple.status)
        couple.members.firstOrNull { member -> member.userId == userId }?.let { me -> it[SIDE] = json.encodeToString(CoupleSide.serializer(), me.side) }
    }

    suspend fun markOnboardingSeen() = dataStore.edit { it[ONBOARDING] = true }

    /** This person chose to look around before linking: the app opens on Home, with a way to connect from there. */
    suspend fun connectLater(userId: String) = dataStore.edit { it[CONNECT_LATER] = userId }

    suspend fun connectsLater(userId: String): Boolean = dataStore.data.first()[CONNECT_LATER] == userId

    /** The invite this phone made last, for this account: shown again instead of revoking it with a new one. */
    suspend fun saveInvite(userId: String, invite: PartnerInvite) = dataStore.edit {
        it[INVITE] = json.encodeToString(KeptInvite.serializer(), KeptInvite(userId, invite))
    }

    suspend fun invite(userId: String): PartnerInvite? = dataStore.data.first()[INVITE]
        ?.let { runCatching { json.decodeFromString(KeptInvite.serializer(), it) }.getOrNull() }
        ?.takeIf { it.userId == userId }?.invite

    suspend fun clearInvite() = dataStore.edit { it.remove(INVITE) }

    /**
     * A partner's invite link that opened the app, kept until it is used: through signing up or in, and across the
     * app being closed meanwhile. [signedOut] says it came before an account, so it joins without asking again.
     */
    suspend fun savePendingInvite(token: String, signedOut: Boolean) = dataStore.edit {
        it[PENDING_INVITE] = token
        it[PENDING_SIGNED_OUT] = signedOut
    }

    suspend fun pendingInvite(): Pair<String, Boolean>? = dataStore.data.first().let { prefs ->
        prefs[PENDING_INVITE]?.let { it to (prefs[PENDING_SIGNED_OUT] == true) }
    }

    suspend fun clearPendingInvite() = dataStore.edit { it.remove(PENDING_INVITE); it.remove(PENDING_SIGNED_OUT) }

    suspend fun clear() {
        token = null
        dataStore.edit {
            it.remove(TOKEN); it.remove(USER); it.remove(COUPLE_STATUS); it.remove(SIDE); it.remove(CONNECT_LATER); it.remove(INVITE)
        }
    }

    private fun Preferences.toSession(): Session? {
        val token = this[TOKEN] ?: return null
        val user = this[USER]?.let { runCatching { json.decodeFromString(AuthUser.serializer(), it) }.getOrNull() } ?: return null
        val status = this[COUPLE_STATUS]?.let { runCatching { json.decodeFromString(CoupleStatus.serializer(), it) }.getOrNull() }
        val side = this[SIDE]?.let { runCatching { json.decodeFromString(CoupleSide.serializer(), it) }.getOrNull() } ?: CoupleSide.Left
        return Session(token, user, status, side)
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val USER = stringPreferencesKey("user")
        val COUPLE_STATUS = stringPreferencesKey("couple_status")
        val SIDE = stringPreferencesKey("side")
        val ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val CONNECT_LATER = stringPreferencesKey("connect_later")
        val INVITE = stringPreferencesKey("invite")
        val PENDING_INVITE = stringPreferencesKey("pending_invite")
        val PENDING_SIGNED_OUT = booleanPreferencesKey("pending_invite_signed_out")
    }
}

@kotlinx.serialization.Serializable
private data class KeptInvite(val userId: String, val invite: PartnerInvite)
