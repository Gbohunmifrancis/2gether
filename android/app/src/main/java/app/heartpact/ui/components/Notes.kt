package app.heartpact.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

/** A short message that isn't tied to one field: offline, rate limited, server trouble. Read out when it appears. */
@Composable
fun NoteCard(text: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(Shapes.Well)
            .background(colors.sticker2)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = colors.ink2)
        Text(text, style = Heartpact.type.body, color = colors.ink)
    }
}
