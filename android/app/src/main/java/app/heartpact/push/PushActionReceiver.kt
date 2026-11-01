package app.heartpact.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.edit
import app.heartpact.HeartpactApplication
import app.heartpact.data.ApiResult
import app.heartpact.data.AppContainer
import app.heartpact.data.SendMessageRequest
import app.heartpact.data.apiCall
import app.heartpact.i18n.Tr
import app.heartpact.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A notification's action done from the shade, without opening the app (PushActions): a reply or a heart sent as a
 * message, a cheer for a dare just done, letting the partner off today's dare (the same `cancel` as "Call it off
 * together", which the server allows only while that dare is ringing), or "Got it" / "Keep it", which only clear it.
 * Each says how it went in the notification's place.
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { name -> PushAction.entries.firstOrNull { it.name == name } } ?: return
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (action == PushAction.GotIt || action == PushAction.KeepIt) {
            PushNotifier.dismiss(context, id)
            return
        }
        val container = (context.applicationContext as HeartpactApplication).container
        Tr.load(context)
        val reply = if (action == PushAction.Reply) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PushNotifier.REPLY_KEY)?.toString()?.trim() else null
        val pending = goAsync()
        scope.launch {
            try {
                val done = withTimeoutOrNull(9_000) { handle(context, container, action, intent, id, reply) }
                if (done == null) unreached(context, container, action, intent, id)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, container: AppContainer, action: PushAction, intent: Intent, id: Int, reply: String?) {
        // The token for the calls below; signed out, there is nothing to send for.
        val session = container.session.load()
        val partner = PartnerCache.face(container, refresh = false)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val target = PushTarget(intent.getStringExtra(EXTRA_TAB) ?: "today", intent.getStringExtra(EXTRA_ROUTE))
        if (session == null) {
            PushNotifier.settle(context, id, title, tr("Sign in to Heartpact to answer."), target, failed = true)
            return
        }
        when (action) {
            PushAction.Reply, PushAction.Heart -> {
                val text = if (action == PushAction.Heart) PushActions.HEART else reply
                if (text.isNullOrBlank()) {
                    PushNotifier.replied(context, session.user.id, partner, null)
                    return
                }
                val sent = apiCall(container.json) { container.api.sendMessage(SendMessageRequest(text.take(4000))) }
                if (sent is ApiResult.Ok) PushNotifier.replied(context, session.user.id, partner, text)
                else PushNotifier.replied(context, session.user.id, partner, null, failed = failure(sent, tr("Not sent. Try again in a moment.")))
            }
            PushAction.Cheer -> {
                val sent = apiCall(container.json) { container.api.sendMessage(SendMessageRequest(PushActions.CHEER)) }
                if (sent is ApiResult.Ok) PushNotifier.settle(context, id, title, tr("Cheer sent to {partner}.", "partner" to partner.name), target, failed = false)
                else PushNotifier.settle(context, id, title, failure(sent, tr("Your cheer didn't go. Open Heartpact to try again.")), target, failed = true)
            }
            PushAction.LetOff -> {
                val occurrence = intent.getStringExtra(EXTRA_OCCURRENCE)
                if (occurrence == null) {
                    PushNotifier.settle(context, id, title, tr("Open Heartpact to answer."), target, failed = true)
                    return
                }
                val name = intent.getStringExtra(EXTRA_PARTNER) ?: partner.name
                when (val result = container.exercise.cancel(occurrence)) {
                    is ApiResult.Ok -> {
                        PushNotifier.settle(context, id, title, tr("You let {partner} off today.", "partner" to name), target, failed = false)
                        container.pushEvents.tryEmit("exerciseCancelled")
                    }
                    // The dare isn't ringing any more: done, missed or already called off.
                    is ApiResult.Failure -> if (result.status == 409 || result.status == 404) {
                        PushNotifier.settle(context, id, title, tr("That dare isn't ringing any more."), target, failed = false)
                    } else PushNotifier.settle(context, id, title, result.message, target, failed = true)
                    ApiResult.Offline -> PushNotifier.settle(context, id, title, tr("Couldn't reach Heartpact. Open the app to answer."), target, failed = true)
                }
            }
            else -> PushNotifier.dismiss(context, id)
        }
    }

    /** Ran out of time: say so where the notice was, so nothing is left spinning. */
    private fun unreached(context: Context, container: AppContainer, action: PushAction, intent: Intent, id: Int) {
        val line = tr("Couldn't reach Heartpact. Open the app to answer.")
        val target = PushTarget(intent.getStringExtra(EXTRA_TAB) ?: "today", intent.getStringExtra(EXTRA_ROUTE))
        if (action == PushAction.Reply || action == PushAction.Heart) {
            PushNotifier.replied(context, null, PartnerCache.cached(container), null, failed = line)
        } else PushNotifier.settle(context, id, intent.getStringExtra(EXTRA_TITLE).orEmpty(), line, target, failed = true)
    }

    private fun failure(result: ApiResult<*>, fallback: String) = when (result) {
        ApiResult.Offline -> tr("Couldn't reach Heartpact. Open the app to answer.")
        is ApiResult.Failure -> result.message.ifBlank { fallback }
        else -> fallback
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val EXTRA_ACTION = "app.heartpact.push.ACTION"
        const val EXTRA_NOTIFICATION = "app.heartpact.push.NOTIFICATION"
        const val EXTRA_TYPE = "app.heartpact.push.TYPE"
        const val EXTRA_TITLE = "app.heartpact.push.TITLE"
        const val EXTRA_PARTNER = "app.heartpact.push.PARTNER"
        const val EXTRA_OCCURRENCE = "app.heartpact.push.OCCURRENCE"
        const val EXTRA_TAB = "app.heartpact.push.TAB"
        const val EXTRA_ROUTE = "app.heartpact.push.ROUTE"
    }
}

/**
 * The partner's name and picture for the shade, kept in the push prefs so a notice drawn offline still has them.
 * Refreshed from the couple when a push comes in (a short wait at most).
 */
object PartnerCache {
    private const val ID = "partner_id"
    private const val NAME = "partner_name"
    private const val AVATAR = "partner_avatar_version"

    fun cached(container: AppContainer): PartnerFace {
        val prefs = container.pushPrefs
        return PartnerFace(prefs.getString(ID, null), prefs.getString(NAME, null) ?: tr("your partner"))
    }

    suspend fun face(container: AppContainer, refresh: Boolean = true): PartnerFace {
        val prefs = container.pushPrefs
        if (refresh) {
            val session = container.session.load()
            val couple = session?.let { withTimeoutOrNull(3_000) { container.couple.current(it.user.id) } as? ApiResult.Ok }?.value
            couple?.members?.firstOrNull { it.userId != session.user.id }?.let { partner ->
                prefs.edit { putString(ID, partner.userId).putString(NAME, partner.displayName).putLong(AVATAR, partner.avatarVersion) }
            }
        }
        val id = prefs.getString(ID, null)
        val version = prefs.getLong(AVATAR, 0)
        val avatar = id?.let { withTimeoutOrNull(3_000) { runCatching { container.avatars.load(it, version) }.getOrNull() } }
        return PartnerFace(id, prefs.getString(NAME, null) ?: tr("your partner"), avatar?.asAndroidBitmap())
    }
}
