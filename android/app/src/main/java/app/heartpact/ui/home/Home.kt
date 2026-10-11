package app.heartpact.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Diversity1
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.Diversity1
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.Mood
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Today("Today", Icons.Outlined.Today, Icons.Rounded.Today),
    Pacts("Pacts", Icons.Outlined.FavoriteBorder, Icons.Rounded.Favorite),
    Food("Food", Icons.Outlined.Restaurant, Icons.Rounded.Restaurant),
    Us("Us", Icons.Outlined.Diversity1, Icons.Rounded.Diversity1),
    Album("Album", Icons.Outlined.PhotoLibrary, Icons.Rounded.PhotoLibrary),
}

/** The signed-in, linked app: five tabs. Each tab's real content arrives with its feature. */
@Composable
fun HomeScreen(myName: String, partnerName: String, onSignOut: () -> Unit, initialTab: Tab = Tab.Today, today: @Composable () -> Unit = {}, pacts: (@Composable () -> Unit)? = null, onTab: (Tab) -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            when (tab) {
                Tab.Today -> today()
                Tab.Pacts -> pacts?.invoke() ?: TabPage("Pacts", "Your sticker page") { EmptyPage(Who.Right, Pose.Stand, "No dares yet", "Dares you set each other live here, with every heart you've kept.") }
                Tab.Food -> TabPage("Food", "Photo estimates run about ±30%") { EmptyPage(Who.Left, Pose.Stand, "Nothing logged today", "Snap a meal and you'll get a calorie range, never a single made-up number.") }
                Tab.Us -> TabPage("Us", "$myName and $partnerName") {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Character(Who.Both, Pose.Hug, 130.dp) }
                        StickerCard {
                            CardRow("Sign out", subtitle = "You can sign back in any time", icon = Icons.AutoMirrored.Rounded.Logout, onClick = onSignOut)
                        }
                    }
                }
                Tab.Album -> TabPage("Album", "Photos stay on your phone until you sync") { EmptyPage(Who.Left, Pose.Rest, "No photos yet", "Add photos and they stay on this phone. When you tap Sync, they join your shared album.", Mood.Sleep) }
            }
        }
        NavBar(tab) { tab = it; onTab(it) }
    }
}

@Composable
private fun TabPage(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
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

/** Material 3 navigation bar in the sticker world: deep page fill, the active tab in a white pill. */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = Heartpact.colors
    Row(Modifier.fillMaxWidth().background(colors.page2).navigationBarsPadding().height(80.dp).padding(top = 12.dp, start = 8.dp, end = 8.dp)) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            Column(
                Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(tab) }.semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.width(64.dp).height(32.dp).clip(Shapes.Pill).background(if (on) colors.sticker else colors.page2), contentAlignment = Alignment.Center) {
                    Icon(if (on) tab.selectedIcon else tab.icon, null, Modifier.size(22.dp), tint = if (on) Heartpact.actor.action else colors.ink2)
                }
                Text(tab.label, style = Heartpact.type.labelSmall.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold), color = if (on) colors.ink else colors.ink2)
            }
        }
    }
}
