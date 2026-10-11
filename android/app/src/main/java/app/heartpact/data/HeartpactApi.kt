package app.heartpact.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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
}
