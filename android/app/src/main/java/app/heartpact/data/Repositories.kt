package app.heartpact.data

import kotlinx.serialization.json.Json

/** Account: sign up, sign in, sign out. A successful call stores the session before returning. */
class AuthRepository(private val api: HeartpactApi, private val session: SessionStore, private val json: Json) {
    suspend fun register(request: RegisterRequest): ApiResult<AuthResponse> =
        apiCall(json) { api.register(request) }.alsoOk { session.save(it.accessToken, it.user) }

    suspend fun login(email: String, password: String): ApiResult<AuthResponse> =
        apiCall(json) { api.login(LoginRequest(email.trim(), password)) }.alsoOk { session.save(it.accessToken, it.user) }

    suspend fun signOut() = session.clear()
}

/** The couple: its state, and linking by invite code or link token. */
class CoupleRepository(private val api: HeartpactApi, private val session: SessionStore, private val json: Json) {
    suspend fun current(userId: String): ApiResult<Couple> = apiCall(json) { api.couple() }.alsoOk { session.saveCouple(it, userId) }

    /** A fresh invite (any earlier pending one is revoked). The server may hand back a refreshed token. */
    suspend fun createInvite(): ApiResult<PartnerInvite> = apiCall(json) { api.createInvite() }.alsoOk { invite -> invite.accessToken?.let { session.saveToken(it) } }

    suspend fun accept(code: String? = null, token: String? = null): ApiResult<AuthResponse> =
        apiCall(json) { api.acceptInvite(AcceptInviteRequest(code = code, token = token)) }.alsoOk { session.save(it.accessToken, it.user) }
}

inline fun <T> ApiResult<T>.alsoOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(value)
    return this
}
