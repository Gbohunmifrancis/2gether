package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.heartpact.album.LocalPhoto
import app.heartpact.data.Memory
import app.heartpact.data.MemoryUsage
import app.heartpact.ui.album.AlbumTab
import app.heartpact.ui.album.AlbumUi
import app.heartpact.ui.album.PhotoSlot
import app.heartpact.ui.album.SyncSheetContent
import app.heartpact.ui.album.SyncState
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.theme.Side
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AlbumShotsTest {
    /** Stand-in photos: soft gradients, so layout reads without real images. */
    private val swatches = listOf(Color(0xFFF4B9A0) to Color(0xFF8E6BBF), Color(0xFF9FD3C7) to Color(0xFF3E6E8E), Color(0xFFFFE08A) to Color(0xFFE07A5F), Color(0xFFB8C0FF) to Color(0xFF1F2557))
    private val fake: PhotoSlot = { model, _, modifier, _ ->
        val (a, b) = swatches[Math.floorMod(model.hashCode(), swatches.size)]
        Box(modifier.background(Brush.linearGradient(listOf(a, b))))
    }

    private val mb = 1024L * 1024
    private fun local(n: Int, error: String? = null) = LocalPhoto("c$n", "c$n.jpg", "c$n.jpg", "image/jpeg", 3 * mb + n * 100_000L, null, 0, errorCode = error)
    private fun memory(n: Int, by: String) = Memory("m$n", by, if (by == "maya") "Maya" else "Theo", "image/jpeg", sortAtUtc = "2026-10-0${n % 9 + 1}T10:00:00Z", url = "https://x/m$n")
    private val sides = mapOf("maya" to Side.Left, "theo" to Side.Right)
    // Two of them are videos: one with a poster frame, one still waiting for its poster.
    private val shared = (1..11).map { memory(it, if (it % 3 == 0) "theo" else "maya") }.mapIndexed { index, item ->
        when (index) {
            1 -> item.copy(contentType = "video/mp4", isVideo = true, thumbnailUrl = "https://x/thumb2")
            4 -> item.copy(contentType = "video/quicktime", isVideo = true)
            else -> item
        }
    }
    private val usage = MemoryUsage(count = 214, usedBytes = 812 * mb, limitBytes = 5L * 1024 * mb)

    @androidx.compose.runtime.Composable private fun AlbumScreen(ui: AlbumUi) = HomeScreen("Maya", "Theo", {}, initialTab = Tab.Album, album = {
        AlbumTab(ui, sides, localModel = { it.clientId }, onAdd = {}, onSync = {}, onRemove = {}, onRetry = {}, onLoadMore = {}, onDismissResult = {}, unmetered = { true }, image = fake)
    })

    @Test fun album() {
        val waiting = (1..3).map { local(it) } + local(4).copy(contentType = "video/mp4", fileName = "c4.mp4") + local(5, "memories.too_large")
        shot("album") { AlbumScreen(AlbumUi(local = waiting, shared = shared, usage = usage, loaded = true, onThisDay = listOf(memory(40, "theo").copy(sortAtUtc = "2025-10-11T09:00:00Z"), memory(41, "maya").copy(sortAtUtc = "2024-10-11T09:00:00Z")))) }
        shot("album-syncing") { AlbumScreen(AlbumUi(local = waiting, shared = shared, usage = usage, loaded = true, sync = SyncState.Running(2, 4))) }
        shot("album-empty") { AlbumScreen(AlbumUi(loaded = true)) }
        shot("album-no-storage") { AlbumScreen(AlbumUi(local = waiting.take(2), loaded = true, storageMissing = true)) }
        shot("album-night", night = true) { AlbumScreen(AlbumUi(local = waiting, shared = shared, usage = usage, loaded = true, result = "11 photos added to your shared album.")) }
    }

    @Test fun syncSheet() {
        val waiting = (1..4).map { local(it) }
        shot("album-sync-sheet") { Box(Modifier.background(app.heartpact.ui.theme.Heartpact.colors.sticker)) { SyncSheetContent(AlbumUi(local = waiting, usage = usage), onWifi = true, {}, {}) } }
        shot("album-sync-sheet-data") { Box(Modifier.background(app.heartpact.ui.theme.Heartpact.colors.sticker)) { SyncSheetContent(AlbumUi(local = waiting, usage = usage.copy(usedBytes = 5L * 1024 * mb - 7 * mb)), onWifi = false, {}, {}) } }
    }
}
