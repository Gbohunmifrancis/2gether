package app.heartpact.push

import app.heartpact.data.NotificationPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What a notification offers besides a tap. Each is done from the shade where it safely can be. */
enum class PushAction {
    /** A message: an inline reply, sent as a message. */
    Reply,
    /** A message: a heart back, sent as a message. */
    Heart,
    /** Opens the app where the notice points. */
    Open,
    /** A dare set or changed: seen, nothing more to do. */
    GotIt,
    /** The partner finished a dare: a cheer back, sent as a message. */
    Cheer,
    /** A game invite or turn: opens the game. */
    Play,
    /** The partner asks to call off today's dare: grants it (`cancel`), as "Call it off together" does. */
    LetOff,
    /** The partner asks to call off today's dare: leaves it ringing. Nothing to send; the request simply goes unanswered. */
    KeepIt,
    /** The partner started a dare: opens their live count ("watch/{occurrenceId}"). */
    WatchLive,
}

/** The ids a notice carries in its `data` (the notification's `dataJson`), as far as the app uses them. */
data class PushData(
    val occurrenceId: String? = null,
    val goalId: String? = null,
    val sessionId: String? = null,
    val senderUserId: String? = null,
    val senderName: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Lenient: a missing, empty or malformed payload is no ids at all, never a crash. */
        fun parse(text: String?): PushData {
            if (text.isNullOrBlank()) return PushData()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return PushData()
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return PushData(field("occurrenceId"), field("goalId"), field("sessionId"), field("senderUserId"), field("senderName"))
        }

        fun from(obj: JsonObject?): PushData = parse(obj?.toString())
    }
}

/** Where a tap goes: a tab of Home ("today", "us", "album"), the games, or a screen's route over Home. */
data class PushTarget(val tab: String, val route: String? = null)

/**
 * Pure rules for a notice on this phone (docs/push-notifications.md): its channel, its actions, where it opens and its
 * category. The server decides the same channel (`PushPolicy.ChannelFor`) and category (`NotificationRouting.CategoryOf`).
 */
object PushActions {
    /** The partner is waiting on an answer, or just did something worth a cheer: the partner channel, high importance. */
    private val partnerAlerts = setOf("message", "gameInvite", "gameTurn", "exerciseCompleted", "exerciseCallOffAsked", "exerciseStarted")

    fun isPartnerAlert(type: String?) = type in partnerAlerts

    /** Quiet hours win: silent on the quiet channel. Otherwise a partner alert on its own channel, the rest on general. */
    fun channel(type: String?, quiet: Boolean): String = when {
        quiet -> Push.QUIET
        isPartnerAlert(type) -> Push.PARTNER
        else -> Push.GENERAL
    }

    /** At most three, in the order they show. A call-off or a live dare without its id can only open the app. */
    fun actions(type: String?, data: PushData = PushData()): List<PushAction> = when (type) {
        "message" -> listOf(PushAction.Reply, PushAction.Heart)
        "exerciseGoalChanged" -> listOf(PushAction.Open, PushAction.GotIt)
        "exerciseCompleted" -> listOf(PushAction.Cheer, PushAction.Open)
        "gameInvite", "gameTurn", "gameStarted" -> listOf(PushAction.Play)
        "exerciseCallOffAsked" -> if (data.occurrenceId != null) listOf(PushAction.LetOff, PushAction.KeepIt) else listOf(PushAction.Open)
        "exerciseStarted" -> if (data.occurrenceId != null) listOf(PushAction.WatchLive) else listOf(PushAction.Open)
        else -> emptyList()
    }

    /** The screen a live dare opens on; an app without that screen yet opens Today instead. */
    fun watchRoute(occurrenceId: String) = "watch/$occurrenceId"

    fun target(type: String?, data: PushData = PushData()): PushTarget {
        val tab = PushInbox.tabFor(type)
        return if (type == "exerciseStarted" && data.occurrenceId != null) PushTarget(tab, watchRoute(data.occurrenceId)) else PushTarget(tab)
    }

    /**
     * The category a type belongs to, as `NotificationRouting.CategoryOf` on the server: "couple" and "alarm" can't be
     * switched off. A type newer than this app falls in by its prefix ("exerciseStarted" is an exercise update).
     */
    fun category(type: String?): String? = when {
        type == null -> null
        type in setOf("coupleConnected", "breakup", "plusEnding", "plusStarted", "locationShared") -> "couple"
        type == "exerciseDue" -> "alarm"
        type == "message" -> "messages"
        type.startsWith("game") -> "games"
        type.startsWith("exercise") -> "exercise"
        type == "cycleUpdated" || type == "cycleShared" -> "cycle"
        type == "pregnancyUpdated" -> "pregnancy"
        type == "dailyQuestionAnswered" -> "dailyQuestions"
        type in setOf("memoriesSynced", "albumSyncReminder", "albumAlmostFull") -> "album"
        else -> null
    }

    /** Whether the person lets this kind reach the phone; the in-app banner obeys the same switches as the push. */
    fun allowed(type: String?, prefs: NotificationPreferences?): Boolean {
        if (prefs == null) return true
        return when (category(type)) {
            "messages" -> prefs.messages
            "games" -> prefs.games
            "exercise" -> prefs.exercise
            "cycle" -> prefs.cycle
            "pregnancy" -> prefs.pregnancy
            "dailyQuestions" -> prefs.dailyQuestions
            "album" -> prefs.album
            else -> true
        }
    }

    /** What a heart and a cheer send: a picture both of you read, whatever language each phone is in. */
    const val HEART = "❤️"
    const val CHEER = "👏❤️"
}
