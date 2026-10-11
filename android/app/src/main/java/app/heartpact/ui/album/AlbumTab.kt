package app.heartpact.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.heartpact.album.LocalPhoto
import app.heartpact.data.Memory
import app.heartpact.data.utc
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import coil3.compose.AsyncImage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Draws one photo. The app uses Coil; previews and screenshot tests pass a stand-in. */
typealias PhotoSlot = @Composable (model: Any?, description: String?, modifier: Modifier, crop: Boolean) -> Unit

val CoilPhoto: PhotoSlot = { model, description, modifier, crop ->
    AsyncImage(model = model, contentDescription = description, modifier = modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
}

/**
 * Album: offline-first, like a chat app's media. Photos added here stay on this phone (badged
 * with a phone) until the person taps Sync; then they join the shared album, each badged with
 * the half of the heart that added it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumTab(
    ui: AlbumUi,
    sides: Map<String, Side>,
    localModel: (LocalPhoto) -> Any?,
    onAdd: () -> Unit,
    onSync: () -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onLoadMore: () -> Unit,
    onDismissResult: () -> Unit,
    unmetered: () -> Boolean,
    image: PhotoSlot = CoilPhoto,
    /** Set when the couple is on Free: a nearly full album then offers Plus's extra space. */
    onGetPlus: (() -> Unit)? = null,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var openLocal by remember { mutableStateOf<LocalPhoto?>(null) }
    var openShared by remember { mutableStateOf<Memory?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            full("header") { Header(ui) }
            if (ui.storageMissing) full("storage") { NoteCard("Photo storage isn't set up on this server yet. Photos you add stay safe on this phone until it is.", Modifier.padding(bottom = 10.dp)) }
            ui.note?.let { note -> full("note") { NoteCard(note, Modifier.padding(bottom = 10.dp)) } }
            ui.result?.let { result -> full("result") { ResultCard(result, onDismissResult) } }
            val usage = ui.usage
            val nearlyFull = usage != null && usage.limitBytes > 0 && usage.usedBytes >= usage.limitBytes * 9 / 10
            val refusedFull = ui.refused.any { it.errorCode == "memories.quota_exceeded" }
            if (onGetPlus != null && (nearlyFull || refusedFull)) full("plus") { PlusPrompt(refusedFull || (usage != null && usage.usedBytes >= usage.limitBytes), onGetPlus) }
            if (ui.waiting.isNotEmpty() || ui.sync != SyncState.Idle) full("banner") { SyncBanner(ui, onSync = { sheetOpen = true }) }
            if (ui.onThisDay.isNotEmpty()) full("onthisday") { OnThisDay(ui.onThisDay, image) { openShared = it } }

            if (ui.local.isNotEmpty()) {
                full("local-title") { Section("Only on this phone", if (ui.refused.isEmpty()) AlbumText.photos(ui.local.size) else "${ui.waiting.size} waiting · ${ui.refused.size} not added") }
                items(ui.local, key = { "local-" + it.clientId }) { photo ->
                    LocalTile(photo, localModel(photo), image) { openLocal = photo }
                }
            }
            if (ui.shared.isNotEmpty()) {
                full("shared-title") { Section("Shared", ui.usage?.let { AlbumText.photos(it.count) } ?: "") }
                items(ui.shared, key = { "shared-" + it.id }) { memory ->
                    SharedTile(memory, sides[memory.uploadedByUserId], image) { openShared = memory }
                }
                if (ui.nextCursor != null) full("more") {
                    LaunchedEffect(ui.nextCursor) { onLoadMore() }
                    Spacer(Modifier.height(48.dp))
                }
            }
            if (ui.loaded && ui.local.isEmpty() && ui.shared.isEmpty() && !ui.storageMissing) full("empty") { Empty() }
        }
        HeartButton(
            "Add photos", onAdd,
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = Icons.Rounded.AddPhotoAlternate, fillWidth = false,
        )
    }

    if (sheetOpen) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val onWifi = remember { unmetered() }
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = state, containerColor = Heartpact.colors.sticker, shape = Shapes.Sheet) {
            SyncSheetContent(ui, onWifi, onSync = { onSync(); sheetOpen = false }, onNotNow = { sheetOpen = false })
        }
    }
    openLocal?.let { photo ->
        val refused = photo.errorCode != null
        AlertDialog(
            onDismissRequest = { openLocal = null },
            title = { Text(if (refused) "Not added" else "Only on this phone") },
            text = { Text(if (refused) AlbumText.reason(photo) else "It joins your shared album the next time you sync. Removing it here never touches the original in your gallery.") },
            confirmButton = {
                if (refused) TextButton(onClick = { onRetry(photo.clientId); openLocal = null }) { Text("Try again") }
                else TextButton(onClick = { openLocal = null }) { Text("Keep it") }
            },
            dismissButton = { TextButton(onClick = { onRemove(photo.clientId); openLocal = null }) { Text("Remove from Heartpact") } },
        )
    }
    openShared?.let { memory -> PhotoViewer(memory, image) { openShared = null } }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(ui: AlbumUi) {
    Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 14.dp)) {
        Text("Album", style = Heartpact.type.headline, color = Heartpact.colors.ink)
        Text(
            ui.usage?.let { "Shared: " + AlbumText.usage(it) } ?: "Photos stay on your phone until you sync",
            style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The album is nearly full on Free: Plus has more room for both of you. */
@Composable
private fun PlusPrompt(full: Boolean, onGetPlus: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Shapes.Well).background(colors.left.tint).padding(14.dp)) {
        Text(if (full) "Your shared album is full" else "Your shared album is nearly full", style = Heartpact.type.label, color = colors.ink)
        Text("Plus gives you both more space. Or delete a few shared photos to make room.", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
        HeartButton("See Plus", onGetPlus, Modifier.padding(top = 10.dp), kind = ButtonKind.Tonal, height = 44.dp, fillWidth = false)
    }
}

@Composable
private fun ResultCard(text: String, onDismiss: () -> Unit) {
    val colors = Heartpact.colors
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Shapes.Well).background(colors.left.tint).padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeartSticker(HalfState.Stuck, HalfState.Stuck, 20.dp)
        Text(text, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp))
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = colors.ink2) }
    }
}

