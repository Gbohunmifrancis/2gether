package app.heartpact

import app.heartpact.ui.games.DiffKind
import app.heartpact.ui.games.Prop
import app.heartpact.ui.games.SceneKind
import app.heartpact.ui.games.SpotScene
import app.heartpact.ui.games.SpotScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Spot the difference's pictures: the same on both phones, and every difference fair to find. */
class SpotScenesTest {
    private val seeds = (0 until 150).map { it * 7_919 + 13 } + listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 1_234_567_890)

    private fun everyScene(block: (SpotScene) -> Unit) = SceneKind.entries.forEach { kind -> seeds.forEach { seed -> block(SpotScenes.build(kind, seed)) } }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))

    @Test fun the_same_kind_and_seed_build_the_same_pictures() {
        SceneKind.entries.forEach { kind ->
            seeds.take(40).forEach { seed ->
                // A fresh build on each "phone", not the cached one.
                val a = SpotScenes.fresh(kind, seed)
                val b = SpotScenes.fresh(kind, seed)
                assertEquals(a, b)
            }
        }
        // Different seeds draw different pictures.
        assertNotEquals(SpotScenes.build(SceneKind.Picnic, 1).original, SpotScenes.build(SceneKind.Picnic, 2).original)
        assertEquals(seeds.size, seeds.map { SpotScenes.build(SceneKind.Kitchen, it).original }.distinct().size)
    }

    @Test fun the_server_names_every_kind() {
        val server = listOf("Picnic", "Bedroom", "Kitchen", "Beach", "Garden", "NightSky", "Cafe", "LivingRoom")
        assertEquals(server, SceneKind.entries.map { it.name })
        server.forEach { assertEquals(it, SceneKind.of(it)?.name) }
        assertNull(SceneKind.of("Moon base"))
    }

    @Test fun every_picture_has_seven_differences_numbered_in_order() = everyScene { scene ->
        assertEquals("${scene.kind} ${scene.seed}", SpotScenes.DIFFERENCES, scene.spots.size)
        assertEquals((0 until SpotScenes.DIFFERENCES).toList(), scene.spots.map { it.index })
    }

    @Test fun differences_are_fair_big_enough_apart_and_on_the_page() = everyScene { scene ->
        scene.spots.forEach { s ->
            assertTrue("${scene.kind} ${scene.seed}: a circle a finger can hit", s.r >= SpotScenes.MIN_RADIUS)
            assertTrue(s.x in 0f..SpotScenes.WIDTH && s.y in 0f..SpotScenes.HEIGHT)
        }
        for (i in scene.spots.indices) for (j in i + 1 until scene.spots.size) {
            val a = scene.spots[i]; val b = scene.spots[j]
            assertTrue("${scene.kind} ${scene.seed}: circles $i and $j overlap", dist(a.x, a.y, b.x, b.y) >= a.r + b.r)
        }
    }

    @Test fun a_missing_prop_and_an_added_one_are_never_side_by_side() = everyScene { scene ->
        val missing = scene.spots.filter { it.kind == DiffKind.Missing }
        val extra = scene.spots.filter { it.kind == DiffKind.Extra }
        for (m in missing) for (e in extra) {
            assertTrue("${scene.kind} ${scene.seed}: a missing and an added prop read as one that moved", dist(m.x, m.y, e.x, e.y) >= m.r + e.r + 30f)
        }
    }

    @Test fun lit_windows_keep_room_for_their_glow() = everyScene { scene ->
        for (picture in listOf(scene.original, scene.changed)) {
            val windows = picture.filter { it.kind.name == "Window" }
            for (a in windows) for (b in windows) if (a.id < b.id) {
                val apartX = kotlin.math.abs(a.x - b.x) >= a.halfW + b.halfW + 10f
                val apartY = kotlin.math.abs(a.y - b.y) >= a.halfH + b.halfH + 10f
                assertTrue("${scene.kind} ${scene.seed}: two windows' glows touch", apartX || apartY)
            }
        }
    }

    @Test fun props_never_overlap_and_stay_on_the_page() = everyScene { scene ->
        listOf(scene.original, scene.changed).forEach { props ->
            props.forEach { p ->
                assertTrue("${scene.kind} ${scene.seed} ${p.kind} off the page", p.x - p.halfW >= 0f && p.x + p.halfW <= SpotScenes.WIDTH && p.y - p.halfH >= 0f && p.y + p.halfH <= SpotScenes.HEIGHT)
            }
            for (i in props.indices) for (j in i + 1 until props.size) {
                val a = props[i]; val b = props[j]
                val apart = kotlin.math.abs(a.x - b.x) >= a.halfW + b.halfW || kotlin.math.abs(a.y - b.y) >= a.halfH + b.halfH
                assertTrue("${scene.kind} ${scene.seed}: ${a.kind} and ${b.kind} overlap", apart)
            }
        }
    }

    @Test fun each_difference_is_a_real_change_inside_its_circle_and_nothing_else_changes() = everyScene { scene ->
        val before = scene.original.associateBy { it.id }
        val after = scene.changed.associateBy { it.id }
        val changedIds = (before.keys + after.keys).filter { before[it] != after[it] }
        assertEquals("${scene.kind} ${scene.seed}: one changed prop per difference", SpotScenes.DIFFERENCES, changedIds.size)
        changedIds.forEach { id ->
            val owners = scene.spots.filter { s -> listOfNotNull(before[id], after[id]).all { p -> covers(s.x, s.y, s.r, p) } }
            assertEquals("${scene.kind} ${scene.seed}: prop $id in exactly one circle", 1, owners.size)
            val s = owners.single()
            val was = before[id]; val now = after[id]
            when (s.kind) {
                DiffKind.Missing -> { assertTrue(was != null && now == null) }
                DiffKind.Extra -> { assertTrue(was == null && now != null) }
                DiffKind.Colour -> assertTrue(was!!.paint != now!!.paint && SpotScenes.contrast.getValue(was.paint).contains(now.paint))
                DiffKind.Size -> assertTrue(now!!.size / was!!.size !in 0.7f..1.3f)
                DiffKind.Moved -> assertTrue(dist(was!!.x, was.y, now!!.x, now.y) >= 14f)
                DiffKind.Turned -> assertTrue(was!!.flip != now!!.flip || kotlin.math.abs(was.tilt - now.tilt) >= 30f)
                DiffKind.Pattern -> assertTrue(was!!.pattern != now!!.pattern)
            }
            // Big enough to see at phone size.
            listOfNotNull(was, now).forEach { p -> assertTrue("${scene.kind} ${scene.seed}: ${p.kind} too small", p.size >= 9f) }
        }
    }

    @Test fun differences_come_in_many_kinds() {
        val all = mutableSetOf<DiffKind>()
        everyScene { scene ->
            val kinds = scene.spots.map { it.kind }.toSet()
            all += kinds
            assertTrue("${scene.kind} ${scene.seed}: only $kinds", kinds.size >= 4)
        }
        assertEquals(DiffKind.entries.toSet(), all)
    }

    @Test fun a_tap_on_a_difference_finds_it_and_a_tap_elsewhere_finds_nothing() = everyScene { scene ->
        scene.spots.forEach { s -> assertEquals(s.index, scene.hit(s.x, s.y)) }
        // A corner of the page far from every circle.
        val far = listOf(2f to 2f, 298f to 2f, 2f to 198f, 298f to 198f, 150f to 100f).firstOrNull { (x, y) -> scene.spots.none { dist(it.x, it.y, x, y) <= it.r + 6f } }
        if (far != null) assertNull(scene.hit(far.first, far.second))
    }

    @Test fun scenes_are_full_of_things() = everyScene { scene ->
        assertTrue("${scene.kind} ${scene.seed}: ${scene.original.size} props", scene.original.size >= 10)
        assertFalse(scene.original.isEmpty())
    }

    private fun covers(x: Float, y: Float, r: Float, p: Prop) = dist(x, y, p.x, p.y) <= r
}
