package app.heartpact.data

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.Interceptor

/**
 * The server's clock as best the phone can tell, for counting down to a server deadline (a game's countdown, a round's
 * time left). Every API response carries a `Date` header; the gap between it and the phone's own clock, taken as the
 * median of the last few responses, is how far the phone is off. The header only has whole seconds, so a gap under
 * [TRUST_MS] is taken as none: a phone that keeps good time counts by itself, and only one set minutes wrong is corrected.
 */
object ServerClock {
    /** Smaller gaps than this are the header's rounding and the trip, not a wrong clock. */
    const val TRUST_MS = 2_000L
    private const val KEEP = 7
    private val samples = ArrayDeque<Long>()

    /** How far the server's clock is ahead of the phone's, in milliseconds (0 when the phone keeps good time). */
    @Volatile var offsetMs: Long = 0
        private set

    fun now(): Instant = Instant.now().plusMillis(offsetMs)

    /** A response's `Date` header, read at [receivedAtMs] on the phone's clock. Half a second added: the header rounds down. */
    fun observe(dateHeader: String?, receivedAtMs: Long = System.currentTimeMillis()) {
        val server = dateHeader?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() } ?: return
        record(server + 500 - receivedAtMs)
    }

    /** One gap between the server's clock and the phone's, in milliseconds. */
    fun record(gapMs: Long) = synchronized(samples) {
        samples.addLast(gapMs)
        while (samples.size > KEEP) samples.removeFirst()
        val median = samples.sorted()[samples.size / 2]
        offsetMs = if (kotlin.math.abs(median) < TRUST_MS) 0 else median
    }

    /** Forget what was seen (tests). */
    fun reset() = synchronized(samples) { samples.clear(); offsetMs = 0 }

    /** Reads each response's `Date` header. */
    fun interceptor() = Interceptor { chain ->
        chain.proceed(chain.request()).also { response -> observe(response.header("Date")) }
    }
}
