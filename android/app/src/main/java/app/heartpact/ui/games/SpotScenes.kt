package app.heartpact.ui.games

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Spot the difference: the pictures. Both phones build the same two pictures from the scene's kind and seed (the server
 * draws them when the game is invited), so the pictures and their differences are the same for both of you. Everything
 * here is pure arithmetic on a 300 by 200 page: Kotlin's seeded Random, + - * / and square roots only (correctly
 * rounded everywhere), never sin, cos or hashing, so no two phones can disagree by a rounding error. SpotArt draws it.
 */

/** The kinds of picture, by the server's names (SpotTheDifferenceRules.Kinds). */
internal enum class SceneKind {
    Picnic, Bedroom, Kitchen, Beach, Garden, NightSky, Cafe, LivingRoom;

    companion object {
        fun of(name: String?): SceneKind? = entries.firstOrNull { it.name == name }
    }
}

/** The sticker paints the props come in: bright enough to read at phone size, softer than the pen pink. */
internal enum class Paint { Rose, Coral, Butter, Mint, Teal, Sky, Peri, Indigo, Lilac, Cream, White, Toast }

internal enum class Pattern { Plain, Stripes, Dots }

/** How a prop can be turned so you notice: flipped left to right (it isn't symmetric), rotated, or neither. */
internal enum class Twist { Flip, Rotate, None }

/**
 * Everything a picture can hold. [w] and [h] are its box, in sizes (a prop of size 20 with w 1.2 is 24 wide); [paints]
 * are the colours it comes in to begin with.
 */
