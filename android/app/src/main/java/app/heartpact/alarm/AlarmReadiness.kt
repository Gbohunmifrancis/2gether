package app.heartpact.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** What the phone needs for a dare to ring on time and over the lock screen. */
data class AlarmReadiness(val notifications: Boolean, val exactAlarms: Boolean, val fullScreen: Boolean) {
    val ready get() = notifications && exactAlarms && fullScreen

    companion object {
        fun check(context: Context) = AlarmReadiness(
            notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            exactAlarms = AlarmScheduler.canRingOnTime(context),
            fullScreen = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
        )

        fun exactAlarmSettings(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()) else null

        fun fullScreenSettings(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri()) else null
    }
}
