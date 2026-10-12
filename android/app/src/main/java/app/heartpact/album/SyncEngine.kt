package app.heartpact.album

import app.heartpact.data.ApiResult
import app.heartpact.data.CompleteSyncRequest
import app.heartpact.data.HeartpactApi
import app.heartpact.data.ReportPendingRequest
import app.heartpact.data.SyncCompleteStatus
import app.heartpact.data.SyncItem
import app.heartpact.data.SyncItemStatus
import app.heartpact.data.SyncRequest
import app.heartpact.data.apiCall
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a sync went, for the album banner and the notification. */
data class SyncOutcome(val synced: Int, val refused: Int, val left: Int, val albumFull: Boolean, val stoppedBy: String? = null)

/**
 * Sends the waiting photos: in batches, each photo keyed by its own id so a retried batch never
 * adds it twice. Bytes go straight to storage on the signed links, through [uploads], a client
 * WITHOUT the sign-in token (an extra Authorization header breaks a signed link). The last batch
 * is marked finished, so the partner hears "added N photos" once.
 */
class SyncEngine(private val api: HeartpactApi, private val uploads: OkHttpClient, private val queue: AlbumQueue, private val json: Json, private val batchSize: Int = 50) {

    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): SyncOutcome {
        val photos = queue.waiting
        val total = photos.size
        var synced = 0; var refused = 0; var albumFull = false; var stoppedBy: String? = null
        var lastReady: String? = null
        var finishedSent = false
        var handled = 0
        val batches = photos.chunked(batchSize)
        for ((index, batch) in batches.withIndex()) {
            val last = index == batches.lastIndex
            val reserved = apiCall(json) { api.sync(SyncRequest(batch.map { it.toItem() })) }
            if (reserved !is ApiResult.Ok) {
                stoppedBy = (reserved as? ApiResult.Failure)?.message ?: OFFLINE
                break
            }
            val byId = batch.associateBy { it.clientId }
            val toComplete = mutableListOf<Pair<String, LocalPhoto>>()
            for (result in reserved.value.items) {
                val photo = byId[result.clientId] ?: continue
                when (result.status) {
                    SyncItemStatus.Upload -> {
                        val upload = result.upload ?: continue
                        val sent = withContext(Dispatchers.IO) { runCatching {
                            put(upload.uploadUrl, upload.headers, queue.file(photo), photo.contentType)
                            val thumb = queue.thumb(photo)
                            if (upload.thumbnail != null && thumb != null && thumb.exists()) {
                                // A thumbnail that fails never fails the photo; the server simply has none.
                                runCatching { put(upload.thumbnail.uploadUrl, upload.thumbnail.headers, thumb, "image/jpeg") }
                            }
                        } }
                        if (sent.isSuccess) toComplete += upload.memoryId to photo
                        onProgress(++handled, total)
                    }
                    SyncItemStatus.Done -> { queue.synced(photo.clientId); synced++; result.memoryId?.let { lastReady = it } }
                    SyncItemStatus.Removed -> queue.removed(photo.clientId)
                    SyncItemStatus.Rejected -> {
                        val error = result.error
                        queue.rejected(photo.clientId, error?.code ?: "rejected", error?.message ?: "This photo couldn't be added.")
                        refused++
                        if (error?.code == "memories.quota_exceeded") albumFull = true
                    }
                }
                if (result.status != SyncItemStatus.Upload) onProgress(++handled, total)
            }
            val ids = toComplete.map { it.first }
            // The finished flag needs at least one id: re-confirm one already in the album if this batch had none.
            val completeIds = if (ids.isEmpty() && last) listOfNotNull(lastReady) else ids
            if (completeIds.isNotEmpty()) {
                val completed = apiCall(json) { api.completeSync(CompleteSyncRequest(completeIds, finished = last)) }
                if (completed is ApiResult.Ok) {
                    if (last) finishedSent = true
                    val photosById = toComplete.toMap()
                    for (item in completed.value.items) {
                        val photo = photosById[item.memoryId] ?: continue
                        when (item.status) {
                            SyncCompleteStatus.Ready -> { queue.synced(photo.clientId); synced++; lastReady = item.memoryId }
                            SyncCompleteStatus.NotUploaded -> Unit // stays waiting; the next sync renews its links
                            SyncCompleteStatus.Rejected -> { queue.rejected(photo.clientId, item.error?.code ?: "rejected", item.error?.message ?: "The upload didn't match the photo. Try again."); refused++ }
                            SyncCompleteStatus.NotFound -> queue.removed(photo.clientId)
                        }
                    }
                } else {
                    stoppedBy = (completed as? ApiResult.Failure)?.message ?: OFFLINE
                    break
                }
            }
        }
        if (!finishedSent && stoppedBy == null && lastReady != null) {
            apiCall(json) { api.completeSync(CompleteSyncRequest(listOf(lastReady!!), finished = true)) }
        }
        reportPending()
        return SyncOutcome(synced, refused, queue.waiting.size, albumFull, stoppedBy)
    }

    /** Tells the server how much is waiting, so it can nudge once a day. Best effort. */
    suspend fun reportPending() {
        apiCall(json) { api.reportPending(ReportPendingRequest(queue.waiting.size, queue.waitingBytes)) }
    }

    private fun LocalPhoto.toItem() = SyncItem(clientId, contentType, sizeBytes, takenAtUtc = takenAtUtc, thumbnailContentType = if (thumbName != null) "image/jpeg" else null)

    private fun put(url: String, headers: Map<String, String>, file: File, fallbackType: String) {
        val type = (headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: fallbackType).toMediaType()
        val request = Request.Builder().url(url).put(file.asRequestBody(type)).apply {
            headers.forEach { (k, v) -> if (!k.equals("Content-Type", true)) header(k, v) }
        }.build()
        uploads.newCall(request).execute().use { if (!it.isSuccessful) throw IOException("Upload failed with ${it.code}") }
    }

    private companion object {
        const val OFFLINE = "You're offline. The rest will sync next time."
    }
}
