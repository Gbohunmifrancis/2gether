package app.heartpact.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.heartpact.HeartpactApplication
import app.heartpact.MainActivity
import app.heartpact.R
import app.heartpact.alarm.AlarmNotifier
import app.heartpact.alarm.AlarmPlan
import app.heartpact.alarm.AlarmScheduler
import app.heartpact.alarm.AlarmStore
import app.heartpact.album.AlbumNotices
import app.heartpact.album.AlbumReminderWorker
import app.heartpact.data.ApiResult
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Every server notification arrives here as data (docs/push-notifications.md). The alarm and the
 * album nudge are handled by the app; everything else is shown on the general or quiet channel.
 */
class HeartpactMessagingService : FirebaseMessagingService() {
    private val container get() = (application as HeartpactApplication).container

    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(15_000) { Push.register(container, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val id = data["notificationId"] ?: return
        val prefs = container.pushPrefs
        val seen = prefs.getString(SEEN, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val next = PushInbox.accept(seen, id) ?: return
        prefs.edit { putString(SEEN, next.joinToString(",")) }

        val type = data["type"]
        if (PushInbox.refreshesToday(type)) container.pushEvents.tryEmit(type!!)
        when (type) {
            "exerciseDue" -> ring(data["data"])
            "albumSyncReminder" -> remindOnWifi()
            else -> show(id, type, data["title"] ?: message.notification?.title ?: return, data["body"] ?: message.notification?.body.orEmpty(), data["quiet"] == "true")
        }
        if (type == "memoriesSynced" || PushInbox.refreshesHome(type)) container.pushEvents.tryEmit(type!!)
        // The partner ended the pact: their dares must not ring here any more.
        if (type == "breakup") AlarmScheduler.apply(this, emptyList())
    }

    /** The phone's own exact alarm normally rings first; this catches a lost alarm or a wrong clock. */
    private fun ring(payload: String?) {
        val goalId = payload?.let { runCatching { container.json.parseToJsonElement(it).jsonObject["goalId"]?.jsonPrimitive?.content }.getOrNull() } ?: return
        val store = AlarmStore(this)
        val now = Instant.now()
        val known = AlarmPlan.ringingNow(store.slots, store.silenced, now).firstOrNull { it.goalId == goalId }
        if (known != null) {
            // Already ringing from the phone's own alarm: leave it be rather than restart the sound.
            val showing = getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == AlarmNotifier.notificationId(known) }
            if (!showing) AlarmNotifier.ring(this, known)
            return
        }
        runBlocking {
            withTimeoutOrNull(8_000) {
                val session = container.session.load() ?: return@withTimeoutOrNull
                val today = (container.exercise.today() as? ApiResult.Ok)?.value ?: return@withTimeoutOrNull
                val partner = (container.couple.current(session.user.id) as? ApiResult.Ok)?.value?.members?.firstOrNull { it.userId != session.user.id }?.displayName
                val slots = AlarmPlan.plan(today, session.user.id, partner, now)
                AlarmScheduler.apply(this@HeartpactMessagingService, slots, now)
                AlarmPlan.ringingNow(slots, store.silenced, now).firstOrNull { it.goalId == goalId }?.let { AlarmNotifier.ring(this@HeartpactMessagingService, it) }
            }
        }
    }

    /** The server prefers Wi-Fi for this nudge: show it once the phone is on an unmetered network. */
    private fun remindOnWifi() {
        val request = OneTimeWorkRequestBuilder<AlbumReminderWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("album-reminder-push", ExistingWorkPolicy.REPLACE, request)
    }

    private fun show(id: String, type: String?, title: String, body: String, quiet: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Push.ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, id.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(AlbumNotices.EXTRA_OPEN_TAB, PushInbox.tabFor(type)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, if (quiet) Push.QUIET else Push.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(this).notify(id.hashCode(), notification)
    }

    private companion object {
        const val SEEN = "seen_ids"
    }
}
