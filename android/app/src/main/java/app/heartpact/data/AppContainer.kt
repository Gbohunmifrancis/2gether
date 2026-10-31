package app.heartpact.data

import kotlinx.coroutines.launch
import android.content.Context
import android.content.SharedPreferences
import app.heartpact.live.LiveHub
import kotlinx.coroutines.flow.MutableSharedFlow
import app.heartpact.album.AlbumQueue
import app.heartpact.album.SyncEngine
import app.heartpact.billing.GooglePlayStore
import app.heartpact.billing.PlayStore
import java.io.File
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

    /** For work that outlives a screen: alarms, notifications. */
    val appContext: Context = context.applicationContext

    val session = SessionStore(context, json)

    val theme = app.heartpact.ui.theme.ThemeSettings(context)

    val tour = app.heartpact.ui.tour.TourState(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(bearer(session))
        .addInterceptor(language())
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

    /** For signed storage links only: no sign-in token, generous timeouts for large photos. */
    private val uploads = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val albumQueue = AlbumQueue(File(context.filesDir, "album"))
    val albumSync = SyncEngine(api, uploads, albumQueue, json)

    /** Push token bookkeeping and the ids already shown; plain prefs so the messaging service reads them at once. */
    val pushPrefs: SharedPreferences = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** A push that changes what an open screen shows ("exerciseCompleted", "memoriesSynced"...). */
    val pushEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The couple hub. Its "something changed" events join [pushEvents], so screens refresh either way. */
    val live = LiveHub(baseUrl, session) { event -> pushEvents.tryEmit(LiveHub.appName(event.name)) }

    val auth = AuthRepository(api, session, json)
    val couple = CoupleRepository(api, session, json)
    val exercise = ExerciseRepository(api, json)
    val food = FoodRepository(api, json)
    val us = UsRepository(api, json)
    val avatars = AvatarRepository(api, json, context.cacheDir)

    init {
        // The admin dashboard's switches (Christmas): asked once per start; offline keeps what was last known.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            (apiCall(json) { api.config() } as? ApiResult.Ok)?.let { theme.setChristmas(it.value.christmas) }
        }
    }
    val cycle = CycleRepository(api, json)
    val pregnancy = PregnancyRepository(api, json)
    val location = LocationRepository(api, json)
    val games = GameRepository(api, json)
    val plus = PlusRepository(api, json)

    /** Proves completions come from this app on a genuine phone, when a project number is set. */
    val integrity = PlayIntegrity(appContext, BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER.toLongOrNull())

    /** Google Play, connected on first use. */
    val playStore: PlayStore by lazy { GooglePlayStore(appContext) }
}

/** Tells the API the phone's language, so its messages, notifications and emails come back in it (docs/translation.md). */
fun language() = Interceptor { chain ->
    chain.proceed(chain.request().newBuilder().header("Accept-Language", java.util.Locale.getDefault().toLanguageTag()).build())
}

/** Adds the signed-in person's token to every request that does not set its own Authorization. */
fun bearer(session: SessionStore) = Interceptor { chain ->
    val request = chain.request()
    val token = session.token
    chain.proceed(if (token == null || request.header("Authorization") != null) request else request.newBuilder().header("Authorization", "Bearer $token").build())
}
