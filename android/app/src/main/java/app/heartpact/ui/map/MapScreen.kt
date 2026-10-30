package app.heartpact.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heartpact.data.LocationPoint
import app.heartpact.data.SharingStatus
import app.heartpact.i18n.tr
import app.heartpact.ui.components.Avatar
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.Face
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.ReadableWidth
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Icon3D
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Shapes
import app.heartpact.ui.theme.Side
import app.heartpact.ui.theme.partner
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZonedDateTime

/** Asks for location and notifications the first time, then starts sharing (which needs both). Location is only used while sharing. */
@Composable
fun rememberShareLocation(onGranted: () -> Unit): () -> Unit {
    val granted by rememberUpdatedState(onGranted)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) granted()
    }
    return { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) }
}

private fun LocationPoint.who(mine: Side) = if (isCurrentUser) (if (mine == Side.Left) Who.Left else Who.Right) else (if (mine == Side.Left) Who.Right else Who.Left)

/**
 * A drawn map of the two of you: streets and a park as decoration, both of you placed by your real
 * distance and direction from each other. Used on the Us card, and on the map screen when the base map can't load.
 */
@Composable
fun SketchMap(points: List<LocationPoint>, modifier: Modifier, character: Dp = 56.dp) {
    val colors = Heartpact.colors
    val mine = LocalActor.current
    val street = Color.White.copy(alpha = if (colors.isNight) 0.12f else 0.9f)
    val park = Color(0xFF9ED3A6).copy(alpha = 0.35f)
    val water = Color(0xFF8DB7E8).copy(alpha = 0.35f)
    val line = colors.ink2
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(colors.page2)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val me = points.firstOrNull { it.isCurrentUser }
        val them = points.firstOrNull { !it.isCurrentUser }
        val placed = if (me != null && them != null) LocationMath.place(me.latitude, me.longitude, them.latitude, them.longitude, w / h) else null
        Canvas(Modifier.fillMaxSize()) {
            drawRect(water, Offset(0f, size.height * 0.78f), androidx.compose.ui.geometry.Size(size.width, size.height * 0.22f))
            drawRoundRect(park, Offset(size.width * 0.55f, size.height * 0.18f), androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.3f), androidx.compose.ui.geometry.CornerRadius(24f))
            for (i in 1..4) drawLine(street, Offset(0f, size.height * i / 5f), Offset(size.width, size.height * i / 5f + 18f * (i % 2)), 14f)
            for (i in 1..4) drawLine(street, Offset(size.width * i / 5f, 0f), Offset(size.width * i / 5f - 10f * (i % 2), size.height), 14f)
            if (placed != null) {
                val (a, b) = placed
                drawLine(line, Offset(a.first * size.width, a.second * size.height), Offset(b.first * size.width, b.second * size.height), 4f,
                    cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 16f)))
            }
        }
        val density = LocalDensity.current
        val half = with(density) { (character / 2).toPx() }
        fun at(fraction: Pair<Float, Float>) = with(density) { Modifier.offset((fraction.first * w - half).toDp(), (fraction.second * h - half * 1.2f).toDp()) }
        when {
            placed != null -> {
                Character(me!!.who(mine), Pose.Wave, character, modifier = at(placed.first))
                Character(them!!.who(mine), Pose.Stand, character, modifier = at(placed.second))
            }
            me != null -> Character(me.who(mine), Pose.Wave, character, modifier = at(0.5f to 0.5f))
        }
    }
}

