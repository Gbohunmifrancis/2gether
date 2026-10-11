package app.heartpact.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Downscaled, upright JPEG bytes from any image file, kept under a size budget. */
object Jpeg {
    fun shrink(source: File, edge: Int, maxBytes: Int, startQuality: Int = 82): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Not an image")
        val scale = edge.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(runCatching { ExifInterface(source).rotationDegrees }.getOrDefault(0).toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var quality = startQuality
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 12
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }
}
