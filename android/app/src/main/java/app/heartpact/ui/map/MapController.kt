package app.heartpact.ui.map

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circlePitchScale
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot

/** How far the sheet's rounded top covers the bottom of the map view, so the corners show map rather than the page. */
const val MAP_SHEET_OVERLAP_DP = 28f

/** One of the two of you, as the map needs to draw them. */
class MapPerson(
    val userId: String,
    val isMe: Boolean,
    val name: String,
    /** "now", "2 min ago"; null for yourself. */
    val age: String?,
    /** Whether the fix is fresh enough for the halo to pulse. */
    val live: Boolean,
    /** Ring colour, ARGB. */
    val ring: Int,
    val photo: Bitmap?,
    val pos: Pos,
    /** Which half of the heart they are; their marker is that half standing on the map. */
    val rightHalf: Boolean = false,
)

/**
 * Owns the MapLibre map behind [LiveMap]: the restyled base map, the two markers and the line between them, their
 * glide between fixes, the pulsing halo, the camera moves (fly to you both, frame both, recenter on me) and the slow
 * sway. Everything runs on the main thread; the Compose side only hands it the latest people and the look.
 */
class MapController {
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var density = 1f
    private var destroyed = false
    private var onFailed: (() -> Unit)? = null

    private var people: List<MapPerson> = emptyList()
    private val tweens = HashMap<String, MarkerTween>()
    private val imageIds = HashMap<String, String>()
    private val imageKeys = HashMap<String, String>()
    private val retired = ArrayList<Pair<String, Long>>()
    private var imageCounter = 0
    private var peopleSource: GeoJsonSource? = null
    private var linkSource: GeoJsonSource? = null
    private var haloLayer: CircleLayer? = null
    private var linkLayer: LineLayer? = null

    private var night = false
    private var appliedNight: Boolean? = null
    private var dirty = false
    private var wasMoving = false
    private var lastTickMs = 0L
    private var reduceMotion = false

    private var flewIn = false
    private var viewSized = false
    private var touching = false
    private var lastTouchMs = 0L
    private var cameraBusyUntilMs = 0L
    private var swayStartMs = -1L
    private var swayStartBearing = 0.0

    val styleReady: Boolean get() = style != null && !destroyed

