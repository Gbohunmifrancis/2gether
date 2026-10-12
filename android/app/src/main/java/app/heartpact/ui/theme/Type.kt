package app.heartpact.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.heartpact.R

val Sora = FontFamily(
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
)

val Figtree = FontFamily(
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** One ramp: Sora for display, numerals and titles; Figtree for everything you read or tap. */
@Immutable
data class HeartpactType(
    val display: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 60.sp, lineHeight = 55.sp, letterSpacing = (-0.04).em, lineHeightStyle = Trim),
    val displaySmall: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 42.sp, lineHeight = 41.sp, letterSpacing = (-0.035).em, lineHeightStyle = Trim),
    val headline: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-0.03).em, lineHeightStyle = Trim),
    val title: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 23.sp, letterSpacing = (-0.02).em),
    val titleSmall: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.015).em),
    val wordmark: TextStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 22.sp, letterSpacing = (-0.045).em),
    val bodyLarge: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    val body: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    val small: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 17.5.sp),
    val label: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.5.sp),
    val labelSmall: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 15.5.sp),
    val button: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.sp),
)

val LocalHeartpactType = staticCompositionLocalOf { HeartpactType() }
