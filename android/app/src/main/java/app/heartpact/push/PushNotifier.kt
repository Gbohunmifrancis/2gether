package app.heartpact.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import app.heartpact.MainActivity
import app.heartpact.R
import app.heartpact.album.AlbumNotices
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn

/** A server notice to show on this phone, as its push carried it. */
data class PushNotice(val id: String, val type: String?, val title: String, val body: String, val quiet: Boolean = false, val data: PushData = PushData()) {
    val target: PushTarget get() = PushActions.target(type, data)
}

/** The partner as the shade shows them: a name and, when there is one, their picture. */
data class PartnerFace(val userId: String?, val name: String, val avatar: Bitmap? = null)

/**
 * Draws the server's notices (docs/push-notifications.md): a chat-style conversation for messages, with the partner's
 * picture, an inline reply and a heart; big text with the heart as the picture for the rest, with the actions
 * [PushActions] gives each type; the app's indigo; one group for everything from the partner, with a summary once
 * there are several.
 */
object PushNotifier {
    const val GROUP = "app.heartpact.partner"
    /** Every message from the partner lands in one conversation, as a chat app shows it. */
    const val CONVERSATION_ID = 0x48500002
    private const val SUMMARY_ID = 0x48500001
    private const val SHORTCUT = "partner"
    const val REPLY_KEY = "reply"
    /** A settled notice (a cheer sent, a dare let off) says so, then goes by itself. */
    private const val SETTLED_MS = 6_000L

    const val EXTRA_OPEN_ROUTE = "app.heartpact.OPEN_ROUTE"
    /** An action that opens the app (Open, Play, Watch live) clears its notification once the app has it. */
    const val EXTRA_CLEAR = "app.heartpact.CLEAR_NOTIFICATION"

    /** The partner's name as last shown, for the group summary. */
    @Volatile private var lastPartner: String? = null

    fun notificationId(notice: PushNotice) = if (notice.type == "message") CONVERSATION_ID else notice.id.hashCode()

    fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** [silent] when the app is open and its own banner already told the person: it waits in the shade, quietly. */
    fun show(context: Context, notice: PushNotice, partner: PartnerFace, me: String?, silent: Boolean = false) {
        if (!canPost(context)) return
        lastPartner = partner.name
        Push.ensureChannels(context)
        val id = notificationId(notice)
        val builder = NotificationCompat.Builder(context, PushActions.channel(notice.type, notice.quiet))
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(Push.ACCENT)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setAutoCancel(true)
            .setContentIntent(open(context, notice.target, id * 31))
            .setGroup(GROUP)
            .setCategory(category(notice.type))
            .setSilent(silent)
            .setOnlyAlertOnce(silent)
        if (notice.type == "message") {
            val person = person(partner)
            builder.setStyle(conversation(context, me).addMessage(notice.body, System.currentTimeMillis(), person))
                .setShortcutId(shortcut(context, person))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notice.body)).setLargeIcon(appIcon(context))
        }
        PushActions.actions(notice.type, notice.data).forEach { builder.addAction(action(context, it, notice, id, partner.name)) }
        post(context, id, builder.build())
        summarise(context, partner.name, notice.quiet)
    }

    /** After a reply or a heart from the shade: the conversation shows what was sent, or says it wasn't, with Reply again. */
    fun replied(context: Context, me: String?, partner: PartnerFace, text: String?, failed: String? = null) {
        if (!canPost(context)) return
        val notice = PushNotice("conversation", "message", partner.name, failed ?: text.orEmpty())
        val builder = NotificationCompat.Builder(context, Push.PARTNER)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(Push.ACCENT)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(shortcut(context, person(partner)))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(open(context, notice.target, CONVERSATION_ID * 31))
            .addAction(action(context, PushAction.Reply, notice, CONVERSATION_ID, partner.name))
        if (failed == null) {
            val style = conversation(context, me)
            // A message with no person is the person holding the phone.
            if (!text.isNullOrBlank()) style.addMessage(text, System.currentTimeMillis(), null as Person?)
            builder.setStyle(style)
        } else {
            builder.setContentTitle(partner.name).setContentText(failed).setStyle(NotificationCompat.BigTextStyle().bigText(failed))
        }
        post(context, CONVERSATION_ID, builder.build())
    }

    /** A notice whose action is done (or couldn't be): a quiet line in its place that goes by itself unless it failed. */
    fun settle(context: Context, id: Int, title: String, line: String, target: PushTarget, failed: Boolean) {
        if (!canPost(context)) return
        val builder = NotificationCompat.Builder(context, Push.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(Push.ACCENT)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open(context, target, id * 31))
        if (failed) builder.addAction(0, tr("Open"), open(context, target, id * 31 + 1, clear = id)) else builder.setTimeoutAfter(SETTLED_MS)
        post(context, id, builder.build())
        summarise(context, null, quiet = false)
    }

    /** [canPost] checks the permission; one revoked in between costs only this notice. */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun dismiss(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
        summarise(context, null, quiet = false)
    }

    /** Several from the partner: one summary over them, quiet itself (the newest one already rang). */
    private fun summarise(context: Context, partnerName: String?, quiet: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val children = runCatching { manager.activeNotifications.filter { it.notification.group == GROUP && it.id != SUMMARY_ID && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 } }
            .getOrNull() ?: return
        if (children.size < 2) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val name = partnerName ?: lastPartner ?: tr("your partner")
        val inbox = NotificationCompat.InboxStyle()
        children.sortedByDescending { it.postTime }.take(6).forEach { child ->
            val title = child.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)
            val text = child.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)
            inbox.addLine(listOfNotNull(title, text).joinToString(": "))
        }
        val summary = NotificationCompat.Builder(context, if (quiet) Push.QUIET else Push.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setColor(Push.ACCENT)
            .setContentTitle(trn(children.size, "{count} from {partner}", "{count} from {partner}", "partner" to name))
            .setStyle(inbox)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open(context, PushTarget("today"), SUMMARY_ID))
            .build()
        post(context, SUMMARY_ID, summary)
    }

    private fun category(type: String?) = when {
        type == "message" -> NotificationCompat.CATEGORY_MESSAGE
        type == null -> NotificationCompat.CATEGORY_SOCIAL
        type.startsWith("game") -> NotificationCompat.CATEGORY_SOCIAL
        type.startsWith("exercise") -> NotificationCompat.CATEGORY_REMINDER
        else -> NotificationCompat.CATEGORY_SOCIAL
    }

    /** Opens the app where [target] points: a tab, or a screen over Home. */
    fun open(context: Context, target: PushTarget, requestCode: Int, clear: Int? = null): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(AlbumNotices.EXTRA_OPEN_TAB, target.tab)
            .apply { target.route?.let { putExtra(EXTRA_OPEN_ROUTE, it) } }
            .apply { clear?.let { putExtra(EXTRA_CLEAR, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: PushAction, notice: PushNotice, id: Int, partnerName: String): NotificationCompat.Action {
        val code = id * 31 + 2 + action.ordinal
        return when (action) {
            PushAction.Open -> NotificationCompat.Action.Builder(0, tr("Open"), open(context, notice.target, code, clear = id)).build()
            PushAction.Play -> NotificationCompat.Action.Builder(0, tr("Play"), open(context, notice.target, code, clear = id)).build()
            PushAction.WatchLive -> NotificationCompat.Action.Builder(0, tr("Watch live"), open(context, notice.target, code, clear = id)).build()
            PushAction.Reply -> NotificationCompat.Action.Builder(R.drawable.ic_stat_heart, tr("Reply"), broadcast(context, action, notice, id, partnerName, code, mutable = true))
                .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel(tr("Reply to {partner}", "partner" to partnerName)).build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(true)
                .build()
            else -> NotificationCompat.Action.Builder(0, label(action), broadcast(context, action, notice, id, partnerName, code, mutable = false))
                .setShowsUserInterface(false)
                .apply { if (action == PushAction.KeepIt || action == PushAction.GotIt) setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ) }
                .build()
        }
    }

    fun label(action: PushAction): String = when (action) {
        PushAction.Reply -> tr("Reply")
        PushAction.Heart -> tr("Send a heart")
        PushAction.Open -> tr("Open")
        PushAction.GotIt -> tr("Got it")
        PushAction.Cheer -> tr("Cheer")
        PushAction.Play -> tr("Play")
        PushAction.LetOff -> tr("Let them off")
        PushAction.KeepIt -> tr("Keep it ringing")
        PushAction.WatchLive -> tr("Watch live")
    }

    private fun broadcast(context: Context, action: PushAction, notice: PushNotice, id: Int, partnerName: String, code: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, PushActionReceiver::class.java)
            .setAction("app.heartpact.push.${action.name}")
            .putExtra(PushActionReceiver.EXTRA_ACTION, action.name)
            .putExtra(PushActionReceiver.EXTRA_NOTIFICATION, id)
            .putExtra(PushActionReceiver.EXTRA_TYPE, notice.type)
            .putExtra(PushActionReceiver.EXTRA_TITLE, notice.title)
            .putExtra(PushActionReceiver.EXTRA_PARTNER, partnerName)
            .putExtra(PushActionReceiver.EXTRA_OCCURRENCE, notice.data.occurrenceId)
            .putExtra(PushActionReceiver.EXTRA_TAB, notice.target.tab)
            .putExtra(PushActionReceiver.EXTRA_ROUTE, notice.target.route)
        // A reply's text is filled in by the system, so only that one may change.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }

    private fun person(partner: PartnerFace): Person = Person.Builder()
        .setName(partner.name)
        .setKey(partner.userId ?: SHORTCUT)
        .setImportant(true)
        .apply { partner.avatar?.let { setIcon(IconCompat.createWithBitmap(circle(it))) } }
        .build()

    /** The conversation so far (the shade's own copy), or a new one, so messages pile up as in a chat. */
    private fun conversation(context: Context, me: String?): NotificationCompat.MessagingStyle {
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = runCatching { manager?.activeNotifications?.firstOrNull { it.id == CONVERSATION_ID }?.notification }.getOrNull()
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(tr("You")).setKey(me ?: "me").build())
        existing?.messages?.takeLast(7)?.forEach { style.addMessage(it) }
        return style
    }

    /** A long-lived shortcut for the partner, so the conversation sits with the person's other chats. */
    private fun shortcut(context: Context, partner: Person): String {
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, ShortcutInfoCompat.Builder(context, SHORTCUT)
                .setShortLabel(partner.name ?: tr("your partner"))
                .setPerson(partner)
                .setLongLived(true)
                .setIcon(partner.icon ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(AlbumNotices.EXTRA_OPEN_TAB, "today"))
                .build())
        }
        return SHORTCUT
    }

    /** The launcher heart, as the picture beside a notice. */
    private var icon: Bitmap? = null
    private fun appIcon(context: Context): Bitmap? = icon ?: runCatching {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        createBitmap(size, size).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }
    }.getOrNull()?.also { icon = it }

    private fun circle(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val out = createBitmap(size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-(source.width - size) / 2f, -(source.height - size) / 2f) })
            }
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }
}