    /** Called once, with the view the lifecycle driver also manages. [failed] is called if the base map cannot load. */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(mapView: MapView, density: Float, failed: () -> Unit) {
        view = mapView
        this.density = density
        onFailed = failed
        reduceMotion = !ValueAnimator.areAnimatorsEnabled()
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; lastTouchMs = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; lastTouchMs = SystemClock.uptimeMillis() }
            }
            false
        }
        mapView.addOnDidFailLoadingMapListener { if (style == null) fail() }
        mapView.postDelayed({ if (style == null && !destroyed) fail() }, STYLE_TIMEOUT_MS)
        mapView.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            configure(m)
            m.setStyle(Style.Builder().fromUri(MapStyler.STYLE_URL)) { loaded -> if (!destroyed) onStyleLoaded(loaded) }
        }
    }

    /** The view is going: drop every reference so late callbacks do nothing. */
    fun detach() {
        destroyed = true
        view?.setOnTouchListener(null)
        map?.removeOnMapClickListener(clickListener)
        view = null
        map = null
        style = null
        peopleSource = null
        linkSource = null
        haloLayer = null
        linkLayer = null
        onFailed = null
    }

    private fun fail() {
        val callback = onFailed ?: return
        onFailed = null
        callback()
    }

    private fun configure(m: MapLibreMap) {
        m.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true
            setAttributionGravity(Gravity.BOTTOM or Gravity.START)
            setAttributionMargins(dp(12f), 0, 0, dp(MAP_SHEET_OVERLAP_DP + 8f))
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        m.setMinZoomPreference(2.0)
        m.setMaxZoomPreference(19.0)
        m.setMaxPitchPreference(MAX_PITCH)
        m.cameraPosition = CameraPosition.Builder().target(LatLng(20.0, 0.0)).zoom(1.6).tilt(0.0).bearing(0.0).build()
        m.addOnMapClickListener(clickListener)
    }

    private fun dp(v: Float) = (v * density).toInt()

    private val clickListener = MapLibreMap.OnMapClickListener { point ->
        val m = map ?: return@OnMapClickListener false
        val nowMs = SystemClock.uptimeMillis()
        val tap = m.projection.toScreenLocation(point)
        // A tap on the character or at their feet flies to that person.
        val hit = people.filter { p ->
            val pos = tweens[p.userId]?.at(nowMs) ?: p.pos
            val s = m.projection.toScreenLocation(LatLng(pos.lat, pos.lng))
            hypot(tap.x - s.x, tap.y - (s.y - MapMarkerBitmap.TIP_FROM_TOP_DP / 2 * density)) < dp(40f) || hypot(tap.x - s.x, tap.y - s.y) < dp(28f)
        }.firstOrNull()
        if (hit != null) { flyTo(hit.pos); true } else false
    }

    private fun onStyleLoaded(loaded: Style) {
        MapStyler.apply(loaded, night)
        appliedNight = night
        style = loaded

        val link = GeoJsonSource(LINK_SOURCE)
        val source = GeoJsonSource(PEOPLE_SOURCE)
        loaded.addSource(link)
        loaded.addSource(source)
        linkSource = link
        peopleSource = source

        val p = MapPalette.of(night)
        val line = LineLayer(LINK_LAYER, LINK_SOURCE).withProperties(
            lineColor(android.graphics.Color.parseColor(p.link)), lineOpacity(p.linkOpacity), lineWidth(3f),
            lineDasharray(arrayOf(0.4f, 2.2f)), lineCap(Property.LINE_CAP_ROUND),
        )
        // The halo lies on the ground under the tail's tip, so it flattens with the camera's tilt.
        val halo = CircleLayer(HALO_LAYER, PEOPLE_SOURCE).withProperties(
            circleColor(Expression.toColor(Expression.get("ring"))), circleRadius(0f), circleOpacity(0f), circleBlur(0.5f),
            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP), circlePitchScale(Property.CIRCLE_PITCH_SCALE_MAP),
        ).apply { setFilter(Expression.eq(Expression.get("live"), 1)) }
        val symbols = SymbolLayer(PEOPLE_LAYER, PEOPLE_SOURCE).withProperties(
            iconImage(Expression.get("img")), iconAnchor(Property.ICON_ANCHOR_CENTER),
            iconAllowOverlap(true), iconIgnorePlacement(true),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        )
        loaded.addLayer(line)
        loaded.addLayer(halo)
        loaded.addLayer(symbols)
        linkLayer = line
        haloLayer = halo

        renderImages(force = true)
        dirty = true
        flyInIfReady()
    }

    /** The people, the look, and the time have changed. Cheap to call often. */
    fun update(newPeople: List<MapPerson>, night: Boolean) {
        if (destroyed) return
        val nowMs = SystemClock.uptimeMillis()
        people = newPeople
        val ids = newPeople.map { it.userId }.toSet()
        tweens.keys.retainAll(ids)
        for (p in newPeople) {
            val tween = tweens[p.userId]
            when {
                tween == null -> tweens[p.userId] = MarkerTween(p.pos)
                reduceMotion -> tween.snapTo(p.pos)
                else -> tween.moveTo(p.pos, nowMs)
            }
        }
        val nightChanged = night != this.night
        this.night = night
        if (style != null) {
            if (nightChanged || appliedNight != night) restyle()
            renderImages(force = false)
            dirty = true
            flyInIfReady()
        }
    }

    private fun restyle() {
        val s = style ?: return
        MapStyler.apply(s, night)
        appliedNight = night
        val p = MapPalette.of(night)
        linkLayer?.setProperties(lineColor(android.graphics.Color.parseColor(p.link)), lineOpacity(p.linkOpacity))
        renderImages(force = true)
    }

    private fun renderImages(force: Boolean) {
        val s = style ?: return
        val palette = MapPalette.of(night)
        val nowMs = SystemClock.uptimeMillis()
        for (p in people) {
            val key = "${System.identityHashCode(p.photo)}|${p.age}|${p.name}|${p.ring}|${p.rightHalf}|$night|${app.heartpact.ui.theme.Season.christmas}"
            if (!force && imageKeys[p.userId] == key) continue
            val bitmap = MapMarkerBitmap.render(
                MarkerLook(p.name, p.age, p.ring, p.photo, palette.pillBackground, palette.pillText, palette.pillMuted, rightHalf = p.rightHalf), density,
            )
            val id = "hc-face-${p.userId}-${imageCounter++}"
            s.addImage(id, bitmap)
            imageIds.put(p.userId, id)?.let { retired += it to nowMs }
            imageKeys[p.userId] = key
        }
        imageIds.keys.retainAll(people.map { it.userId }.toSet())
    }

    /** The map view has a size, so cameras can be framed. */
    fun onViewSized() {
        // After the view's own layout, so the native map knows its size when the camera is framed.
        view?.post {
            if (destroyed) return@post
            viewSized = true
            flyInIfReady()
        }
    }

    /** First time there is a map, a style, a size and someone to show: drop in from above and fly to the two of you. */
    private fun flyInIfReady() {
        val m = map ?: return
        if (flewIn || style == null || !viewSized || people.isEmpty()) return
        flewIn = true
        val target = framingCamera() ?: return
        if (reduceMotion) { m.moveCamera(CameraUpdateFactory.newCameraPosition(target)); return }
        m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target.target).zoom(11.5).tilt(10.0).bearing(0.0).build()))
        animate(target, FLY_IN_MS)
    }

    private fun framingCamera(): CameraPosition? {
        val m = map ?: return null
        val current = m.cameraPosition.bearing
        val targets = people.map { LatLng(it.pos.lat, it.pos.lng) }
        return when (targets.size) {
            0 -> null
            1 -> CameraPosition.Builder().target(targets[0]).zoom(16.5).tilt(MapMotion.PITCH).bearing(current).build()
            else -> {
                val bounds = LatLngBounds.Builder().includes(targets).build()
                val padding = intArrayOf(dp(60f), dp(150f), dp(60f), dp(78f))
                val camera = m.getCameraForLatLngBounds(bounds, padding, current, MapMotion.PITCH) ?: return null
                CameraPosition.Builder(camera).zoom(MapMotion.frameZoom(camera.zoom)).build()
            }
        }
    }

    private fun animate(camera: CameraPosition, durationMs: Int) {
        val m = map ?: return
        cameraBusyUntilMs = SystemClock.uptimeMillis() + durationMs + 1_200
        swayStartMs = -1L
        m.cancelTransitions()
        if (reduceMotion) m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), durationMs)
    }

    /** The "frame both" button. */
    fun frameBoth() {
        framingCamera()?.let { animate(it, FRAME_MS) }
    }

    /** The "recenter on me" button. */
    fun recenterOnMe() {
        val m = map ?: return
        val me = people.firstOrNull { it.isMe } ?: return frameBoth()
        val c = m.cameraPosition
        val camera = CameraPosition.Builder().target(LatLng(me.pos.lat, me.pos.lng)).zoom(c.zoom.coerceIn(15.5, 17.5)).tilt(MapMotion.PITCH).bearing(c.bearing).build()
        animate(camera, FRAME_MS)
    }

    private fun flyTo(place: Pos) {
        val m = map ?: return
        val c = m.cameraPosition
        animate(CameraPosition.Builder().target(LatLng(place.lat, place.lng)).zoom(c.zoom.coerceIn(15.5, 17.5)).tilt(MapMotion.PITCH).bearing(c.bearing).build(), FRAME_MS)
    }

    /** Once per frame from the Compose side: glide, pulse, and sway. Does little when nothing moves. */
    fun tick() {
        val m = map ?: return
        if (style == null || destroyed) return
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastTickMs < FRAME_INTERVAL_MS) return
        lastTickMs = nowMs

        val moving = !reduceMotion && people.any { tweens[it.userId]?.isMoving(nowMs) == true }
        if (moving || wasMoving || dirty) pushFeatures(nowMs)
        wasMoving = moving
        dirty = false

        if (retired.isNotEmpty()) {
            val it = retired.iterator()
            while (it.hasNext()) {
                val (id, since) = it.next()
                if (nowMs - since > 600) { style?.removeImage(id); it.remove() }
            }
        }

        if (!reduceMotion) {
            val halo = haloLayer
            if (halo != null) {
                if (people.any { it.live }) {
                    val h = MapMotion.halo(nowMs)
                    halo.setProperties(circleRadius(HALO_MAX_RADIUS * h.radius), circleOpacity(h.opacity))
                } else halo.setProperties(circleOpacity(0f))
            }
            sway(m, nowMs)
        }
    }

    private fun sway(m: MapLibreMap, nowMs: Long) {
        val idle = !touching && people.isNotEmpty() && nowMs - lastTouchMs > IDLE_BEFORE_SWAY_MS && nowMs > cameraBusyUntilMs && m.cameraPosition.tilt > 20.0
        if (!idle) { swayStartMs = -1L; return }
        if (swayStartMs < 0) { swayStartMs = nowMs; swayStartBearing = m.cameraPosition.bearing }
        m.moveCamera(CameraUpdateFactory.bearingTo(MapMotion.sway(swayStartBearing, nowMs - swayStartMs)))
    }

    private fun pushFeatures(nowMs: Long) {
        val source = peopleSource ?: return
        val points = ArrayList<Point>(2)
        val features = people.mapNotNull { p ->
            val id = imageIds[p.userId] ?: return@mapNotNull null
            val pos = tweens[p.userId]?.at(nowMs) ?: p.pos
            val point = Point.fromLngLat(pos.lng, pos.lat)
            points += point
            Feature.fromGeometry(point).apply {
                addStringProperty("img", id)
                addNumberProperty("live", if (p.live) 1 else 0)
                addStringProperty("ring", "#%06X".format(p.ring and 0xFFFFFF))
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        if (points.size == 2) linkSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        else linkSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    private companion object {
        const val PEOPLE_SOURCE = "hc-people"
        const val LINK_SOURCE = "hc-link"
        const val PEOPLE_LAYER = "hc-people-symbols"
        const val HALO_LAYER = "hc-halo"
        const val LINK_LAYER = "hc-link-line"
        const val STYLE_TIMEOUT_MS = 15_000L
        const val FRAME_INTERVAL_MS = 30L
        const val IDLE_BEFORE_SWAY_MS = 6_000L
        const val FLY_IN_MS = 2_400
        const val FRAME_MS = 1_300
        const val HALO_MAX_RADIUS = 58f
        const val MAX_PITCH = 60.0
    }
}
