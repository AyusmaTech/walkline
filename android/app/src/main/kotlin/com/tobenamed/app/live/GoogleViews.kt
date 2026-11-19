package com.tobenamed.app.live

import android.content.ComponentCallbacks
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.PolylineOptions
import com.tobenamed.app.BuildConfig
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.model.LocationSample
import kotlinx.coroutines.delay

private fun LatLng.google() = com.google.android.gms.maps.model.LatLng(lat, lng)
private class UserOverlays(var marker: Marker? = null, var circle: Circle? = null, var animator: ValueAnimator? = null)

internal class SdkLifecycle(
    val start: () -> Unit, val resume: () -> Unit, val pause: () -> Unit,
    val stop: () -> Unit, val destroy: () -> Unit, val lowMemory: () -> Unit, val save: (Bundle) -> Unit,
) {
    private var started = false
    private var resumed = false
    private var destroyed = false
    fun event(event: Lifecycle.Event) {
        if (destroyed) return
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) { start(); started = true }
            Lifecycle.Event.ON_RESUME -> if (!resumed) { resume(); resumed = true }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { pause(); resumed = false }
            Lifecycle.Event.ON_STOP -> if (started) { stop(); started = false }
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }
    fun dispose() {
        if (destroyed) return
        if (resumed) pause()
        if (started) stop()
        destroy()
        destroyed = true
    }
}

@Composable
internal fun BindSdkLifecycle(key: String, adapter: SdkLifecycle) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val registry = LocalSaveableStateRegistry.current
    DisposableEffect(adapter, owner, registry) {
        val observer = LifecycleEventObserver { _, event -> adapter.event(event) }
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(configuration: Configuration) = Unit
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = adapter.lowMemory()
        }
        val registration = registry?.registerProvider(key) { Bundle().also(adapter.save) }
        context.registerComponentCallbacks(callbacks)
        owner.lifecycle.addObserver(observer)
        onDispose {
            registration?.unregister()
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            adapter.dispose()
        }
    }
}

