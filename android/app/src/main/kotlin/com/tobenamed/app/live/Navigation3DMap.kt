package com.tobenamed.app.live

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.live.WalkingProgress
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.route.RouteStatus
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.hypot

private val mapInk = Color(0xFF111B2E)
internal fun mapFeatures(vararg features: Feature?) = FeatureCollection.fromFeatures(features.filterNotNull().filter { it.geometry() != null })
private fun emptyFeatures() = mapFeatures()
private fun LatLng.point() = Point.fromLngLat(lng, lat)
private fun LatLng.mapPoint() = org.maplibre.android.geometry.LatLng(lat, lng)
private fun org.maplibre.android.geometry.LatLng.nav() = LatLng(latitude, longitude)

/** Native vector geometry with shaded buildings, roof rims, and restrained footprint-based detailing. */
@Composable
fun NativeNavigation3DMap(destination: AddressCandidate, route: WalkingRoute?, progress: WalkingProgress?, fix: LocationSample?,
    recenter: Int, modifier: Modifier = Modifier, streetPosition: LatLng? = null, onSelectStreet: ((LatLng) -> Unit)? = null,
    walking: Boolean = false, follow: Boolean = false, onBrowse: () -> Unit = {}, topInsetPx: Int = 0, facingDeg: Double? = null) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val uri = LocalUriHandler.current
    val registry = LocalSaveableStateRegistry.current
    val picker by rememberUpdatedState(onSelectStreet)
    val browse by rememberUpdatedState(onBrowse)
    val activeWalk by rememberUpdatedState(walking)
    val currentFacing by rememberUpdatedState(facingDeg)
    val view = remember {
        MapLibre.getInstance(context)
        registry?.consumeRestored("navigation-3d-map")
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false)
            .compassEnabled(false).tiltGesturesEnabled(false).minPitchPreference(45.0).maxPitchPreference(60.0)
            .camera(CameraPosition.Builder().target((streetPosition ?: destination.position).mapPoint())
                .zoom(18.0).tilt(60.0).build())).apply { onCreate(null) }
    }
    val adapter = remember(view) { SdkLifecycle(view::onStart, view::onResume, view::onPause, view::onStop,
        view::onDestroy, view::onLowMemory, view::onSaveInstanceState) }
    BindSdkLifecycle("navigation-3d-map", adapter)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var rendered by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    val animator = remember { arrayOfNulls<ValueAnimator>(1) }
    var lastPuck by remember { mutableStateOf<LatLng?>(null) }
    var lastBearing by remember { mutableDoubleStateOf(0.0) }
    var previewFramed by remember(destination, if (onSelectStreet == null) streetPosition else null) { mutableStateOf(false) }
    var framedRecenter by remember { mutableIntStateOf(-1) }
    val meshCache = remember { MeshCache() }
    LaunchedEffect(walking) { if (walking) previewFramed = false }
    DisposableEffect(view) {
        val idle = MapView.OnDidBecomeIdleListener {
            if (alive && SystemClock.uptimeMillis() - meshCache.lastEvent > 1000) {
                meshCache.lastEvent = SystemClock.uptimeMillis(); revision++
            }
        }
        val failed = MapView.OnDidFailLoadingMapListener { if (alive) error = "The 3D map could not load. Check the connection." }
        val finished = MapView.OnDidFinishRenderingMapListener { fully -> if (alive && style != null && fully) rendered = true }
        view.addOnDidBecomeIdleListener(idle); view.addOnDidFailLoadingMapListener(failed)
        view.addOnDidFinishRenderingMapListener(finished)
        onDispose {
            alive = false; animator[0]?.cancel()
            view.removeOnDidBecomeIdleListener(idle); view.removeOnDidFailLoadingMapListener(failed)
            view.removeOnDidFinishRenderingMapListener(finished)
        }
    }
    Box(modifier.background(mapInk)) {
        AndroidView(factory = {
            view.apply { getMapAsync { m ->
                if (alive) {
                    map = m
                    m.uiSettings.isCompassEnabled = false
                    m.uiSettings.isTiltGesturesEnabled = false
                    m.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                    m.addOnMapClickListener { point -> picker?.invoke(point.nav()); picker != null }
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && activeWalk) browse()
                    }
                }
            } }
        }, modifier = Modifier.fillMaxSize())
        if (!rendered || error != null) Column(Modifier.align(Alignment.Center)
            .background(mapInk.copy(alpha = .94f)).padding(18.dp)) {
            Text(error ?: "Loading 3D buildings…", color = Color(0xFFD9EAF8))
            if (error != null) TextButton({ error = null; retry++ }) { Text("Retry 3D map") }
        }
        TextButton({
            val p = map?.cameraPosition?.target?.nav() ?: destination.position
            uri.openUri("https://www.openstreetmap.org/fixthemap?lat=${p.lat}&lon=${p.lng}&zoom=18")
        }, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp)) {
            Text("© OpenStreetMap · Fix map", color = Color(0xFFC0D4E7), fontSize = 9.sp)
        }
    }
    LaunchedEffect(map, retry) {
        val m = map ?: return@LaunchedEffect
        style = null; rendered = false; meshCache.center = null; meshCache.framedTall = false
        m.setStyle(Style.Builder().fromUri("asset://navigation-3d.json")) { loaded ->
            if (alive) { configureNavigationLayers(loaded); style = loaded; error = null; revision++ }
        }
        delay(18_000)
        if (!rendered) error = "The 3D map did not finish loading. Check the connection."
    }
    LaunchedEffect(map, topInsetPx, density) {
        map?.let { it.cameraPosition = CameraPosition.Builder(it.cameraPosition)
            .padding(8.0 * density, topInsetPx + 8.0 * density, 8.0 * density, 20.0 * density).build() }
    }
    LaunchedEffect(style, destination, streetPosition, route) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(Feature.fromGeometry(destination.position.point()).apply {
            addStringProperty("label", destination.streetNumber.ifBlank { "Building" })
        })
        s.getSourceAs<GeoJsonSource>("street-spot")?.setGeoJson(mapFeatures(streetPosition?.let { Feature.fromGeometry(it.point()) }))
        s.getSourceAs<GeoJsonSource>("route-full")?.setGeoJson(mapFeatures(route?.let { Feature.fromGeometry(LineString.fromLngLats(it.points.map(LatLng::point))) }))
        meshCache.center = null; revision++
    }
    LaunchedEffect(style, fix, route, progress, walking) {
        val s = style ?: return@LaunchedEffect
        val current = fix ?: return@LaunchedEffect
        val projection = route?.line?.project(current.position)
        val matched = progress?.quality == FixQuality.GOOD && progress.route?.status == RouteStatus.ON_ROUTE && projection != null &&
            projection.distanceFromLineM <= maxOf(10.0, current.accuracyM)
        val position = current.position
        val bearing = if (matched) projection.segmentBearingDeg else current.courseDeg ?: current.headingDeg ?: projection?.segmentBearingDeg ?: 0.0
        val future = if (route != null && projection != null && progress?.route?.status != RouteStatus.ARRIVED)
            listOf(projection.snapped) + route.points.drop(projection.segmentIndex + 1) else emptyList()
        s.getSourceAs<GeoJsonSource>("route-future")?.setGeoJson(mapFeatures(if (future.size >= 2)
            Feature.fromGeometry(LineString.fromLngLats(future.map(LatLng::point))) else null))
        val start = lastPuck ?: position; val initialBearing = lastBearing
        animator[0]?.cancel()
        animator[0] = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (walking && Geo.distance(start, position) <= 30) 700 else 0
            addUpdateListener { animation ->
                if (alive) {
                    val t = (animation.animatedValue as Float).toDouble()
                    val point = LatLng(start.lat + (position.lat - start.lat) * t, start.lng + (position.lng - start.lng) * t)
                    val angle = initialBearing + Geo.angleDiff(bearing, initialBearing) * t
                    s.getSourceAs<GeoJsonSource>("walker")?.setGeoJson(Feature.fromGeometry(point.point()).apply { addNumberProperty("bearing", currentFacing ?: angle) })
                    lastPuck = point; lastBearing = angle
                }
            }
            start()
        }
    }
    LaunchedEffect(style, facingDeg) {
        val point = lastPuck ?: return@LaunchedEffect
        val heading = facingDeg ?: return@LaunchedEffect
        style?.getSourceAs<GeoJsonSource>("walker")?.setGeoJson(Feature.fromGeometry(point.point()).apply { addNumberProperty("bearing", heading) })
    }
    LaunchedEffect(map, style, walking, follow, fix, route, progress, recenter, streetPosition, facingDeg) {
        val m = map ?: return@LaunchedEffect
        if (style == null || walking && !follow || !walking && previewFramed && framedRecenter == recenter) return@LaunchedEffect
        val projection = fix?.let { route?.line?.project(it.position) }
        val arrived = progress?.route?.status == RouteStatus.ARRIVED
        val target = if (walking && fix != null) fix.position else streetPosition ?: destination.position
        val ahead = projection?.let { route?.line?.pointAt((it.alongTrackM + 18).coerceAtMost(route.line.lengthM)) }
        val bearing = if (walking && facingDeg != null) facingDeg else if (walking && !arrived) {
            if (ahead != null && Geo.distance(projection.snapped, ahead) > 1) Geo.bearing(projection.snapped, ahead) else projection?.segmentBearingDeg ?: 0.0
        } else target.takeIf { Geo.distance(it, destination.position) > 2 }?.let { Geo.bearing(it, destination.position) } ?: 0.0
        val focus = target
        val previous = m.cameraPosition
        if (previous.target == null || Geo.distance(previous.target!!.nav(), focus) > .3 || abs(Geo.angleDiff(bearing, previous.bearing)) > 2 || previous.tilt < 50)
            m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(focus.mapPoint())
                .bearing(bearing).zoom(if (walking && !arrived) 18.9 else 18.3).tilt(60.0)
                .padding(8.0 * density, topInsetPx + 8.0 * density, 8.0 * density, 20.0 * density).build()), if (walking && facingDeg != null) 120 else 750)
        if (!walking) { previewFramed = true; framedRecenter = recenter; meshCache.framedTall = false }
    }
    LaunchedEffect(style, revision, destination) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        delay(250)
        val camera = m.cameraPosition; val center = camera.target?.nav() ?: return@LaunchedEffect
        if (meshCache.center?.let { Geo.distance(it, center) < 18 } == true &&
            abs(Geo.angleDiff(meshCache.bearing, camera.bearing)) < 25 && abs(meshCache.zoom - camera.zoom) < .5) return@LaunchedEffect
        val buildings = s.getSourceAs<VectorSource>("openmaptiles")?.querySourceFeatures(arrayOf("building"), null) ?: emptyList()
        val pixel = m.projection.toScreenLocation(center.mapPoint())
        val side = m.projection.toScreenLocation(Geo.offset(center, camera.bearing + 90, 1.0).mapPoint())
        val pixelsPerMeter = hypot((side.x - pixel.x).toDouble(), (side.y - pixel.y).toDouble()).coerceAtLeast(.05)
        // Keep roof/corner strokes legible when the view zooms out over tall city blocks.
        val mesh = BuildingGeometry.build(buildings, center, destination.position, .60 * density / pixelsPerMeter, solidScene = true)
        s.getSourceAs<GeoJsonSource>("building-bodies")?.setGeoJson(mesh.bodies)
        s.getSourceAs<GeoJsonSource>("building-edges")?.setGeoJson(mesh.edges)
        if (mesh.count > 0) { meshCache.center = center; meshCache.bearing = camera.bearing; meshCache.zoom = camera.zoom }
        val destinationHeight = mesh.selected.features().orEmpty().maxOfOrNull { it.getNumberProperty("top").toDouble() } ?: 0.0
        // Geocodes can sit outside the footprint; neighboring towers can also occlude the preview.
        val framingHeight = maxOf(destinationHeight, mesh.maxHeightM)
        if (!walking && picker == null && !meshCache.framedTall && framingHeight > 50) {
            meshCache.framedTall = true
            val zoom = when { framingHeight > 250 -> 16.5; framingHeight > 100 -> 16.9; else -> 17.4 }
            if (camera.zoom > zoom) m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(camera).zoom(zoom).build()), 700)
        }
    }
}

