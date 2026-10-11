package app.heartpact.album

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.heartpact.HeartpactApplication
import java.util.concurrent.TimeUnit

/**
 * Runs a sync the person asked for. WorkManager keeps it going after they leave the app and
 * resumes it when the network returns. It never starts on its own: only [enqueue] starts it.
 */
class AlbumSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as HeartpactApplication).container.albumSync
        // A visible, foreground sync is not cut off after ten minutes. Starting one can be refused
        // (the app went to the background first); the sync then runs as ordinary work.
        runCatching { setForeground(foreground(0, 0)) }
        val outcome = engine.run { done, total ->
            setProgress(workDataOf(DONE to done, TOTAL to total))
            runCatching { setForeground(foreground(done, total)) }
        }
        val data = workDataOf(SYNCED to outcome.synced, REFUSED to outcome.refused, LEFT to outcome.left, FULL to outcome.albumFull, STOPPED to outcome.stoppedBy)
        // Stopped by the network: try again when it is back. Anything else is reported, not retried.
        return if (outcome.stoppedBy != null && outcome.left > 0 && runAttemptCount < 3) Result.retry() else Result.success(data)
    }

    override suspend fun getForegroundInfo() = foreground(0, 0)

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val notification = AlbumNotices.syncing(applicationContext, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(AlbumNotices.SYNC_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(AlbumNotices.SYNC_ID, notification)
        }
    }

    companion object {
        const val NAME = "album-sync"
        const val DONE = "done"; const val TOTAL = "total"
        const val SYNCED = "synced"; const val REFUSED = "refused"; const val LEFT = "left"; const val FULL = "full"; const val STOPPED = "stopped"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AlbumSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Once a day: if photos are waiting on this phone, say so. It never syncs anything itself. */
class AlbumReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HeartpactApplication).container
        val waiting = container.albumQueue.waiting.size
        if (waiting > 0 && container.session.load() != null) AlbumNotices.remind(applicationContext, waiting)
        return Result.success()
    }

    companion object {
        private const val NAME = "album-reminder"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlbumReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
