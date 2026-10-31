package app.heartpact.ui.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionVerticalGradient
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloBlur
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Restyles OpenFreeMap's "liberty" style in place, once it has loaded: a soft pastel map (or a deep navy one with glowing
 * roads at night), 3D buildings, and no clutter. Every layer is handled on its own, so a layer the style renames or drops
 * costs one rule, not the whole map.
 */
object MapStyler {
    /** The map's own layers (the people and the line between them) are named with this and are not restyled. */
    const val OWN_PREFIX = "hc-"

    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** The 3D buildings come in from here, so the flat map's last zoom has something under it. */
    private const val BUILDINGS_FROM_ZOOM = 13f

    fun apply(style: Style, night: Boolean) {
        val palette = MapPalette.of(night)
        for (layer in style.layers) if (!layer.id.startsWith(OWN_PREFIX)) runCatching { restyle(layer, palette, night) }
    }

    private fun sourceLayerOf(layer: Layer): String? = when (layer) {
        is FillLayer -> layer.sourceLayer
        is LineLayer -> layer.sourceLayer
        is SymbolLayer -> layer.sourceLayer
        is FillExtrusionLayer -> layer.sourceLayer
        else -> null
    }

    private fun typeOf(layer: Layer): String = when (layer) {
        is BackgroundLayer -> "background"
        is FillExtrusionLayer -> "fill-extrusion"
        is FillLayer -> "fill"
        is LineLayer -> "line"
        is SymbolLayer -> "symbol"
        is org.maplibre.android.style.layers.RasterLayer -> "raster"
        else -> "other"
    }

    private fun c(hex: String) = Color.parseColor(hex)

    private fun restyle(layer: Layer, p: MapPalette, night: Boolean) {
        val id = layer.id
        val role = MapStyleRoles.classify(id, typeOf(layer), sourceLayerOf(layer))
        if (role == LayerRole.Hidden) {
            layer.setProperties(visibility(Property.NONE))
            return
        }
        // Casings are the glow behind the roads at night, and nothing by day ("white roads with no heavy casing").
        if (role == LayerRole.RoadCasing) {
            if (night && p.roadGlow != null && !id.startsWith("tunnel")) {
                layer.setProperties(visibility(Property.VISIBLE), lineColor(c(p.roadGlow)), lineOpacity(0.28f), lineBlur(6f))
            } else layer.setProperties(visibility(Property.NONE))
            return
        }
        if (role != LayerRole.Keep) layer.setProperties(visibility(Property.VISIBLE))
        when (role) {
            LayerRole.Background -> layer.setProperties(backgroundColor(c(p.land)))
            LayerRole.Land -> (layer as? FillLayer)?.setProperties(
                fillColor(c(if (id.endsWith("residential")) p.land else p.landAlt)), fillOpacity(1f), fillAntialias(false),
            )
            LayerRole.Green -> (layer as? FillLayer)?.setProperties(fillColor(c(p.green)), fillOpacity(1f), fillAntialias(false))
            LayerRole.Water -> when (layer) {
                is FillLayer -> layer.setProperties(fillColor(c(p.water)), fillOpacity(1f))
                is LineLayer -> layer.setProperties(lineColor(c(p.water)), lineOpacity(1f))
            }
            LayerRole.Road -> (layer as? LineLayer)?.setProperties(
                lineColor(c(p.road)), lineOpacity(if (id.startsWith("tunnel")) 0.5f else if (night) 0.9f else 1f), lineBlur(if (night) 0.8f else 0.2f),
            )
            LayerRole.Path -> (layer as? LineLayer)?.setProperties(lineColor(c(p.path)), lineOpacity(if (night) 0.6f else 0.85f))
            LayerRole.Boundary -> (layer as? LineLayer)?.setProperties(lineColor(c(p.boundary)), lineOpacity(0.7f))
            LayerRole.Label, LayerRole.WaterLabel -> (layer as? SymbolLayer)?.setProperties(
                textColor(c(if (role == LayerRole.WaterLabel) p.waterLabel else p.label)),
                textHaloColor(c(p.labelHalo)), textHaloWidth(1.4f), textHaloBlur(0.6f),
            )
            LayerRole.Building3d -> (layer as? FillExtrusionLayer)?.let { building(it, p) }
            LayerRole.Hidden, LayerRole.RoadCasing, LayerRole.Keep -> Unit
        }
    }

    /** Pastel towers that grow in as you zoom, taller ones shading from lilac towards periwinkle. */
    private fun building(layer: FillExtrusionLayer, p: MapPalette) {
        val height = coalesce(get("render_height"), literal(6))
        val minHeight = coalesce(get("render_min_height"), literal(0))
        layer.minZoom = BUILDINGS_FROM_ZOOM
        layer.setProperties(
            fillExtrusionColor(interpolate(linear(), height, stop(0, color(c(p.buildingLow))), stop(40, color(c(p.buildingMid))), stop(140, color(c(p.buildingTall))))),
            // Zoom may only drive the top-level interpolation, so the data expression sits inside its stops.
            fillExtrusionHeight(interpolate(linear(), zoom(), stop(BUILDINGS_FROM_ZOOM + 0.5f, 0), stop(15.5f, height))),
            fillExtrusionBase(interpolate(linear(), zoom(), stop(BUILDINGS_FROM_ZOOM + 0.5f, 0), stop(15.5f, minHeight))),
            fillExtrusionOpacity(p.buildingOpacity),
            fillExtrusionVerticalGradient(true),
        )
    }
}
