package app.heartpact.ui.games

import androidx.compose.ui.geometry.Offset
import app.heartpact.data.SnakesLink
import app.heartpact.data.SnakesMove
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Snakes and ladders, the pure part: where each square sits on the board, the shape of every ladder and snake, and
 * how one roll from the server (its `last`) becomes a sequence of beats both phones play the same way: hop, hop,
 * hop, then the ladder, the snake, the swap. Nothing here draws; the board (SnakesAndLaddersBoard) draws what this says.
 */

/** The board in units: ten squares a side, one unit each, square 1 bottom left, with a start strip under row one. */
internal object SnakesGeometry {
    const val SIDE = 10
    /** The strip under the board where both tokens wait at 0, before square 1. */
    const val START_STRIP = 0.9f
    const val HEIGHT = SIDE + START_STRIP

    fun row(square: Int): Int = (square - 1) / SIDE

    /** Columns from the left: rows run left to right, then right to left, turning at each end. */
    fun column(square: Int): Int {
        val inRow = (square - 1) % SIDE
        return if (row(square) % 2 == 0) inRow else SIDE - 1 - inRow
    }

    /** The middle of a square, in board units (y down). Square 0 is the start strip, under square 1. */
    fun center(square: Int): Offset = when {
        square <= 0 -> Offset(0.75f, SIDE + START_STRIP / 2f)
        else -> {
            val s = square.coerceAtMost(SIDE * SIDE)
            Offset(column(s) + 0.5f, SIDE - row(s) - 0.5f)
        }
    }

    /** A ladder's two rails, from its foot to its top, [half] apart either side of the middle. */
    fun rails(link: SnakesLink, half: Float = 0.17f): Pair<Pair<Offset, Offset>, Pair<Offset, Offset>> {
        val a = center(link.from)
        val b = center(link.to)
        val n = normal(b - a) * half
        return (a + n to b + n) to (a - n to b - n)
    }

    /** How many rungs a ladder has: one about every third of a square. */
    fun rungs(link: SnakesLink): Int = max(3, ((center(link.to) - center(link.from)).getDistance() / 0.34f).toInt())

    /** A point on a ladder, [t] 0 at the foot to 1 at the top. */
    fun onLadder(link: SnakesLink, t: Float): Offset = lerp(center(link.from), center(link.to), t)

    /**
     * A point on a snake, [t] 0 at the head to 1 at the tail: an S-curve whose bend side comes from [index], with a
     * body that sways ([sway] is the idle wiggle's phase, in radians; the head and tail stay put).
     */
    fun onSnake(link: SnakesLink, index: Int, t: Float, sway: Float = 0f): Offset {
        val head = center(link.from)
        val tail = center(link.to)
        val d = tail - head
        val n = normal(d)
        val length = d.getDistance()
        val side = if (index % 2 == 0) 1f else -1f
        val bend = side * (0.55f + 0.12f * (index % 3)) * (0.6f + 0.4f * (length / 6f).coerceAtMost(1f))
        val c1 = head + d * (1f / 3f) + n * bend
        val c2 = head + d * (2f / 3f) - n * bend
        val u = 1f - t
        val p = head * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + tail * (t * t * t)
        // The wiggle: a travelling wave along the body, zero at both ends.
        val wave = 0.16f * sin(t * PI.toFloat() * 3f - sway) * (4f * t * (1f - t))
        return p + n * wave
    }

    fun normal(d: Offset): Offset {
        val l = d.getDistance().takeIf { it > 0.0001f } ?: return Offset(0f, 1f)
        return Offset(-d.y / l, d.x / l)
    }

    fun lerp(a: Offset, b: Offset, t: Float) = a + (b - a) * t
}

/** One step of a replay, in the order the server played it. [ms] is how long it takes on screen. */
internal sealed interface SnakesBeat {
    val player: String
    val ms: Int

    /** One square forward (or back, bouncing off 100). */
    data class Hop(override val player: String, val from: Int, val to: Int, val bouncing: Boolean) : SnakesBeat { override val ms get() = 210 }
    data class Climb(override val player: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 420 + 70 * (to - from).coerceAtMost(60) / 3 }
    data class Slide(override val player: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 900 + 60 * (from - to).coerceAtMost(60) / 3 }
    /** A snake's head, and a hug shield that cancels it. */
    data class Shielded(override val player: String, val at: Int) : SnakesBeat { override val ms get() = 1000 }
    data class Hug(override val player: String, val at: Int) : SnakesBeat { override val ms get() = 850 }
    data class Boost(override val player: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 700 }
    data class Heartbreak(override val player: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 1100 }
    /** The roller goes from [from] to [to]; their partner flies the other way. */
    data class Swap(override val player: String, val partner: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 1100 }
    data class Kiss(override val player: String, val at: Int) : SnakesBeat { override val ms get() = 750 }
    data class ThreeSixes(override val player: String, val from: Int, val to: Int) : SnakesBeat { override val ms get() = 1200 }
    data class Win(override val player: String) : SnakesBeat { override val ms get() = 1300 }
}

