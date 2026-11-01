package app.heartpact.ui.pair

import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What a scanned QR code, a pasted clipboard or an opened link holds: a partner's link, or their six-digit code. */
sealed interface InviteInput {
    data class Link(val token: String) : InviteInput
    data class Code(val digits: String) : InviteInput
}

/**
 * Finding an invite in text people bring: the share message pasted whole ("…heartpact.me/invite/ab12… Or enter the
 * code 482 031"), a bare code ("482-031"), or a QR code holding the link. Pure, so it is tested.
 */
object InviteParsing {
    const val CODE_DIGITS = 6
    private const val HOST = "heartpact.me"
    /** The server's tokens are 48 hex characters (24 random bytes); a little leeway either way. */
    private val TOKEN = Regex("[0-9a-f]{16,128}")
    private val LINK = Regex("""(?i)(?:https?://)?(?:www\.)?heartpact\.me/invite/([0-9a-f]{16,128})(?![0-9a-z])""")
    /** Six digits, maybe split 3+3 by a space or dash, not part of a longer number. */
    private val CODE = Regex("""(?<![0-9])([0-9]{3})[  -]?([0-9]{3})(?![0-9])""")

    fun parse(text: String?): InviteInput? = text?.let { linkToken(it)?.let(InviteInput::Link) ?: code(it)?.let(InviteInput::Code) }

    /** The token of the first invite link in [text]. */
    fun linkToken(text: String): String? = LINK.find(text)?.groupValues?.get(1)?.lowercase()

    /** The token from an opened link's host and path, as Android hands them over. */
    fun tokenFromPath(host: String?, pathSegments: List<String>): String? =
        pathSegments.takeIf { (host == HOST || host == "www.$HOST") && it.size == 2 && it[0] == "invite" && TOKEN.matches(it[1]) }?.get(1)

    /** A six-digit code in [text], only when there is exactly one (links are left out first, their hex can hold digits). */
    fun code(text: String): String? {
        val withoutLinks = LINK.replace(text, " ")
        val found = CODE.findAll(withoutLinks).map { it.groupValues[1] + it.groupValues[2] }.distinct().toList()
        return found.singleOrNull()
    }

    /**
     * "482 031": easier to read out and to check. Kept left to right in Arabic text too (an isolate around it), where
     * the space would otherwise let the two halves swap places ("031 482").
     */
    fun format(code: String) = LTR_START + (if (code.length == CODE_DIGITS) "${code.take(3)} ${code.drop(3)}" else code) + LTR_END

    private const val LTR_START = "\u2066"
    private const val LTR_END = "\u2069"

    fun link(token: String) = "https://$HOST/invite/$token"
}

/** How long an invite has left, by the minute. */
sealed interface InviteLeft {
    data object Expired : InviteLeft
    /** Under an hour: shown as a countdown. */
    data class Minutes(val minutes: Long) : InviteLeft
    data object Later : InviteLeft

    companion object {
        fun of(expiresAt: Instant, now: Instant): InviteLeft {
            val left = Duration.between(now, expiresAt)
            return when {
                left.isNegative || left.isZero -> Expired
                left < Duration.ofHours(1) -> Minutes((left.toMillis() + 59_999) / 60_000) // rounded up: never "0 min"
                else -> Later
            }
        }
    }
}

object InviteExpiry {
    /** The line under the code: "Works once · expires Tuesday at 9:14 AM", "Expires in 12 min", "This code has expired". */
    fun text(expiresAtUtc: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val expires = app.heartpact.data.InviteKeeping.expiresAtMs(expiresAtUtc)?.let(Instant::ofEpochMilli) ?: return tr("Works once")
        return when (val left = InviteLeft.of(expires, now)) {
            InviteLeft.Expired -> tr("This code has expired")
            is InviteLeft.Minutes -> trn(left.minutes.toInt(), "Works once · expires in {count} minute", "Works once · expires in {count} minutes")
            InviteLeft.Later -> {
                val local = expires.atZone(zone)
                tr("Works once · expires {day} at {time}", "day" to local.format(Dates.formatter("EEEE")), "time" to local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            }
        }
    }

    fun expired(expiresAtUtc: String, now: Instant = Instant.now()): Boolean =
        app.heartpact.data.InviteKeeping.expiresAtMs(expiresAtUtc)?.let { InviteLeft.of(Instant.ofEpochMilli(it), now) == InviteLeft.Expired } ?: false

    /** Soon enough to show it ticking. */
    fun ticking(expiresAtUtc: String, now: Instant = Instant.now()): Boolean =
        app.heartpact.data.InviteKeeping.expiresAtMs(expiresAtUtc)?.let { InviteLeft.of(Instant.ofEpochMilli(it), now) is InviteLeft.Minutes } ?: false
}

object InviteShare {
    /**
     * What goes to the partner, in WhatsApp or anywhere: what Heartpact is in one line, the link on a line of its own
     * (so it is tappable in every language, right to left too), then the code for typing in.
     */
    fun message(token: String, code: String): String = tr(
        "Let's try Heartpact together: little promises and dares we keep for each other.\nTap to join me: {link}\nOr enter my code in the app: {code}",
        "link" to InviteParsing.link(token), "code" to InviteParsing.format(code),
    )
}
