package app.heartpact.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.theme.Heartpact

/**
 * Home before linking ("Connect later" on the pair screen): a strip above every tab that opens the pair screen, where
 * the invite code waits. It goes once the partner joins.
 */
@Composable
fun ConnectBanner(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    StickerCard(modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // My half, the partner's still an empty slot.
            HeartSticker(HalfState.Stuck, HalfState.Slot, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("Connect with your partner"), style = Heartpact.type.title, color = colors.ink)
                Text(tr("Share your invite code, or enter theirs."), style = Heartpact.type.small, color = colors.ink2)
            }
            HeartButton(tr("Connect"), onConnect, fillWidth = false, height = 40.dp, icon = Icons.Rounded.FavoriteBorder)
        }
    }
}

/** Something that needs a partner (a dare, the daily question, the album, location, food) tapped before linking. */
@Composable
fun NeedsPartnerDialog(onConnect: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Connect with your partner first")) },
        text = { Text(tr("This is something you do together. Share your invite code or enter theirs, and it's ready for both of you.")) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConnect() }) { Text(tr("Connect now")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}