@Composable
private fun SyncBanner(ui: AlbumUi, onSync: () -> Unit) {
    val colors = Heartpact.colors
    StickerCard(Modifier.padding(bottom = 14.dp).fillMaxWidth(), lift = 6.dp) {
        Column(Modifier.padding(16.dp)) {
            when (val sync = ui.sync) {
                is SyncState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(24.dp), tint = Heartpact.actor.action)
                        Text(if (sync.total > 0) "Syncing ${sync.done} of ${sync.total}" else "Getting ready to sync", style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(start = 10.dp))
                    }
                    LinearProgressIndicator(
                        progress = { if (sync.total > 0) sync.done / sync.total.toFloat() else 0f },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(Shapes.Pill),
                        color = Heartpact.actor.action, trackColor = colors.sticker2, drawStopIndicator = {},
                    )
                    Text("Keeps going if you leave Heartpact.", style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
                }
                SyncState.WaitingForNetwork -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(24.dp), tint = colors.ink2)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Waiting for a connection", style = Heartpact.type.titleSmall, color = colors.ink)
                            Text("Your sync starts as soon as you're online.", style = Heartpact.type.small, color = colors.ink2)
                        }
                    }
                }
                SyncState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(Shapes.Tile).background(colors.sticker2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(22.dp), tint = colors.ink)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("${AlbumText.photos(ui.waiting.size)} waiting", style = Heartpact.type.titleSmall, color = colors.ink)
                        Text("Only on this phone · ${AlbumText.size(ui.waitingBytes)}", style = Heartpact.type.small, color = colors.ink2)
                    }
                    HeartButton("Sync", onSync, kind = ButtonKind.Filled, height = 44.dp, fillWidth = false, enabled = !ui.storageMissing)
                }
            }
        }
    }
}

