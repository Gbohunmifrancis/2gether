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

/** Dares: today's state, history for the sticker page, and the moves on an occurrence. */
class ExerciseRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun today(): ApiResult<ExerciseToday> = apiCall(json) { api.exerciseToday() }
    suspend fun history(days: Int = 7): ApiResult<ExerciseHistory> = apiCall(json) { api.exerciseHistory(days) }
    suspend fun createGoal(request: CreateGoalRequest): ApiResult<ExerciseGoal> = apiCall(json) { api.createGoal(request) }
    suspend fun start(id: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.startOccurrence(id) }
    /** Today's dare before it rings: opens the day's occurrence now. Completing it cancels today's alarm. */
    suspend fun startEarly(goalId: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.startEarly(goalId) }
    suspend fun complete(id: String, count: Int, confidence: Double): ApiResult<ExerciseOccurrence> = apiCall(json) { api.completeOccurrence(id, CompleteRequest(count, confidence)) }
    /** Only the partner may cancel: this is "call it off together", granting the other's request. */
    suspend fun cancel(id: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.cancelOccurrence(id) }
    suspend fun askOff(id: String, note: String?): ApiResult<ExerciseOccurrence> = apiCall(json) { api.askOff(id, AskOffRequest(note?.trim()?.takeIf { it.isNotEmpty() })) }
}