internal enum class PropKind(val w: Float, val h: Float, val turn: Twist, val patterned: Boolean, val paints: List<Paint>) {
    Heart(1f, 0.9f, Twist.Rotate, true, listOf(Paint.Rose, Paint.Coral, Paint.Lilac, Paint.Peri)),
    Star(1f, 1f, Twist.None, false, listOf(Paint.Butter, Paint.Coral, Paint.Rose)),
    Flower(0.8f, 1.3f, Twist.Flip, false, listOf(Paint.Rose, Paint.Butter, Paint.Lilac, Paint.Coral, Paint.Sky)),
    Balloon(0.8f, 1.5f, Twist.Flip, true, listOf(Paint.Rose, Paint.Sky, Paint.Butter, Paint.Mint, Paint.Lilac, Paint.Coral)),
    Cloud(1.6f, 0.9f, Twist.None, false, listOf(Paint.White)),
    Bird(1.2f, 0.9f, Twist.Flip, false, listOf(Paint.Sky, Paint.Rose, Paint.Butter, Paint.Teal, Paint.Coral)),
    Butterfly(1.2f, 1f, Twist.Rotate, true, listOf(Paint.Lilac, Paint.Rose, Paint.Butter, Paint.Sky)),
    Apple(0.95f, 1.05f, Twist.None, false, listOf(Paint.Rose, Paint.Mint, Paint.Butter)),
    Orange(1f, 1f, Twist.None, false, listOf(Paint.Coral, Paint.Butter)),
    Cherries(1f, 1.1f, Twist.Flip, false, listOf(Paint.Rose)),
    Strawberry(0.85f, 1.05f, Twist.Rotate, false, listOf(Paint.Rose)),
    Cupcake(1f, 1.1f, Twist.None, true, listOf(Paint.Rose, Paint.Lilac, Paint.Sky, Paint.Butter)),
    Donut(1.1f, 0.8f, Twist.None, true, listOf(Paint.Rose, Paint.Lilac, Paint.Butter, Paint.Sky)),
    Croissant(1.3f, 0.75f, Twist.Rotate, false, listOf(Paint.Toast)),
    Mug(1.05f, 0.95f, Twist.Flip, true, listOf(Paint.Sky, Paint.Rose, Paint.Butter, Paint.Mint, Paint.Teal, Paint.Cream, Paint.Indigo)),
    Teapot(1.4f, 1.05f, Twist.Flip, true, listOf(Paint.Teal, Paint.Rose, Paint.Sky, Paint.Cream, Paint.Lilac)),
    Jar(0.8f, 1f, Twist.None, true, listOf(Paint.Rose, Paint.Coral, Paint.Butter, Paint.Mint)),
    Bottle(0.5f, 1.4f, Twist.None, false, listOf(Paint.Mint, Paint.Teal, Paint.Sky, Paint.Rose)),
    Vase(0.8f, 1.4f, Twist.None, true, listOf(Paint.Sky, Paint.Rose, Paint.Teal, Paint.Butter, Paint.Lilac)),
    Plant(1f, 1.3f, Twist.Flip, true, listOf(Paint.Coral, Paint.Cream, Paint.Rose, Paint.Sky)),
    Candle(0.45f, 1.2f, Twist.None, true, listOf(Paint.Cream, Paint.Rose, Paint.Lilac, Paint.Sky)),
    Book(0.42f, 1.2f, Twist.Rotate, true, listOf(Paint.Rose, Paint.Sky, Paint.Mint, Paint.Butter, Paint.Indigo, Paint.Lilac, Paint.Teal, Paint.Coral)),
    BookStack(1.3f, 0.75f, Twist.Flip, false, listOf(Paint.Rose, Paint.Sky, Paint.Mint, Paint.Butter, Paint.Lilac)),
    Frame(1f, 0.85f, Twist.Rotate, false, listOf(Paint.Butter, Paint.Rose, Paint.Sky, Paint.Mint, Paint.Toast)),
    Clock(1f, 1f, Twist.None, false, listOf(Paint.Rose, Paint.Sky, Paint.Mint, Paint.Butter, Paint.Coral)),
    Lamp(0.9f, 1.35f, Twist.None, true, listOf(Paint.Butter, Paint.Rose, Paint.Mint, Paint.Sky, Paint.Cream)),
    Cushion(1.1f, 0.9f, Twist.Rotate, true, listOf(Paint.Rose, Paint.Butter, Paint.Sky, Paint.Mint, Paint.Lilac, Paint.Coral, Paint.Teal)),
    Cat(1f, 1.15f, Twist.Flip, false, listOf(Paint.Coral, Paint.Cream, Paint.Indigo, Paint.Peri)),
    Sun(1.2f, 1.2f, Twist.None, false, listOf(Paint.Butter, Paint.Coral)),
    Moon(0.9f, 1f, Twist.Flip, false, listOf(Paint.Butter, Paint.Cream)),
    Mushroom(1f, 1f, Twist.None, true, listOf(Paint.Rose, Paint.Coral, Paint.Lilac)),
    Shell(1f, 0.9f, Twist.Rotate, false, listOf(Paint.Rose, Paint.Cream, Paint.Coral, Paint.Lilac)),
    Starfish(1f, 1f, Twist.None, false, listOf(Paint.Coral, Paint.Rose, Paint.Butter)),
    BeachBall(1f, 1f, Twist.Rotate, false, listOf(Paint.Rose, Paint.Sky, Paint.Butter, Paint.Teal)),
    Bucket(0.9f, 1f, Twist.Flip, true, listOf(Paint.Sky, Paint.Rose, Paint.Butter, Paint.Mint, Paint.Teal)),
    Umbrella(1.6f, 1.7f, Twist.Flip, true, listOf(Paint.Rose, Paint.Sky, Paint.Teal, Paint.Coral)),
    Window(0.8f, 1f, Twist.None, false, listOf(Paint.Butter)),
    Bush(1.4f, 0.9f, Twist.None, false, listOf(Paint.Mint, Paint.Teal)),
    Pendant(0.9f, 0.8f, Twist.None, true, listOf(Paint.Butter, Paint.Rose, Paint.Teal, Paint.Mint)),
}

/** The kinds of difference. */
internal enum class DiffKind { Missing, Extra, Colour, Size, Moved, Turned, Pattern }

/**
 * One thing in a picture, centred on [x], [y] (page units, 300 by 200). [size] scales its box; [variant] picks small
 * details (what a frame shows, how a flower's petals sit) that never make a difference on their own.
 */
