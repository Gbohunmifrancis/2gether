package app.heartpact.ui.pair

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/**
 * A partner's code as six boxes, like a one-time code: one hidden field behind them, so typing moves along by itself,
 * backspace steps back, and a pasted or autofilled code fills them all. [onCheckClipboard] runs when the field is
 * focused or tapped, never in the background.
 */
@Composable
fun CodeBoxes(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = InviteParsing.CODE_DIGITS,
    error: String? = null,
    focusRequester: FocusRequester = androidx.compose.runtime.remember { FocusRequester() },
    onCheckClipboard: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val colors = Heartpact.colors
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onCheckClipboard() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) onCheckClipboard() }
    }
    val label = tr("Your partner's code")
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            // The cursor stays at the end: digits are only ever added or taken from the end.
            value = TextFieldValue(value, TextRange(value.length)),
            onValueChange = { onValue(it.text.filter { c -> c in '0'..'9' }.take(length)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics {
                contentDescription = label
                stateDescription = tr("{typed} of {total} digits", "typed" to value.length.toString(), "total" to length.toString())
                if (error != null) error(error)
            },
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(Heartpact.actor.action),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = {
                // Digits run left to right in every language, Arabic included.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until length) {
                        // A small gap in the middle, as the code is shown: "482 031".
                        if (i == length / 2) Spacer(Modifier.width(6.dp))
                        val digit = value.getOrNull(i)
                        val current = focused && (i == value.length || (i == length - 1 && value.length == length))
                        val outline = when {
                            error != null -> colors.error
                            current -> Heartpact.actor.action
                            digit != null -> colors.ink3
                            else -> colors.rule2
                        }
                        Box(
                            Modifier.weight(1f, fill = false).width(46.dp).height(58.dp).clip(Shapes.Field).background(colors.sticker)
                                .border(if (current || error != null) 2.5.dp else 2.dp, outline, Shapes.Field),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (digit != null) Text(digit.toString(), style = Heartpact.type.title.copy(fontSize = 24.sp), color = colors.ink)
                            else if (current) Box(Modifier.width(2.dp).height(24.dp).background(Heartpact.actor.action))
                        }
                    }
                } }
            },
        )
        if (error != null) {
            Text(error, style = Heartpact.type.small, color = colors.error,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** Reads the clipboard once, when asked (the code field focused or tapped). Android 13+ shows its own "pasted" note. */
object ClipboardInvite {
    fun read(context: Context): InviteInput? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        // Looking at the description doesn't read the clip, so nothing is shown for an empty or non-text clipboard.
        if (!clipboard.hasPrimaryClip()) return null
        val description = clipboard.primaryClipDescription ?: return null
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return null
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        return InviteParsing.parse(text?.take(2_000))
    }
}
