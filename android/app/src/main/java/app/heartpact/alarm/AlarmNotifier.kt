package app.heartpact.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.heartpact.R
import app.heartpact.i18n.tr
import kotlinx.serialization.json.Json

/**
 * Rings a dare: an insistent notification on the alarm channel (the sound repeats until it is
 * handled), which opens the full-screen alarm over the lock screen. It always carries "Silence on
 * this phone" (Play policy: an alarm must be locally dismissible) and expires at the deadline.
 */
object AlarmNotifier {
    const val CHANNEL = "alarms"
    const val ACTION_SILENCE = "app.heartpact.alarm.SILENCE"
    const val EXTRA_SLOT = "slot"
    private val json = Json { ignoreUnknownKeys = true }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Creating an existing channel only renames it (the person's own settings stay), so names follow the language.
        val channel = NotificationChannel(CHANNEL, tr("Dare alarms"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = tr("Rings when a dare is due. You can always silence it on this phone.")
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun encode(slot: AlarmSlot) = json.encodeToString(AlarmSlot.serializer(), slot)
    fun decode(value: String?): AlarmSlot? = value?.let { runCatching { json.decodeFromString(AlarmSlot.serializer(), it) }.getOrNull() }

    fun notificationId(slot: AlarmSlot) = slot.goalId.hashCode()

    /** Already ringing: posting it again would restart the sound and pop the full-screen alarm back up. */
    fun isShowing(context: Context, slot: AlarmSlot): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == notificationId(slot) }

    fun ring(context: Context, slot: AlarmSlot) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33) return
        ensureChannel(context)
        val now = System.currentTimeMillis()
        if (slot.deadlineEpochMs <= now) return
        val open = Intent(context, AlarmActivity::class.java).putExtra(EXTRA_SLOT, encode(slot)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val openPending = PendingIntent.getActivity(context, notificationId(slot), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val silence = PendingIntent.getBroadcast(
            context, notificationId(slot),
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_SILENCE).putExtra(EXTRA_SLOT, encode(slot)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = slot.setByName?.let { tr("{title} · {partner}'s dare is ringing", "title" to slot.title, "partner" to it) }
            ?: tr("{title} · Your dare is ringing", "title" to slot.title)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(tr("Do it on camera to stop it, or silence it on this phone."))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openPending, true)
            .setContentIntent(openPending)
            .addAction(0, tr("Silence on this phone"), silence)
            .setTimeoutAfter(slot.deadlineEpochMs - now)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        NotificationManagerCompat.from(context).notify(notificationId(slot), notification)
    }

    /** Stops the ringing on this phone. The dare itself stays open on the server. */
    fun silence(context: Context, slot: AlarmSlot) {
        AlarmStore(context).silence(slot.key)
        stop(context, slot)
    }

    fun stop(context: Context, slot: AlarmSlot) = NotificationManagerCompat.from(context).cancel(notificationId(slot))
}
