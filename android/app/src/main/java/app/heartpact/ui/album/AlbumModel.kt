package app.heartpact.ui.album

import app.heartpact.album.LocalPhoto
import app.heartpact.data.Memory
import app.heartpact.data.MemoryUsage
import java.util.Locale
import kotlin.math.roundToLong

/** Where a sync the person started has got to. */
sealed interface SyncState {
    data object Idle : SyncState
    /** Enqueued, waiting for any connection. */
    data object WaitingForNetwork : SyncState
    data class Running(val done: Int, val total: Int) : SyncState
}

data class AlbumUi(
    val local: List<LocalPhoto> = emptyList(),
    val shared: List<Memory> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val onThisDay: List<Memory> = emptyList(),
    val usage: MemoryUsage? = null,
    val sync: SyncState = SyncState.Idle,
    /** How the last sync went, shown once on the banner. */
    val result: String? = null,
    val note: String? = null,
    /** The server has no photo storage set up: photos can wait here but cannot sync. */
    val storageMissing: Boolean = false,
    val loaded: Boolean = false,
) {
    val waiting: List<LocalPhoto> get() = local.filter { it.errorCode == null }
    val refused: List<LocalPhoto> get() = local.filter { it.errorCode != null }
    val waitingBytes: Long get() = waiting.sumOf { it.sizeBytes }
}

/** Pure wording and arithmetic for the album, kept apart so it can be tested without a phone. */
object AlbumText {
    fun size(bytes: Long): String {
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) { value /= 1024; unit++ }
        if (bytes < 1024) return "$bytes B"
        val shown = if (value >= 10 || unit == 0) value.roundToLong().toString() else String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        return "$shown ${units[unit]}"
    }

    fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"

    fun usage(usage: MemoryUsage): String =
        if (usage.limitBytes > 0) "${photos(usage.count)} · ${size(usage.usedBytes)} of ${size(usage.limitBytes)}" else "${photos(usage.count)} · ${size(usage.usedBytes)}"

    /** What the album would hold after this sync, and how many of the waiting photos fit. */
    data class After(val usedBytes: Long, val limitBytes: Long, val fits: Int)

    fun after(usage: MemoryUsage?, waiting: List<LocalPhoto>): After {
        val used = usage?.usedBytes ?: 0
        val limit = usage?.limitBytes ?: 0
        if (limit <= 0) return After(used + waiting.sumOf { it.sizeBytes }, 0, waiting.size)
        var total = used
        var fits = 0
        for (photo in waiting) {
            if (total + photo.sizeBytes > limit) break
            total += photo.sizeBytes; fits++
        }
        return After(total, limit, fits)
    }

    fun outcome(synced: Int, refused: Int, left: Int, full: Boolean, stopped: String?): String = buildList {
        if (synced > 0) add("${photos(synced)} added to your shared album.")
        if (full) add("Your shared album is full, so some stayed on this phone.")
        else if (refused > 0) add("${photos(refused)} couldn't be added; tap one to see why.")
        if (stopped != null && left > 0) add("${photos(left)} still waiting: $stopped")
        if (isEmpty()) add("Everything's already in your shared album.")
    }.joinToString(" ")

    /** The server's refusal codes in the couple's words. */
    fun reason(photo: LocalPhoto): String = when (photo.errorCode) {
        "memories.quota_exceeded" -> "Your shared album is full. Delete a few shared photos, then try again."
        "memories.too_large" -> "This photo is too large for the album."
        "memories.unsupported_type" -> "This kind of file can't go in the album."
        else -> photo.errorMessage ?: "This photo couldn't be added."
    }
}
