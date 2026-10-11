package app.heartpact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/**
 * The outlined field: a white well with its label notched into the top edge. Focus draws the
 * outline in the actor's colour; an error draws it in the error colour and says why underneath.
 */
@Composable
fun HeartField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false,
    error: String? = null,
    hint: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    /** More than one line makes it a writing box (answers, notes) instead of a single-line field. */
    minLines: Int = 1,
    maxLength: Int? = null,
) {
    val colors = Heartpact.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val outline = when {
        error != null -> colors.error
        focused -> Heartpact.actor.action
        else -> colors.rule2
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 9.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Shapes.Field)
                    .background(colors.sticker)
                    .border(if (focused || error != null) 2.5.dp else 2.dp, outline, Shapes.Field)
                    .padding(start = 16.dp, end = if (password || trailing != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(if (maxLength != null) it.take(maxLength) else it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .semantics {
                            contentDescription = label
                            if (error != null) error(error)
                        },
                    singleLine = minLines == 1,
                    minLines = minLines,
                    textStyle = Heartpact.type.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(Heartpact.actor.action),
                    keyboardOptions = if (password) keyboard.copy(keyboardType = KeyboardType.Password) else keyboard,
                    keyboardActions = actions,
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation('•') else VisualTransformation.None,
                    interactionSource = interaction,
                )
                if (password) {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide password" else "Show password", tint = colors.ink2)
                    }
                }
                trailing?.invoke()
            }
            Text(
                label,
                style = TextStyle(fontFamily = Heartpact.type.label.fontFamily, fontWeight = Heartpact.type.label.fontWeight, fontSize = 12.sp),
                color = if (error != null) colors.error else if (focused) Heartpact.actor.action else colors.ink2,
                modifier = Modifier
                    .offset(x = 12.dp, y = (-9).dp)
                    .clip(Shapes.Tile)
                    .background(colors.sticker)
                    .padding(horizontal = 6.dp),
            )
        }
        val note = error ?: hint
        if (maxLength != null && minLines > 1) {
            Text("${value.length} / $maxLength", style = Heartpact.type.small, color = colors.ink3, modifier = Modifier.align(Alignment.End).padding(top = 6.dp, end = 4.dp))
        }
        if (note != null) {
            Text(note, style = Heartpact.type.small, color = if (error != null) colors.error else colors.ink2, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
    }
}