internal object SnakesReplay {
    /** A move is replayed once: only one newer than the last one seen (and none of what was there when the board opened). */
    fun shouldReplay(seen: Int, move: SnakesMove?): Boolean = move != null && move.move > seen

    /** The beats of one roll: a hop per square, then each event the server listed, in its order. */
    fun beats(move: SnakesMove, partner: String?): List<SnakesBeat> {
        val out = mutableListOf<SnakesBeat>()
        var at = move.from
        move.steps.forEach { square ->
            out += SnakesBeat.Hop(move.player, at, square, bouncing = square < at)
            at = square
        }
        move.events.forEach { e ->
            when (e.kind) {
                "ladder" -> out += SnakesBeat.Climb(move.player, e.from, e.to)
                "snake" -> out += SnakesBeat.Slide(move.player, e.from, e.to)
                "shield" -> out += SnakesBeat.Shielded(move.player, e.from)
                "hug" -> out += SnakesBeat.Hug(move.player, e.from)
                "boost" -> out += SnakesBeat.Boost(move.player, e.from, e.to)
                "heartbreak" -> out += SnakesBeat.Heartbreak(move.player, e.from, e.to)
                "swap" -> if (partner != null) out += SnakesBeat.Swap(move.player, partner, e.from, e.to)
                "kiss" -> out += SnakesBeat.Kiss(move.player, e.from)
                "threeSixes" -> out += SnakesBeat.ThreeSixes(move.player, e.from, e.to)
                "win" -> out += SnakesBeat.Win(move.player)
                // "bounce" is already in the hops; anything newer just isn't animated (the board ends where the state says).
            }
        }
        return out
    }

    /** Where both tokens stand before a move plays: the roller where it began, the partner where they were (before any swap). */
    fun before(move: SnakesMove, partner: String?, partnerNow: Int): Map<String, Int> {
        val swap = move.events.firstOrNull { it.kind == "swap" }
        return buildMap {
            put(move.player, move.from)
            if (partner != null) put(partner, swap?.to ?: partnerNow)
        }
    }

    /** Where a beat leaves the tokens. */
    fun after(beat: SnakesBeat, positions: Map<String, Int>): Map<String, Int> = positions.toMutableMap().apply {
        when (beat) {
            is SnakesBeat.Hop -> put(beat.player, beat.to)
            is SnakesBeat.Climb -> put(beat.player, beat.to)
            is SnakesBeat.Slide -> put(beat.player, beat.to)
            is SnakesBeat.Boost -> put(beat.player, beat.to)
            is SnakesBeat.Heartbreak -> put(beat.player, beat.to)
            is SnakesBeat.ThreeSixes -> put(beat.player, beat.to)
            is SnakesBeat.Swap -> { put(beat.player, beat.to); put(beat.partner, beat.from) }
            is SnakesBeat.Shielded, is SnakesBeat.Hug, is SnakesBeat.Kiss, is SnakesBeat.Win -> Unit
        }
    }

    /** Where a whole list of beats leaves the tokens. */
    fun after(beats: List<SnakesBeat>, start: Map<String, Int>): Map<String, Int> = beats.fold(start) { at, beat -> after(beat, at) }

    fun durationMs(beats: List<SnakesBeat>): Int = beats.sumOf { it.ms }

    /**
     * Where [player]'s token is, [p] (0 to 1) of the way through [beat], and how high it's lifted (units) for the hop.
     * [snakeIndex] finds the snake a slide rides; [sway] is the snakes' wiggle.
     */
    fun tokenAt(beat: SnakesBeat, p: Float, player: String, rest: Map<String, Int>, snakes: List<SnakesLink>, sway: Float = 0f): TokenPose {
        val home = SnakesGeometry.center(rest[player] ?: 0)
        val q = p.coerceIn(0f, 1f)
        fun arc(a: Int, b: Int, height: Float, t: Float = q) =
            TokenPose(SnakesGeometry.lerp(SnakesGeometry.center(a), SnakesGeometry.center(b), ease(t)), height * sin(PI.toFloat() * t))
        if (beat is SnakesBeat.Swap && player == beat.partner) return arc(beat.to, beat.from, -0.9f)
        if (player != beat.player) return TokenPose(home, 0f)
        return when (beat) {
            is SnakesBeat.Hop -> arc(beat.from, beat.to, if (beat.bouncing) 0.35f else 0.48f, q).copy(squash = landing(q))
            is SnakesBeat.Climb -> {
                val link = SnakesLink(beat.from, beat.to)
                val n = SnakesGeometry.rungs(link)
                // Rung by rung: a little pull up each one, a rest on it.
                val k = (q * n).toInt().coerceAtMost(n - 1)
                val f = q * n - k
                TokenPose(SnakesGeometry.onLadder(link, (k + ease(f)) / n), 0.06f * sin(PI.toFloat() * f), cheer = true)
            }
            is SnakesBeat.Slide -> {
                val index = snakes.indexOfFirst { it.from == beat.from }
                val link = snakes.getOrNull(index) ?: SnakesLink(beat.from, beat.to)
                // A gulp at the head, a ride down the curve that speeds up and eases out at the tail.
                val t = ((q - 0.18f) / 0.82f).coerceIn(0f, 1f)
                TokenPose(SnakesGeometry.onSnake(link, index.coerceAtLeast(0), ease(t), sway), 0f, worried = true, tilt = 16f * sin(PI.toFloat() * t))
            }
            is SnakesBeat.Boost -> TokenPose(SnakesGeometry.lerp(SnakesGeometry.center(beat.from), SnakesGeometry.center(beat.to), easeOut(q)), 0.25f * sin(PI.toFloat() * q), cheer = true, trail = q in 0.05f..0.9f)
            is SnakesBeat.Heartbreak -> {
                // A shake on the square, then a sad drift back.
                if (q < 0.35f) TokenPose(home.copy(x = home.x + 0.06f * sin(q * 60f)), 0f, worried = true)
                else TokenPose(SnakesGeometry.lerp(SnakesGeometry.center(beat.from), SnakesGeometry.center(beat.to), ease((q - 0.35f) / 0.65f)), 0.2f * sin(PI.toFloat() * (q - 0.35f) / 0.65f), worried = true)
            }
            is SnakesBeat.Swap -> arc(beat.from, beat.to, 0.9f).copy(cheer = true)
            is SnakesBeat.ThreeSixes -> TokenPose(SnakesGeometry.lerp(SnakesGeometry.center(beat.from), SnakesGeometry.center(beat.to), ease(q)), 1.1f * sin(PI.toFloat() * q), worried = true, spin = 360f * q)
            is SnakesBeat.Win -> TokenPose(home, 0.5f * kotlin.math.abs(sin(PI.toFloat() * q * 3f)), cheer = true)
            is SnakesBeat.Shielded, is SnakesBeat.Hug, is SnakesBeat.Kiss -> TokenPose(home, 0.18f * sin(PI.toFloat() * q), cheer = beat !is SnakesBeat.Shielded || q > 0.4f)
        }
    }