private class MeshCache(var center: LatLng? = null, var bearing: Double = 0.0, var zoom: Double = 0.0, var lastEvent: Long = 0,
    var framedTall: Boolean = false)

private fun configureNavigationLayers(style: Style) {
    listOf("building-bodies", "building-edges", "route-full", "route-future", "destination", "street-spot", "walker").forEach {
        val options = GeoJsonOptions()
        // Screen-width facade strips must survive GeoJSON tile simplification.
        if (it == "building-edges") options.withTolerance(0f).withMaxZoom(20)
        style.addSource(GeoJsonSource(it, emptyFeatures(), options))
    }
    listOf("far" to .38f, "middle" to .65f, "near" to .72f).forEach { (depth, opacity) ->
        style.addLayer(FillExtrusionLayer("building-glass-$depth", "building-bodies").withFilter(eq(get("depth"), literal(depth))).withProperties(
            fillExtrusionHeight(get("top")), fillExtrusionBase(get("base")), fillExtrusionColor("#283c60"),
            fillExtrusionOpacity(opacity), fillExtrusionVerticalGradient(true)))
    }
    listOf("halo" to .16f, "floor" to .28f, "facade" to .64f, "corner" to .32f, "roof" to .86f).forEach { (kind, opacity) ->
        val near = when (kind) { "roof" -> 0xFFD9EEFF.toInt(); "facade" -> 0xFF8CACEA.toInt(); "floor" -> 0xFF6385B3.toInt(); else -> 0xFFB2D5FF.toInt() }
        style.addLayer(FillExtrusionLayer("building-$kind", "building-edges").withFilter(eq(get("kind"), literal(kind))).withProperties(
            fillExtrusionHeight(get("top")), fillExtrusionBase(get("base")), fillExtrusionVerticalGradient(kind != "roof"),
            fillExtrusionColor(switchCase(eq(get("selected"), literal(true)), color(0xFFFFD28E.toInt()),
                interpolate(linear(), get("distance"), stop(30, color(near)), stop(140, color(0xFF6080AC.toInt())), stop(280, color(0xFF253651.toInt()))))),
            fillExtrusionOpacity(opacity)))
    }
    style.addLayer(LineLayer("walk-completed", "route-full").withProperties(lineColor("#37647a"), lineWidth(5f), lineOpacity(.65f), lineCap("round"), lineJoin("round")))
    style.addLayer(LineLayer("walk-glow", "route-future").withProperties(lineColor("#20e6d1"), lineWidth(22f), lineBlur(8f), lineOpacity(.45f), lineCap("round"), lineJoin("round")))
    style.addLayer(LineLayer("walk-route", "route-future").withProperties(lineColor("#31dfcb"), lineWidth(8f), lineCap("round"), lineJoin("round")))
    style.addLayer(LineLayer("walk-route-center", "route-future").withProperties(lineColor("#d7fff9"), lineWidth(2.2f), lineCap("round"), lineJoin("round")))
    style.addImage("route-chevron", routeChevronBitmap())
    style.addLayer(SymbolLayer("walking-route-chevrons", "route-future").withProperties(symbolPlacement("line"), symbolSpacing(52f),
        iconImage("route-chevron"), iconAllowOverlap(true), iconIgnorePlacement(true), iconKeepUpright(false),
        iconRotationAlignment("map"), iconPitchAlignment("map")))
    style.addImage("walk-puck", puckBitmap())
    style.addLayer(SymbolLayer("walking-location", "walker").withProperties(iconImage("walk-puck"), iconAllowOverlap(true), iconIgnorePlacement(true),
        iconRotationAlignment("map"), iconPitchAlignment("viewport"), iconRotate(get("bearing"))))
    style.addLayer(CircleLayer("street-arrival-glow", "street-spot").withProperties(circleColor("#31dfcb"), circleRadius(10f), circleOpacity(.35f), circleBlur(.5f)))
    style.addLayer(CircleLayer("street-arrival-point", "street-spot").withProperties(circleColor("#e7fffa"), circleRadius(3.5f)))
    style.addLayer(CircleLayer("building-anchor", "destination").withProperties(circleColor("#ffd28e"), circleRadius(5f), circleStrokeColor("#172337"), circleStrokeWidth(2f)))
    style.addLayer(SymbolLayer("building-number", "destination").withProperties(textField(get("label")), textFont(arrayOf("Noto Sans Regular")),
        textSize(19f), textColor("#ffd28e"), textHaloColor("#121d30"), textHaloWidth(3f), textAllowOverlap(true),
        textAnchor("bottom"), textOffset(arrayOf(0f, -.7f))))
}

private fun puckBitmap() = createBitmap(48, 48).also { bitmap ->
    val c = Canvas(bitmap); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x5528E2CF; c.drawCircle(24f, 24f, 23f, p)
    val shape = Path().apply { moveTo(24f, 8f); lineTo(38f, 38f); lineTo(24f, 31f); lineTo(10f, 38f); close() }
    p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeJoin = Paint.Join.ROUND; p.color = 0xFF102237.toInt(); c.drawPath(shape, p)
    p.style = Paint.Style.FILL; p.color = 0xFFE2FFFA.toInt(); c.drawPath(shape, p)
}

private fun routeChevronBitmap() = createBitmap(18, 18).also { bitmap ->
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5FFFA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    Canvas(bitmap).drawPath(Path().apply { moveTo(6f, 4f); lineTo(12f, 9f); lineTo(6f, 14f) }, p)
}
