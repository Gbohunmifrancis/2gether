package app.heartpact.map

import app.heartpact.ui.map.LayerRole
import app.heartpact.ui.map.MapPalette
import app.heartpact.ui.map.MapStyleRoles.classify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layers of OpenFreeMap's liberty style, by id, type and source layer, and what the pastel restyle does with them. */
class MapStyleRolesTest {
    @Test fun `base layers`() {
        assertEquals(LayerRole.Background, classify("background", "background", null))
        assertEquals(LayerRole.Hidden, classify("natural_earth", "raster", null))
        assertEquals(LayerRole.Water, classify("water", "fill", "water"))
        assertEquals(LayerRole.Water, classify("waterway_river", "line", "waterway"))
        assertEquals(LayerRole.Green, classify("park", "fill", "park"))
        assertEquals(LayerRole.Green, classify("landcover_wood", "fill", "landcover"))
        assertEquals(LayerRole.Green, classify("landcover_grass", "fill", "landcover"))
        assertEquals(LayerRole.Green, classify("landuse_pitch", "fill", "landuse"))
        assertEquals(LayerRole.Land, classify("landuse_residential", "fill", "landuse"))
        assertEquals(LayerRole.Land, classify("landcover_sand", "fill", "landcover"))
        assertEquals(LayerRole.Boundary, classify("boundary_2", "line", "boundary"))
    }

    @Test fun `roads are white lines without casings, paths are lighter`() {
        assertEquals(LayerRole.Road, classify("road_minor", "line", "transportation"))
        assertEquals(LayerRole.Road, classify("road_motorway", "line", "transportation"))
        assertEquals(LayerRole.Road, classify("bridge_secondary_tertiary", "line", "transportation"))
        assertEquals(LayerRole.RoadCasing, classify("road_minor_casing", "line", "transportation"))
        assertEquals(LayerRole.RoadCasing, classify("bridge_motorway_casing", "line", "transportation"))
        assertEquals(LayerRole.Path, classify("road_path_pedestrian", "line", "transportation"))
        assertEquals(LayerRole.Hidden, classify("road_area_pattern", "fill", "transportation"))
    }

    @Test fun `clutter is hidden`() {
        for (id in listOf("poi_r20", "poi_r7", "poi_r1", "poi_transit")) assertEquals(id, LayerRole.Hidden, classify(id, "symbol", "poi"))
        assertEquals(LayerRole.Hidden, classify("airport", "symbol", "aerodrome_label"))
        assertEquals(LayerRole.Hidden, classify("highway-shield-non-us", "symbol", "transportation_name"))
        assertEquals(LayerRole.Hidden, classify("road_shield_us", "symbol", "transportation_name"))
        assertEquals(LayerRole.Hidden, classify("road_one_way_arrow", "symbol", "transportation"))
        assertEquals(LayerRole.Hidden, classify("road_major_rail", "line", "transportation"))
        assertEquals(LayerRole.Hidden, classify("road_transit_rail_hatching", "line", "transportation"))
        assertEquals(LayerRole.Hidden, classify("aeroway_runway", "line", "aeroway"))
    }

    @Test fun `buildings are 3d and the flat ones go`() {
        assertEquals(LayerRole.Building3d, classify("building-3d", "fill-extrusion", "building"))
        assertEquals(LayerRole.Hidden, classify("building", "fill", "building"))
    }

    @Test fun `labels are muted, not hidden`() {
        assertEquals(LayerRole.Label, classify("label_city", "symbol", "place"))
        assertEquals(LayerRole.Label, classify("highway-name-major", "symbol", "transportation_name"))
        assertEquals(LayerRole.WaterLabel, classify("water_name_point_label", "symbol", "water_name"))
        assertEquals(LayerRole.WaterLabel, classify("waterway_line_label", "symbol", "waterway"))
    }

    @Test fun `night is a dark map with glowing roads, day has no glow`() {
        assertNull(MapPalette.Day.roadGlow)
        assertNotNull(MapPalette.Night.roadGlow)
        fun brightness(hex: String): Int = hex.drop(1).chunked(2).sumOf { it.toInt(16) }
        assertTrue(brightness(MapPalette.Night.land) < brightness(MapPalette.Day.land) / 4)
        assertTrue(brightness(MapPalette.Night.water) < brightness(MapPalette.Day.water))
        assertEquals(MapPalette.Night, MapPalette.of(true))
        assertEquals(MapPalette.Day, MapPalette.of(false))
    }
}