@Composable
fun GoogleWalkingMap(destination: AddressCandidate, route: List<LatLng>?, fix: LocationSample?, recenter: Int,
    modifier: Modifier = Modifier, streetPosition: LatLng? = null, onSelectStreet: ((LatLng) -> Unit)? = null,
    walking: Boolean = false, follow: Boolean = false, onBrowse: () -> Unit = {}, topInsetPx: Int = 0, facingDeg: Double? = null) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        Text("Google Map needs an Android Maps key. See the setup guide.", color = MaterialTheme.colorScheme.secondary)
        return
    }
    val context = LocalContext.current
    val registry = LocalSaveableStateRegistry.current
    val picker by rememberUpdatedState(onSelectStreet)
    val browse by rememberUpdatedState(onBrowse)
    val activeWalk by rememberUpdatedState(walking)
    val currentFacing by rememberUpdatedState(facingDeg)
    val density = LocalDensity.current.density
    val routeLine = remember(route) { route?.takeIf { it.size >= 2 }?.let(::Polyline) }
    val view = remember { MapView(context, GoogleMapOptions().camera(CameraPosition.Builder()
        .target(destination.position.google()).zoom(18.5f).tilt(60f).build()))
        .apply { onCreate(registry?.consumeRestored("google-map") as? Bundle) } }
    val adapter = remember(view) { SdkLifecycle(view::onStart, view::onResume, view::onPause, view::onStop,
        view::onDestroy, view::onLowMemory, view::onSaveInstanceState) }
    BindSdkLifecycle("google-map", adapter)
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val puck = remember(map, density) { map?.let { BitmapDescriptorFactory.fromBitmap(walkingPuck(density)) } }
    val userOverlays = remember(map) { UserOverlays() }
    DisposableEffect(userOverlays) { onDispose { userOverlays.animator?.cancel() } }
    var loaded by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var previewFramed by remember(destination, if (onSelectStreet == null) streetPosition else null) { mutableStateOf(false) }
    var framedRecenter by remember { mutableIntStateOf(-1) }
    LaunchedEffect(view) { delay(15_000); timedOut = !loaded }
    Box(modifier) {
        AndroidView(factory = { view.apply { getMapAsync { googleMap ->
            map = googleMap
            googleMap.uiSettings.isMapToolbarEnabled = false
            googleMap.uiSettings.isZoomControlsEnabled = false
            googleMap.uiSettings.isCompassEnabled = false
            googleMap.uiSettings.isTiltGesturesEnabled = false
            googleMap.isBuildingsEnabled = true
            googleMap.isIndoorEnabled = false
            googleMap.setMapStyle(MapStyleOptions(DARK_MAP_STYLE))
            googleMap.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE && activeWalk) browse()
            }
            googleMap.setOnMapLoadedCallback { loaded = true; timedOut = false }
            googleMap.setOnMapClickListener { picker?.invoke(LatLng(it.latitude, it.longitude)) }
        } } }, modifier = Modifier.matchParentSize())
        if (!loaded) Text(if (timedOut) "Map did not load. Check the connection and app setup." else "Loading Google Map…",
            modifier = Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface).padding(10.dp))
    }
    LaunchedEffect(map, topInsetPx) {
        map?.setPadding((12 * density).toInt(), topInsetPx + (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
    }
    LaunchedEffect(map, destination, route, streetPosition) {
        val googleMap = map ?: return@LaunchedEffect
        userOverlays.animator?.cancel()
        googleMap.clear()
        userOverlays.marker = null
        userOverlays.circle = null
        googleMap.addMarker(MarkerOptions().position(destination.position.google()).title(destination.address)
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            .snippet(if (destination.precision.uncertaintyM > 5) "Approximate address area" else "Building position; entrance unconfirmed"))
        if (destination.precision.uncertaintyM > 5) googleMap.addCircle(CircleOptions()
            .center(destination.position.google()).radius(destination.precision.uncertaintyM)
            .strokeColor(0xCCFFCD87.toInt()).fillColor(0x22FFCD87).strokeWidth(2f))
        if (route != null) {
            val points = route.map { it.google() }
            googleMap.addPolyline(PolylineOptions().addAll(points).width(18 * density).color(0x5548DFC7).zIndex(10f))
            googleMap.addPolyline(PolylineOptions().addAll(points).width(9 * density).color(0xFF48DFC7.toInt()).zIndex(11f))
            googleMap.addPolyline(PolylineOptions().addAll(points).width(2 * density).color(0xFFE2FFF8.toInt()).zIndex(12f))
        }
        if (streetPosition != null) googleMap.addMarker(MarkerOptions().position(streetPosition.google())
            .title("Street viewing spot").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
    }
    LaunchedEffect(map, destination, route, fix, streetPosition) {
        val googleMap = map ?: return@LaunchedEffect
        val current = fix ?: return@LaunchedEffect
        val projection = routeLine?.project(current.position)
        val bearing = projection?.segmentBearingDeg ?: current.courseDeg ?: current.headingDeg ?: 0.0
        if (userOverlays.marker == null) userOverlays.marker = googleMap.addMarker(MarkerOptions()
            .position(current.position.google()).title("Your location")
            .icon(puck).anchor(.5f, .5f).flat(true).rotation(bearing.toFloat()).zIndex(20f))
        if (userOverlays.circle == null) userOverlays.circle = googleMap.addCircle(CircleOptions()
            .center(current.position.google()).radius(current.accuracyM)
            .strokeColor(0xAA2580DC.toInt()).fillColor(0x222580DC).strokeWidth(2f))
        userOverlays.animator?.cancel()
        val start = userOverlays.marker?.position ?: current.position.google()
        val startBearing = userOverlays.marker?.rotation?.toDouble() ?: bearing
        val target = current.position.google()
        val jump = Geo.distance(LatLng(start.latitude, start.longitude), current.position) > 30
        userOverlays.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (jump || !walking) 0 else 700
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val point = com.google.android.gms.maps.model.LatLng(start.latitude + (target.latitude - start.latitude) * fraction,
                    start.longitude + (target.longitude - start.longitude) * fraction)
                userOverlays.marker?.position = point
                userOverlays.marker?.rotation = (currentFacing ?: (startBearing + Geo.angleDiff(bearing, startBearing) * fraction)).toFloat()
                userOverlays.circle?.center = point
                userOverlays.circle?.radius = current.accuracyM
            }
            start()
        }
    }
    LaunchedEffect(map, facingDeg) { facingDeg?.let { userOverlays.marker?.rotation = it.toFloat() } }
    LaunchedEffect(map, loaded, destination, route, streetPosition, recenter, walking, follow, fix, facingDeg) {
        val googleMap = map ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        if (walking && !follow) return@LaunchedEffect
        if (!walking && previewFramed && framedRecenter == recenter) return@LaunchedEffect
        val projection = fix?.let { routeLine?.project(it.position) }
        val target = if (walking && fix != null) fix.position else streetPosition ?: destination.position
        val bearing = if (walking && facingDeg != null) facingDeg else if (walking) {
            val ahead = projection?.let { routeLine?.pointAt((it.alongTrackM + 18).coerceAtMost(routeLine.lengthM)) }
            if (ahead != null && Geo.distance(projection.snapped, ahead) > 1) Geo.bearing(projection.snapped, ahead)
            else projection?.segmentBearingDeg ?: fix?.courseDeg ?: 0.0
        } else streetPosition?.takeIf { Geo.distance(it, destination.position) > 2 }?.let { Geo.bearing(it, destination.position) } ?: 0.0
        val camera = CameraPosition.Builder().target(target.google()).zoom(if (walking) 19f else 18.5f).tilt(60f).bearing(bearing.toFloat()).build()
        val previous = googleMap.cameraPosition
        if (Geo.distance(LatLng(previous.target.latitude, previous.target.longitude), target) > .3 ||
            kotlin.math.abs(Geo.angleDiff(previous.bearing.toDouble(), bearing)) > 2 || previous.tilt < 40 || previous.zoom < 18)
            googleMap.animateCamera(CameraUpdateFactory.newCameraPosition(camera), if (walking && facingDeg != null) 120 else 750, null)
        if (!walking) { previewFramed = true; framedRecenter = recenter }
    }
}

