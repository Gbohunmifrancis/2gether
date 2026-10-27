package app.heartpact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.ui.graphics.Icon3D
import app.heartpact.ui.graphics.Icon3DStill
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** Sticker stock: a white card lifted off the page by a soft, indigo-tinted shadow (Lift-Not-Line). */
@Composable
fun StickerCard(modifier: Modifier = Modifier, shape: Shape = Shapes.Card, lift: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = Heartpact.colors
    Column(
        modifier
            .shadow(lift, shape, ambientColor = colors.left.art.copy(alpha = 0.5f), spotColor = colors.left.art.copy(alpha = 0.55f))
            .clip(shape)
            .background(colors.sticker),
        content = content,
    )
}

/** A list row inside a card. Rows after the first get a dashed perforation, like a page meant to be torn. */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon3d: Icon3D? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = Heartpact.colors
    var m = modifier.fillMaxWidth()
    if (divider) m = m.drawBehind {
        drawLine(colors.rule, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
    }
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.defaultMinSize(minHeight = 54.dp).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // A 3D icon (feature rows) takes the flat icon's place, bigger: its art fills about two thirds of the square.
        if (icon3d != null) Icon3DStill(icon3d, 44.dp) else icon?.let { Icon(it, null, Modifier.size(22.dp), tint = colors.ink2) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Heartpact.type.label.copy(fontSize = Heartpact.type.label.fontSize * 1.035f), color = colors.ink)
            subtitle?.let { Text(it, style = Heartpact.type.small, color = colors.ink2) }
        }
        trailing()
    }
}

/** A selectable chip. Selected chips take the actor's soft fill, a check, and the on-soft ink. */
@Composable
fun HeartChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val shape = RoundedCornerShape(19.dp)
    var m = modifier.height(38.dp).clip(shape)
    m = if (selected) m.background(actor.soft) else m.background(colors.sticker).drawBehind {
        // A 2dp inner outline: the stroke straddles the clipped edge, so half of 4dp shows.
        drawRoundRect(colors.rule, cornerRadius = CornerRadius(19.dp.toPx()), style = Stroke(4.dp.toPx()))
    }
    Row(
        m.clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = actor.onSoft)
        Text(text, style = Heartpact.type.label.copy(fontSize = Heartpact.type.label.fontSize * 0.965f), color = if (selected) actor.onSoft else colors.ink2)
    }
}

/** Section heading with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Heartpact.type.titleSmall, color = Heartpact.colors.ink, modifier = Modifier.weight(1f))
        action?.let { Text(it, style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.clickable(onClick = onAction)) }
    }
}

/** Pager dots: the current page stretches into a pill in the actor's colour. */
@Composable
fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (index == current) 26.dp else 8.dp)
                    .clip(Shapes.Pill)
                    .background(if (index == current) Heartpact.actor.action else Heartpact.colors.ink4),
            )
        }
    }
}

@Composable
fun Spacer(height: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.height(height))

@Composable
fun ColumnScope.Fill() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
