package app.heartpact.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface HeartpactApi {
    @GET("api/config")
    suspend fun config(): Response<AppConfig>

    @PUT("api/account/avatar")
    suspend fun putAvatar(@Body body: okhttp3.RequestBody): Response<Unit>

    @DELETE("api/account/avatar")
    suspend fun deleteAvatar(): Response<Unit>

    @GET("api/users/{id}/avatar")
    suspend fun avatar(@Path("id") id: String): Response<okhttp3.ResponseBody>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api/auth/me")
    suspend fun me(): Response<AuthUser>

    @PUT("api/auth/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<AuthResponse>

    @GET("api/couple")
    suspend fun couple(): Response<Couple>

    @POST("api/couple/invites")
    suspend fun createInvite(): Response<PartnerInvite>

    @POST("api/couple/invites/accept")
    suspend fun acceptInvite(@Body request: AcceptInviteRequest): Response<AuthResponse>

    @GET("api/exercise/today")
    suspend fun exerciseToday(): Response<ExerciseToday>

    @GET("api/exercise/history")
    suspend fun exerciseHistory(@Query("days") days: Int): Response<ExerciseHistory>

    @GET("api/exercise/goals")
    suspend fun goals(): Response<List<ExerciseGoal>>

    @DELETE("api/exercise/goals/{goalId}")
    suspend fun deleteGoal(@Path("goalId") goalId: String): Response<Unit>

    @POST("api/exercise/goals")
    suspend fun createGoal(@Body request: CreateGoalRequest): Response<ExerciseGoal>

    @POST("api/exercise/goals/{goalId}/start-early")
    suspend fun startEarly(@Path("goalId") goalId: String): Response<ExerciseOccurrence>

    @POST("api/exercise/occurrences/{id}/start")
    suspend fun startOccurrence(@Path("id") id: String): Response<ExerciseOccurrence>

    @POST("api/exercise/occurrences/{id}/complete")
    suspend fun completeOccurrence(@Path("id") id: String, @Body request: CompleteRequest): Response<ExerciseOccurrence>

    @POST("api/exercise/occurrences/{id}/cancel")
    suspend fun cancelOccurrence(@Path("id") id: String): Response<ExerciseOccurrence>

    @POST("api/exercise/occurrences/{id}/ask-off")
    suspend fun askOff(@Path("id") id: String, @Body request: AskOffRequest): Response<ExerciseOccurrence>

    @GET("api/memories")
    suspend fun memories(@Query("cursor") cursor: String?, @Query("limit") limit: Int = 30): Response<MemoryPage>

    @PATCH("api/memories/{id}")
    suspend fun updateMemory(@Path("id") id: String, @Body request: UpdateMemoryRequest): Response<Memory>

    @DELETE("api/memories/{id}")
    suspend fun deleteMemory(@Path("id") id: String): Response<Unit>

    @GET("api/memories/on-this-day")
    suspend fun onThisDay(@Query("date") date: String): Response<List<Memory>>

    @GET("api/memories/sync")
    suspend fun albumSync(): Response<AlbumSyncStatus>

    @PUT("api/memories/sync/pending")
    suspend fun reportPending(@Body request: ReportPendingRequest): Response<Unit>

    @POST("api/memories/sync")
    suspend fun sync(@Body request: SyncRequest): Response<SyncResponse>

    @POST("api/memories/sync/complete")
    suspend fun completeSync(@Body request: CompleteSyncRequest): Response<CompleteSyncResponse>

    @GET("api/billing/entitlement")
    suspend fun entitlement(): Response<Entitlement>

    @POST("api/billing/google/verify")
    suspend fun verifyPurchase(@Body request: VerifyPurchaseRequest): Response<Entitlement>

    @POST("api/billing/bachs/checkout")
    suspend fun bachsCheckout(@Body request: BachsCheckoutRequest): Response<BachsCheckout>

    @POST("api/billing/bachs/portal")
    suspend fun bachsPortal(): Response<BachsPortal>

    @PUT("api/devices")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<Unit>

    @POST("api/devices/unregister")
    suspend fun unregisterDevice(@Body request: UnregisterDeviceRequest): Response<Unit>

    @GET("api/food/day")
    suspend fun foodDay(@Query("date") date: String): Response<FoodDay>

    @PUT("api/food/targets")
    suspend fun saveFoodTargets(@Body request: UpsertTargetRequest): Response<NutritionTarget>

    @POST("api/food/entries")
    suspend fun createFoodEntry(@Body request: CreateFoodEntryRequest): Response<FoodEntry>

    @PUT("api/food/entries/{id}")
    suspend fun updateFoodEntry(@Path("id") id: String, @Body request: UpdateFoodEntryRequest): Response<FoodEntry>

    @DELETE("api/food/entries/{id}")
    suspend fun deleteFoodEntry(@Path("id") id: String): Response<Unit>

    @POST("api/food/scans")
    suspend fun scanFood(@Body request: FoodScanRequest): Response<FoodScanResult>

    @GET("api/questions/today")
    suspend fun questionToday(@Query("date") date: String): Response<DailyQuestion>

    @PUT("api/questions/{id}/answer")
    suspend fun answerQuestion(@Path("id") id: String, @Body request: AnswerRequest): Response<DailyQuestion>

    @GET("api/questions/history")
    suspend fun questionHistory(@Query("days") days: Int = 30): Response<DailyQuestionHistory>

    @GET("api/notification/preferences")
    suspend fun notificationPreferences(): Response<NotificationPreferences>

    @PUT("api/notification/preferences")
    suspend fun saveNotificationPreferences(@Body request: NotificationPreferences): Response<NotificationPreferences>

    @POST("api/couple/breakup")
    suspend fun breakup(): Response<Unit>

    @POST("api/account/delete")
    suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<Unit>

    @GET("api/cycle/profile")
    suspend fun cycleProfile(): Response<CycleProfile?>

    @POST("api/cycle/claim")
    suspend fun claimCycle(): Response<CycleProfile>

    @PUT("api/cycle/profile")
    suspend fun saveCycleProfile(@Body request: UpdateCycleProfile): Response<CycleProfile>

    @GET("api/cycle/logs")
    suspend fun cycleLogs(@Query("from") from: String, @Query("to") to: String): Response<List<CycleDayLog>>

    @PUT("api/cycle/logs/{date}")
    suspend fun saveCycleLog(@Path("date") date: String, @Body request: CycleLogRequest): Response<CycleDayLog>

    @GET("api/cycle/prediction")
    suspend fun cyclePrediction(@Query("date") date: String): Response<CyclePrediction>

    @GET("api/cycle/shared")
    suspend fun sharedCycle(@Query("date") date: String): Response<SharedCycle>

    @GET("api/pregnancy")
    suspend fun pregnancies(@Query("date") date: String, @Query("includeEnded") includeEnded: Boolean = false): Response<List<Pregnancy>>

    @DELETE("api/pregnancy/{id}")
    suspend fun deletePregnancy(@Path("id") id: String): Response<Unit>

    @POST("api/pregnancy")
    suspend fun startPregnancy(@Body request: StartPregnancyRequest): Response<Pregnancy>

    @PUT("api/pregnancy/{id}")
    suspend fun updatePregnancy(@Path("id") id: String, @Body request: UpdatePregnancyRequest): Response<Pregnancy>

    @POST("api/pregnancy/{id}/end")
    suspend fun endPregnancy(@Path("id") id: String): Response<Unit>

    @GET("api/pregnancy/{id}/appointments")
    suspend fun appointments(@Path("id") id: String): Response<List<Appointment>>

    @POST("api/pregnancy/{id}/appointments")
    suspend fun addAppointment(@Path("id") id: String, @Body request: AppointmentRequest): Response<Appointment>

    @DELETE("api/pregnancy/appointments/{id}")
    suspend fun deleteAppointment(@Path("id") id: String): Response<Unit>

    @GET("api/pregnancy/{id}/kicks")
    suspend fun kicks(@Path("id") id: String, @Query("days") days: Int = 7): Response<List<KickSession>>

    @POST("api/pregnancy/{id}/kicks")
    suspend fun startKicks(@Path("id") id: String): Response<KickSession>

    @PUT("api/pregnancy/kicks/{id}")
    suspend fun recordKicks(@Path("id") id: String, @Body request: KickUpdate): Response<KickSession>

    @GET("api/location")
    suspend fun locations(): Response<List<LocationPoint>>

    @GET("api/location/status")
    suspend fun locationStatus(): Response<SharingStatusView>

    @POST("api/location/pause")
    suspend fun pauseLocation(@Body request: PauseRequest): Response<SharingStatusView>

    @POST("api/location/resume")
    suspend fun resumeLocation(): Response<SharingStatusView>

    @POST("api/location/start")
    suspend fun startLocation(): Response<SharingStatusView>

    @POST("api/location/heartbeat")
    suspend fun locationHeartbeat(): Response<SharingStatusView>

    /** [authorization] is set by a retry after sign-out, which carries the signed-out session's token. */
    @POST("api/location/stop")
    suspend fun stopLocation(@Header("Authorization") authorization: String? = null): Response<Unit>

    @POST("api/auth/password/forgot")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): Response<Unit>

    @POST("api/auth/password/reset")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<AuthResponse>

    @POST("api/auth/google")
    suspend fun googleSignIn(@Body request: GoogleSignInRequest): Response<AuthResponse>

    @GET("api/game/region")
    suspend fun gameRegion(): Response<GameRegionView>

    @GET("api/game/sessions")
    suspend fun gameSessions(): Response<List<GameSession>>

    @GET("api/game/history")
    suspend fun gameHistory(@Query("limit") limit: Int): Response<List<GameHistoryItem>>

    @POST("api/game/sessions")
    suspend fun createGame(@Body request: CreateGameRequest): Response<GameSession>

    @POST("api/game/sessions/{id}/accept")
    suspend fun acceptGame(@Path("id") id: String, @Body body: Map<String, String> = emptyMap()): Response<GameSession>

    @POST("api/game/sessions/{id}/actions")
    suspend fun gameAction(@Path("id") id: String, @Body request: GameActionRequest): Response<GameSession>

    @POST("api/game/sessions/{id}/abandon")
    suspend fun abandonGame(@Path("id") id: String, @Body body: Map<String, String> = emptyMap()): Response<GameSession>

    @GET("api/game/sessions/{id}")
    suspend fun gameSession(@Path("id") id: String): Response<GameSession>

    // The AI features (docs/ai.md).
    @GET("api/ai/status")
    suspend fun aiStatus(): Response<AiStatus>

    @POST("api/ai/dare-ideas")
    suspend fun dareIdeas(@Body request: DareIdeasRequest): Response<DareIdeas>

    @GET("api/ai/coach/{occurrenceId}")
    suspend fun coachNote(@Path("occurrenceId") occurrenceId: String): Response<CoachNote>

    @GET("api/ai/recap")
    suspend fun weeklyRecap(): Response<WeeklyRecap>

    @POST("api/ai/plan")
    suspend fun plan(@Body request: PlanRequest): Response<DatePlan>
}
