package app.heartpact

import app.heartpact.data.NotificationPreferences
import app.heartpact.push.PushAction
import app.heartpact.push.PushActions
import app.heartpact.push.PushData
import app.heartpact.push.PushTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushActionsTest {
    private val withOccurrence = PushData(occurrenceId = "occ-1", goalId = "goal-1")

    @Test fun `each kind of notice offers the actions that fit it`() {
        assertEquals(listOf(PushAction.Reply, PushAction.Heart), PushActions.actions("message"))
        assertEquals(listOf(PushAction.Open, PushAction.GotIt), PushActions.actions("exerciseGoalChanged", withOccurrence))
        assertEquals(listOf(PushAction.Cheer, PushAction.Open), PushActions.actions("exerciseCompleted", withOccurrence))
        assertEquals(listOf(PushAction.Play), PushActions.actions("gameInvite"))
        assertEquals(listOf(PushAction.Play), PushActions.actions("gameTurn"))
        assertEquals(listOf(PushAction.LetOff, PushAction.KeepIt), PushActions.actions("exerciseCallOffAsked", withOccurrence))
        assertEquals(listOf(PushAction.WatchLive), PushActions.actions("exerciseStarted", withOccurrence))
        // Nothing to do from the shade: a tap opens it.
        assertEquals(emptyList<PushAction>(), PushActions.actions("memoriesSynced"))
        assertEquals(emptyList<PushAction>(), PushActions.actions("somethingNewer"))
        assertEquals(emptyList<PushAction>(), PushActions.actions(null))
    }

    @Test fun `a call-off or a live dare without its id can only open the app`() {
        assertEquals(listOf(PushAction.Open), PushActions.actions("exerciseCallOffAsked"))
        assertEquals(listOf(PushAction.Open), PushActions.actions("exerciseStarted"))
    }

    @Test fun `a live dare opens the watch screen over Today, everything else its tab`() {
        assertEquals(PushTarget("today", "watch/occ-1"), PushActions.target("exerciseStarted", withOccurrence))
        assertEquals(PushTarget("today"), PushActions.target("exerciseStarted"))
        assertEquals(PushTarget("today"), PushActions.target("exerciseCompleted", withOccurrence))
        assertEquals(PushTarget("games"), PushActions.target("gameInvite"))
        assertEquals(PushTarget("album"), PushActions.target("memoriesSynced"))
        assertEquals(PushTarget("us"), PushActions.target("message"))
    }

    @Test fun `partner alerts ring on their own channel, quiet hours always win`() {
        listOf("message", "gameInvite", "gameTurn", "exerciseCompleted", "exerciseCallOffAsked", "exerciseStarted").forEach {
            assertEquals(it, "partner", PushActions.channel(it, quiet = false))
            assertEquals(it, "quiet", PushActions.channel(it, quiet = true))
        }
        listOf("exerciseGoalChanged", "exerciseMissed", "memoriesSynced", "cycleShared", "plusStarted", "breakup").forEach {
            assertEquals(it, "general", PushActions.channel(it, quiet = false))
        }
    }

    /** The server's NotificationRouting.CategoryOf, type by type: the banner obeys the same switches as the push. */
    @Test fun `categories match the server's routing`() {
        val server = mapOf(
            "coupleConnected" to "couple", "breakup" to "couple", "plusEnding" to "couple", "plusStarted" to "couple", "locationShared" to "couple",
            "exerciseDue" to "alarm", "message" to "messages",
            "gameInvite" to "games", "gameStarted" to "games", "gameEnded" to "games", "gameTurn" to "games",
            "exerciseCompleted" to "exercise", "exerciseMissed" to "exercise", "exerciseCancelled" to "exercise", "exerciseGoalChanged" to "exercise",
            "exerciseCallOffAsked" to "exercise", "exercisePausedByPlan" to "exercise",
            "cycleUpdated" to "cycle", "cycleShared" to "cycle", "pregnancyUpdated" to "pregnancy", "dailyQuestionAnswered" to "dailyQuestions",
            "memoriesSynced" to "album", "albumSyncReminder" to "album", "albumAlmostFull" to "album",
        )
        server.forEach { (type, category) -> assertEquals(type, category, PushActions.category(type)) }
        // A type newer than the app falls in by its name.
        assertEquals("exercise", PushActions.category("exerciseStarted"))
    }

    @Test fun `a switched-off category is never shown, couple notices always are`() {
        val noGames = NotificationPreferences(games = false, messages = false)
        assertFalse(PushActions.allowed("gameInvite", noGames))
        assertFalse(PushActions.allowed("message", noGames))
        assertTrue(PushActions.allowed("exerciseCompleted", noGames))
        assertTrue(PushActions.allowed("breakup", NotificationPreferences(games = false, messages = false, exercise = false, cycle = false)))
        assertTrue(PushActions.allowed("gameInvite", null))
    }

    @Test fun `the payload is read leniently`() {
        assertEquals(PushData(occurrenceId = "o", goalId = "g"), PushData.parse("""{"occurrenceId":"o","goalId":"g","extra":3}"""))
        assertEquals(PushData(sessionId = "s"), PushData.parse("""{"sessionId":"s","reminder":"duelFinished"}"""))
        assertEquals(PushData(senderUserId = "u", senderName = "Maya"), PushData.parse("""{"messageId":"m","senderUserId":"u","senderName":"Maya"}"""))
        assertEquals(PushData(), PushData.parse(null))
        assertEquals(PushData(), PushData.parse("not json"))
        assertNull(PushData.parse("""{"occurrenceId":12}""").occurrenceId)
    }
}
