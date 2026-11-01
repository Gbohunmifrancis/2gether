package app.heartpact.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.heartpact.data.GameType
import app.heartpact.i18n.Tr
import app.heartpact.push.InAppNotice
import app.heartpact.ui.components.InAppBannerCard
import app.heartpact.ui.components.InAppBannerHost
import app.heartpact.push.BannerQueue
import app.heartpact.ui.games.GameRules
import app.heartpact.ui.games.RulesSheetContent
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** The in-app banner and a game's "How to play" sheet, by day, by night and right to left. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class NotifyShotsTest {
    private val done = InAppNotice("1", "exerciseCompleted", "Dare done", "Maya finished Squats: 15 reps.")
    private val callOff = InAppNotice("2", "exerciseCallOffAsked", "Maya asks to call off today's squats", "Knee is sore. Let her off today, or keep the alarm ringing.")
    private val message = InAppNotice("3", "message", "New message from Maya", "Running ten minutes late, save me a seat ❤️")
    private val game = InAppNotice("4", "gameInvite", "A game is waiting", "Maya invited you to play Snakes and ladders.")

    /** A page behind the banners, so they read as lying over the app. */
    @Composable
    private fun Banners(vararg notices: InAppNotice) {
        Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                notices.forEach { InAppBannerCard(it, Modifier.fillMaxWidth().padding(bottom = 14.dp)) }
            }
        }
    }

    @Test fun banner() {
        shot("banner") { Banners(done, callOff, message, game) }
        shot("banner-night", night = true) { Banners(done, callOff, message, game) }
        // The host itself, settled (screenshots hold motion still).
        shot("banner-host") {
            Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
                InAppBannerHost(BannerQueue().offer(done).offer(message), blocked = false, onOpen = {}, onDone = {}, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }

    @Test fun rules() {
        shot("rules-sheet") { Sheet { RulesSheetContent(GameType.SnakesAndLadders, null, GameRules.of(GameType.SnakesAndLadders)!!) {} } }
        shot("rules-sheet-night", night = true) { Sheet { RulesSheetContent(GameType.ICallOn, null, GameRules.of(GameType.ICallOn)!!) {} } }
    }

    @Test fun arabic() {
        val english = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))
        Tr.use("ar", File("src/main/assets/i18n/ar.json").readText())
        try {
            shot("ar/banner") { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Banners(done, game) } }
            shot("ar/rules-sheet") {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Sheet { RulesSheetContent(GameType.ConnectFour, null, GameRules.of(GameType.ConnectFour)!!) {} }
                }
            }
        } finally {
            Locale.setDefault(english)
            Tr.use("en", null)
        }
    }

    /** A sheet over a dimmed page, as ModalBottomSheet shows it. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Heartpact.colors.page)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Heartpact.colors.sticker, Shapes.Sheet)) {
                Spacer(Modifier.height(22.dp))
                content()
            }
        }
    }
}
