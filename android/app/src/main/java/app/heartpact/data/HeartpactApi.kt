package app.heartpact.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface HeartpactApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api/auth/me")
    suspend fun me(): Response<AuthUser>

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
}
