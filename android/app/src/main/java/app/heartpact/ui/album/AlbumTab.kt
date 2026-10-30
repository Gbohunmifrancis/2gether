package app.heartpact.ui.album

import app.heartpact.ui.components.heroHeight
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.HeartField
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
import androidx.compose.foundation.layout.widthIn
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
import app.heartpact.ui.components.TwoPaneWidth
import app.heartpact.ui.components.readable
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
    /** Who is looking: only the uploader may caption or delete a shared photo. */
    myId: String? = null,
    onCaption: (Memory, String, (String?) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteShared: (Memory, (String?) -> Unit) -> Unit = { _, _ -> },
    /** Plays a video in the viewer; screenshots pass a still. */
    video: VideoSlot = PlatformVideo,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var openLocal by remember { mutableStateOf<LocalPhoto?>(null) }
    var openShared by remember { mutableStateOf<Memory?>(null) }

    Box(Modifier.fillMaxSize()) {
        // Three photos across on a phone; as many as fit on a tablet.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize().readable(TwoPaneWidth),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            full("header") { Header(ui) }
            if (ui.storageMissing) full("storage") { NoteCard(tr("Photo storage isn't set up on this server yet. Photos you add stay safe on this phone until it is."), Modifier.padding(bottom = 10.dp)) }
            ui.note?.let { note -> full("note") { NoteCard(note, Modifier.padding(bottom = 10.dp)) } }
            ui.result?.let { result -> full("result") { ResultCard(result, onDismissResult) } }
            val usage = ui.usage
            val nearlyFull = usage != null && usage.limitBytes > 0 && usage.usedBytes >= usage.limitBytes * 9 / 10
            val refusedFull = ui.refused.any { it.errorCode == "memories.quota_exceeded" }
            if (onGetPlus != null && (nearlyFull || refusedFull)) full("plus") { PlusPrompt(refusedFull || (usage != null && usage.usedBytes >= usage.limitBytes), onGetPlus) }
            if (ui.waiting.isNotEmpty() || ui.sync != SyncState.Idle) full("banner") { SyncBanner(ui, onSync = { sheetOpen = true }) }
            if (ui.onThisDay.isNotEmpty()) full("onthisday") { OnThisDay(ui.onThisDay, image) { openShared = it } }

            if (ui.local.isNotEmpty()) {
                full("local-title") { Section(tr("Only on this phone"), if (ui.refused.isEmpty()) AlbumText.photos(ui.local.size) else tr("{waiting} waiting · {refused} not added", "waiting" to ui.waiting.size, "refused" to ui.refused.size)) }
                items(ui.local, key = { "local-" + it.clientId }) { photo ->
                    LocalTile(photo, localModel(photo), image) { openLocal = photo }
                }
            }
            if (ui.shared.isNotEmpty()) {
                full("shared-title") { Section(tr("Shared"), ui.usage?.let { AlbumText.photos(it.count) } ?: "") }
                items(ui.shared, key = { "shared-" + it.id }) { memory ->
                    SharedTile(memory, sides[memory.uploadedByUserId], image) { openShared = memory }
                }
                if (ui.nextCursor != null) full("more") {
                    LaunchedEffect(ui.nextCursor) { onLoadMore() }
                    Spacer(Modifier.height(48.dp))
                }
            }
            if (ui.loaded && ui.local.isEmpty() && ui.shared.isEmpty()) full("empty") { Empty() }
        }
        HeartButton(
            tr("Add photos"), onAdd,
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
            title = { Text(if (refused) tr("Not added") else tr("Only on this phone")) },
            text = { Text(if (refused) AlbumText.reason(photo) else tr("It joins your shared album the next time you sync. Removing it here never touches the original in your gallery.")) },
            confirmButton = {
                if (refused) TextButton(onClick = { onRetry(photo.clientId); openLocal = null }) { Text(tr("Try again")) }
                else TextButton(onClick = { openLocal = null }) { Text(tr("Keep it")) }
            },
            dismissButton = { TextButton(onClick = { onRemove(photo.clientId); openLocal = null }) { Text(tr("Remove from Heartpact")) } },
        )
    }
    openShared?.let { open ->
        // Show the latest copy (a caption just saved) of the open memory.
        val memory = ui.shared.firstOrNull { it.id == open.id } ?: ui.onThisDay.firstOrNull { it.id == open.id } ?: open
        PhotoViewer(memory, image, video, mine = myId != null && memory.uploadedByUserId == myId, onCaption = onCaption,
            onDelete = { done -> onDeleteShared(memory) { error -> done(error); if (error == null) openShared = null } }, onClose = { openShared = null })
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Header(ui: AlbumUi) {
    Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 14.dp)) {
        Text(tr("Album"), style = Heartpact.type.headline, color = Heartpact.colors.ink)
        Text(
            ui.usage?.let { tr("Shared: {usage}", "usage" to AlbumText.usage(it)) } ?: tr("Photos stay on your phone until you sync"),
            style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The album is nearly full on Free: Plus has more room for both of you. */
@Composable
private fun PlusPrompt(full: Boolean, onGetPlus: () -> Unit) {
    val colors = Heartpact.colors
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Shapes.Well).background(colors.left.tint).padding(14.dp)) {
        Text(if (full) tr("Your shared album is full") else tr("Your shared album is nearly full"), style = Heartpact.type.label, color = colors.ink)
        Text(tr("Plus gives you both more space. Or delete a few shared photos to make room."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
        HeartButton(tr("See Plus"), onGetPlus, Modifier.padding(top = 10.dp), kind = ButtonKind.Tonal, height = 44.dp, fillWidth = false)
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
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, tr("Dismiss"), tint = colors.ink2) }
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
                        Text(if (sync.total > 0) tr("Syncing {done} of {total}", "done" to sync.done, "total" to sync.total) else tr("Getting ready to sync"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(start = 10.dp))
                    }
                    LinearProgressIndicator(
                        progress = { if (sync.total > 0) sync.done / sync.total.toFloat() else 0f },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(Shapes.Pill),
                        color = Heartpact.actor.action, trackColor = colors.sticker2, drawStopIndicator = {},
                    )
                    Text(tr("Keeps going if you leave Heartpact."), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
                }
                SyncState.WaitingForNetwork -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(24.dp), tint = colors.ink2)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(tr("Waiting for a connection"), style = Heartpact.type.titleSmall, color = colors.ink)
                            Text(tr("Your sync starts as soon as you're online."), style = Heartpact.type.small, color = colors.ink2)
                        }
                    }
                }
                SyncState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(Shapes.Tile).background(colors.sticker2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(22.dp), tint = colors.ink)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(trn(ui.waiting.size, "{count} photo waiting", "{count} photos waiting"), style = Heartpact.type.titleSmall, color = colors.ink)
                        Text(tr("Only on this phone · {size}", "size" to AlbumText.size(ui.waitingBytes)), style = Heartpact.type.small, color = colors.ink2)
                    }
                    HeartButton(tr("Sync"), onSync, kind = ButtonKind.Filled, height = 44.dp, fillWidth = false, enabled = !ui.storageMissing)
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
        Text(trn(ui.waiting.size, "Sync {count} photo?", "Sync {count} photos?"), style = Heartpact.type.title, color = colors.ink)
        Text(tr("They join your shared album, and your partner sees them once they're in."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetRow(if (onWifi) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt,
                if (onWifi) tr("You're on Wi-Fi") else tr("You're on mobile data"),
                if (onWifi) tr("{size} to send", "size" to AlbumText.size(ui.waitingBytes)) else tr("{size} to send, which may count against your data", "size" to AlbumText.size(ui.waitingBytes)))
            SheetRow(Icons.Rounded.CloudUpload, tr("Keeps going in the background"),
                tr("Leave Heartpact whenever you like. If the connection drops, it carries on when you're back."))
            if (after.limitBytes > 0) {
                val tail = if (after.fits < ui.waiting.size) tr("Only {fits} of {total} fit; the rest stay on this phone.", "fits" to after.fits, "total" to ui.waiting.size) else tr("Everything fits.")
                SheetRow(Icons.Rounded.PhoneAndroid, tr("After: {used} of {limit}", "used" to AlbumText.size(after.usedBytes), "limit" to AlbumText.size(after.limitBytes)), tail)
            }
        }
        HeartButton(tr("Sync now"), onSync, Modifier.padding(top = 22.dp), icon = Icons.Rounded.CloudUpload)
        HeartButton(tr("Not now"), onNotNow, Modifier.padding(top = 4.dp), kind = ButtonKind.Text)
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
            Text(tr("On this day"), style = Heartpact.type.titleSmall, color = colors.ink)
            // Three across, and no bigger than on a large phone, however wide the album gets.
            Row(Modifier.padding(top = 10.dp).widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memories.take(3).forEach { memory ->
                    val years = today.year - utc(memory.sortAtUtc).atZone(ZoneId.systemDefault()).year
                    Column(Modifier.weight(1f).clickable { onOpen(memory) }) {
                        image(memory.thumbnailUrl ?: memory.url, memory.caption, Modifier.fillMaxWidth().aspectRatio(1f).clip(Shapes.Tile), true)
                        Text(trn(years, "A year ago", "{count} years ago"), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))
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
            .semantics {
                contentDescription = when {
                    photo.isVideo && refused -> tr("Video not added: {reason}", "reason" to AlbumText.reason(photo))
                    photo.isVideo -> tr("Video only on this phone")
                    refused -> tr("Photo not added: {reason}", "reason" to AlbumText.reason(photo))
                    else -> tr("Photo only on this phone")
                }
            },
    ) {
        image(model, null, Modifier.fillMaxSize(), true)
        if (photo.isVideo) PlayMark(Modifier.align(Alignment.Center))
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
            .semantics { contentDescription = memory.caption ?: if (memory.isVideo) tr("Video added by {name}", "name" to memory.uploadedByName) else tr("Photo added by {name}", "name" to memory.uploadedByName) },
    ) {
        // A video without a poster frame yet has nothing an image loader can draw: show the play mark alone.
        if (!memory.isVideo || memory.thumbnailUrl != null) image(memory.thumbnailUrl ?: memory.url, null, Modifier.fillMaxSize(), true)
        if (memory.isVideo) PlayMark(Modifier.align(Alignment.Center))
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
        Column(Modifier.fillMaxWidth().heroHeight().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Character(Who.Left, Pose.Rest, 130.dp, mood = Mood.Sleep)
            Text(tr("No photos yet"), style = Heartpact.type.title, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 12.dp))
            Text(
                tr("Add photos and they stay on this phone. When you tap Sync, they join your shared album."),
                style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Plays a video from [url]; [poster] shows until it starts. */
typealias VideoSlot = @Composable (url: String, modifier: Modifier) -> Unit

/** The platform player with its own controls: no extra library for a few clips a week. */
val PlatformVideo: VideoSlot = { url, modifier ->
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnPreparedListener { start() }
                setVideoURI(url.toUri())
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = modifier,
    )
}

@Composable
private fun PlayMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(modifier.size(size).clip(Shapes.Pill).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, tr("Video"), Modifier.size(size * 0.66f), tint = Color.White)
    }
}

/**
 * A shared photo or video, full screen. A video shows its poster with a play button and starts only
 * when tapped, so nobody spends mobile data by opening one. The uploader can caption or delete it.
 */
@Composable
private fun PhotoViewer(
    memory: Memory,
    image: PhotoSlot,
    video: VideoSlot,
    mine: Boolean,
    onCaption: (Memory, String, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val date = utc(memory.takenAtUtc ?: memory.sortAtUtc).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    var playing by remember(memory.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                memory.isVideo && playing -> video(memory.url, Modifier.fillMaxSize())
                memory.isVideo -> {
                    memory.thumbnailUrl?.let { image(it, null, Modifier.fillMaxSize(), false) }
                    Box(Modifier.align(Alignment.Center).clickable(role = androidx.compose.ui.semantics.Role.Button) { playing = true }) { PlayMark(size = 72.dp) }
                }
                else -> image(memory.url, memory.caption ?: tr("Photo added by {name}", "name" to memory.uploadedByName), Modifier.fillMaxSize(), false)
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, tr("Close"), tint = Color.White) }
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text(memory.uploadedByName.ifBlank { if (memory.isVideo) tr("Shared video") else tr("Shared photo") }, style = Heartpact.type.label, color = Color.White)
                    Text(date, style = Heartpact.type.small, color = Color.White.copy(alpha = 0.75f))
                }
                if (mine) {
                    IconButton(onClick = { error = null; editing = true }) { Icon(Icons.Rounded.Edit, tr("Caption"), tint = Color.White) }
                    IconButton(onClick = { error = null; deleting = true }) { Icon(Icons.Rounded.Delete, tr("Delete"), tint = Color.White) }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).navigationBarsPadding().padding(20.dp)) {
                memory.caption?.let { Text(it, style = Heartpact.type.body, color = Color.White) }
                error?.let { Text(it, style = Heartpact.type.small, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(memory.caption.orEmpty()) }
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text(if (memory.caption == null) tr("Add a caption") else tr("Change the caption")) },
            text = { HeartField(tr("Caption"), text, { if (it.length <= MAX_CAPTION) text = it }, minLines = 2, maxLength = MAX_CAPTION) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    onCaption(memory, text) { failure -> busy = false; error = failure; if (failure == null) editing = false }
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !busy) { Text(tr("Cancel")) } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { if (!busy) deleting = false },
        title = { Text(if (memory.isVideo) tr("Delete this video?") else tr("Delete this photo?")) },
        text = { Text(tr("It's removed from your shared album for both of you. The original stays in your phone's gallery.")) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onDelete { failure -> busy = false; deleting = false; error = failure }
            }) { Text(tr("Delete")) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text(tr("Keep it")) } },
    )
}

private const val MAX_CAPTION = 500
