package app.heartpact.push

import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.heartpact.HeartpactApplication
import app.heartpact.alarm.AlarmNotifier
import app.heartpact.alarm.AlarmPlan
import app.heartpact.alarm.AlarmScheduler
import app.heartpact.alarm.AlarmStore
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
            else -> {
                val title = data["title"] ?: message.notification?.title ?: return
                show(PushNotice(id, type, title, data["body"] ?: message.notification?.body.orEmpty(), data["quiet"] == "true", PushData.parse(data["data"])))
            }
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

    /**
     * The app open on screen: its banner drops in instead (a message also waits quietly in the shade, where it can be
     * answered). Otherwise the notice is drawn with its actions, the partner's name and picture, and grouped.
     */
    private fun show(notice: PushNotice) {
        if (AppForeground.resumed) {
            container.notices.tryEmit(InAppNotice.from(notice))
            if (notice.type != "message") return
        }
        // A message shows the partner's picture: ask for the couple (and the picture) briefly. The rest only need the name.
        val chat = notice.type == "message" || PartnerCache.cached(container).userId == null
        val partner = if (chat) runBlocking { withTimeoutOrNull(6_000) { PartnerCache.face(container) } } ?: PartnerCache.cached(container) else PartnerCache.cached(container)
        val me = runBlocking { withTimeoutOrNull(1_000) { container.session.load()?.user?.id } }
        PushNotifier.show(this, notice, partner, me, silent = AppForeground.resumed)
    }

    private companion object {
        const val SEEN = "seen_ids"
    }
}
