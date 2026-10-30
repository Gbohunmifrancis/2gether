package app.heartpact.ui.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** A place on the map. */
data class Pos(val lat: Double, val lng: Double)

/**
 * Pure: how the markers and the camera move. Markers glide between fixes, a halo pulses while a fix is live, and the
 * camera sways a few degrees when nobody is touching it. The map code feeds these a clock and applies the result.
 */
object MapMotion {
    /** A marker takes this long to glide from its last fix to the new one. */
    const val GLIDE_MS = 800L

    /** A fix younger than this is "live": the halo pulses around it. */
    const val LIVE_MINUTES = 5L

    /** How long one pulse of the halo takes. */
    const val PULSE_MS = 2_200L

    /** The camera's resting tilt, steep enough for the buildings to stand up. */
    const val PITCH = 55.0

    /** The sway: degrees either side of where the camera was left, and the time one sway takes. */
    const val SWAY_DEGREES = 9.0
    const val SWAY_PERIOD_MS = 36_000L

    /** Never zoom closer than this when framing, so two people standing together don't fill the screen with one roof. */
    const val MAX_FRAME_ZOOM = 17.0

    fun isLive(minutesSinceFix: Long): Boolean = minutesSinceFix < LIVE_MINUTES

    /** Ease in and out, 0..1 to 0..1. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    /** Between two places, the short way round the date line. */
    fun lerp(from: Pos, to: Pos, t: Float): Pos {
        var dLng = to.lng - from.lng
        if (dLng > 180) dLng -= 360 else if (dLng < -180) dLng += 360
        var lng = from.lng + dLng * t
        if (lng > 180) lng -= 360 else if (lng < -180) lng += 360
        return Pos(from.lat + (to.lat - from.lat) * t, lng)
    }

    /** The halo of a live marker at [timeMs]: radius as a fraction of its widest (0..1) and opacity (0..1). */
    data class Halo(val radius: Float, val opacity: Float)

    fun halo(timeMs: Long): Halo {
        val phase = ((timeMs % PULSE_MS).toFloat() / PULSE_MS).coerceIn(0f, 1f)
        val grow = 1f - (1f - phase).pow(2)
        return Halo(radius = 0.3f + 0.7f * grow, opacity = 0.5f * (1f - phase).pow(1.6f))
    }

    /** The camera's bearing a while after it was left at [startBearing]: a slow sine, so it starts exactly where it was. */
    fun sway(startBearing: Double, elapsedMs: Long): Double {
        val angle = 2 * PI * (elapsedMs.toDouble() / SWAY_PERIOD_MS)
        return normalizeBearing(startBearing + SWAY_DEGREES * sin(angle))
    }

    /** Degrees in 0..360. */
    fun normalizeBearing(degrees: Double): Double {
        val r = degrees % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** The zoom to frame two places and no closer than [MAX_FRAME_ZOOM]. */
    fun frameZoom(computed: Double): Double = computed.coerceAtMost(MAX_FRAME_ZOOM)

    /** True when two positions are the same place for a map (about a metre). */
    fun samePlace(a: Pos, b: Pos): Boolean = abs(a.lat - b.lat) < 1e-5 && abs(a.lng - b.lng) < 1e-5
}

/**
 * One marker's position over time. [moveTo] starts a glide from wherever the marker is now, so a fix that arrives
 * mid-glide continues smoothly rather than jumping back to the previous fix.
 */
class MarkerTween(start: Pos, private val glideMs: Long = MapMotion.GLIDE_MS) {
    private var from = start
    private var to = start
    private var startedAtMs = 0L

    val target: Pos get() = to

    fun at(nowMs: Long): Pos {
        val t = ((nowMs - startedAtMs).toFloat() / glideMs).coerceIn(0f, 1f)
        return if (t >= 1f) to else MapMotion.lerp(from, to, MapMotion.ease(t))
    }

    fun isMoving(nowMs: Long): Boolean = nowMs - startedAtMs < glideMs && from != to

    /** Glide to [target], unless that is where it is already heading. */
    fun moveTo(target: Pos, nowMs: Long) {
        if (MapMotion.samePlace(target, to)) return
        from = at(nowMs)
        to = target
        startedAtMs = nowMs
    }

    /** Jump to [place] with no glide. */
    fun snapTo(place: Pos) {
        from = place
        to = place
        startedAtMs = 0L
    }
}
