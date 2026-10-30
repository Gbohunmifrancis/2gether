package app.heartpact.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Heartpact's tokens (DESIGN.md, "Indigo Fold"). One ink folded in two: the couple's first
 * member is the solid indigo half, the second the periwinkle half, on every screen and phone.
 */
@Immutable
data class HeartpactColors(
    val page: Color,
    val page2: Color,
    val page3: Color,
    val sticker: Color,
    val sticker2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val rule: Color,
    val rule2: Color,
    val left: PartnerColors,
    val right: PartnerColors,
    /** Label colour on a filled action button: white by day, deep ink at night. */
    val onAction: Color,
    val error: Color,
    val isNight: Boolean,
)

/** One partner's colours. Art is for stickers and characters; action carries labels. */
@Immutable
data class PartnerColors(
    val art: Color,
    val action: Color,
    val soft: Color,
    val tint: Color,
    /** Text on soft and tint fills (the action value fails small text there). */
    val onSoft: Color,
)

/** Christmas accents: the gold of the lights' caps and ornaments, and the bulbs' colours. */
object Festive {
    val Gold = Color(0xFFD4A537)
    val Bulbs = listOf(Color(0xFFE5484D), Color(0xFFF5C451), Color(0xFF3DBE7A), Color(0xFF5AA9F0), Color(0xFFF08BC0))
}

object Art {
    /** Sticker colours stay fixed at night, framed by their white keyline. At Christmas: holly red and pine green. */
    val Left get() = if (Season.christmas) Color(0xFFA61B29) else Color(0xFF1F2557)
    val Right get() = if (Season.christmas) Color(0xFF1E6B45) else Color(0xFF7480D4)
    val Keyline = Color(0xFFFFFFFF)
    val Shadow get() = if (Season.christmas) Color(0xFF3A1216) else Color(0xFF1F2557)
    val SplashIndigo get() = if (Season.christmas) Color(0xFF0F3D2E) else Color(0xFF1F2557)
    /** The mark on night grounds: the left half turns white. */
    val MarkNightLeft = Color(0xFFFFFFFF)
    val MarkNightRight get() = if (Season.christmas) Color(0xFF9ADBB1) else Color(0xFFA9B1F2)
}

val DayColors = HeartpactColors(
    page = Color(0xFFE3E5F6),
    page2 = Color(0xFFD7DAEF),
    page3 = Color(0xFFCBCFE8),
    sticker = Color(0xFFFFFFFF),
    sticker2 = Color(0xFFF2F3FA),
    ink = Color(0xFF161A3D),
    ink2 = Color(0xFF474C72),
    ink3 = Color(0xFF6A6F92),
    ink4 = Color(0xFFA4A8C6),
    rule = Color(0xFFE4E6F3),
    rule2 = Color(0xFFC8CBE3),
    left = PartnerColors(art = Art.Left, action = Color(0xFF1F2557), soft = Color(0xFFD5D8F6), tint = Color(0xFFECEEFC), onSoft = Color(0xFF1F2557)),
    right = PartnerColors(art = Art.Right, action = Color(0xFF4D58BE), soft = Color(0xFFDFE2FA), tint = Color(0xFFEEF0FD), onSoft = Color(0xFF3F49A8)),
    onAction = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    isNight = false,
)

val NightColors = HeartpactColors(
    page = Color(0xFF10132B),
    page2 = Color(0xFF161A38),
    page3 = Color(0xFF1F2446),
    sticker = Color(0xFF1E2347),
    sticker2 = Color(0xFF262C55),
    ink = Color(0xFFEEF0FB),
    ink2 = Color(0xFFB5B9D8),
    ink3 = Color(0xFF8C91B6),
    ink4 = Color(0xFF5B618C),
    rule = Color(0xFF2B3160),
    rule2 = Color(0xFF383F72),
    left = PartnerColors(art = Art.Left, action = Color(0xFFE6E8FF), soft = Color(0xFF2C3263), tint = Color(0xFF232853), onSoft = Color(0xFFE6E8FF)),
    right = PartnerColors(art = Art.Right, action = Color(0xFFA9B1F2), soft = Color(0xFF2F3670), tint = Color(0xFF262C5C), onSoft = Color(0xFFA9B1F2)),
    onAction = Color(0xFF10142A),
    error = Color(0xFFFFB4AB),
    isNight = true,
)

private val Holly = Color(0xFFA61B29)
private val Pine = Color(0xFF1E6B45)

/** Christmas by day: ivory pages like fresh paper, warm ink, cranberry and pine for the two of you. */
val DayChristmas = DayColors.copy(
    page = Color(0xFFF8F3EA), page2 = Color(0xFFEFE6D6), page3 = Color(0xFFE6DAC6), sticker2 = Color(0xFFFFFBF4),
    ink = Color(0xFF1F1A17), ink2 = Color(0xFF52463E), ink3 = Color(0xFF7A6C61), ink4 = Color(0xFFB9AC9E),
    rule = Color(0xFFF0E7D9), rule2 = Color(0xFFDCCDB8),
    left = PartnerColors(art = Holly, action = Color(0xFFA61B29), soft = Color(0xFFF6DADA), tint = Color(0xFFFCEFEE), onSoft = Color(0xFF861522)),
    right = PartnerColors(art = Pine, action = Color(0xFF1E6B45), soft = Color(0xFFD9EBDF), tint = Color(0xFFEEF6F0), onSoft = Color(0xFF17573A)),
)

/** Christmas by night: deep pine pages, the same two colours lit up. */
val NightChristmas = NightColors.copy(
    page = Color(0xFF0E1A15), page2 = Color(0xFF13241C), page3 = Color(0xFF1A3025), sticker = Color(0xFF1B2D24), sticker2 = Color(0xFF22392D),
    ink = Color(0xFFF4EFE6), ink2 = Color(0xFFC9CFC4), ink3 = Color(0xFF9AA79B), ink4 = Color(0xFF607464),
    rule = Color(0xFF2B4538), rule2 = Color(0xFF365646),
    left = PartnerColors(art = Holly, action = Color(0xFFFF9A9A), soft = Color(0xFF4A2326), tint = Color(0xFF3A1D20), onSoft = Color(0xFFFFB4B4)),
    right = PartnerColors(art = Pine, action = Color(0xFF8FD5A6), soft = Color(0xFF1F4A33), tint = Color(0xFF183D2A), onSoft = Color(0xFF8FD5A6)),
    onAction = Color(0xFF10201A),
)

val LocalHeartpactColors = staticCompositionLocalOf { DayColors }

/** Whose action colour presses carry on this screen: the person acting. */
val LocalActor = staticCompositionLocalOf { Side.Left }

enum class Side { Left, Right }

fun HeartpactColors.partner(side: Side): PartnerColors = if (side == Side.Left) left else right
