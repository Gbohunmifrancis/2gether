package app.heartpact.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire contracts of api/memories (docs/memories.md), including offline-first sync. */

@Serializable
enum class MemorySource { @SerialName("camera") Camera, @SerialName("gallery") Gallery, @SerialName("googlePhotos") GooglePhotos, @SerialName("other") Other }

@Serializable
data class Memory(
    val id: String,
    val uploadedByUserId: String,
    val uploadedByName: String = "",
    val contentType: String,
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0,
    val caption: String? = null,
    val takenAtUtc: String? = null,
    val sortAtUtc: String,
    val url: String,
    val thumbnailUrl: String? = null,
)

@Serializable
data class MemoryPage(val items: List<Memory> = emptyList(), val nextCursor: String? = null)

@Serializable
data class MemoryUsage(val count: Int = 0, val usedBytes: Long = 0, val limitBytes: Long = 0)

@Serializable
data class AlbumSyncStatus(val pendingCount: Int = 0, val pendingBytes: Long = 0, val lastSyncedAtUtc: String? = null, val usage: MemoryUsage = MemoryUsage(), val maxBatchItems: Int = 50)

@Serializable
data class ReportPendingRequest(val pendingCount: Int, val pendingBytes: Long)

@Serializable
data class SyncItem(val clientId: String, val contentType: String, val sizeBytes: Long, val caption: String? = null, val takenAtUtc: String? = null, val source: MemorySource = MemorySource.Gallery, val thumbnailContentType: String? = null)

@Serializable
data class SyncRequest(val items: List<SyncItem>)

@Serializable
data class ThumbnailUpload(val uploadUrl: String, val method: String = "PUT", val headers: Map<String, String> = emptyMap(), val maxBytes: Long = 0)

@Serializable
data class MemoryUpload(val memoryId: String, val uploadUrl: String, val method: String = "PUT", val headers: Map<String, String> = emptyMap(), val thumbnail: ThumbnailUpload? = null)

@Serializable
enum class SyncItemStatus { @SerialName("upload") Upload, @SerialName("done") Done, @SerialName("removed") Removed, @SerialName("rejected") Rejected }

@Serializable
data class SyncError(val code: String = "", val message: String = "")

@Serializable
data class SyncItemResult(val clientId: String, val status: SyncItemStatus, val memoryId: String? = null, val upload: MemoryUpload? = null, val error: SyncError? = null)

@Serializable
data class SyncResponse(val items: List<SyncItemResult> = emptyList(), val usage: MemoryUsage = MemoryUsage())

@Serializable
data class CompleteSyncRequest(val memoryIds: List<String>, val finished: Boolean)

@Serializable
enum class SyncCompleteStatus { @SerialName("ready") Ready, @SerialName("notUploaded") NotUploaded, @SerialName("rejected") Rejected, @SerialName("notFound") NotFound }

@Serializable
data class SyncCompleteItem(val memoryId: String, val status: SyncCompleteStatus, val error: SyncError? = null)

@Serializable
data class CompleteSyncResponse(val items: List<SyncCompleteItem> = emptyList())
