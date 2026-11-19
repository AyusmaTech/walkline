package com.tobenamed.app.live

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.StreetViewPanorama
import com.google.android.gms.maps.StreetViewPanoramaOptions
import com.google.android.gms.maps.StreetViewPanoramaView
import com.google.android.gms.maps.model.StreetViewPanoramaCamera
import com.google.android.gms.maps.model.StreetViewPanoramaLocation
import com.google.android.gms.maps.model.StreetViewPanoramaOrientation
import com.google.android.gms.maps.model.StreetViewSource
import com.tobenamed.app.BuildConfig
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.marker.MarkerPlanner
import com.tobenamed.nav.marker.MarkerStyle
import com.tobenamed.nav.marker.PanoCandidate
import com.tobenamed.nav.streetview.StreetScene
import com.tobenamed.nav.streetview.StreetSceneMode
import com.tobenamed.nav.streetview.StreetScenePlanner
import com.tobenamed.nav.streetview.StreetSceneRequestGate
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class SceneAvailability { LOADING, AVAILABLE, UNAVAILABLE, NOT_CONFIGURED }
data class StreetSceneStatus(val availability: SceneAvailability, val panoramaPosition: LatLng? = null)

private class InteractivePanorama(context: Context, options: StreetViewPanoramaOptions) : StreetViewPanoramaView(context, options) {
    constructor(context: Context) : this(context, StreetViewPanoramaOptions())
    var onUserTouch: () -> Unit = {}
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserTouch()
        return super.dispatchTouchEvent(event)
    }
}

