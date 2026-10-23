package app.heartpact.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.heartpact.BuildConfig
import app.heartpact.data.ApiResult
import app.heartpact.data.AppContainer
import app.heartpact.data.RegisterDeviceRequest
import app.heartpact.data.UnregisterDeviceRequest
import app.heartpact.data.apiCall
import app.heartpact.i18n.tr
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging, configured from build settings (CI fills them from secrets) rather than
 * google-services.json. With no settings, [enabled] is false and the app falls back to polling.
 */
object Push {
    private const val TAG = "Push"
    const val GENERAL = "general"
    const val QUIET = "quiet"

    val options: FirebaseOptions? by lazy {
        val id = BuildConfig.FIREBASE_APP_ID
        val key = BuildConfig.FIREBASE_API_KEY
        val project = BuildConfig.FIREBASE_PROJECT_ID
        if (id.isBlank() || key.isBlank() || project.isBlank()) null
        else FirebaseOptions.Builder().setApplicationId(id).setApiKey(key).setProjectId(project)
            .apply { BuildConfig.FIREBASE_SENDER_ID.takeIf { it.isNotBlank() }?.let { setGcmSenderId(it) } }
            .build()
    }

    @Volatile var enabled = false
        private set

    /** Once, from Application.onCreate. */
    fun init(context: Context) {
        val options = options ?: return
        enabled = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
            true
        }.onFailure { Log.w(TAG, "Firebase did not start; push is off", it) }.getOrDefault(false)
        ensureChannels(context)
    }

    /** The alarm channel belongs to the alarm; these two carry everything else. */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(GENERAL) == null) {
            manager.createNotificationChannel(NotificationChannel(GENERAL, tr("From your partner"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr("Dares done or missed, call-off requests, photos added.")
            })
        }
        if (manager.getNotificationChannel(QUIET) == null) {
            manager.createNotificationChannel(NotificationChannel(QUIET, tr("During quiet hours"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("The same notifications, arriving silently during your quiet hours.")
            })
        }
    }

    suspend fun currentToken(): String? {
        if (!enabled) return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }
    }

    /** On every start while signed in and whenever the token rotates. The server makes it safe to repeat. */
    suspend fun register(container: AppContainer, token: String? = null): Boolean {
        val value = token ?: currentToken() ?: return false
        if (container.session.token == null && container.session.load() == null) return false
        val result = apiCall(container.json) { container.api.registerDevice(RegisterDeviceRequest(value, appVersion = BuildConfig.VERSION_NAME)) }
        if (result is ApiResult.Ok) container.pushPrefs.edit { putString(REGISTERED, value) }
        return result is ApiResult.Ok
    }

    /** Before signing out, while the token still authorizes the call: this phone stops getting pushes for this account. */
    suspend fun unregister(container: AppContainer) {
        val token = container.pushPrefs.getString(REGISTERED, null) ?: return
        apiCall(container.json) { container.api.unregisterDevice(UnregisterDeviceRequest(token)) }
        container.pushPrefs.edit { remove(REGISTERED) }
        if (enabled) runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private const val REGISTERED = "registered_token"
}
