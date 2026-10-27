package app.heartpact.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.i18n.tr
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.WidthClass
import app.heartpact.ui.components.readable
import app.heartpact.ui.components.widthClass
import app.heartpact.ui.graphics.Icon3D
import app.heartpact.ui.graphics.LiveLogo
import app.heartpact.ui.graphics.TabIcon3D
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Tab(val icon: Icon3D) {
    Today(Icon3D.Today),
    Pacts(Icon3D.Pacts),
    Food(Icon3D.Food),
    Us(Icon3D.Us),
    Album(Icon3D.Album);

    val label: String get() = when (this) {
        Today -> tr("Today")
        Pacts -> tr("Pacts")
        Food -> tr("Food")
        Us -> tr("Us")
        Album -> tr("Album")
    }
}

/** The signed-in, linked app: five tabs. Each tab's real content arrives with its feature. */
@Composable
fun HomeScreen(myName: String, partnerName: String, onSignOut: () -> Unit, initialTab: Tab = Tab.Today, today: @Composable () -> Unit = {}, pacts: (@Composable () -> Unit)? = null, food: (@Composable () -> Unit)? = null, us: (@Composable () -> Unit)? = null, album: (@Composable () -> Unit)? = null, onTab: (Tab) -> Unit = {}, requestedTab: Tab? = null, onRequestedTab: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // A notification can ask for a tab (the album nudge opens Album).
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) { tab = requestedTab; onTab(requestedTab); onRequestedTab() }
    }
    val select = { next: Tab -> tab = next; onTab(next) }
    val content: @Composable () -> Unit = {
            when (tab) {
                Tab.Today -> today()
                Tab.Pacts -> pacts?.invoke() ?: TabPage(tr("Pacts"), tr("Your sticker page")) { EmptyPage(Who.Right, Pose.Stand, tr("No dares yet"), tr("Dares you set each other live here, with every heart you've kept.")) }
                Tab.Food -> food?.invoke() ?: TabPage(tr("Food"), tr("Photo estimates run about ±30%")) { EmptyPage(Who.Left, Pose.Stand, tr("Nothing logged today"), tr("Snap a meal and you'll get a calorie range, never a single made-up number.")) }
                Tab.Us -> us?.invoke() ?: TabPage(tr("Us"), tr("{me} and {partner}", "me" to myName, "partner" to partnerName)) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Character(Who.Both, Pose.Hug, 130.dp) }
                        StickerCard {
                            CardRow(tr("Sign out"), subtitle = tr("You can sign back in any time"), icon = Icons.AutoMirrored.Rounded.Logout, onClick = onSignOut)
                        }
                    }
                }
                Tab.Album -> album?.invoke() ?: TabPage(tr("Album"), tr("Photos stay on your phone until you sync")) { EmptyPage(Who.Left, Pose.Rest, tr("No photos yet"), tr("Add photos and they stay on this phone. When you tap Sync, they join your shared album."), Mood.Sleep) }
            }
    }
    // Phones get the bottom bar; tablets, and phones on their side, the rail down the side (android/README.md, Tablets).
    if (widthClass() == WidthClass.Compact) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding()) { content() }
            NavBar(tab, select)
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NavRail(tab, select)
            Box(Modifier.weight(1f).statusBarsPadding().navigationBarsPadding()) { content() }
        }
    }
}

@Composable
private fun TabPage(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().readable()) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
            Text(title, style = Heartpact.type.headline, color = Heartpact.colors.ink)
            Text(subtitle, style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp))
        }
        content()
    }
}

@Composable
private fun EmptyPage(who: Who, pose: Pose, title: String, body: String, mood: Mood = Mood.Smile, extra: @Composable () -> Unit = {}) {
    StickerCard(Modifier.padding(horizontal = 12.dp).fillMaxWidth(), shape = Shapes.Dare) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Character(who, pose, 110.dp, mood = mood)
            Text(title, style = Heartpact.type.title, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 12.dp))
            Text(body, style = Heartpact.type.body, color = Heartpact.colors.ink2, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            extra()
        }
    }
}

/** Material 3 navigation bar in the sticker world: deep page fill, the active tab in a white pill, its 3D icon popping
 *  out of it (TabIcon3D). */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = Heartpact.colors
    Row(Modifier.fillMaxWidth().background(colors.page2).navigationBarsPadding().height(88.dp).padding(top = 10.dp, start = 8.dp, end = 8.dp)) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(tab) }.semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabIconPill(tab, on, width = 64.dp)
                Text(tab.label, style = Heartpact.type.labelSmall.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold), color = if (on) colors.ink else colors.ink2)
            }
        }
    }
}

/** The tab's pill, white when selected, with the 3D icon standing a little proud of it. */
@Composable
private fun TabIconPill(tab: Tab, on: Boolean, width: Dp) {
    Box(Modifier.width(width).height(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().clip(Shapes.Pill).background(if (on) Heartpact.colors.sticker else Heartpact.colors.page2))
        TabIcon3D(tab.icon, selected = on, size = 58.dp, modifier = Modifier.requiredSize(58.dp))
    }
}

/** Between 11 at night and 6 in the morning the rail's mark sleeps. */
private fun lateAtNight(now: LocalTime = LocalTime.now()) = now.hour >= 23 || now.hour < 6

/** The navigation bar's tablet form: the same tabs down the start side, under the mark. */
@Composable
private fun NavRail(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = Heartpact.colors
    Column(
        Modifier.fillMaxHeight().width(96.dp).background(colors.page2).statusBarsPadding().navigationBarsPadding().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        LiveLogo(36.dp, if (lateAtNight()) LogoMood.Sleepy else LogoMood.Idle, night = colors.isNight, modifier = Modifier.padding(bottom = 18.dp))
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier.width(80.dp).clickable(role = Role.Tab) { onSelect(tab) }.semantics { selected = on }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabIconPill(tab, on, width = 56.dp)
                Text(tab.label, style = Heartpact.type.labelSmall.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold),
                    color = if (on) colors.ink else colors.ink2, maxLines = 1)
            }
        }
    }
}
