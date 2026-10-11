package app.heartpact.album

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.heartpact.MainActivity
import app.heartpact.R

/** The album's quiet notifications: sync progress while it runs, and the once-a-day nudge. */
object AlbumNotices {
    const val CHANNEL = "album"
    const val SYNC_ID = 4100
    const val REMINDER_ID = 4101
    /** Extra on the launch intent: open the app on the Album tab. */
    const val EXTRA_OPEN_TAB = "app.heartpact.OPEN_TAB"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Shared album", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sync progress and a daily nudge when photos are waiting on this phone."
            },
        )
    }

    private fun openAlbum(context: Context) = PendingIntent.getActivity(
        context, REMINDER_ID,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "album").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun syncing(context: Context, done: Int, total: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("Syncing photos")
            .setContentText(if (total > 0) "$done of $total in your shared album" else "Getting ready")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAlbum(context))
            .build()
    }

    fun remind(context: Context, waiting: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(if (waiting == 1) "1 photo is only on this phone" else "$waiting photos are only on this phone")
            .setContentText("Sync when you're ready and they'll join your shared album.")
            .setContentIntent(openAlbum(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REMINDER_ID, notification)
    }
}