data class MapActions(
    val onShare: () -> Unit = {},
    val onPause: (Int) -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * The map (mockup 13): the two of you on a Snap-style map with your pictures, and the controls that are always one tap
 * away. [me] and [partner] say who is who (picture, name, side of the heart). With [drawn], or when the base map cannot
 * be loaded (no network and nothing cached), it shows the drawn map of the two of you instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(ui: LocationUi, me: Face, partner: Face, actions: MapActions, now: Instant = Instant.now(), drawn: Boolean = false) {
    val colors = Heartpact.colors
    val partnerName = partner.name
    var failed by remember { mutableStateOf(false) }
    val sketch = drawn || failed
    val controller = remember { MapController() }
    // The ages on the markers and in the sheet move on while the screen is open.
    var clock by remember { mutableStateOf(now) }
    if (!drawn) LaunchedEffect(Unit) { while (true) { delay(15_000); clock = Instant.now() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.page)) {
        var sheetPx by remember { mutableIntStateOf(0) }
        val sheet = with(LocalDensity.current) { sheetPx.toDp() }
        // The map ends just under the sheet's rounded top, so what the camera frames is what can be seen.
        val mapBottom = (sheet - MAP_SHEET_OVERLAP_DP.dp).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxSize().padding(bottom = mapBottom)) {
            if (sketch) SketchMap(ui.points, Modifier.fillMaxSize())
            else {
                val myPhoto = rememberFaceBitmap(me)
                val partnerPhoto = rememberFaceBitmap(partner)
                val people = remember(ui.points, myPhoto, partnerPhoto, clock, me, partner, colors) {
                    ui.points.map { point ->
                        val mine = point.isCurrentUser
                        val face = if (mine) me else partner
                        MapPerson(
                            userId = point.userId, isMe = mine,
                            name = if (mine) tr("You") else face.name.ifBlank { point.displayName },
                            age = if (mine) null else LocationMath.markerAge(point.updatedAtUtc, clock),
                            live = MapMotion.isLive(LocationMath.minutesSince(point.updatedAtUtc, clock)),
                            ring = colors.partner(face.side).art.toArgb(),
                            photo = if (mine) myPhoto else partnerPhoto,
                            pos = Pos(point.latitude, point.longitude),
                        )
                    }
                }
                LiveMap(controller, people, colors.isNight, onFailed = { failed = true }, modifier = Modifier.fillMaxSize())
            }
        }
        MapButton(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), actions.onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = colors.ink)
        }
        if (!sketch) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = sheet + 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(Modifier, controller::frameBoth) { Icon(Icons.Outlined.FavoriteBorder, tr("Show both of us"), tint = colors.ink) }
            MapButton(Modifier, controller::recenterOnMe) { Icon(Icons.Outlined.MyLocation, tr("Center on me"), tint = colors.ink) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = ReadableWidth).heightIn(max = maxHeight * 0.62f)
                .onSizeChanged { sheetPx = it.height }
                .clip(Shapes.Sheet).background(colors.sticker)
                .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 14.dp).size(36.dp, 4.dp).clip(Shapes.Pill).background(colors.rule2))
            val status = ui.status
            Row(Modifier.clip(Shapes.Pill).background(Heartpact.actor.tint).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (status?.mutual == true) colors.ink else colors.ink3))
                Text(LocationMath.headline(status, partnerName), style = Heartpact.type.labelSmall, color = colors.ink, modifier = Modifier.padding(start = 8.dp))
            }
            ui.note?.let { NoteCard(it, Modifier.padding(top = 12.dp)) }
            val them = ui.partner
            val mine = ui.me
            Person(partner, partnerName, when {
                them != null && mine != null -> LocationMath.distance(LocationMath.meters(mine.latitude, mine.longitude, them.latitude, them.longitude)) + " · " + LocationMath.age(them.updatedAtUtc, clock)
                status?.partner?.status == SharingStatus.Paused -> LocationMath.pausedUntil(status.partner.pausedUntilUtc)?.let { tr("Paused until {time}", "time" to it) } ?: tr("Paused")
                status?.partner?.status == SharingStatus.Sharing -> tr("Sharing; you'll see each other once you share too")
                else -> tr("Not sharing")
            })
            Person(me, tr("You"), when (ui.myStatus) {
                SharingStatus.Sharing -> tr("{partner} sees you while you both share", "partner" to partnerName)
                SharingStatus.Paused -> LocationMath.pausedUntil(status?.me?.pausedUntilUtc)?.let { tr("Paused until {time}", "time" to it) } ?: tr("Paused")
                SharingStatus.Off -> tr("Not sharing")
            })
            when (ui.myStatus) {
                SharingStatus.Off -> {
                    HeartButton(if (ui.sendingHere) tr("Starting…") else tr("Share my location"), actions.onShare, Modifier.padding(top = 16.dp), icon = Icons.Outlined.LocationOn, enabled = !ui.sendingHere)
                    Text(tr("Sharing is mutual and always visible: you only see {partner} while you both share, and a notification shows the whole time. Stop any time.", "partner" to partnerName),
                        style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 12.dp))
                }
                // A phone that isn't sending (restarted during the pause) resumes by starting again.
                SharingStatus.Paused -> HeartButton(tr("Resume sharing"), if (ui.sendingHere) actions.onResume else actions.onShare, Modifier.padding(top = 16.dp), busy = ui.busy)
                SharingStatus.Sharing -> {
                    if (!ui.sendingHere) HeartButton(tr("Send from this phone"), actions.onShare, Modifier.padding(top = 16.dp), kind = ButtonKind.Tonal)
                    Text(tr("Pause sharing"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeartChip(tr("1 hour"), false, { actions.onPause(60) })
                        HeartChip(tr("Until tomorrow"), false, { actions.onPause(LocationMath.minutesUntilTomorrow(ZonedDateTime.now())) })
                        HeartChip(tr("Stop sharing"), false, actions.onStop)
                    }
                    Text(tr("Either of you can pause. While one of you is paused, neither of you sees the other."), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** A round sticker button floating on the map. */
@Composable
private fun MapButton(modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Heartpact.colors.sticker)) { icon() }
}

@Composable
private fun Person(face: Face, name: String, line: String) {
    val colors = Heartpact.colors
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(face, 46.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(name, style = Heartpact.type.titleSmall, color = colors.ink)
            Text(line, style = Heartpact.type.body, color = colors.ink2)
        }
    }
}

