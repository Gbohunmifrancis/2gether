package app.heartpact.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Whether the app is on screen. Set by MainActivity: while it is resumed, a push becomes the in-app banner rather than
 * a system notification. Anything else on top (the alarm, the camera, another app) leaves it false, so a notice there
 * still reaches the shade and no banner is drawn over those screens.
 */
object AppForeground {
    @Volatile var resumed = false
}

/** A notice that arrived while the app is open: from a push received in the foreground, or the hub's NotificationCreated. */
data class InAppNotice(val id: String, val type: String?, val title: String, val body: String, val data: PushData = PushData()) {
    val target: PushTarget get() = PushActions.target(type, data)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(push: PushNotice) = InAppNotice(push.id, push.type, push.title, push.body, push.data)

        /** The hub's `NotificationCreated` payload (`{id, type, title, body, dataJson, ...}`); null when it can't be read. */
        fun fromHub(text: String): InAppNotice? {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = field("id") ?: return null
            val title = field("title") ?: return null
            return InAppNotice(id, field("type"), title, field("body").orEmpty(), PushData.parse(field("dataJson")))
        }
    }
}

/**
 * The in-app banners, one at a time in the order they came. The same notice from the push and the hub shows once; a
 * long burst keeps the newest few. Pure, so it can be tested; the banner host holds it as state.
 */
data class BannerQueue(
    val showing: InAppNotice? = null,
    val waiting: List<InAppNotice> = emptyList(),
    val seen: List<String> = emptyList(),
) {
    fun offer(notice: InAppNotice): BannerQueue {
        if (notice.id in seen || !bannerable(notice.type)) return this
        val remembered = (seen + notice.id).takeLast(REMEMBER)
        return if (showing == null) copy(showing = notice, seen = remembered)
        else copy(waiting = (waiting + notice).takeLast(MAX_WAITING), seen = remembered)
    }

    /** The banner on screen is done (tapped, swiped away or timed out): the next one comes down. */
    fun next(): BannerQueue = copy(showing = waiting.firstOrNull(), waiting = waiting.drop(1))

    val size get() = waiting.size + if (showing == null) 0 else 1

    companion object {
        const val MAX_WAITING = 4
        const val REMEMBER = 64
        /** How long a banner stays before it goes up by itself. */
        const val SHOW_MS = 5_000L

        /** The alarm rings its own screen, and the album nudge waits for later: neither is a banner. */
        fun bannerable(type: String?) = type != "exerciseDue" && type != "albumSyncReminder" && type != "exerciseProgress"
    }
}

/** Which Heartpact art a banner wears for a kind of notice. */
enum class BannerArt { Message, DareDone, DareSet, CallOff, Game, Live, Missed, Cycle, Album, Plus, Couple, Heart }

fun bannerArt(type: String?): BannerArt = when {
    type == null -> BannerArt.Heart
    type == "message" -> BannerArt.Message
    type == "exerciseCompleted" -> BannerArt.DareDone
    type == "exerciseGoalChanged" -> BannerArt.DareSet
    type == "exerciseCallOffAsked" -> BannerArt.CallOff
    type == "exerciseStarted" -> BannerArt.Live
    type == "exerciseMissed" || type == "exerciseCancelled" || type == "exercisePausedByPlan" -> BannerArt.Missed
    type.startsWith("game") -> BannerArt.Game
    type.startsWith("cycle") || type.startsWith("pregnancy") -> BannerArt.Cycle
    type.startsWith("memories") || type.startsWith("album") -> BannerArt.Album
    type.startsWith("plus") -> BannerArt.Plus
    type == "coupleConnected" || type == "locationShared" || type == "dailyQuestionAnswered" -> BannerArt.Couple
    else -> BannerArt.Heart
}
