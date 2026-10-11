package app.heartpact.data

import kotlinx.serialization.json.Json

/** Account: sign up, sign in, sign out. A successful call stores the session before returning. */
class AuthRepository(private val api: HeartpactApi, private val session: SessionStore, private val json: Json) {
    suspend fun register(request: RegisterRequest): ApiResult<AuthResponse> =
        apiCall(json) { api.register(request) }.alsoOk { session.save(it.accessToken, it.user) }

    suspend fun login(email: String, password: String): ApiResult<AuthResponse> =
        apiCall(json) { api.login(LoginRequest(email.trim(), password)) }.alsoOk { session.save(it.accessToken, it.user) }

    suspend fun forgot(email: String): ApiResult<Unit> = apiCall(json) { api.forgotPassword(ForgotPasswordRequest(email.trim())) }

    suspend fun reset(email: String, code: String, newPassword: String): ApiResult<AuthResponse> =
        apiCall(json) { api.resetPassword(ResetPasswordRequest(email.trim(), code.trim(), newPassword)) }.alsoOk { session.save(it.accessToken, it.user) }

    suspend fun google(request: GoogleSignInRequest): ApiResult<AuthResponse> =
        apiCall(json) { api.googleSignIn(request) }.alsoOk { session.save(it.accessToken, it.user) }

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
    suspend fun goals(): ApiResult<List<ExerciseGoal>> = apiCall(json) { api.goals() }
    /** Stops a dare: it no longer rings. Its history stays on both pages. */
    suspend fun stopGoal(id: String): ApiResult<Unit> = apiCall(json) { api.deleteGoal(id) }
    suspend fun start(id: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.startOccurrence(id) }
    /** Today's dare before it rings: opens the day's occurrence now. Completing it cancels today's alarm. */
    suspend fun startEarly(goalId: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.startEarly(goalId) }
    suspend fun complete(id: String, count: Int, confidence: Double): ApiResult<ExerciseOccurrence> = apiCall(json) { api.completeOccurrence(id, CompleteRequest(count, confidence)) }
    /** Only the partner may cancel: this is "call it off together", granting the other's request. */
    suspend fun cancel(id: String): ApiResult<ExerciseOccurrence> = apiCall(json) { api.cancelOccurrence(id) }
    suspend fun askOff(id: String, note: String?): ApiResult<ExerciseOccurrence> = apiCall(json) { api.askOff(id, AskOffRequest(note?.trim()?.takeIf { it.isNotEmpty() })) }
}

class FoodRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun day(date: String): ApiResult<FoodDay> = apiCall(json) { api.foodDay(date) }
    suspend fun saveTargets(request: UpsertTargetRequest): ApiResult<NutritionTarget> = apiCall(json) { api.saveFoodTargets(request) }
    suspend fun create(request: CreateFoodEntryRequest): ApiResult<FoodEntry> = apiCall(json) { api.createFoodEntry(request) }
    suspend fun update(id: String, request: UpdateFoodEntryRequest): ApiResult<FoodEntry> = apiCall(json) { api.updateFoodEntry(id, request) }
    suspend fun delete(id: String): ApiResult<Unit> = apiCall(json) { api.deleteFoodEntry(id) }
    suspend fun scan(request: FoodScanRequest): ApiResult<FoodScanResult> = apiCall(json) { api.scanFood(request) }
}

class UsRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun question(date: String): ApiResult<DailyQuestion> = apiCall(json) { api.questionToday(date) }
    suspend fun answer(id: String, body: String): ApiResult<DailyQuestion> = apiCall(json) { api.answerQuestion(id, AnswerRequest(body)) }
    suspend fun history(days: Int = 30): ApiResult<DailyQuestionHistory> = apiCall(json) { api.questionHistory(days) }
    suspend fun preferences(): ApiResult<NotificationPreferences> = apiCall(json) { api.notificationPreferences() }
    suspend fun savePreferences(value: NotificationPreferences): ApiResult<NotificationPreferences> = apiCall(json) { api.saveNotificationPreferences(value) }
    suspend fun endPact(): ApiResult<Unit> = apiCall(json) { api.breakup() }
    suspend fun deleteAccount(request: DeleteAccountRequest): ApiResult<Unit> = apiCall(json) { api.deleteAccount(request) }
}

class CycleRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun me(): ApiResult<AuthUser> = apiCall(json) { api.me() }
    suspend fun profile(): ApiResult<CycleProfile?> = apiCall(json) { api.cycleProfile() }
    suspend fun claim(): ApiResult<CycleProfile> = apiCall(json) { api.claimCycle() }
    suspend fun saveProfile(request: UpdateCycleProfile): ApiResult<CycleProfile> = apiCall(json) { api.saveCycleProfile(request) }
    suspend fun logs(from: String, to: String): ApiResult<List<CycleDayLog>> = apiCall(json) { api.cycleLogs(from, to) }
    suspend fun log(date: String, request: CycleLogRequest): ApiResult<CycleDayLog> = apiCall(json) { api.saveCycleLog(date, request) }
    suspend fun prediction(date: String): ApiResult<CyclePrediction> = apiCall(json) { api.cyclePrediction(date) }
    suspend fun shared(date: String): ApiResult<SharedCycle> = apiCall(json) { api.sharedCycle(date) }
}

class PregnancyRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun list(date: String): ApiResult<List<Pregnancy>> = apiCall(json) { api.pregnancies(date) }
    suspend fun start(request: StartPregnancyRequest): ApiResult<Pregnancy> = apiCall(json) { api.startPregnancy(request) }
    suspend fun update(id: String, request: UpdatePregnancyRequest): ApiResult<Pregnancy> = apiCall(json) { api.updatePregnancy(id, request) }
    suspend fun end(id: String): ApiResult<Unit> = apiCall(json) { api.endPregnancy(id) }
    suspend fun appointments(id: String): ApiResult<List<Appointment>> = apiCall(json) { api.appointments(id) }
    suspend fun addAppointment(id: String, request: AppointmentRequest): ApiResult<Appointment> = apiCall(json) { api.addAppointment(id, request) }
    suspend fun deleteAppointment(id: String): ApiResult<Unit> = apiCall(json) { api.deleteAppointment(id) }
    suspend fun kicks(id: String): ApiResult<List<KickSession>> = apiCall(json) { api.kicks(id) }
    suspend fun startKicks(id: String): ApiResult<KickSession> = apiCall(json) { api.startKicks(id) }
    suspend fun recordKicks(id: String, count: Int, end: Boolean): ApiResult<KickSession> = apiCall(json) { api.recordKicks(id, KickUpdate(count, end)) }
}

class LocationRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun points(): ApiResult<List<LocationPoint>> = apiCall(json) { api.locations() }
    suspend fun status(): ApiResult<SharingStatusView> = apiCall(json) { api.locationStatus() }
    suspend fun pause(minutes: Int): ApiResult<SharingStatusView> = apiCall(json) { api.pauseLocation(PauseRequest(minutes)) }
    suspend fun resume(): ApiResult<SharingStatusView> = apiCall(json) { api.resumeLocation() }
}

class GameRepository(private val api: HeartpactApi, private val json: Json) {
    suspend fun sessions(): ApiResult<List<GameSession>> = apiCall(json) { api.gameSessions() }
    suspend fun session(id: String): ApiResult<GameSession> = apiCall(json) { api.gameSession(id) }
    suspend fun create(type: GameType, letter: String? = null): ApiResult<GameSession> = apiCall(json) { api.createGame(CreateGameRequest(type, letter)) }
    suspend fun accept(id: String): ApiResult<GameSession> = apiCall(json) { api.acceptGame(id) }
    suspend fun act(id: String, version: Long, action: String, value: String? = null, index: Int? = null): ApiResult<GameSession> =
        apiCall(json) { api.gameAction(id, GameActionRequest(version, action, value, index)) }
    suspend fun abandon(id: String): ApiResult<GameSession> = apiCall(json) { api.abandonGame(id) }
}
