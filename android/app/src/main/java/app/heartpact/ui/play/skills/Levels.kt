package app.heartpact.ui.play.skills

import kotlin.math.roundToInt

/*
 * Levels. Practice climbs them one round at a time: clear a level's pass mark and the next one is harder (new shapes
 * come in, things move faster or hide sooner, and the scoring gets stricter). Level 1 is each game exactly as it was
 * before levels existed, and it is what Heart to heart duels always play, so a duel stays fair between two phones
 * whatever levels each has reached.
 */

/** How hard a round is: [level] 1 and up. The ramp runs to [RAMP]; past it the levels go on at the hardest settings. */
class Difficulty(val level: Int) {
    init { require(level >= 1) { "Levels start at 1" } }

    /** How far up the ramp: 0 at level 1, 1 from level [RAMP] on. */
    val d: Float = ((level - 1) / (RAMP - 1f)).coerceIn(0f, 1f)

    /** [easy] at level 1, [hard] at the top of the ramp, in between on the way. */
    fun lerp(easy: Float, hard: Float): Float = easy + (hard - easy) * d

    /** An Int that grows from [easy] at level 1 to [hard] at the top of the ramp. */
    fun step(easy: Int, hard: Int): Int = (easy + (hard - easy) * d).roundToInt()

    /** True from [level] on: for things that switch on part-way up (a shape that starts to turn, a target that hides). */
    fun from(level: Int): Boolean = this.level >= level

    /**
     * A scoring tolerance (a falloff k) tightened with the level: [k] at level 1, [hardest] times [k] at the top of the
     * ramp. The same miss scores a little lower each level.
     */
    fun k(k: Float, hardest: Float = 0.7f): Float = k * lerp(1f, hardest)

    /** The score that clears this level in practice: 50 at level 1, rising to 80. */
    val passMark: Int = (50 + 30 * d).roundToInt()

    /** Stars for a round at this level: none below the pass mark, then 1, 2 at 10 over it, 3 at 20 over it (or 95). */
    fun stars(score: Int): Int = when {
        score < passMark -> 0
        score >= minOf(passMark + 20, 95) -> 3
        score >= minOf(passMark + 10, 90) -> 2
        else -> 1
    }

    fun next(): Difficulty = Difficulty(level + 1)

    override fun equals(other: Any?): Boolean = other is Difficulty && other.level == level
    override fun hashCode(): Int = level
    override fun toString(): String = "Difficulty($level)"

    companion object {
        /** The level the ramp tops out at. */
        const val RAMP = 20

        /** Level 1: the game as it always was. Duels play this. */
        val Start = Difficulty(1)
    }
}
