package com.tobenamed.app.live

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tobenamed.app.BuildConfig
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.WalkingProgress
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.route.RouteStatus
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.FeatureCollection
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val SCENE_HOST = "appassets.androidplatform.net"
private const val SCENE_URL = "https://$SCENE_HOST/navigation3d/index.html"
private val SceneBackground = Color(0xFF141D31)

/** Bundled WebGL scene: high-pitch camera and solid, selectively occluded building materials. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Navigation3DMap(destination: AddressCandidate, route: WalkingRoute?, progress: WalkingProgress?, fix: LocationSample?,
    recenter: Int, modifier: Modifier = Modifier, streetPosition: LatLng? = null, onSelectStreet: ((LatLng) -> Unit)? = null,
    walking: Boolean = false, follow: Boolean = false, onBrowse: () -> Unit = {}, topInsetPx: Int = 0, facingDeg: Double? = null) {
    if (!BuildConfig.WEB_3D_SCENE) {
        NativeNavigation3DMap(destination, route, progress, fix, recenter, modifier, streetPosition, onSelectStreet, walking, follow, onBrowse, topInsetPx, facingDeg)
        return
    }
    var useNativeFallback by remember { mutableStateOf(false) }
    if (useNativeFallback) {
        NativeNavigation3DMap(destination, route, progress, fix, recenter, modifier, streetPosition, onSelectStreet, walking, follow, onBrowse, topInsetPx, facingDeg)
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val uriHandler = LocalUriHandler.current
    val pick by rememberUpdatedState(onSelectStreet)
    val browse by rememberUpdatedState(onBrowse)
    var pageReady by remember { mutableStateOf(false) }
    var surfaceReady by remember { mutableStateOf(false) }
    var pageGeneration by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }
    val alive = remember { AtomicBoolean(true) }
    val view = remember {
        WebView(context).apply {
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            setBackgroundColor(android.graphics.Color.rgb(20, 29, 49))
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        }
    }
    val bridge: NavigationSceneBridge = remember(view) {
        NavigationSceneBridge(view, alive,
            onReady = { ready = true; error = false }, onLoading = { ready = false; error = false }, onError = { useNativeFallback = true },
            onBrowse = { browse() }, onPick = { position -> pick?.invoke(position) })
    }
    val state = remember(destination, route, progress, fix, recenter, streetPosition, walking, follow, onSelectStreet != null, topInsetPx, density) {
        navigationScenePacket(destination, route, progress, fix, recenter, streetPosition, walking, follow,
            onSelectStreet != null, topInsetPx / density).toString()
    }
    DisposableEffect(view, lifecycle) {
        bridge.attach()
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // This bridge is available only to the bundled page. Links leave the WebView.
                if (request.url.host == SCENE_HOST && request.url.path == "/navigation3d/index.html") return false
                if (request.isForMainFrame && request.url.scheme == "https") uriHandler.openUri(request.url.toString())
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != SCENE_HOST) return null
                val path = request.url.path.orEmpty().removePrefix("/")
                val allowed = path in setOf("navigation-3d.json", "navigation3d/index.html", "navigation3d/scene.js",
                    "navigation3d/vendor/maplibre-gl.js", "navigation3d/vendor/maplibre-gl.css")
                if (!allowed) return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                val mime = when { path.endsWith(".html") -> "text/html"; path.endsWith(".json") -> "application/json"
                    path.endsWith(".css") -> "text/css"; else -> "text/javascript" }
                return WebResourceResponse(mime, "UTF-8", context.assets.open(path))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageGeneration++; pageReady = false; surfaceReady = false; ready = false
            }
            override fun onPageFinished(view: WebView, url: String?) { if (alive.get() && url == SCENE_URL) pageReady = true }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) {
                if (request.isForMainFrame && alive.get()) error = true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (alive.get()) useNativeFallback = true
                return true
            }
        }
        view.loadUrl(SCENE_URL)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; view.onResume(); view.evaluateJavascript("window.navigation3d?.resume()", null) }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; view.evaluateJavascript("window.navigation3d?.pause()", null); view.onPause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            alive.set(false); lifecycle.removeObserver(observer)
            view.removeJavascriptInterface("NativeScene"); view.stopLoading()
        }
    }
    LaunchedEffect(pageReady, viewportWidth, viewportHeight) {
        if (pageReady && viewportWidth > 0 && viewportHeight > 0) {
            val generation = pageGeneration
            view.post {
                if (alive.get() && view.isAttachedToWindow) {
                    // Page loading and JS animation frames do not fence Android's first visual frame.
                    view.postVisualStateCallback(generation.toLong(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            view.postOnAnimation {
                                if (alive.get() && pageReady && generation == pageGeneration) surfaceReady = true
                            }
                        }
                    })
                    view.postInvalidateOnAnimation()
                }
            }
        }
    }
    LaunchedEffect(state, pageReady, surfaceReady, viewportWidth, viewportHeight) {
        if (pageReady && surfaceReady && viewportWidth > 0 && viewportHeight > 0) {
            val sized = JSONObject(state).put("viewportHeight", viewportHeight).put("viewportWidth", viewportWidth)
            val encoded = JSONObject.quote(sized.toString())
            view.post { if (alive.get() && view.isAttachedToWindow)
                view.evaluateJavascript("window.navigation3d?.update(JSON.parse($encoded))", null) }
        }
    }
    LaunchedEffect(facingDeg, pageReady, surfaceReady, resumed) {
        if (pageReady && surfaceReady && resumed) {
            val heading = facingDeg?.takeIf { it.isFinite() }?.toString() ?: "null"
            // Orientation has its own channel: it must not restart GPS interpolation
            // or resend route/building payloads while the user turns in place.
            view.post { if (alive.get() && view.isAttachedToWindow)
                view.evaluateJavascript("window.navigation3d?.face($heading)", null) }
        }
    }
    LaunchedEffect(pageReady, ready, resumed) {
        if (pageReady && !ready && resumed) { kotlinx.coroutines.delay(30_000); if (!ready) useNativeFallback = true }
    }
    Box(modifier.background(SceneBackground)) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { view }, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize().onSizeChanged {
            viewportWidth = it.width / density; viewportHeight = it.height / density
        })
        if (!ready || error) Column(Modifier.align(Alignment.Center).background(SceneBackground.copy(alpha = .96f)).padding(18.dp)) {
            Text(if (error) "The 3D scene could not load." else "Loading 3D buildings…", color = Color(0xFFD8E9FF))
            if (error) {
                TextButton({ error = false; ready = false; view.reload() }) { Text("Retry 3D map") }
                TextButton({ useNativeFallback = true }) { Text("Use simpler 3D map") }
            }
        }
    }
}

/** Navigation state only; credentials and private delivery notes never cross the renderer bridge. */
internal fun navigationScenePacket(destination: AddressCandidate, route: WalkingRoute?, progress: WalkingProgress?, fix: LocationSample?,
    recenter: Int, street: LatLng?, walking: Boolean, follow: Boolean, picking: Boolean, topPadding: Float): JSONObject {
    fun LatLng.json() = JSONArray().put(lng).put(lat)
    val projection = fix?.let { route?.line?.project(it.position) }
    val matched = progress?.quality == FixQuality.GOOD && progress.route?.status == RouteStatus.ON_ROUTE && projection != null &&
        projection.distanceFromLineM <= maxOf(10.0, fix.accuracyM)
    val arrived = progress?.route?.status == RouteStatus.ARRIVED
    val position = fix?.position
    val target = if (walking && position != null) position else street ?: destination.position
    val ahead = projection?.let { route?.line?.pointAt((it.alongTrackM + 10).coerceAtMost(route.line.lengthM)) }
    val bearing = if (walking && !arrived) {
        if (matched && ahead != null && Geo.distance(projection.snapped, ahead) > 1) Geo.bearing(projection.snapped, ahead)
        else fix?.courseDeg ?: projection?.segmentBearingDeg ?: 0.0
    } else (if (walking) position else street)?.takeIf { Geo.distance(it, destination.position) > 2 }
        ?.let { Geo.bearing(it, destination.position) } ?: 0.0
    // The camera receives the raw position. Projection is only for route progress
    // and route geometry; it must not pull the viewpoint onto a road centreline.
    val focus = if (walking && !arrived && matched) Geo.offset(position!!, bearing, 6.0) else target
    val cameraTurn = if (walking && !arrived && matched) walkingCameraTurn(route!!.line, projection.alongTrackM) else 0.0
    val future = if (route != null && projection != null && !arrived) listOf(projection.snapped) + route.points.drop(projection.segmentIndex + 1) else emptyList()
    return JSONObject().put("destination", destination.position.json()).put("label", destination.streetNumber.ifBlank { "Building" })
        .put("street", street?.json() ?: JSONObject.NULL).put("walker", position?.json() ?: JSONObject.NULL)
        .put("walkerBearing", fix?.courseDeg ?: projection?.segmentBearingDeg ?: bearing)
        .put("focus", focus.json()).put("bearing", bearing).put("walking", walking).put("follow", follow).put("arrived", arrived)
        .put("cameraTurn", cameraTurn)
        .put("picking", picking).put("recenter", recenter).put("topPadding", topPadding.toDouble())
        .put("route", JSONArray().apply { route?.points?.forEach { put(it.json()) } })
        .put("future", JSONArray().apply { future.forEach { put(it.json()) } })
        .put("guiding", progress?.quality == FixQuality.GOOD && progress.route?.status == RouteStatus.ON_ROUTE)
}

