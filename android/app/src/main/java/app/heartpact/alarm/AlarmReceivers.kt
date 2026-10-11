package app.heartpact.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A dare is due (exact alarm), or the person silenced it from the notification. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = AlarmNotifier.decode(intent.getStringExtra(AlarmNotifier.EXTRA_SLOT)) ?: return
        when (intent.action) {
            AlarmScheduler.ACTION_DUE -> if (slot.key !in AlarmStore(context).silenced) AlarmNotifier.ring(context, slot)
            AlarmNotifier.ACTION_SILENCE -> AlarmNotifier.silence(context, slot)
        }
    }
}

/** Alarms do not survive a reboot, a clock or time zone change, or an update: put them back. */
class AlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> AlarmScheduler.restore(context)
        }
    }
}
