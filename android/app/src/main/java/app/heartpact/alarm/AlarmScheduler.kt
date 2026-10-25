package app.heartpact.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.heartpact.MainActivity
import java.time.Instant

/**
 * Wakes the phone at each planned dare. Exact alarms when the person allowed them (Android 12+
 * asks); otherwise the closest the system offers, and Today says so. Re-planning replaces every
 * earlier wake-up, so a changed or deleted dare never rings.
 */
object AlarmScheduler {
    const val ACTION_DUE = "app.heartpact.alarm.DUE"

    fun canRingOnTime(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun apply(context: Context, slots: List<AlarmSlot>, now: Instant = Instant.now()) {
        val store = AlarmStore(context)
        val previous = store.slots
        previous.forEach { cancel(context, it) }
        // Anything no longer planned stops ringing too (cancelled by the partner, done, deleted).
        val plannedKeys = slots.map { it.key }.toSet()
        previous.filter { it.key !in plannedKeys }.forEach { AlarmNotifier.stop(context, it) }
        store.slots = slots
        val manager = context.getSystemService(AlarmManager::class.java)
        for (slot in slots) {
            if (slot.dueAtEpochMs <= now.toEpochMilli()) continue
            val operation = operation(context, slot)
            if (canRingOnTime(context)) {
                val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(slot.dueAtEpochMs, show), operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.dueAtEpochMs, operation)
            }
        }
        // Every refresh re-plans (Today checks in every 15-60 s, even in the background), so only start what isn't ringing yet.
        AlarmPlan.ringingNow(slots, store.silenced, now).filterNot { AlarmNotifier.isShowing(context, it) }.forEach { AlarmNotifier.ring(context, it) }
    }

    /** After a reboot or clock change: replan from what was stored, without the network. */
    fun restore(context: Context) = apply(context, AlarmStore(context).slots)

    private fun operation(context: Context, slot: AlarmSlot) = PendingIntent.getBroadcast(
        context, slot.key.hashCode(),
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_DUE).putExtra(AlarmNotifier.EXTRA_SLOT, AlarmNotifier.encode(slot)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(context: Context, slot: AlarmSlot) = context.getSystemService(AlarmManager::class.java).cancel(operation(context, slot))
}
