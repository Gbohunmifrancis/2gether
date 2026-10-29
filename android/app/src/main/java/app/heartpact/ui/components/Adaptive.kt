package app.heartpact.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen widths, as Material's window size classes: phones; tablets upright and phones on their side; tablets lying
 * down. Phones get the bottom bar, everything wider the navigation rail.
 */
enum class WidthClass { Compact, Medium, Expanded }

/** From the window's own size, so split screen and foldables get the layout for the room they really have. */
@Composable
fun widthClass(): WidthClass {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return when {
        width < 600.dp -> WidthClass.Compact
        width < 840.dp -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
}

/** The widest one column of cards gets: past this, lines run long and cards look empty. */
val ReadableWidth = 640.dp

/** The widest two panes get together. */
val TwoPaneWidth = 1120.dp

/** Room for two panes side by side. */
val TwoPaneFrom = 840.dp

/**
 * Keeps content to a readable width, centred. Whatever comes before it in the chain (a background, the scroll) still
 * spans the whole screen; what comes after it is the centred column.
 */
fun Modifier.readable(max: Dp = ReadableWidth): Modifier = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * A one-column screen on a tablet: it keeps a readable width, centred on the page. Inline, so a navigation route can
 * still return early from inside it.
 */
@Composable
inline fun ReadableScreen(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().readable()) { content() }

/**
 * A tab's page: a [header], then its sections, scrolling as one. On a phone the sections come one under the other,
 * [first] then [second]. With room for two panes, [first] and [second] sit side by side; give [first] what matters
 * most right now and [second] the rest, and the phone order stays the same. A [wide] page with one pane (a page of
 * cards in [CardColumns]) takes the same room as two.
 */
@Composable
fun AdaptivePage(
    header: @Composable ColumnScope.() -> Unit,
    second: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    wide: Boolean = false,
    first: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val room = maxWidth >= TwoPaneFrom
        val twoPanes = second != null && room
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).readable(if (twoPanes || wide && room) TwoPaneWidth else ReadableWidth)) {
            header()
            if (twoPanes) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) { first() }
                    Column(Modifier.weight(1f)) { second?.invoke(this) }
                }
            } else {
                first()
                second?.invoke(this)
            }
        }
    }
}

/**
 * Cards in as many columns as fit at [minWidth] each, up to [most]: one on a phone, two or three on a tablet. Each
 * row's cards share its width evenly, [gap] apart; a short last row keeps the same column width.
 */
@Composable
fun <T> CardColumns(items: List<T>, modifier: Modifier = Modifier, minWidth: Dp = 360.dp, most: Int = 3, gap: Dp = 0.dp, card: @Composable (T) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / minWidth).toInt().coerceIn(1, most)
        if (columns == 1) {
            Column { items.forEach { card(it) } }
        } else {
            Column {
                items.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { item -> Box(Modifier.weight(1f)) { card(item) } }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
