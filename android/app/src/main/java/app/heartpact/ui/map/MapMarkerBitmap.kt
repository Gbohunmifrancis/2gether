package app.heartpact.ui.map

import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import app.heartpact.ui.graphics.inUnits
import app.heartpact.ui.graphics.drawCharacters
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.CHAR_VB_Y
import app.heartpact.ui.graphics.CHAR_VB_X
import app.heartpact.ui.graphics.CHAR_VB_W
import app.heartpact.ui.graphics.CHAR_VB_H
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
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
    /** Which half of the heart this person is: they stand on the map as that half, like a Snap Map Bitmoji. */
    val rightHalf: Boolean = false,
    /** The letter on the badge with no picture: their own name's, even when the pill says "You". */
    val initial: String? = null,
)

/**
 * Draws a map marker: the profile picture in a circle with a white ring and a ring in the person's colour, a small
 * pointer tail, and a name pill under it. The image is as tall below its tip as above, so a symbol anchored at the
 * image's centre puts the tip of the tail exactly on the person's place.
 */
object MapMarkerBitmap {
    /** The picture badge on the character's outer shoulder. */
    const val PHOTO_DP = 30f
    const val COLOUR_RING_DP = 2.5f
    const val WHITE_RING_DP = 3f
    private const val PILL_GAP_DP = 4f
    private const val PILL_HEIGHT_DP = 24f
    private const val PILL_PAD_DP = 10f
    private const val SHADOW_DP = 6f
    private const val TEXT_SP = 12f

    /** The character's drawn width; its height follows the character art's proportions. */
    const val CHARACTER_DP = 108f
    private val CHARACTER_HEIGHT_DP = CHARACTER_DP * (CHAR_VB_H / CHAR_VB_W)
    /** Diameter of the badge, rings included. */
    const val BADGE_DP = PHOTO_DP + 2 * (COLOUR_RING_DP + WHITE_RING_DP)

    /** From the top of the drawing to the tip (the feet), where the symbol is anchored. */
    val TIP_FROM_TOP_DP = CHARACTER_HEIGHT_DP * ((FEET_UNIT_Y - CHAR_VB_Y) / CHAR_VB_H)
    /** The badge's centre, above the tip and out to the side of the half it sits on. */
    val BADGE_CENTRE_ABOVE_TIP_DP = TIP_FROM_TOP_DP * 0.62f
    const val BADGE_SIDE_DP = 34f

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
        val width = ceil(max(dp(CHARACTER_DP) + dp(BADGE_SIDE_DP) * 2, pillWidth) + 2 * shadow).toInt()
        val height = ceil(2 * half).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = (density * 160).toInt()
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val tipY = height / 2f

        // A soft shadow on the ground where they stand.
        canvas.drawOval(RectF(cx - dp(22f), tipY - dp(4f), cx + dp(22f), tipY + dp(4f)), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33101540 })

        // Their half of the heart, standing with its feet on the tip and waving: the app's own character art.
        val charW = dp(CHARACTER_DP)
        val charH = dp(CHARACTER_HEIGHT_DP)
        val who = if (look.rightHalf) Who.Right else Who.Left
        // Each half sits on its side of the character canvas: slide it so the half itself is centred over the tip.
        val unit = charW / CHAR_VB_W
        val slide = (if (look.rightHalf) -HALF_CENTRE_OFFSET_UNITS else HALF_CENTRE_OFFSET_UNITS) * unit
        val left = cx - charW / 2 + slide
        val top = tipY - dp(TIP_FROM_TOP_DP)
        canvas.save()
        canvas.translate(left, top)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, ComposeCanvas(canvas), Size(charW, charH)) {
            inUnits(CHAR_VB_X, CHAR_VB_Y, CHAR_VB_W, CHAR_VB_H) { drawCharacters(who, Pose.Wave, Mood.Smile, sticker = true) }
        }
        canvas.restore()

        // The picture badge on the outer shoulder: white ring, a ring in their colour, then the picture or initial.
        val badgeCx = cx + (if (look.rightHalf) 1 else -1) * dp(BADGE_SIDE_DP)
        val badgeCy = tipY - dp(BADGE_CENTRE_ABOVE_TIP_DP)
        val photoR = dp(PHOTO_DP) / 2
        val ringR = photoR + dp(COLOUR_RING_DP)
        val whiteR = ringR + dp(WHITE_RING_DP)
        canvas.drawCircle(badgeCx, badgeCy, whiteR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            setShadowLayer(dp(3f), 0f, dp(1f), 0x40101540)
        })
        canvas.drawCircle(badgeCx, badgeCy, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ring })
        val photo = look.photo
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val edge = minOf(photo.width, photo.height)
            val scale = (2 * photoR) / edge
            val matrix = Matrix().apply {
                postTranslate(-(photo.width - edge) / 2f, -(photo.height - edge) / 2f)
                postScale(scale, scale)
                postTranslate(badgeCx - photoR, badgeCy - photoR)
            }
            val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            canvas.drawCircle(badgeCx, badgeCy, photoR, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        } else {
            val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = look.initialText
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = photoR * 1.0f
                textAlign = Paint.Align.CENTER
            }
            val letter = (look.initial ?: look.name).trim().take(1).uppercase().ifEmpty { "?" }
            canvas.drawText(letter, badgeCx, badgeCy - (initial.ascent() + initial.descent()) / 2, initial)
        }

        // The name pill, and how old the fix is, under their feet.
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

    /** In character units: the bottom of the feet, and how far each half's middle sits from the canvas middle. */
    private const val FEET_UNIT_Y = 116f
    private const val HALF_CENTRE_OFFSET_UNITS = 25f
}