internal data class Prop(
    val id: Int,
    val kind: PropKind,
    val x: Float,
    val y: Float,
    val size: Float,
    val paint: Paint,
    val pattern: Pattern = Pattern.Plain,
    val flip: Boolean = false,
    val tilt: Float = 0f,
    val variant: Int = 0,
) {
    /** Half its box, wide and high; a tilted prop takes the larger either way. */
    val halfW: Float get() = if (tilt != 0f) reach else size * kind.w / 2f
    val halfH: Float get() = if (tilt != 0f) reach else size * kind.h / 2f
    val reach: Float get() = size * max(kind.w, kind.h) / 2f
    /** Where it stands, for props that sit on something. */
    val bottom: Float get() = y + size * kind.h / 2f
}

/** A difference: what it is, and the circle (page units) a tap must land in. [index] is what the server counts. */
internal data class Spot(val index: Int, val kind: DiffKind, val x: Float, val y: Float, val r: Float)

/** A house on the night rooftops: its left edge, width, the top of its wall, and its roof (0 flat, 1 pointed). */
internal data class House(val x: Float, val w: Float, val top: Float, val roof: Int, val tone: Int)

/** The fixed part of a picture (the room, the sky), built from the seed. [tone] picks its colourway. */
internal data class SceneLayout(val kind: SceneKind, val tone: Int, val houses: List<House> = emptyList())

/** A picture and its changed twin: the props in each, and where they differ. */
internal data class SpotScene(
    val kind: SceneKind,
    val seed: Int,
    val layout: SceneLayout,
    val original: List<Prop>,
    val changed: List<Prop>,
    val spots: List<Spot>,
) {
    /**
     * The difference a tap at [x], [y] (page units) lands on, or null: within its circle plus [slop], the nearest
     * when two are close.
     */
    fun hit(x: Float, y: Float, slop: Float = 5f): Int? = spots
        .filter { d2(it.x, it.y, x, y) <= (it.r + slop) * (it.r + slop) }
        .minByOrNull { sqrt(d2(it.x, it.y, x, y)) - it.r }?.index
}

private fun d2(ax: Float, ay: Float, bx: Float, by: Float): Float = (ax - bx) * (ax - bx) + (ay - by) * (ay - by)

/** Where props go: a box, the kinds that fit there, how many and how big. A [sit] zone's props stand on a line between [top] and [bottom]. */
internal class Zone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val kinds: List<PropKind>,
    val count: IntRange,
    val sizes: ClosedFloatingPointRange<Float>,
    val sit: Boolean = false,
)

internal object SpotScenes {
    const val WIDTH = 300f
    const val HEIGHT = 200f
    const val DIFFERENCES = 7

    /** The smallest a difference's circle gets, so it's a fair finger's width on a phone. */
    const val MIN_RADIUS = 15f
    private const val EDGE = 3f
    private const val GAP = 3f
    /** Every zone's sizes, a touch larger than written: big, friendly stickers read better on a phone. */
    private const val GROW = 1.15f

    /** A picture has at most one of each of these. */
    private val onlyOne = setOf(PropKind.Sun, PropKind.Moon)

