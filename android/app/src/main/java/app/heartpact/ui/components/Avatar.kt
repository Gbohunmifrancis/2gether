package app.heartpact.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.AvatarRepository
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.partner
import app.heartpact.ui.theme.Side
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Where pictures come from; provided once at the app's root. */
val LocalAvatars = staticCompositionLocalOf<AvatarRepository?> { null }

/** Who a picture belongs to, which version of it, and the half of the heart they wear. */
data class Face(val userId: String, val name: String, val version: Long, val side: Side)

/**
 * A round profile picture in its owner's colour, or their initial on that colour until there is one. The ring is the
 * colour of their half of the heart, so two faces side by side read as the two halves.
 */
@Composable
fun Avatar(face: Face, size: Dp, modifier: Modifier = Modifier) {
    val repo = LocalAvatars.current
    val changes = repo?.changes?.collectAsState()
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, face.userId, face.version, changes?.value) {
        value = repo?.load(face.userId, face.version)
    }
    val colour = Heartpact.colors.partner(face.side).action
    Box(
        modifier.size(size).clip(CircleShape).background(colour).border(3.dp, Heartpact.colors.sticker, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) Image(loaded, face.name, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text(face.name.trim().take(1).uppercase(), color = Heartpact.colors.onAction, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp)
    }
}

/** Two faces side by side, a little overlapped, with a heart between them when [heart] is set. */
@Composable
fun AvatarPair(left: Face, right: Face, size: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-size.value * 0.18f).dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(left, size)
        Avatar(right, size)
    }
}

/** A picked photo, cropped to a centred square, upright, at most 512 px, as a JPEG small enough for the server (400 KB). */
object AvatarPrep {
    fun fromUri(context: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val edge = minOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            postRotate(rotation)
            val scale = 512f / edge
            postScale(scale, scale)
        }
        val square = Bitmap.createBitmap(decoded, (decoded.width - edge) / 2, (decoded.height - edge) / 2, edge, edge, matrix, true)
        var quality = 88
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 10
        } while (bytes.size > 380 * 1024 && quality > 30)
        bytes
    }.getOrNull()
}