/** The sheet behind Sync: how much, on which network, what the album holds after. */
@Composable
fun SyncSheetContent(ui: AlbumUi, onWifi: Boolean, onSync: () -> Unit, onNotNow: () -> Unit) {
    val colors = Heartpact.colors
    val after = AlbumText.after(ui.usage, ui.waiting)
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 22.dp).navigationBarsPadding()) {
        Text("Sync ${AlbumText.photos(ui.waiting.size)}?", style = Heartpact.type.title, color = colors.ink)
        Text("They join your shared album, and your partner sees them once they're in.", style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetRow(if (onWifi) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt,
                if (onWifi) "You're on Wi-Fi" else "You're on mobile data",
                if (onWifi) "${AlbumText.size(ui.waitingBytes)} to send" else "${AlbumText.size(ui.waitingBytes)} to send, which may count against your data")
            SheetRow(Icons.Rounded.CloudUpload, "Keeps going in the background",
                "Leave Heartpact whenever you like. If the connection drops, it carries on when you're back.")
            if (after.limitBytes > 0) {
                val tail = if (after.fits < ui.waiting.size) "Only ${after.fits} of ${ui.waiting.size} fit; the rest stay on this phone." else "Everything fits."
                SheetRow(Icons.Rounded.PhoneAndroid, "After: ${AlbumText.size(after.usedBytes)} of ${AlbumText.size(after.limitBytes)}", tail)
            }
        }
        HeartButton("Sync now", onSync, Modifier.padding(top = 22.dp), icon = Icons.Rounded.CloudUpload)
        HeartButton("Not now", onNotNow, Modifier.padding(top = 4.dp), kind = ButtonKind.Text)
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val colors = Heartpact.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(Shapes.Tile).background(colors.sticker2), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = colors.ink)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = Heartpact.type.label, color = colors.ink)
            Text(body, style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun OnThisDay(memories: List<Memory>, image: PhotoSlot, onOpen: (Memory) -> Unit) {
    val colors = Heartpact.colors
    val today = LocalDate.now()
    StickerCard(Modifier.padding(bottom = 14.dp).fillMaxWidth(), shape = Shapes.Dare, lift = 6.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("On this day", style = Heartpact.type.titleSmall, color = colors.ink)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memories.take(3).forEach { memory ->
                    val years = today.year - utc(memory.sortAtUtc).atZone(ZoneId.systemDefault()).year
                    Column(Modifier.weight(1f).clickable { onOpen(memory) }) {
                        image(memory.thumbnailUrl ?: memory.url, memory.caption, Modifier.fillMaxWidth().aspectRatio(1f).clip(Shapes.Tile), true)
                        Text(if (years == 1) "A year ago" else "$years years ago", style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(3 - memories.take(3).size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, count: String) {
    Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Heartpact.type.titleSmall, color = Heartpact.colors.ink, modifier = Modifier.weight(1f))
        Text(count, style = Heartpact.type.small, color = Heartpact.colors.ink2)
    }
}

@Composable
private fun LocalTile(photo: LocalPhoto, model: Any?, image: PhotoSlot, onClick: () -> Unit) {
    val colors = Heartpact.colors
    val refused = photo.errorCode != null
    Box(
        Modifier.aspectRatio(1f).clip(Shapes.Tile).background(colors.sticker2).clickable(onClick = onClick)
            .semantics { contentDescription = if (refused) "Photo not added: ${AlbumText.reason(photo)}" else "Photo only on this phone" },
    ) {
        image(model, null, Modifier.fillMaxSize(), true)
        if (refused) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Badge(Modifier.align(Alignment.BottomStart).padding(6.dp), if (refused) colors.error else colors.sticker) {
            Icon(if (refused) Icons.Rounded.PriorityHigh else Icons.Rounded.PhoneAndroid, null, Modifier.size(14.dp), tint = if (refused) Color.White else colors.ink)
        }
    }
}

@Composable
private fun SharedTile(memory: Memory, side: Side?, image: PhotoSlot, onClick: () -> Unit) {
    val colors = Heartpact.colors
    Box(
        Modifier.aspectRatio(1f).clip(Shapes.Tile).background(colors.sticker2).clickable(onClick = onClick)
            .semantics { contentDescription = memory.caption ?: "Photo added by ${memory.uploadedByName}" },
    ) {
        image(memory.thumbnailUrl ?: memory.url, null, Modifier.fillMaxSize(), true)
        if (side != null) Badge(Modifier.align(Alignment.BottomStart).padding(6.dp), colors.sticker) {
            HeartSticker(if (side == Side.Left) HalfState.Stuck else HalfState.Empty, if (side == Side.Right) HalfState.Stuck else HalfState.Empty, 15.dp)
        }
    }
}

@Composable
private fun Badge(modifier: Modifier, background: Color, content: @Composable () -> Unit) {
    Box(modifier.size(24.dp).clip(Shapes.Pill).background(background).border(1.5.dp, Heartpact.colors.rule, Shapes.Pill), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Empty() {
    StickerCard(Modifier.fillMaxWidth(), shape = Shapes.Dare) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Character(Who.Left, Pose.Rest, 110.dp, mood = Mood.Sleep)
            Text("No photos yet", style = Heartpact.type.title, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Add photos and they stay on this phone. When you tap Sync, they join your shared album.",
                style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PhotoViewer(memory: Memory, image: PhotoSlot, onClose: () -> Unit) {
    val date = utc(memory.takenAtUtc ?: memory.sortAtUtc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            image(memory.url, memory.caption ?: "Photo added by ${memory.uploadedByName}", Modifier.fillMaxSize(), false)
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Column(Modifier.padding(start = 4.dp)) {
                    Text(memory.uploadedByName.ifBlank { "Shared photo" }, style = Heartpact.type.label, color = Color.White)
                    Text(date, style = Heartpact.type.small, color = Color.White.copy(alpha = 0.75f))
                }
            }
            memory.caption?.let {
                Text(it, style = Heartpact.type.body, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(20.dp))
            }
        }
    }
}
