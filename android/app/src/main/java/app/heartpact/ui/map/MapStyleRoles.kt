package app.heartpact.ui.map

/** What a layer of the OpenFreeMap "liberty" style is for, which decides how the map restyles it. */
enum class LayerRole {
    Background, Land, Green, Water, WaterLabel, Road, RoadCasing, Path, Building3d, Boundary, Label,

    /** Dropped for the soft look: points of interest, shields, one-way arrows, rail, the raster shading, flat buildings. */
    Hidden,

    /** Left as the style has it. */
    Keep,
}

/** Pure: sorts the style's layers into roles by their id, type and source layer. */
object MapStyleRoles {
    /** [type] is the style spec's layer type ("fill", "line", "symbol", "background", "raster", "fill-extrusion"). */
    fun classify(id: String, type: String, sourceLayer: String?): LayerRole = when {
        type == "background" -> LayerRole.Background
        type == "raster" -> LayerRole.Hidden
        type == "fill-extrusion" -> LayerRole.Building3d
        sourceLayer == "building" -> LayerRole.Hidden
        sourceLayer == "poi" || sourceLayer == "aerodrome_label" || sourceLayer == "aeroway" -> LayerRole.Hidden
        "shield" in id || "one_way" in id || "hatching" in id || "rail" in id -> LayerRole.Hidden
        sourceLayer == "transportation" -> when {
            type == "fill" -> LayerRole.Hidden
            id.endsWith("_casing") -> LayerRole.RoadCasing
            "path" in id || "pedestrian" in id -> LayerRole.Path
            else -> LayerRole.Road
        }
        sourceLayer == "transportation_name" -> LayerRole.Label
        sourceLayer == "water" || sourceLayer == "waterway" -> if (type == "symbol") LayerRole.WaterLabel else LayerRole.Water
        sourceLayer == "water_name" -> LayerRole.WaterLabel
        sourceLayer == "boundary" -> LayerRole.Boundary
        sourceLayer == "park" -> if (type == "fill") LayerRole.Green else LayerRole.Hidden
        sourceLayer == "landcover" && (id.endsWith("wood") || id.endsWith("grass") || id.endsWith("wetland")) -> LayerRole.Green
        sourceLayer == "landuse" && id.endsWith("pitch") -> LayerRole.Green
        (sourceLayer == "landuse" || sourceLayer == "landcover") && type == "fill" -> LayerRole.Land
        type == "symbol" -> LayerRole.Label
        else -> LayerRole.Keep
    }
}

/** One look of the map, as `#rrggbb` strings. */
data class MapPalette(
    val land: String,
    val landAlt: String,
    val green: String,
    val water: String,
    val road: String,
    /** Behind the roads: nothing by day, a glow at night. */
    val roadGlow: String?,
    val path: String,
    val boundary: String,
    val label: String,
    val labelHalo: String,
    val waterLabel: String,
    /** The 3D buildings, from low to tall. */
    val buildingLow: String,
    val buildingMid: String,
    val buildingTall: String,
    val buildingOpacity: Float,
    /** The dashed line between the two of you. */
    val link: String,
    val linkOpacity: Float,
    val pillBackground: Int,
    val pillText: Int,
    val pillMuted: Int,
) {
    companion object {
        val Day = MapPalette(
            land = "#F6F3EE", landAlt = "#F0ECE6", green = "#CFEBD6", water = "#C3E5F7",
            road = "#FFFFFF", roadGlow = null, path = "#FFFFFF", boundary = "#D8D2E6",
            label = "#8A87A3", labelHalo = "#FFFFFF", waterLabel = "#7FA8C9",
            buildingLow = "#F3EEFA", buildingMid = "#E4DDF3", buildingTall = "#D5D8F3", buildingOpacity = 0.94f,
            link = "#474C72", linkOpacity = 0.55f,
            pillBackground = 0xF2FFFFFF.toInt(), pillText = 0xFF161A3D.toInt(), pillMuted = 0xFF6A6F92.toInt(),
        )
        val Night = MapPalette(
            land = "#0F1530", landAlt = "#131A3B", green = "#143A3F", water = "#1B3668",
            road = "#7C8CFF", roadGlow = "#4C5CFF", path = "#4A5599", boundary = "#2E3A78",
            label = "#9AA3D6", labelHalo = "#0F1530", waterLabel = "#6E8FCB",
            buildingLow = "#1E2656", buildingMid = "#2A3470", buildingTall = "#3A478F", buildingOpacity = 0.95f,
            link = "#C8CEFF", linkOpacity = 0.6f,
            pillBackground = 0xF21E2347.toInt(), pillText = 0xFFEEF0FB.toInt(), pillMuted = 0xFF8C91B6.toInt(),
        )

        fun of(night: Boolean) = if (night) Night else Day
    }
}
