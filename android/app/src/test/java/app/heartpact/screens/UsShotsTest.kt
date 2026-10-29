package app.heartpact.screens

import app.heartpact.data.DailyAnswer
import app.heartpact.data.DailyQuestion
import app.heartpact.data.Gender
import app.heartpact.data.NotificationPreferences
import app.heartpact.ui.home.HomeScreen
import app.heartpact.ui.home.Tab
import app.heartpact.ui.us.NotificationsScreen
import app.heartpact.ui.us.QuestionScreen
import app.heartpact.ui.us.QuestionUi
import app.heartpact.ui.us.UsTab
import app.heartpact.ui.us.UsUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class UsShotsTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val question = DailyQuestion("q", "2026-10-11", "future-03", "future", "If we had one extra free weekend every month, what would you want us to do with it?",
        partnerAnswered = true, streakDays = 14)
    private val theirs = DailyAnswer("theo", "Theo", "Learn to sail, badly, and laugh about it.", "2026-10-11T06:12:00Z")
    private val mine = DailyAnswer("maya", "Maya", "Somewhere with no signal. A cabin, a lake, and no alarms at all, not even yours.", "2026-10-11T07:30:00Z")
    private val yesterday = DailyQuestion("y", "2026-10-10", "closeness-02", "closeness", "What's a small thing I do that you love?", myAnswer = mine, partnerAnswered = true, partnerAnswer = theirs)

    @Test fun us() {
        shot("us") {
            HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
                UsTab(UsUi(question = question, preferences = NotificationPreferences(quietHoursEnabled = true)), "Maya", "Theo", "together since 2 March", {}, {}, {}, {}, {}, {}, country = "NG", onCountry = {}, gender = Gender.Female, onGender = {})
            })
        }
        shot("us-no-country") {
            HomeScreen("Maya", "Theo", {}, initialTab = Tab.Us, us = {
                UsTab(UsUi(question = question, preferences = NotificationPreferences(quietHoursEnabled = true)), "Maya", "Theo", "together since 2 March", {}, {}, {}, {}, {}, {}, onCountry = {})
            })
        }
    }

    @Test fun question() {
        shot("question") { QuestionScreen(QuestionUi(today = question, earlier = listOf(yesterday), draft = mine.body), "Theo", {}, {}, {}, {}, today = today) }
        shot("question-both") { QuestionScreen(QuestionUi(today = question.copy(myAnswer = mine, partnerAnswer = theirs), earlier = listOf(yesterday)), "Theo", {}, {}, {}, {}, today = today) }
    }

    @Test fun notifications() = shot("notifications") { NotificationsScreen(NotificationPreferences(quietHoursEnabled = true, album = false), null, {}, {}) }
}