/** A single activity-lifetime panorama viewport, reused while the walk advances. */
@Composable
fun NavigationStreetView(
    destination: AddressCandidate,
    scene: StreetScene,
    follow: Boolean,
    allowSearch: Boolean,
    retry: Int,
    onBrowse: () -> Unit,
    onStatus: (StreetSceneStatus) -> Unit,
    modifier: Modifier = Modifier,
    topInsetPx: Int = 0,
    facingDeg: Double? = null,
) {
    val statusCallback by rememberUpdatedState(onStatus)
    if (!BuildConfig.MAPS_CONFIGURED) {
        LaunchedEffect(Unit) { statusCallback(StreetSceneStatus(SceneAvailability.NOT_CONFIGURED)) }
        return
    }
    val context = LocalContext.current
    val registry = LocalSaveableStateRegistry.current
    val frame by rememberUpdatedState(scene)
    val following by rememberUpdatedState(follow)
    val browseCallback by rememberUpdatedState(onBrowse)
    val currentDestination by rememberUpdatedState(destination)
    val view = remember {
        // A restored panorama can belong to an old address. Start from the filtered request.
        registry?.consumeRestored("navigation-panorama")
        InteractivePanorama(context, StreetViewPanoramaOptions()
            .position(scene.queryPosition.googlePoint(), scene.searchRadiusM, StreetViewSource.OUTDOOR)
            .streetNamesEnabled(false)
            .userNavigationEnabled(false)).apply { onCreate(null) }
    }
    val adapter = remember(view) { SdkLifecycle(view::onStart, view::onResume, view::onPause, view::onStop,
        view::onDestroy, view::onLowMemory, view::onSaveInstanceState) }
    BindSdkLifecycle("navigation-panorama", adapter)
    val gate = remember { StreetSceneRequestGate() }
    var panorama by remember { mutableStateOf<StreetViewPanorama?>(null) }
    var panoramaPosition by remember { mutableStateOf<LatLng?>(null) }
    var projected by remember { mutableStateOf(ProjectedStreetScene()) }
    var photoRevision by remember { mutableIntStateOf(0) }
    val lastPhoto = remember { arrayOfNulls<String>(1) }
    val projectionClock = remember { longArrayOf(0) }
    var requestTime by remember { mutableStateOf<Long?>(null) }
    var receivedTime by remember { mutableStateOf<Long?>(null) }
    var lastRetry by remember { mutableIntStateOf(-1) }
    var requestPending by remember { mutableStateOf(false) }
    var requestedScene by remember { mutableStateOf<StreetScene?>(null) }
    var alive by remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(view) { onDispose { alive = false } }

    fun project(force: Boolean = false) {
        val p = panorama ?: return
        val origin = panoramaPosition ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - projectionClock[0] < 40) return
        projectionClock[0] = now
        val cache = mutableMapOf<LatLng, Offset?>()
        fun ground(point: LatLng): Offset? {
            return cache.getOrPut(point) {
                val distance = Geo.distance(origin, point)
                if (distance < 2 || distance > 90) null else
                    p.orientationToPoint(StreetViewPanoramaOrientation(StreetScenePlanner.groundPitchDeg(distance).toFloat(),
                        Geo.bearing(origin, point).toFloat()))?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            }
        }
        fun patch(outline: List<LatLng>, distance: Double): ProjectedPatch? {
            if (outline.size < 3) return null
            val vertices = outline.map { ground(it) ?: return null }
            if (vertices.maxOf { it.x } - vertices.minOf { it.x } > view.width * 1.5f ||
                vertices.maxOf { it.y } - vertices.minOf { it.y } > view.height * 1.5f) return null
            return ProjectedPatch(vertices, (1.0 - distance / 100.0).coerceIn(.4, 1.0).toFloat())
        }
        val ribbon = frame.ribbon.mapNotNull { patch(it.outline, Geo.distance(origin, it.outline[0])) }
        val direction = frame.guidanceBearingDeg?.takeIf { following }?.let {
            Geo.angleDiff(it, p.panoramaCamera.bearing.toDouble()).toFloat()
        }
        val plan = MarkerPlanner.plan(PanoCandidate("current", origin), currentDestination.position, currentDestination.precision)
        val marker = p.orientationToPoint(StreetViewPanoramaOrientation(plan.pitchDeg.toFloat(), plan.headingDeg.toFloat()))
        val building = marker?.takeIf { frame.mode == StreetSceneMode.BUILDING || plan.distanceM <= 65 }?.let {
            ProjectedBuilding(Offset(it.x.toFloat(), it.y.toFloat()), plan.style is MarkerStyle.Band)
        }
        projected = ProjectedStreetScene(ribbon = ribbon, building = building, directionOffsetDeg = direction,
            routeProgressM = frame.guidanceProgressM)
    }

    Box(modifier) {
        AndroidView(factory = {
            view.apply {
                onUserTouch = { browseCallback() }
                getStreetViewPanoramaAsync { p ->
                    if (alive) {
                        panorama = p
                        p.setOnStreetViewPanoramaChangeListener { location: StreetViewPanoramaLocation? ->
                            if (alive) {
                                receivedTime = requestTime
                                requestPending = false
                                if (location == null) {
                                    projected = ProjectedStreetScene()
                                    statusCallback(StreetSceneStatus(SceneAvailability.UNAVAILABLE))
                                } else {
                                    val position = LatLng(location.position.latitude, location.position.longitude)
                                    val requested = requestedScene ?: frame
                                    if (Geo.distance(position, requested.queryPosition) > requested.searchRadiusM + 2 ||
                                        Geo.distance(position, frame.queryPosition) > frame.searchRadiusM + 2) {
                                        projected = ProjectedStreetScene()
                                        statusCallback(StreetSceneStatus(SceneAvailability.UNAVAILABLE))
                                    } else {
                                        panoramaPosition = position
                                        if (lastPhoto[0] != location.panoId) { lastPhoto[0] = location.panoId; photoRevision++ }
                                        statusCallback(StreetSceneStatus(SceneAvailability.AVAILABLE, position))
                                        project(force = true)
                                    }
                                }
                            }
                        }
                        p.setOnStreetViewPanoramaCameraChangeListener { if (alive) project() }
                    }
                }
            }
        }, update = { it.onUserTouch = { browseCallback() } }, modifier = Modifier.fillMaxSize())
        PanoramaRouteOverlay(projected, destination.streetNumber, topInsetPx, photoRevision)
    }
    LaunchedEffect(panorama, allowSearch, retry) {
        val p = panorama ?: return@LaunchedEffect
        if (!allowSearch) return@LaunchedEffect
        // Follow the newest GPS scene after the previous lookup finishes. The timer also
        // services a final movement that happened just before the rate limit expired.
        while (true) {
            val current = frame
            if (!requestPending && gate.shouldRequest(current, SystemClock.elapsedRealtime(), retry != lastRetry)) {
                lastRetry = retry
                requestedScene = current
                requestPending = true
                requestTime = SystemClock.elapsedRealtime()
                if (panoramaPosition == null) statusCallback(StreetSceneStatus(SceneAvailability.LOADING))
                p.setPosition(current.queryPosition.googlePoint(), current.searchRadiusM, StreetViewSource.OUTDOOR)
            }
            delay(500)
        }
    }
    LaunchedEffect(requestTime) {
        val started = requestTime ?: return@LaunchedEffect
        delay(15_000)
        if (receivedTime != started) {
            requestPending = false
            projected = ProjectedStreetScene()
            statusCallback(StreetSceneStatus(SceneAvailability.UNAVAILABLE))
        }
    }
    LaunchedEffect(panorama, panoramaPosition, scene.focus, scene.fallbackBearingDeg, follow, retry, facingDeg) {
        val p = panorama ?: return@LaunchedEffect
        val position = panoramaPosition ?: return@LaunchedEffect
        p.isUserNavigationEnabled = !follow
        if (follow) {
            val heading = facingDeg ?: if (scene.mode == StreetSceneMode.WALK) scene.fallbackBearingDeg else
                if (Geo.distance(position, scene.focus) > 2) Geo.bearing(position, scene.focus) else scene.fallbackBearingDeg
            val pitch = if (scene.mode == StreetSceneMode.WALK) -7f else 2f
            if (abs(Geo.angleDiff(p.panoramaCamera.bearing.toDouble(), heading)) > (if (facingDeg != null) 1.5 else 4.0) || abs(p.panoramaCamera.tilt - pitch) > 2) {
                p.animateTo(StreetViewPanoramaCamera.Builder().bearing(heading.toFloat()).tilt(pitch).zoom(0f).build(), if (facingDeg != null) 120 else 450)
            }
        }
        project(force = true)
    }
    LaunchedEffect(scene.guidanceBearingDeg, scene.guidanceProgressM, scene.ribbon, destination) { project(force = true) }
}

private fun LatLng.googlePoint() = com.google.android.gms.maps.model.LatLng(lat, lng)
