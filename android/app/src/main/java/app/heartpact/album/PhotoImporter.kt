package app.heartpact.album

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Copies a picked photo into the album queue: the original bytes, a small JPEG thumbnail
 * (at most 512 px and 256 KB, as the server allows) and the date it was taken.
 */
class PhotoImporter(private val resolver: ContentResolver, private val queue: AlbumQueue) {
    sealed interface Result {
        data class Added(val photo: LocalPhoto) : Result
        data class Skipped(val reason: String) : Result
    }

    suspend fun import(uri: Uri, nowMs: Long = System.currentTimeMillis()): Result {
        val type = resolver.getType(uri)?.lowercase()
        val ext = type?.let { TYPES[it] } ?: return Result.Skipped("Only photos (JPEG, PNG, WebP or HEIC) can go in the album for now.")
        val id = UUID.randomUUID().toString()
        val target = File(queue.photosDir, "$id$ext")
        val copied = runCatching { resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } } }
        if (copied.isFailure) { target.delete(); return Result.Skipped("That photo couldn't be read.") }
        if (target.length() > MAX_BYTES) { target.delete(); return Result.Skipped("Photos must be under 15 MB.") }
        val taken = runCatching { takenAt(ExifInterface(target)) }.getOrNull()
        val thumb = runCatching { thumbnail(target, File(queue.thumbsDir, "$id.jpg")) }.getOrNull()
        val photo = LocalPhoto(id, target.name, thumb?.name, type, target.length(), taken, nowMs)
        queue.add(photo)
        return Result.Added(photo)
    }

    private fun takenAt(exif: ExifInterface): String? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        // EXIF has no zone; the phone's zone is the best guess for a photo taken on it.
        val local = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        return local.atZone(ZoneId.systemDefault()).toInstant().toString()
    }

    private fun thumbnail(source: File, target: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Not an image")
        val scale = THUMB_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(ExifInterface(source).rotationDegrees.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var quality = 82
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 12
        } while (bytes.size > MAX_THUMB_BYTES && quality > 20)
        target.writeBytes(bytes)
        return target
    }

    companion object {
        const val MAX_BYTES = 15L * 1024 * 1024
        const val MAX_THUMB_BYTES = 256 * 1024
        const val THUMB_EDGE = 512
        val TYPES = mapOf("image/jpeg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp", "image/heic" to ".heic", "image/heif" to ".heif")
    }
}