    private val cache = object : LinkedHashMap<Triple<SceneKind, Int, Int>, SpotScene>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<SceneKind, Int, Int>, SpotScene>?) = size > 8
    }

    /** The picture for [kind] and [seed], with [differences] differences (always that many). The same on every phone. */
    fun build(kind: SceneKind, seed: Int, differences: Int = DIFFERENCES): SpotScene = synchronized(cache) {
        cache.getOrPut(Triple(kind, seed, differences)) { fresh(kind, seed, differences) }
    }

    /** [build] without the cache. */
    internal fun fresh(kind: SceneKind, seed: Int, differences: Int = DIFFERENCES): SpotScene {
        var best: SpotScene? = null
        // A crowded draw can leave too little room for every difference: try again from the next seed in the line.
        for (attempt in 0 until 24) {
            val random = Random(seed xor (attempt * 0x5bd1e995))
            val scene = attempt(kind, seed, random, differences)
            if (scene.spots.size == differences) return scene
            if (best == null || scene.spots.size > best.spots.size) best = scene
        }
        return best!!
    }

    /** The fixed part of a picture. */
    fun layout(kind: SceneKind, random: Random): SceneLayout = when (kind) {
        SceneKind.NightSky -> {
            val houses = mutableListOf<House>()
            var x = -6f
            while (x < WIDTH) {
                val w = 52f + random.nextInt(0, 22)
                val top = 118f + random.nextInt(0, 30)
                houses += House(x, w, top, random.nextInt(0, 2), random.nextInt(0, 3))
                x += w + random.nextInt(0, 6)
            }
            SceneLayout(kind, random.nextInt(0, 3), houses)
        }
        else -> SceneLayout(kind, random.nextInt(0, 3))
    }

    /** Where props go in each kind of picture. */
    fun zones(layout: SceneLayout): List<Zone> = when (layout.kind) {
        SceneKind.Picnic -> listOf(
            Zone(100f, 8f, 292f, 70f, listOf(PropKind.Cloud, PropKind.Balloon, PropKind.Bird, PropKind.Sun, PropKind.Butterfly, PropKind.Heart), 4..6, 16f..24f),
            Zone(6f, 28f, 62f, 78f, listOf(PropKind.Apple, PropKind.Bird, PropKind.Heart), 2..3, 13f..16f),
            Zone(78f, 148f, 222f, 186f, listOf(PropKind.Apple, PropKind.Orange, PropKind.Cherries, PropKind.Strawberry, PropKind.Cupcake, PropKind.Donut, PropKind.Mug,
                PropKind.Teapot, PropKind.Jar, PropKind.Bottle, PropKind.Croissant, PropKind.Vase), 7..9, 16f..23f, sit = true),
            Zone(4f, 150f, 70f, 196f, listOf(PropKind.Flower, PropKind.Mushroom, PropKind.Bush, PropKind.Butterfly, PropKind.Cat), 2..3, 15f..22f, sit = true),
            Zone(230f, 124f, 296f, 196f, listOf(PropKind.Flower, PropKind.Mushroom, PropKind.Bush, PropKind.Bucket), 2..4, 15f..22f, sit = true),
        )
        SceneKind.Bedroom -> listOf(
            Zone(22f, 58f, 118f, 58f, listOf(PropKind.Book, PropKind.Plant, PropKind.Candle, PropKind.Jar, PropKind.Vase, PropKind.Mug, PropKind.Clock), 3..5, 16f..22f, sit = true),
            Zone(128f, 12f, 206f, 96f, listOf(PropKind.Frame, PropKind.Clock, PropKind.Heart, PropKind.Star), 2..3, 18f..26f),
            Zone(10f, 74f, 118f, 96f, listOf(PropKind.Star, PropKind.Heart), 1..2, 13f..16f),
            Zone(22f, 124f, 142f, 126f, listOf(PropKind.Cushion, PropKind.Cat, PropKind.BookStack, PropKind.Heart), 2..3, 18f..24f, sit = true),
            Zone(150f, 168f, 296f, 196f, listOf(PropKind.Plant, PropKind.BookStack, PropKind.Cat, PropKind.Lamp, PropKind.Mug, PropKind.Cushion, PropKind.Bucket), 3..4, 18f..26f, sit = true),
            Zone(222f, 101f, 282f, 101f, listOf(PropKind.Plant, PropKind.Candle, PropKind.Vase, PropKind.Cat), 1..2, 16f..20f, sit = true),
            Zone(222f, 24f, 282f, 76f, listOf(PropKind.Cloud, PropKind.Bird, PropKind.Star, PropKind.Moon), 1..2, 12f..16f),
        )
        SceneKind.Kitchen -> listOf(
            Zone(152f, 52f, 292f, 52f, listOf(PropKind.Jar, PropKind.Mug, PropKind.Plant, PropKind.Bottle, PropKind.Teapot, PropKind.Vase), 3..5, 16f..22f, sit = true),
            Zone(8f, 126f, 292f, 126f, listOf(PropKind.Teapot, PropKind.Mug, PropKind.Apple, PropKind.Orange, PropKind.Cupcake, PropKind.Bottle, PropKind.Jar, PropKind.Plant,
                PropKind.Croissant, PropKind.Cherries, PropKind.Strawberry, PropKind.Donut), 6..8, 16f..24f, sit = true),
            Zone(124f, 62f, 292f, 106f, listOf(PropKind.Clock, PropKind.Frame, PropKind.Heart), 1..2, 18f..24f),
            Zone(160f, 6f, 292f, 34f, listOf(PropKind.Heart, PropKind.Star, PropKind.Clock), 1..2, 14f..18f),
            Zone(26f, 20f, 104f, 72f, listOf(PropKind.Cloud, PropKind.Bird, PropKind.Sun), 1..2, 13f..17f),
            Zone(26f, 86f, 104f, 86f, listOf(PropKind.Plant, PropKind.Vase, PropKind.Candle), 1..2, 15f..19f, sit = true),
        )
        SceneKind.Beach -> listOf(
            Zone(8f, 8f, 292f, 78f, listOf(PropKind.Sun, PropKind.Cloud, PropKind.Bird, PropKind.Balloon), 4..6, 15f..24f),
            Zone(8f, 140f, 292f, 196f, listOf(PropKind.Umbrella, PropKind.BeachBall, PropKind.Shell, PropKind.Starfish, PropKind.Bucket, PropKind.Shell, PropKind.Starfish,
                PropKind.Mug, PropKind.Strawberry, PropKind.Heart), 8..11, 15f..24f, sit = true),
        )
        SceneKind.Garden -> listOf(
            Zone(8f, 6f, 292f, 62f, listOf(PropKind.Sun, PropKind.Cloud, PropKind.Bird, PropKind.Butterfly, PropKind.Balloon), 3..5, 15f..22f),
            Zone(16f, 86f, 284f, 86f, listOf(PropKind.Bird), 1..2, 14f..17f, sit = true),
            Zone(6f, 136f, 294f, 196f, listOf(PropKind.Flower, PropKind.Flower, PropKind.Mushroom, PropKind.Plant, PropKind.Bush, PropKind.Butterfly, PropKind.Bucket, PropKind.Cat,
                PropKind.Heart), 9..12, 15f..23f, sit = true),
        )
        SceneKind.NightSky -> buildList {
            add(Zone(6f, 6f, 294f, 92f, listOf(PropKind.Star, PropKind.Star, PropKind.Star, PropKind.Moon, PropKind.Cloud, PropKind.Heart, PropKind.Balloon), 9..12, 12f..20f))
            layout.houses.forEach { h ->
                val roofTop = if (h.roof == 0) h.top else h.top - h.w * 0.32f
                if (h.roof == 0) add(Zone(h.x + 6f, h.top, h.x + h.w - 6f, h.top, listOf(PropKind.Cat, PropKind.Plant, PropKind.Bird), 0..1, 14f..17f, sit = true))
                else add(Zone(h.x + h.w / 2f - 12f, roofTop - 24f, h.x + h.w / 2f + 12f, roofTop - 2f, listOf(PropKind.Bird, PropKind.Star), 0..1, 12f..14f))
                add(Zone(h.x + 8f, h.top + 8f, h.x + h.w - 8f, 194f, listOf(PropKind.Window), 1..3, 14f..18f))
            }
        }
        SceneKind.Cafe -> listOf(
            Zone(42f, 152f, 258f, 186f, listOf(PropKind.Mug, PropKind.Teapot, PropKind.Cupcake, PropKind.Croissant, PropKind.Donut, PropKind.Vase, PropKind.Candle, PropKind.Strawberry,
                PropKind.Cherries, PropKind.Jar, PropKind.Bottle), 6..8, 16f..23f, sit = true),
            Zone(10f, 46f, 140f, 112f, listOf(PropKind.Frame, PropKind.Clock, PropKind.Heart, PropKind.Frame), 2..3, 17f..24f),
            Zone(160f, 22f, 282f, 92f, listOf(PropKind.Cloud, PropKind.Bird, PropKind.Sun, PropKind.Balloon), 2..3, 13f..19f),
            Zone(160f, 108f, 282f, 108f, listOf(PropKind.Plant, PropKind.Vase, PropKind.Cat, PropKind.Candle), 1..2, 15f..20f, sit = true),
            Zone(14f, 8f, 140f, 44f, listOf(PropKind.Pendant), 1..2, 17f..21f),
        )
        SceneKind.LivingRoom -> listOf(
            Zone(22f, 10f, 184f, 74f, listOf(PropKind.Frame, PropKind.Clock, PropKind.Heart, PropKind.Star, PropKind.Frame), 2..3, 18f..26f),
            Zone(40f, 124f, 172f, 124f, listOf(PropKind.Cushion, PropKind.Cushion, PropKind.Cat, PropKind.BookStack), 3..4, 18f..24f, sit = true),
            Zone(212f, 124f, 262f, 124f, listOf(PropKind.Lamp, PropKind.Plant, PropKind.Mug, PropKind.Vase, PropKind.Candle), 1..2, 16f..22f, sit = true),
            Zone(206f, 56f, 292f, 56f, listOf(PropKind.Book, PropKind.Plant, PropKind.Jar, PropKind.Vase, PropKind.Candle, PropKind.Clock, PropKind.Book), 3..4, 15f..21f, sit = true),
            Zone(8f, 172f, 292f, 196f, listOf(PropKind.Cat, PropKind.Plant, PropKind.BookStack, PropKind.Mug, PropKind.Cushion, PropKind.Bucket), 3..4, 18f..24f, sit = true),
        )
    }

    private fun attempt(kind: SceneKind, seed: Int, random: Random, differences: Int): SpotScene {
        val layout = layout(kind, Random(seed))
        val zones = zones(layout)
        val props = mutableListOf<Prop>()
        zones.forEach { zone ->
            val wanted = zone.count.first + random.nextInt(0, zone.count.last - zone.count.first + 1)
            var placed = 0
            var tries = 0
            while (placed < wanted && tries < 60) {
                tries++
                val prop = draw(zone, random, props.size) ?: continue
                if (fits(prop, props) && (prop.kind !in onlyOne || props.none { it.kind == prop.kind })) { props += prop; placed++ }
            }
        }
        return differ(kind, seed, layout, zones, props, random, differences)
    }

    /** A new prop somewhere in [zone], or null when the zone is too small for the one drawn. */
    private fun draw(zone: Zone, random: Random, id: Int): Prop? {
        val kind = zone.kinds[random.nextInt(zone.kinds.size)]
        val size = (zone.sizes.start + random.nextFloat() * (zone.sizes.endInclusive - zone.sizes.start)) * GROW
        val paint = kind.paints[random.nextInt(kind.paints.size)]
        val pattern = if (kind.patterned && random.nextInt(10) < 3) (if (random.nextBoolean()) Pattern.Stripes else Pattern.Dots)
            else if (kind == PropKind.Mushroom) Pattern.Dots else Pattern.Plain
        val flip = kind.turn == Twist.Flip && random.nextBoolean()
        val tilt = if (kind.turn == Twist.Rotate && kind != PropKind.Book && random.nextInt(3) == 0) (random.nextInt(-12, 13)).toFloat() else 0f
        val variant = random.nextInt(0, 4)
        val halfW = size * kind.w / 2f
        val halfH = size * kind.h / 2f
        val x0 = zone.left + halfW
        val x1 = zone.right - halfW
        if (x1 < x0) return null
        val x = x0 + random.nextFloat() * (x1 - x0)
        val y = if (zone.sit) {
            val base = zone.top + random.nextFloat() * (zone.bottom - zone.top)
            base - halfH
        } else {
            val y0 = zone.top + halfH
            val y1 = zone.bottom - halfH
            if (y1 < y0) return null
            y0 + random.nextFloat() * (y1 - y0)
        }
        return Prop(id, kind, x, y, size, paint, pattern, flip, tilt, variant)
    }

    /** Inside the page and clear of everything in [others] (but [except]). */
    private fun fits(p: Prop, others: List<Prop>, except: Int = -1): Boolean {
        if (p.x - p.halfW < EDGE || p.x + p.halfW > WIDTH - EDGE || p.y - p.halfH < EDGE || p.y + p.halfH > HEIGHT - EDGE) return false
        return others.none { o -> o.id != except && o.id != p.id && overlaps(p, o) }
    }

    private fun overlaps(a: Prop, b: Prop): Boolean =
        (a.x - b.x).let { it * it } < (a.halfW + b.halfW + GAP).let { it * it } && (a.y - b.y).let { it * it } < (a.halfH + b.halfH + GAP).let { it * it }

    /** The circle round a prop where it was ([a]) and where it is ([b]): both fit inside. */
    private fun ring(a: Prop, b: Prop): Triple<Float, Float, Float> {
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        val half = sqrt(d2(a.x, a.y, b.x, b.y)) / 2f
        return Triple(cx, cy, max(MIN_RADIUS, half + max(a.reach, b.reach) + 2f))
    }

    /** Eight ways to move a prop, as unit steps (no trigonometry: the same on every phone). */
    private val directions = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f, 0.70711f to 0.70711f, -0.70711f to 0.70711f, 0.70711f to -0.70711f, -0.70711f to -0.70711f)

    /** Paints each paint may change to: far apart in hue and in lightness, so the change shows even without colour vision. */
    val contrast: Map<Paint, List<Paint>> = mapOf(
        Paint.Rose to listOf(Paint.Butter, Paint.Sky, Paint.Indigo, Paint.Teal),
        Paint.Coral to listOf(Paint.Indigo, Paint.Sky, Paint.Lilac, Paint.Teal),
        Paint.Butter to listOf(Paint.Indigo, Paint.Rose, Paint.Peri, Paint.Lilac),
        Paint.Mint to listOf(Paint.Rose, Paint.Indigo, Paint.Lilac),
        Paint.Teal to listOf(Paint.Rose, Paint.Butter, Paint.Coral),
        Paint.Sky to listOf(Paint.Butter, Paint.Coral, Paint.Rose, Paint.Indigo),
        Paint.Peri to listOf(Paint.Butter, Paint.Coral, Paint.Mint),
        Paint.Indigo to listOf(Paint.Butter, Paint.Rose, Paint.Coral, Paint.Mint, Paint.Sky),
        Paint.Lilac to listOf(Paint.Butter, Paint.Mint, Paint.Coral, Paint.Indigo),
        Paint.Cream to listOf(Paint.Indigo, Paint.Rose, Paint.Sky, Paint.Teal),
        Paint.White to listOf(Paint.Rose, Paint.Butter, Paint.Lilac),
        Paint.Toast to listOf(Paint.Indigo, Paint.Mint, Paint.Rose, Paint.Sky),
    )

    /**
     * Picks [count] differences, every kind once before any comes twice, each on its own prop, each circle clear of the
     * others; the changed picture is the original with them made.
     */
    private fun differ(kind: SceneKind, seed: Int, layout: SceneLayout, zones: List<Zone>, props: List<Prop>, random: Random, count: Int): SpotScene {
        val changed = props.toMutableList()
        val spots = mutableListOf<Spot>()
        val used = mutableSetOf<Int>()
        val order = props.shuffled(random)
        var nextId = props.size

        fun clearOfSpots(x: Float, y: Float, r: Float) = spots.none { s -> d2(s.x, s.y, x, y) < (s.r + r + 2f) * (s.r + r + 2f) }
        fun everything() = changed + props.filter { p -> changed.none { it.id == p.id } }

        fun tryKind(diff: DiffKind): Boolean {
            if (diff == DiffKind.Extra) {
                repeat(30) {
                    val zone = zones[random.nextInt(zones.size)]
                    val prop = draw(zone, random, nextId) ?: return@repeat
                    if (prop.size < 14f) return@repeat
                    val r = max(MIN_RADIUS, prop.reach + 2f)
                    if (fits(prop, everything()) && (prop.kind !in onlyOne || everything().none { it.kind == prop.kind }) && clearOfSpots(prop.x, prop.y, r)) {
                        nextId++
                        changed += prop
                        spots += Spot(spots.size, diff, prop.x, prop.y, r)
                        return true
                    }
                }
                return false
            }
            for (p in order) {
                if (p.id in used) continue
                val next: Prop = when (diff) {
                    DiffKind.Missing -> p.takeIf { it.size >= 14f }
                    DiffKind.Colour -> contrast[p.paint]?.let { choices -> p.copy(paint = choices[random.nextInt(choices.size)]) }?.takeIf { p.size >= 14f }
                    DiffKind.Size -> if (p.kind == PropKind.Window) null else {
                        val standing = isStanding(p, zones)
                        val grown = resize(p, 1.45f, standing)
                        val shrunk = resize(p, 0.62f, standing)
                        when {
                            fits(grown, changed, p.id) -> grown
                            p.size >= 18f && fits(shrunk, changed, p.id) -> shrunk
                            else -> null
                        }
                    }
                    DiffKind.Moved -> if (p.kind == PropKind.Window) null else {
                        val step = max(15f, p.reach * 1.1f)
                        val ways = if (p.kind == PropKind.Pendant || isStanding(p, zones)) directions.take(2) else directions
                        ways.shuffled(random).map { (dx, dy) -> p.copy(x = p.x + dx * step, y = p.y + dy * step) }.firstOrNull { fits(it, changed, p.id) }
                    }
                    DiffKind.Turned -> when (p.kind.turn) {
                        Twist.Flip -> p.copy(flip = !p.flip)
                        Twist.Rotate -> p.copy(tilt = p.tilt + (if (random.nextBoolean()) 1f else -1f) * (38f + random.nextInt(0, 12))).takeIf { fits(it, changed, p.id) }
                        Twist.None -> null
                    }?.takeIf { p.size >= 14f }
                    DiffKind.Pattern -> if (p.kind.patterned && p.size >= 17f) {
                        val options = Pattern.entries.filter { it != p.pattern }
                        p.copy(pattern = options[random.nextInt(options.size)])
                    } else null
                    DiffKind.Extra -> null
                } ?: continue
                val (cx, cy, r) = ring(p, next)
                if (!clearOfSpots(cx, cy, r)) continue
                used += p.id
                val at = changed.indexOfFirst { it.id == p.id }
                if (diff == DiffKind.Missing) changed.removeAt(at) else changed[at] = next
                spots += Spot(spots.size, diff, cx, cy, r)
                return true
            }
            return false
        }

        val kinds = DiffKind.entries.shuffled(random)
        for (k in kinds) if (spots.size < count) tryKind(k)
        // Then round again, the kinds that make the plainest differences first, until there are enough.
        var rounds = 0
        while (spots.size < count && rounds < 4) {
            rounds++
            for (k in DiffKind.entries.shuffled(random)) if (spots.size < count) tryKind(k)
        }
        return SpotScene(kind, seed, layout, props.sortedBy { it.bottom }, changed.sortedBy { it.bottom }, spots.take(count))
    }

    /** Standing on a surface: it can only slide along it. */
    private fun isStanding(p: Prop, zones: List<Zone>): Boolean = zones.any { z -> z.sit && p.kind in z.kinds && p.bottom >= z.top - 0.5f && p.bottom <= z.bottom + 0.5f }

    /** [p] at [factor] its size; a standing prop keeps its feet where they were. */
    private fun resize(p: Prop, factor: Float, standing: Boolean): Prop {
        val size = p.size * factor
        val y = if (standing) p.bottom - size * p.kind.h / 2f else p.y
        return p.copy(size = size, y = y)
    }
}
