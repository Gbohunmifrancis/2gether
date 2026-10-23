package app.heartpact.data

import android.content.Context
import android.util.Log
import app.heartpact.exercise.ExerciseChecks
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Google Play Integrity's standard requests: a token proving a completion came from this app, installed
 * from Play, on a genuine phone. The server decodes it with Google. Without a project number (debug
 * builds, before Play is set up) there is no token and the server records the check as not done.
 * A missing or slow token never holds up a completion.
 */
class PlayIntegrity(private val context: Context, private val projectNumber: Long?) {
    private val lock = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    val enabled get() = projectNumber != null

    /** Warms up the token provider (a few seconds the first time), so the token at the finish is quick. */
    suspend fun prepare(): Boolean = providerOrNull() != null

    /** A token bound to [requestHash], or null when not set up, offline, or Play is unavailable. */
    suspend fun token(requestHash: String): String? {
        val ready = providerOrNull() ?: return null
        return withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
            ready.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).awaitOrNull()?.token()
        }
    }

    private suspend fun providerOrNull(): StandardIntegrityTokenProvider? {
        val number = projectNumber ?: return null
        return lock.withLock {
            provider ?: withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                IntegrityManagerFactory.createStandard(context)
                    .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(number).build())
                    .awaitOrNull()
            }?.also { provider = it }
        }
    }

    companion object {
        private const val TAG = "PlayIntegrity"
        private const val PREPARE_TIMEOUT_MS = 10_000L
        private const val TOKEN_TIMEOUT_MS = 8_000L

        /**
         * The server computes the same: SHA-256 of "heartpact:exercise:<id>:<count>:<the six check numbers>",
         * lower-case hex. Binding the checks means a genuine token can't carry made-up numbers.
         */
        fun requestHash(occurrenceId: String, achievedCount: Int, checks: ExerciseChecks = ExerciseChecks()): String {
            val text = with(checks) { "heartpact:exercise:${occurrenceId.lowercase()}:$achievedCount:$formFaults:$fastReps:$challengesPassed:$challengesMissed:$phoneMovedReps:$outOfFrameSeconds" }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { error -> Log.w(TAG, "Play Integrity request failed", error); continuation.resume(null) }
            addOnCanceledListener { continuation.resume(null) }
        }
    }
}