/** The location card at the top of Us (mockup 12). */
@Composable
fun LocationCard(ui: LocationUi, partnerName: String, onOpen: () -> Unit, onShare: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, now: Instant = Instant.now()) {
    val colors = Heartpact.colors
    val me = ui.me
    val them = ui.partner
    val subtitle = when {
        me != null && them != null -> tr("{partner} is {distance}", "partner" to partnerName, "distance" to LocationMath.distance(LocationMath.meters(me.latitude, me.longitude, them.latitude, them.longitude))) + " · " + LocationMath.ago(them.updatedAtUtc, now)
        ui.myStatus == SharingStatus.Paused -> LocationMath.pausedUntil(ui.status?.me?.pausedUntilUtc)?.let { tr("Paused until {time}", "time" to it) } ?: tr("Paused")
        ui.status?.partner?.status == SharingStatus.Paused -> LocationMath.pausedUntil(ui.status.partner.pausedUntilUtc)?.let { tr("{partner} paused until {time}", "partner" to partnerName, "time" to it) } ?: tr("{partner} paused", "partner" to partnerName)
        else -> tr("Mutual, visible, and you can stop any time")
    }
    StickerCard(Modifier.padding(horizontal = 12.dp).padding(bottom = 14.dp)) {
        if (ui.points.isNotEmpty()) SketchMap(ui.points, Modifier.fillMaxWidth().height(150.dp), character = 44.dp)
        CardRow(LocationMath.headline(ui.status, partnerName), subtitle = subtitle, icon3d = Icon3D.Location, onClick = onOpen) {
            val (label, action) = when (ui.myStatus) {
                SharingStatus.Off -> tr("Share") to onShare
                SharingStatus.Paused -> tr("Resume") to (if (ui.sendingHere) onResume else onShare)
                SharingStatus.Sharing -> tr("Pause") to onPause
            }
            Text(label, style = Heartpact.type.label, color = Heartpact.actor.onSoft, modifier = Modifier.clip(Shapes.Pill).clickable(onClick = action).padding(8.dp))
        }
    }
}
