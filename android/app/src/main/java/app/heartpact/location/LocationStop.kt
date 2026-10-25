package app.heartpact.location

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Tells the server that sharing stopped, so the partner stops seeing a point the moment this
 * phone stops (the notification's Stop, the app's Stop, signing out). It is tried at once; when it
 * can't land (offline, a tunnel), WorkManager retries it once the network is back. The retry
 * carries this session's token, so it still works after signing out. Meanwhile the server's lease
 * ends the share anyway once the phone has been quiet for 10 minutes.
 */
object LocationStop {
    private const val TIMEOUT_MS = 4_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sends the stop and waits a few seconds at most; queues the retry if it didn't land. True when it landed. */
    suspend fun send(container: AppContainer): Boolean {
        val token = container.session.token ?: container.session.load()?.token ?: return true
        val result = withTimeoutOrNull(TIMEOUT_MS) { container.location.stop(token) }
        if (landed(result)) return true
        LocationStopWorker.enqueue(container.appContext, token)
        return false
    }

    /** [send] without waiting, for callers that are about to go away (the service, a screen). */
    fun request(container: AppContainer) = scope.launch { send(container) }

    /** Done, or refused for good (signed out on the server, no couple): nothing left to retry. */
    internal fun landed(result: ApiResult<Unit>?): Boolean = when (result) {
        is ApiResult.Ok -> true
        is ApiResult.Failure -> result.status in 400..499 && result.status != 408 && result.status != 429
        ApiResult.Offline, null -> false
    }
}

/** The retry of [LocationStop]. The token stays in WorkManager's app-private database until the stop lands. */
class LocationStopWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = inputData.getString(TOKEN) ?: return Result.success()
        val container = (applicationContext as HeartpactApplication).container
        return if (LocationStop.landed(container.location.stop(token))) Result.success() else Result.retry()
    }

    companion object {
        private const val TOKEN = "token"

        fun enqueue(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<LocationStopWorker>()
                .setInputData(workDataOf(TOKEN to token))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // One per session: a later sign-in to another account must not replace this one's stop.
            WorkManager.getInstance(context).enqueueUniqueWork("location-stop-${token.hashCode()}", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
