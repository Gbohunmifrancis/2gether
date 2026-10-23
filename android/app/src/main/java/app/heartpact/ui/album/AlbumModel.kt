package app.heartpact.ui.album

import app.heartpact.album.LocalPhoto
import app.heartpact.data.Memory
import app.heartpact.data.MemoryUsage
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
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
        if (bytes < 1024) return tr("{size} B", "size" to bytes)
        val shown = if (value >= 10 || unit == 0) value.roundToLong().toString() else String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        return when (unit) {
            0 -> tr("{size} KB", "size" to shown)
            1 -> tr("{size} MB", "size" to shown)
            2 -> tr("{size} GB", "size" to shown)
            else -> tr("{size} TB", "size" to shown)
        }
    }

    fun photos(n: Int) = trn(n, "{count} photo", "{count} photos")

    fun usage(usage: MemoryUsage): String =
        if (usage.limitBytes > 0) tr("{photos} · {used} of {limit}", "photos" to photos(usage.count), "used" to size(usage.usedBytes), "limit" to size(usage.limitBytes)) else "${photos(usage.count)} · ${size(usage.usedBytes)}"

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
        if (synced > 0) add(trn(synced, "{count} photo added to your shared album.", "{count} photos added to your shared album."))
        if (full) add(tr("Your shared album is full, so some stayed on this phone."))
        else if (refused > 0) add(trn(refused, "{count} photo couldn't be added; tap one to see why.", "{count} photos couldn't be added; tap one to see why."))
        if (stopped != null && left > 0) add(trn(left, "{count} photo still waiting: {reason}", "{count} photos still waiting: {reason}", "reason" to stopped))
        if (isEmpty()) add(tr("Everything's already in your shared album."))
    }.joinToString(" ")

    /** The server's refusal codes in the couple's words. */
    fun reason(photo: LocalPhoto): String = when (photo.errorCode) {
        "memories.quota_exceeded" -> tr("Your shared album is full. Delete a few shared photos, then try again.")
        "memories.too_large" -> if (photo.isVideo) tr("This video is too large for the album (100 MB at most).") else tr("This photo is too large for the album.")
        "memories.unsupported_type" -> tr("This kind of file can't go in the album.")
        else -> photo.errorMessage ?: tr("This photo couldn't be added.")
    }
}
