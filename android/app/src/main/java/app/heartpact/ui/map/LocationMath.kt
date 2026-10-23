package app.heartpact.ui.map

import app.heartpact.data.SharingStatus
import app.heartpact.data.SharingStatusView
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure: distances, ages, the status line, and placing two points on a drawn map. */
object LocationMath {
    private const val EARTH_M = 6_371_000.0

    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = (lat2 - lat1) * PI / 180; val dl = (lng2 - lng1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(a))
    }

    fun distance(m: Double): String = when {
        m < 100 -> tr("right next to you")
        m < 1000 -> tr("{meters} m away", "meters" to (m / 10).roundToInt() * 10)
        m < 10_000 -> tr("{km} km away", "km" to "%.1f".format(java.util.Locale.getDefault(), m / 1000))
        else -> tr("{km} km away", "km" to (m / 1000).roundToInt())
    }

    fun age(updatedAtUtc: String, now: Instant): String {
        val minutes = Duration.between(utc(updatedAtUtc), now).toMinutes()
        return when {
            minutes < 1 -> tr("updated just now")
            minutes < 60 -> tr("updated {minutes} min ago", "minutes" to minutes)
            minutes < 24 * 60 -> tr("updated {hours} h ago", "hours" to minutes / 60)
            else -> tr("updated over a day ago")
        }
    }

    /** [age] without "updated": "2 min ago", "just now". */
    fun ago(updatedAtUtc: String, now: Instant): String {
        val minutes = Duration.between(utc(updatedAtUtc), now).toMinutes()
        return when {
            minutes < 1 -> tr("just now")
            minutes < 60 -> tr("{minutes} min ago", "minutes" to minutes)
            minutes < 24 * 60 -> tr("{hours} h ago", "hours" to minutes / 60)
            else -> tr("over a day ago")
        }
    }

    /** "Until tomorrow" pauses until 8:00 the next morning, within the server's 15 min to 24 h. */
    fun minutesUntilTomorrow(now: ZonedDateTime): Int {
        val morning = now.toLocalDate().plusDays(1).atTime(LocalTime.of(8, 0)).atZone(now.zone)
        return Duration.between(now, morning).toMinutes().toInt().coerceIn(15, 1440)
    }

    fun headline(status: SharingStatusView?, partner: String): String {
        val me = status?.me?.status ?: SharingStatus.Off
        val them = status?.partner?.status ?: SharingStatus.Off
        return when {
            status?.mutual == true -> tr("You both share location")
            me == SharingStatus.Paused || them == SharingStatus.Paused -> if (me == SharingStatus.Paused) tr("You paused sharing") else tr("{partner} paused sharing", "partner" to partner)
            me == SharingStatus.Sharing -> tr("You're sharing; {partner} isn't yet", "partner" to partner)
            them == SharingStatus.Sharing -> tr("{partner} shares; you don't yet", "partner" to partner)
            else -> tr("Location sharing is off")
        }
    }

    /** The time a pause ends, "18:30", for "Paused until {time}". */
    fun pausedUntil(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        iso?.let { utc(it).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString() }

    /**
     * Two points on a drawn map: an equirectangular projection around their midpoint, scaled so both sit
     * inside the frame with a margin. Returns fractions (0..1) of width and height.
     */
    fun place(lat1: Double, lng1: Double, lat2: Double, lng2: Double, aspect: Float, margin: Float = 0.22f): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val midLat = (lat1 + lat2) / 2
        val kx = cos(midLat * PI / 180)
        val dx = ((lng2 - lng1) * kx).toFloat()
        val dy = (lat2 - lat1).toFloat()
        val span = maxOf(kotlin.math.abs(dx) / aspect, kotlin.math.abs(dy), 1e-9f)
        val scale = (1 - 2 * margin) / span
        fun at(x: Float, y: Float) = (0.5f + x * scale / aspect) to (0.5f - y * scale)
        return at(-dx / 2, -dy / 2) to at(dx / 2, dy / 2)
    }
}