private fun walkingPuck(density: Float): Bitmap {
    val size = (40 * density).toInt().coerceAtLeast(40)
    return createBitmap(size, size).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.scale(size / 40f, size / 40f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0x6630DBC5; canvas.drawCircle(20f, 20f, 19f, paint)
        val shape = Path().apply { moveTo(20f, 6f); lineTo(32f, 32f); lineTo(20f, 26f); lineTo(8f, 32f); close() }
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = 4f
        paint.color = 0xFF132133.toInt(); canvas.drawPath(shape, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFFCEFFF4.toInt(); canvas.drawPath(shape, paint)
    }
}

private const val DARK_MAP_STYLE = """[
 {"elementType":"geometry","stylers":[{"color":"#172338"}]},
 {"elementType":"labels.text.fill","stylers":[{"color":"#c5d6e8"}]},
 {"elementType":"labels.text.stroke","stylers":[{"color":"#132033"}]},
 {"featureType":"poi","elementType":"labels","stylers":[{"visibility":"off"}]},
 {"featureType":"transit","stylers":[{"visibility":"off"}]},
 {"featureType":"landscape.man_made","elementType":"geometry.fill","stylers":[{"color":"#293c56"}]},
 {"featureType":"landscape.man_made","elementType":"geometry.stroke","stylers":[{"color":"#58718e"}]},
 {"featureType":"road","elementType":"geometry","stylers":[{"color":"#52677d"}]},
 {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#8ba1b4"}]},
 {"featureType":"road","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
 {"featureType":"water","elementType":"geometry","stylers":[{"color":"#102b42"}]}
]"""
