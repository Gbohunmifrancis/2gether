package app.heartpact.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

enum class ButtonKind { Filled, Tonal, Outline, Text }

/**
 * The pill button. Filled carries the acting partner's action colour; only things you can press
 * get saturated colour (Press-or-Own). While [busy], it shows progress and ignores taps.
 */
@Composable
fun HeartButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    height: Dp = 56.dp,
    fillWidth: Boolean = true,
    /** Overrides the label colour, for text buttons on dark grounds such as the alarm. */
    contentColor: Color? = null,
    /** Draws [icon] in its own colours: a brand mark such as Google's "G". */
    keepIconColors: Boolean = false,
) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val (background, defaultContent) = when (kind) {
        ButtonKind.Filled -> actor.action to colors.onAction
        ButtonKind.Tonal -> actor.soft to actor.onSoft
        ButtonKind.Outline -> Color.Transparent to colors.ink
        ButtonKind.Text -> Color.Transparent to actor.action
    }
    val content = contentColor ?: defaultContent
    val active = enabled && !busy
    var m = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).height(if (kind == ButtonKind.Text) 46.dp.coerceAtMost(height) else height)
    if (kind == ButtonKind.Filled && enabled) m = m.shadow(10.dp, Shapes.Pill, ambientColor = actor.action, spotColor = actor.action)
    m = m.clip(Shapes.Pill).background(if (enabled) background else if (kind == ButtonKind.Filled) colors.ink4 else background)
    if (kind == ButtonKind.Outline) m = m.border(BorderStroke(2.dp, colors.rule2), Shapes.Pill)
    m = m.clickable(enabled = active, role = Role.Button, onClick = onClick).padding(horizontal = if (kind == ButtonKind.Text) 14.dp else 24.dp)
    Box(m, contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.5.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(20.dp), tint = if (keepIconColors) Color.Unspecified else content) }
                Text(text, style = Heartpact.type.button, color = if (enabled) content else colors.sticker)
                trailingIcon?.let { Icon(it, null, Modifier.size(20.dp), tint = content) }
            }
        }
    }
}
