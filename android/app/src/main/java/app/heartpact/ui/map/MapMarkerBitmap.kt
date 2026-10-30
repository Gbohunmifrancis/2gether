package app.heartpact.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/** What a marker shows. */
data class MarkerLook(
    val name: String,
    /** "now", "2 min ago": shown after the name; null on your own marker. */
    val age: String?,
    /** The colour of this person's half of the heart. */
    val ring: Int,
    /** The picture, or null for the initial on [ring]. */
    val photo: Bitmap?,
    val pillBackground: Int,
    val pillText: Int,
    val pillMuted: Int,
    /** Text on [ring] when there is no picture. */
    val initialText: Int = 0xFFFFFFFF.toInt(),
)

/**
 * Draws a map marker: the profile picture in a circle with a white ring and a ring in the person's colour, a small
 * pointer tail, and a name pill under it. The image is as tall below its tip as above, so a symbol anchored at the
 * image's centre puts the tip of the tail exactly on the person's place.
 */
object MapMarkerBitmap {
    const val PHOTO_DP = 50f
    const val COLOUR_RING_DP = 3f
    const val WHITE_RING_DP = 4f
    private const val TAIL_DP = 9f
    private const val PILL_GAP_DP = 6f
    private const val PILL_HEIGHT_DP = 24f
    private const val PILL_PAD_DP = 10f
    private const val SHADOW_DP = 6f
    private const val TEXT_SP = 12f

    /** Diameter of the whole head, rings included. */
    const val HEAD_DP = PHOTO_DP + 2 * (COLOUR_RING_DP + WHITE_RING_DP)

    /** From the top of the head to the tip of the tail. */
    const val TIP_FROM_TOP_DP = HEAD_DP + TAIL_DP - 2f

    /** Where the centre of the head sits above the tip, for hit testing. */
    const val HEAD_CENTRE_ABOVE_TIP_DP = TIP_FROM_TOP_DP - HEAD_DP / 2

    fun render(look: MarkerLook, density: Float): Bitmap {
        fun dp(v: Float) = v * density
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = dp(TEXT_SP)
        }
        val muted = Paint(text).apply { typeface = Typeface.DEFAULT; color = look.pillMuted }
        val name = look.name.trim().ifEmpty { "?" }.let { if (it.length > 14) it.take(13) + "…" else it }
        val age = look.age?.let { "  $it" }.orEmpty()
        val nameWidth = text.measureText(name)
        val ageWidth = if (age.isEmpty()) 0f else muted.measureText(age)
        val pillWidth = nameWidth + ageWidth + 2 * dp(PILL_PAD_DP)

        val shadow = dp(SHADOW_DP)
        val above = dp(TIP_FROM_TOP_DP) + shadow
        val below = dp(PILL_GAP_DP + PILL_HEIGHT_DP) + shadow
        val half = max(above, below)
        val width = ceil(max(dp(HEAD_DP), pillWidth) + 2 * shadow).toInt()
        val height = ceil(2 * half).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = (density * 160).toInt()
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val tipY = height / 2f
        val headTop = tipY - dp(TIP_FROM_TOP_DP)
        val headR = dp(HEAD_DP) / 2
        val headCy = headTop + headR

        // White disc and tail together, with a soft shadow.
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            setShadowLayer(dp(SHADOW_DP), 0f, dp(2f), 0x40101540)
        }
        canvas.drawCircle(cx, headCy, headR, white)
        val tail = Path().apply {
            moveTo(cx - dp(8f), headCy + headR - dp(4f))
            quadTo(cx - dp(2f), headCy + headR + dp(1f), cx, tipY)
            quadTo(cx + dp(2f), headCy + headR + dp(1f), cx + dp(8f), headCy + headR - dp(4f))
            close()
        }
        canvas.drawPath(tail, white)

        // The ring in this person's colour, then the picture (or the initial) inside it.
        val photoR = dp(PHOTO_DP) / 2
        val ringR = photoR + dp(COLOUR_RING_DP)
        canvas.drawCircle(cx, headCy, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ring })
        val photo = look.photo
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val edge = minOf(photo.width, photo.height)
            val scale = (2 * photoR) / edge
            val matrix = Matrix().apply {
                postTranslate(-(photo.width - edge) / 2f, -(photo.height - edge) / 2f)
                postScale(scale, scale)
                postTranslate(cx - photoR, headCy - photoR)
            }
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            canvas.drawCircle(cx, headCy, photoR, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        } else {
            val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = look.initialText
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = photoR * 1.0f
                textAlign = Paint.Align.CENTER
            }
            val letter = look.name.trim().take(1).uppercase().ifEmpty { "?" }
            canvas.drawText(letter, cx, headCy - (initial.ascent() + initial.descent()) / 2, initial)
        }

        // The name pill, and how old the fix is, under the tip.
        val pillTop = tipY + dp(PILL_GAP_DP)
        val pill = RectF(cx - pillWidth / 2, pillTop, cx + pillWidth / 2, pillTop + dp(PILL_HEIGHT_DP))
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = look.pillBackground
            setShadowLayer(dp(4f), 0f, dp(1.5f), 0x33101540)
        }
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pillPaint)
        val baseline = pill.centerY() - (text.ascent() + text.descent()) / 2
        text.color = look.pillText
        canvas.drawText(name, pill.left + dp(PILL_PAD_DP), baseline, text)
        if (age.isNotEmpty()) canvas.drawText(age, pill.left + dp(PILL_PAD_DP) + nameWidth, baseline, muted)
        return bitmap
    }
}
