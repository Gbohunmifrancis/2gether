package app.heartpact.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object Heartpact {
    val colors: HeartpactColors @Composable @ReadOnlyComposable get() = LocalHeartpactColors.current
    val type: HeartpactType @Composable @ReadOnlyComposable get() = LocalHeartpactType.current
    /** The partner whose presses this screen carries. */
    val actor: PartnerColors @Composable @ReadOnlyComposable get() = LocalHeartpactColors.current.partner(LocalActor.current)
}

object Shapes {
    val Dare = RoundedCornerShape(30.dp)
    val Card = RoundedCornerShape(26.dp)
    val Well = RoundedCornerShape(20.dp)
    val Field = RoundedCornerShape(18.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
}

@Composable
fun HeartpactTheme(night: Boolean = isSystemInDarkTheme(), actor: Side = Side.Left, content: @Composable () -> Unit) {
    val colors = if (night) NightColors else DayColors
    // Material components read their own scheme; keep it in step so anything stock still fits.
    val scheme = if (night) {
        darkColorScheme(primary = colors.left.action, onPrimary = colors.onAction, background = colors.page, surface = colors.sticker, onSurface = colors.ink, error = colors.error)
    } else {
        lightColorScheme(primary = colors.left.action, onPrimary = colors.onAction, background = colors.page, surface = colors.sticker, onSurface = colors.ink, error = colors.error)
    }
    CompositionLocalProvider(
        LocalHeartpactColors provides colors,
        LocalHeartpactType provides HeartpactType(),
        LocalActor provides actor,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
