package app.heartpact.data

import android.content.Context
import app.heartpact.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Builds the app's long-lived objects once. Screens get what they need from here. */
class AppContainer(context: Context, baseUrl: String = BuildConfig.API_BASE_URL) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val session = SessionStore(context, json)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(bearer(session))
        .apply {
            // Bodies carry passwords and tokens: log only method, URL and status, and only in debug.
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    val api: HeartpactApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HeartpactApi::class.java)

    val auth = AuthRepository(api, session, json)
    val couple = CoupleRepository(api, session, json)
}

/** Adds the signed-in person's token to every request that does not set its own Authorization. */
fun bearer(session: SessionStore) = Interceptor { chain ->
    val request = chain.request()
    val token = session.token
    chain.proceed(if (token == null || request.header("Authorization") != null) request else request.newBuilder().header("Authorization", "Bearer $token").build())
}
