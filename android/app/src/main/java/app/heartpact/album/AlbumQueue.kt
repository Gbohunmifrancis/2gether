package app.heartpact.album

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A photo or video that is only on this phone until it syncs. */
@Serializable
data class LocalPhoto(
    /** The phone's own id; the server uses it to make a retried sync safe. */
    val clientId: String,
    val fileName: String,
    val thumbName: String? = null,
    val contentType: String,
    val sizeBytes: Long,
    val takenAtUtc: String? = null,
    val addedAtMs: Long,
    /** Set when the server refused it (too large, album full): it stays here with the reason. */
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val isVideo: Boolean get() = contentType.startsWith("video/")
}

/**
 * The offline-first album's local side: photos copied into app-private storage, with a small
 * index file. Nothing here leaves the phone until the person taps Sync. Synced photos are
 * removed from the queue and their local copies deleted; the shared album holds them from then on.
 */
class AlbumQueue(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocalPhoto.serializer())
    private val lock = Mutex()
    private val index = File(root, "index.json")
    val photosDir = File(root, "photos").apply { mkdirs() }
    val thumbsDir = File(root, "thumbs").apply { mkdirs() }

    private val _photos = MutableStateFlow(read())
    val photos: StateFlow<List<LocalPhoto>> = _photos.asStateFlow()

    /** Waiting to sync: everything not refused by the server. */
    val waiting: List<LocalPhoto> get() = _photos.value.filter { it.errorCode == null }
    val waitingBytes: Long get() = waiting.sumOf { it.sizeBytes }

    fun file(photo: LocalPhoto) = File(photosDir, photo.fileName)
    fun thumb(photo: LocalPhoto) = photo.thumbName?.let { File(thumbsDir, it) }

    suspend fun add(photo: LocalPhoto) = update { it + photo }

    /** In the album now: forget it here and free the space. */
    suspend fun synced(clientId: String) = drop(clientId)

    /** Deleted from the album since, or unknown to it: nothing to keep waiting for. */
    suspend fun removed(clientId: String) = drop(clientId)

    suspend fun rejected(clientId: String, code: String, message: String) =
        update { list -> list.map { if (it.clientId == clientId) it.copy(errorCode = code, errorMessage = message) else it } }

    /** Try a refused photo again (for example after making room). */
    suspend fun retry(clientId: String) = update { list -> list.map { if (it.clientId == clientId) it.copy(errorCode = null, errorMessage = null) else it } }

    suspend fun delete(clientId: String) = drop(clientId)

    /** Signing out: nothing waiting here may sync into the next account's album. */
    suspend fun clear() {
        update { emptyList() }
        photosDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun drop(clientId: String) {
        val gone = _photos.value.firstOrNull { it.clientId == clientId } ?: return
        update { list -> list.filterNot { it.clientId == clientId } }
        file(gone).delete()
        thumb(gone)?.delete()
    }

    private suspend fun update(change: (List<LocalPhoto>) -> List<LocalPhoto>) = lock.withLock {
        val next = change(_photos.value)
        val temp = File(root, "index.json.tmp")
        temp.writeText(json.encodeToString(serializer, next))
        temp.renameTo(index)
        _photos.value = next
    }

    private fun read(): List<LocalPhoto> = runCatching { json.decodeFromString(serializer, index.readText()) }.getOrDefault(emptyList())
}