    fun ease(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /** A squash as a hop lands: 0 in the air, up to 1 on touching down. */
    private fun landing(t: Float): Float = if (t > 0.82f) sin((t - 0.82f) / 0.18f * PI.toFloat()) else 0f
}

/** A token mid-beat: where (units), lifted how high, and how it looks. */
internal data class TokenPose(
    val at: Offset,
    val lift: Float,
    val squash: Float = 0f,
    val cheer: Boolean = false,
    val worried: Boolean = false,
    val tilt: Float = 0f,
    val spin: Float = 0f,
    val trail: Boolean = false,
)

/**
 * The die in 3D: a unit cube turned by three angles (x, then y, then z), then tilted a little toward the viewer so a
 * resting die shows two more faces. Faces: 1 front, 6 back, 2 top, 5 bottom, 3 right, 4 left (opposites make 7).
 */
internal object DieMath {
    /** The angles (x, y, z) at which [face] looks straight at you. */
    fun rest(face: Int): Triple<Float, Float, Float> = when (face) {
        6 -> Triple(0f, PI.toFloat(), 0f)
        3 -> Triple(0f, -PI.toFloat() / 2f, 0f)
        4 -> Triple(0f, PI.toFloat() / 2f, 0f)
        2 -> Triple(PI.toFloat() / 2f, 0f, 0f)
        5 -> Triple(-PI.toFloat() / 2f, 0f, 0f)
        else -> Triple(0f, 0f, 0f)
    }

    /** Each face's value and its outward normal before turning. */
    val faces = listOf(1 to V(0f, 0f, 1f), 6 to V(0f, 0f, -1f), 2 to V(0f, 1f, 0f), 5 to V(0f, -1f, 0f), 3 to V(1f, 0f, 0f), 4 to V(-1f, 0f, 0f))

    /** The face that looks at you at these angles (before the resting tilt). */
    fun facing(x: Float, y: Float, z: Float): Int = faces.maxBy { (_, n) -> turn(n, x, y, z).z }.first

    /** Turns [v] by x, then y, then z. */
    fun turn(v: V, x: Float, y: Float, z: Float): V {
        var p = V(v.x, v.y * cos(x) - v.z * sin(x), v.y * sin(x) + v.z * cos(x))
        p = V(p.x * cos(y) + p.z * sin(y), p.y, -p.x * sin(y) + p.z * cos(y))
        return V(p.x * cos(z) - p.y * sin(z), p.x * sin(z) + p.y * cos(z), p.z)
    }

    /** Where to stop spinning so the die shows [face], at least [turns] whole turns on from [from] on each axis it spins. */
    fun landing(from: Triple<Float, Float, Float>, face: Int, turns: Int = 1): Triple<Float, Float, Float> {
        val r = rest(face)
        val tau = 2f * PI.toFloat()
        fun ahead(start: Float, target: Float, extra: Int): Float {
            var t = target + tau * kotlin.math.floor((start - target) / tau)
            while (t < start) t += tau
            return t + tau * extra
        }
        return Triple(ahead(from.first, r.first, turns), ahead(from.second, r.second, turns), ahead(from.third, r.third, 0))
    }

    data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun length() = sqrt(x * x + y * y + z * z)
    }
}
