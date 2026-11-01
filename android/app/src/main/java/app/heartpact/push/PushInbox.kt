package app.heartpact.push

/**
 * Delivery is at least once, so the app remembers the last ids it handled and drops repeats.
 * Pure over a string list so it can be tested; the service keeps the list in SharedPreferences.
 */
object PushInbox {
    const val KEEP = 100

    /** Returns the new list to store, or null when [id] was already handled. */
    fun accept(seen: List<String>, id: String): List<String>? = if (id in seen) null else (seen + id).takeLast(KEEP)

    /** Not a tab: the games screen, over home. */
    const val GAMES = "games"

    /**
     * Which tab a tap on this kind of notification should open; game notices open the games themselves. Plus
     * ("plusStarted", "plusEnding"), the partner sharing their location ("locationShared": the map card) or their
     * cycle ("cycleShared": the cycle row) all live on Us.
     */
    fun tabFor(type: String?): String = when {
        type == null -> "today"
        type.startsWith("exercise") -> "today"
        type == "memoriesSynced" || type == "albumSyncReminder" || type == "albumAlmostFull" -> "album"
        type.startsWith("game") -> GAMES
        // Plus, the map card, the cycle row, today's question, and anything newer than this app.
        else -> "us"
    }

    /**
     * Kinds that change what Home shows beyond Today, so it asks again at once: "plusStarted" the entitlement (and so
     * the Plus celebration), "locationShared" the map card, "cycleShared" the cycle row.
     */
    fun refreshesHome(type: String?) = type == "plusStarted" || type == "locationShared" || type == "cycleShared"

    /** Kinds that change what Today shows, so an open Today refreshes at once. */
    fun refreshesToday(type: String?) = type != null && type.startsWith("exercise") && type != "exerciseProgress" && type !in LIVE

    /** The live view's stream and its replies (hub only): nothing to refresh. */
    private val LIVE = setOf("exerciseLive", "exerciseWatching", "exerciseCheer")

    /** Not a tab: the partner's live view of a dare, `watch/{occurrenceId}`. */
    const val WATCH = "watch/"

    /**
     * Where a tap on a notification goes: the live view for "exerciseStarted" (the partner started counting) when its data
     * names the dare, else [tabFor].
     */
    fun openFor(type: String?, data: String?): String {
        if (type == "exerciseStarted") occurrenceIdIn(data)?.let { return WATCH + it }
        return tabFor(type)
    }

    /** The live view's dare from a [WATCH] target, or null for any other. */
    fun watchedOccurrence(target: String?): String? = target?.takeIf { it.startsWith(WATCH) }?.removePrefix(WATCH)?.takeIf { ID.matches(it) }

    private val ID = Regex("[0-9a-fA-F-]{36}")
    private val OCCURRENCE = Regex("\"occurrenceId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")

    private fun occurrenceIdIn(data: String?): String? = data?.let { OCCURRENCE.find(it)?.groupValues?.get(1) }
}