/** Sample metres of geometry rather than individual vertices: dense tile/provider
 * points should not create camera jitter. The view opens before a bend and settles
 * after it; this affects framing only, never measured progress or turn instructions. */
internal fun walkingCameraTurn(line: Polyline, alongM: Double): Double {
    fun heading(from: Double, to: Double): Double? {
        val a = line.pointAt(from); val b = line.pointAt(to)
        return if (Geo.distance(a, b) > 1.0) Geo.bearing(a, b) else null
    }
    val near = heading(alongM - 6, alongM + 2) ?: return 0.0
    val ahead = heading(alongM + 14, alongM + 22) ?: return 0.0
    return ((kotlin.math.abs(Geo.angleDiff(ahead, near)) - 15) / 75).coerceIn(0.0, 1.0)
}

@androidx.annotation.Keep
internal class NavigationSceneBridge(private val view: WebView, private val alive: AtomicBoolean,
    private val onReady: () -> Unit, private val onLoading: () -> Unit, private val onError: () -> Unit,
    private val onBrowse: () -> Unit, private val onPick: (LatLng) -> Unit) {
    fun attach() { view.addJavascriptInterface(this, "NativeScene") }
    private fun post(action: () -> Unit) { view.post { if (alive.get()) action() } }
    @JavascriptInterface fun ready() = post(onReady)
    @JavascriptInterface fun loading() = post(onLoading)
    @JavascriptInterface fun failed() = post(onError)
    @JavascriptInterface fun frame() = post { view.postInvalidateOnAnimation() }
    @JavascriptInterface fun browse() = post(onBrowse)
    @JavascriptInterface fun pick(lng: Double, lat: Double) {
        if (lng.isFinite() && lat.isFinite() && lng in -180.0..180.0 && lat in -90.0..90.0) post { onPick(LatLng(lat, lng)) }
    }
    @JavascriptInterface fun mesh(payload: String) {
        if (!alive.get() || payload.length > 2_000_000) return
        runCatching {
            val data = JSONObject(payload)
            val center = data.getJSONArray("center"); val destination = data.getJSONArray("destination")
            fun point(a: JSONArray): LatLng {
                val lng = a.getDouble(0); val lat = a.getDouble(1)
                require(lng.isFinite() && lat.isFinite() && lng in -180.0..180.0 && lat in -90.0..90.0)
                return LatLng(lat, lng)
            }
            val features = FeatureCollection.fromJson(data.getJSONObject("features").toString()).features().orEmpty().take(1500)
            val width = data.getDouble("stroke").takeIf { it.isFinite() } ?: .16
            val mesh = BuildingGeometry.build(features, point(center), point(destination), width, solidScene = true)
            val result = JSONObject().put("generation", data.getInt("generation")).put("edges", JSONObject(mesh.edges.toJson()))
                .put("bodies", JSONObject(mesh.bodies.toJson())).put("selected", JSONObject(mesh.selected.toJson()))
            val encoded = JSONObject.quote(result.toString())
            post { view.evaluateJavascript("window.navigation3d?.mesh(JSON.parse($encoded))", null) }
        }
    }
}
