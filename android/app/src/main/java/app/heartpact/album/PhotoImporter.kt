package app.heartpact.album

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import app.heartpact.media.Jpeg
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Copies a picked photo or video into the album queue: the original bytes, a small JPEG thumbnail
 * (at most 512 px and 256 KB, as the server allows; a video's is its first frame) and when it was taken.
 */
class PhotoImporter(private val resolver: ContentResolver, private val queue: AlbumQueue) {
    sealed interface Result {
        data class Added(val photo: LocalPhoto) : Result
        data class Skipped(val reason: String) : Result
    }

    suspend fun import(uri: Uri, nowMs: Long = System.currentTimeMillis()): Result {
        val type = resolver.getType(uri)?.lowercase()
        val ext = type?.let { TYPES[it] } ?: return Result.Skipped("Only photos (JPEG, PNG, WebP or HEIC) and videos (MP4 or MOV) can go in the album.")
        val video = type.startsWith("video/")
        val limit = if (video) MAX_VIDEO_BYTES else MAX_BYTES
        val tooBig = if (video) "Videos must be under 100 MB." else "Photos must be under 15 MB."
        // Check the size first where the picker says it: a long video shouldn't be copied just to be refused.
        if ((declaredSize(uri) ?: 0) > limit) return Result.Skipped(tooBig)
        val id = UUID.randomUUID().toString()
        val target = File(queue.photosDir, "$id$ext")
        val copied = runCatching { resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { copyAtMost(input, it, limit + 1) } } }
        if (copied.isFailure) { target.delete(); return Result.Skipped(if (video) "That video couldn't be read." else "That photo couldn't be read.") }
        if (target.length() > limit) { target.delete(); return Result.Skipped(tooBig) }
        val thumbFile = File(queue.thumbsDir, "$id.jpg")
        val taken: String?
        val thumb: File?
        if (video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(target.path)
                taken = runCatching { videoDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) }.getOrNull()
                thumb = runCatching {
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("No frame")
                    thumbFile.apply { writeBytes(Jpeg.fromBitmap(frame, THUMB_EDGE, MAX_THUMB_BYTES)) }
                }.getOrNull()
            } catch (_: RuntimeException) {
                target.delete()
                return Result.Skipped("That video couldn't be read.")
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            taken = runCatching { takenAt(ExifInterface(target)) }.getOrNull()
            thumb = runCatching { thumbFile.apply { writeBytes(Jpeg.shrink(target, THUMB_EDGE, MAX_THUMB_BYTES)) } }.getOrNull()
        }
        val photo = LocalPhoto(id, target.name, thumb?.name, type, target.length(), taken, nowMs)
        queue.add(photo)
        return Result.Added(photo)
    }

    private fun declaredSize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun takenAt(exif: ExifInterface): String? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        // EXIF has no zone; the phone's zone is the best guess for a photo taken on it.
        val local = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        return local.atZone(ZoneId.systemDefault()).toInstant().toString()
    }

    companion object {
        const val MAX_BYTES = 15L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
        const val MAX_THUMB_BYTES = 256 * 1024
        const val THUMB_EDGE = 512
        val TYPES = mapOf(
            "image/jpeg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp", "image/heic" to ".heic", "image/heif" to ".heif",
            "video/mp4" to ".mp4", "video/quicktime" to ".mov",
        )

        /** Video files record when they were made in UTC, as 20240305T101500.000Z; cameras without a clock write 1904. */
        fun videoDate(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.length >= 15 } ?: return null
            val parsed = LocalDateTime.parse(value.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            return if (parsed.year < 1971) null else parsed.toInstant(ZoneOffset.UTC).toString()
        }

        /** Copies at most [limit] bytes, so a file far over the cap never fills the phone. */
        fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
            return total
        }
    }
}
