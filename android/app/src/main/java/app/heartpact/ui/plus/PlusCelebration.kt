package app.heartpact.ui.plus

import android.content.Context
import androidx.core.content.edit
import app.heartpact.data.Entitlement
import app.heartpact.data.Plan
import app.heartpact.data.PlanLimits

/** Who brought Plus, from this person's side: it picks the celebration's words. */
enum class CelebrationKind { BoughtByMe, BoughtByPartner, Gift }

/** The Plus celebration to show: who brought it, until when for a gift, and what it unlocks. */
data class Celebration(val kind: CelebrationKind, val untilUtc: String? = null, val limits: PlanLimits? = null)

/** What to do with an entitlement: show [show] (null: nothing), and remember [remember] for this person (null: keep). */
data class CelebrationDecision(val show: Celebration? = null, val remember: String? = null)

/**
 * When the couple goes from Free to Plus, each partner gets a celebration once, the next time Home shows (or at once on
 * the Plus screen after buying). It is decided from the entitlement, not from the `plusStarted` push, which can be
 * missed: the phone remembers, per signed-in person, the "Plus key" it last saw (who bought which plan in which store,
 * or the gift and its end), and celebrates a Plus whose key differs.
 *
 * - Free is remembered as [FREE], so the next Plus is a new Plus period and is celebrated, even the same plan again.
 * - Nothing remembered yet (this version's first look for this person): an existing Plus is remembered silently, so a
 *   couple already on Plus when the app updates isn't congratulated on old news. [sawFree] says the caller watched it
 *   go from Free to Plus itself (the Plus screen, after a purchase), which is celebrated even then.
 */
object PlusCelebrations {
    const val FREE = "free"

    /** The key of the Plus the couple has, or null on Free. */
    fun key(entitlement: Entitlement): String? {
        if (entitlement.plan != Plan.Plus) return null
        val subscription = entitlement.subscription
        return when {
            subscription != null -> "sub:${subscription.store}:${subscription.productId}:${if (subscription.purchasedByMe) "me" else "partner"}"
            entitlement.complimentary -> "gift:${entitlement.complimentaryUntilUtc.orEmpty()}"
            else -> "plus"
        }
    }

    fun celebration(entitlement: Entitlement): Celebration {
        val subscription = entitlement.subscription
        val kind = when {
            subscription != null -> if (subscription.purchasedByMe) CelebrationKind.BoughtByMe else CelebrationKind.BoughtByPartner
            entitlement.complimentary -> CelebrationKind.Gift
            else -> CelebrationKind.BoughtByMe
        }
        return Celebration(kind, if (kind == CelebrationKind.Gift) entitlement.complimentaryUntilUtc else null, entitlement.plusLimits ?: entitlement.limits)
    }

    fun decide(entitlement: Entitlement, remembered: String?, sawFree: Boolean = false): CelebrationDecision {
        val key = key(entitlement)
            ?: return CelebrationDecision(remember = FREE.takeIf { remembered != FREE })
        return when {
            key == remembered -> CelebrationDecision()
            remembered == null && !sawFree -> CelebrationDecision(remember = key)
            else -> CelebrationDecision(show = celebration(entitlement), remember = key)
        }
    }
}

/** Where the last Plus key seen is kept for each person who signs in on this phone. */
interface CelebrationMemory {
    fun get(userId: String): String?
    fun set(userId: String, key: String)
}

/** The app's prefs: per person, so a partner signing in on the same phone gets their own celebration. */
class PlusCelebrationStore(context: Context) : CelebrationMemory {
    private val prefs = context.applicationContext.getSharedPreferences("plus_celebration", Context.MODE_PRIVATE)
    override fun get(userId: String): String? = prefs.getString(userId, null)
    override fun set(userId: String, key: String) = prefs.edit { putString(userId, key) }
}

/** Decides for [userId] and remembers what the decision says; returns what to show, if anything. */
fun CelebrationMemory.observe(userId: String, entitlement: Entitlement, sawFree: Boolean = false): Celebration? {
    val decision = PlusCelebrations.decide(entitlement, get(userId), sawFree)
    decision.remember?.let { set(userId, it) }
    return decision.show
}
