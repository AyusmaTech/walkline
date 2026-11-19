package com.tobenamed.app

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobenamed.app.live.GoogleWalkingMap
import com.tobenamed.app.live.Navigation3DMap
import com.tobenamed.app.live.NavigationStreetView
import com.tobenamed.app.live.SceneAvailability
import com.tobenamed.app.live.StreetSceneStatus
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.ManeuverCue
import com.tobenamed.nav.live.WalkingSession
import com.tobenamed.nav.route.RouteStatus
import com.tobenamed.nav.streetview.StreetScenePlanner
import kotlin.math.roundToInt

private val SceneInk = Color(0xFF101C1B)
private val SceneAmber = Color(0xFFFFCD87)

/** Primary walking surface: a panorama viewport with compact guidance over the street. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetNavigationScreen(
    state: WalkUiState,
    onLocation: () -> Unit,
    onSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onCue: (WalkCue) -> Unit,
    onSelectStreet: (LatLng) -> Unit,
) {
    val live = state.live
    val destination = live.destination ?: return
    val walking = state.screen == Screen.LIVE_WALK
    val progress = live.progress
    val usable = progress?.quality == FixQuality.GOOD
    val status = progress?.route?.status
    var facingDeg by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(live.facing, live.locationRequested) {
        val sample = live.facing?.takeIf { live.locationRequested && it.isFresh(SystemClock.elapsedRealtime()) }
        facingDeg = sample?.bearingDeg
        if (sample != null) {
            kotlinx.coroutines.delay((com.tobenamed.app.live.DeviceFacing.MAX_AGE_MS -
                (SystemClock.elapsedRealtime() - sample.timeMs)).coerceAtLeast(0))
            facingDeg = null
        }
    }
    var manualMap by rememberSaveable { mutableStateOf(true) }
    var buildingView by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var selectingStreet by rememberSaveable { mutableStateOf(false) }
    var pendingStreet by remember { mutableStateOf<LatLng?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var recenter by remember { mutableIntStateOf(0) }
    var sceneStatus by remember(destination.placeId, live.streetApproach?.position) { mutableStateOf(StreetSceneStatus(SceneAvailability.LOADING)) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    var topContentHeightPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { if (dockHeightPx == 0) 190.dp else dockHeightPx.toDp() }
    val frame = remember(destination.position, live.route, progress, walking, buildingView, live.streetApproach?.position) {
        StreetScenePlanner.plan(destination.position, live.route, progress, buildingView = !walking || buildingView,
            streetPosition = live.streetApproach?.position)
    }
    val unavailable = sceneStatus.availability in setOf(SceneAvailability.UNAVAILABLE, SceneAvailability.NOT_CONFIGURED)
    val mapShown = manualMap || unavailable || selectingStreet || live.streetApproach == null
    val canStart = live.streetApproach != null && live.locationRequested && live.fix?.let {
        SystemClock.elapsedRealtime() - it.timeMs <= WalkingSession.MAX_FIX_AGE_MS && it.accuracyM <= WalkingSession.MAX_GUIDANCE_ACCURACY_M
    } == true
    LaunchedEffect(walking) { buildingView = false; browsing = false }
    val cue = if (!walking || !usable) WalkCue.NONE else when (status) {
        RouteStatus.WRONG_WAY -> WalkCue.TURN_AROUND
        RouteStatus.ARRIVED -> WalkCue.ARRIVED
        else -> WalkCue.NONE
    }
    var previousCue by remember { mutableStateOf(WalkCue.NONE) }
    LaunchedEffect(cue) { if (cue != previousCue) onCue(cue); previousCue = cue }
    val announcedTurns = remember(live.route) { mutableSetOf<Int>() }
    val nearTurn = progress?.instructionIndex?.takeIf {
        walking && usable && status == RouteStatus.ON_ROUTE && progress.instructionIsUpcoming &&
            (progress.distanceToInstructionM ?: Double.MAX_VALUE) <= 15 &&
            ManeuverCue.fromProvider(progress.maneuver) != ManeuverCue.STRAIGHT
    }
    LaunchedEffect(nearTurn) { if (nearTurn != null && announcedTurns.add(nearTurn)) onCue(WalkCue.TURN) }
    val viewpointOffset = if (mapShown) null else sceneStatus.panoramaPosition?.let { pano -> live.fix?.let { Geo.distance(pano, it.position).roundToInt() } }

    Box(Modifier.fillMaxSize().background(SceneInk)) {
        Box(Modifier.fillMaxSize().padding(bottom = dockHeight)) {
            if (live.streetApproach != null && !mapShown) NavigationStreetView(destination, frame, follow = !browsing,
                allowSearch = !manualMap && !selectingStreet && !browsing && (!walking || usable), retry = retry,
                onBrowse = { browsing = true }, onStatus = { sceneStatus = it }, modifier = Modifier.fillMaxSize(), topInsetPx = topContentHeightPx,
                facingDeg = facingDeg.takeIf { walking && !buildingView })
            if (mapShown) {
                Box(Modifier.fillMaxSize().background(SceneInk), contentAlignment = Alignment.Center) {
                    if (live.source != "google") Navigation3DMap(destination, live.route, progress, live.fix, recenter,
                        modifier = Modifier.fillMaxSize(), streetPosition = pendingStreet ?: live.streetApproach?.position,
                        onSelectStreet = if (selectingStreet) { point -> pendingStreet = point } else null,
                        walking = walking && !selectingStreet, follow = walking && usable && !browsing && !selectingStreet,
                        onBrowse = { browsing = true }, topInsetPx = topContentHeightPx, facingDeg = facingDeg)
                    else if (BuildConfig.MAPS_CONFIGURED) GoogleWalkingMap(destination, live.route?.points, live.fix, recenter,
                        modifier = Modifier.fillMaxSize(), streetPosition = pendingStreet ?: live.streetApproach?.position,
                        onSelectStreet = if (selectingStreet) { point -> pendingStreet = point } else null,
                        walking = walking && !selectingStreet, follow = walking && usable && !browsing && !selectingStreet,
                        onBrowse = { browsing = true }, topInsetPx = topContentHeightPx, facingDeg = facingDeg)
                    else Text("This legacy route needs Google map setup. Use the OpenStreetMap service for 3D navigation.", Modifier.padding(32.dp), color = SceneAmber)
                }
            } else if (sceneStatus.availability == SceneAvailability.LOADING) {
                Column(Modifier.align(Alignment.Center).background(SceneInk.copy(alpha = .9f), RoundedCornerShape(20.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("Loading Street View…")
                }
            }
        }
        // Only the top controls sit over imagery. The native attribution sits above the dock.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(SceneInk.copy(alpha = .9f), Color.Transparent)))
            .onSizeChanged { topContentHeightPx = it.height }
            .statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SceneAction("Back", NavIcon.BACK, onBack, iconOnly = true)
                Spacer(Modifier.weight(1f))
                Text(if (mapShown) "3D MAP" else "STREET VIEW", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val mapLabel = if (selectingStreet) "Cancel" else if (live.streetApproach == null) "Choose street"
                    else if (manualMap) "Street View" else if (unavailable) "Retry view" else "3D Map"
                val mapIcon = if (selectingStreet) NavIcon.CLOSE else if (manualMap || unavailable) NavIcon.STREET_VIEW else NavIcon.MAP
                SceneAction(mapLabel, mapIcon, {
                    if (selectingStreet) { selectingStreet = false; pendingStreet = null }
                    else if (live.streetApproach == null) selectingStreet = true
                    else if (manualMap || unavailable) {
                        manualMap = false; browsing = false
                        sceneStatus = StreetSceneStatus(SceneAvailability.LOADING); retry++
                    } else { manualMap = true; browsing = false; buildingView = false }
                })
            }
            if (walking && !selectingStreet) StreetGuidanceCard(state)
            else Surface(color = SceneInk.copy(alpha = .91f), shape = RoundedCornerShape(14.dp)) {
                Text(when {
                    selectingStreet -> "Tap the street outside the building"
                    live.busy && live.streetApproach == null -> "Finding the street outside the building…"
                    live.streetApproach == null -> "Street spot needs confirmation"
                    mapShown -> "3D preview · destination area"
                    else -> "Street preview · facing the building"
                }, Modifier.padding(12.dp), fontSize = 13.sp)
            }
            val imageNote = when {
                live.source == "fixture" -> "TEST SERVICE · synthetic route data"
                selectingStreet -> "Amber pin: building · green pin: street spot"
                live.streetApproach == null -> live.approachMessage ?: "Looking up the address street"
                unavailable && !manualMap -> "Street View unavailable · map fallback"
                mapShown && walking && browsing -> "Looking around · tap Follow walk"
                mapShown && walking && !usable -> "Guidance paused · waiting for GPS"
                mapShown && walking -> if (facingDeg != null) {
                    if (live.testLocation) "Test GPS · phone facing" else "Following phone"
                } else if (live.testLocation) "Test GPS · route facing" else "Following route"
                mapShown -> if (live.testLocation) "Test GPS · 3D map" else "3D map"
                browsing -> "Looking around · tap Follow walk"
                walking && buildingView -> "Building view · walk still tracking"
                walking && !usable -> "Guidance paused · waiting for GPS"
                walking && status == RouteStatus.ARRIVED -> "Recorded view · street spot reached"
                walking -> if (live.testLocation) "Recorded · following test GPS" else "Recorded · following GPS"
                else -> "Recorded · street preview"
            }
            SceneStatusPill(imageNote, attention = live.source == "fixture" || unavailable || browsing || walking && !usable)
            live.error?.let { Text(it, fontSize = 12.sp, color = SceneAmber,
                modifier = Modifier.background(SceneInk.copy(alpha = .95f), RoundedCornerShape(12.dp)).padding(12.dp)) }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = dockHeight + 48.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mapShown) SceneAction(if (walking) "Follow walk" else "Recenter", NavIcon.LOCATION,
                { browsing = false; recenter++ }, prominent = walking && browsing)
            else if (browsing || buildingView) SceneAction(if (walking) "Follow walk" else "Face building", NavIcon.FOLLOW, {
                browsing = false; buildingView = false; retry++
            }, prominent = true)
            if (walking && status != RouteStatus.ARRIVED && !buildingView && !mapShown) SceneAction("View building", NavIcon.HOUSE,
                { buildingView = true; browsing = false; retry++ })
            if (walking && mapShown && !selectingStreet) SceneAction("View building", NavIcon.HOUSE, {
                manualMap = false; buildingView = true; browsing = false
                sceneStatus = StreetSceneStatus(SceneAvailability.LOADING); retry++
            })
            if (!walking && live.streetApproach != null && !selectingStreet) SceneAction("Change street", NavIcon.MAP, {
                pendingStreet = live.streetApproach.position; selectingStreet = true
            })
        }
        Surface(color = SceneInk, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockHeightPx = it.height }) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DestinationHeading(destination) { details = true }
                if (selectingStreet) {
                    val selectionValid = pendingStreet?.let { Geo.distance(it, destination.position) <= 150 } == true
                    Text(if (pendingStreet != null && !selectionValid) "Pick a spot within 150 m of the building."
                        else "Choose where you'd stand on the street to look at the building.", color = SceneAmber, fontSize = 12.sp)
                    PrimaryButton("Use this street spot", {
                        pendingStreet?.let { onSelectStreet(it) }
                        selectingStreet = false; pendingStreet = null; browsing = false; retry++
                    }, enabled = selectionValid)
                } else if (walking) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val remaining = progress?.route?.remainingM ?: live.route?.line?.lengthM?.let {
                            it + if (progress?.joiningRoute == true) progress.distanceToInstructionM ?: 0.0 else 0.0
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(if (status == RouteStatus.ARRIVED) "Arrived" else "${remaining?.roundToInt() ?: "—"} m",
                                fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                            Text(if (status == RouteStatus.ARRIVED) "At the street spot" else "to street spot",
                                color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                        }
                        OutlinedButton(onBack, Modifier.heightIn(min = 48.dp)) {
                            NavigationIcon(NavIcon.CLOSE, MaterialTheme.colorScheme.primary, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("End walk", fontSize = 12.sp)
                        }
                    }
                } else {
                    if (live.locationMessage != null) Text(live.locationMessage, color = SceneAmber, fontSize = 12.sp, maxLines = 2)
                    if (live.streetApproach == null) PrimaryButton(if (live.busy) "Finding street…" else "Choose street",
                        { selectingStreet = true }, enabled = !live.busy && (BuildConfig.MAPS_CONFIGURED || live.source != "google"))
                    else {
                        Text(if (live.streetApproach.selectedByUser) "Street spot selected by you" else "Outside on ${live.streetApproach.streetName}",
                            color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, maxLines = 1)
                        PrimaryButton(if (live.busy) "Finding walking route…" else if (canStart) "Start walk" else "Get my location",
                            if (canStart) onStart else onLocation, enabled = !live.busy && (BuildConfig.MAPS_CONFIGURED || live.source != "google"))
                    }
                }
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }, containerColor = SceneInk) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Walk details", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(destination.address)
            if (live.source == "openstreetmap") {
                Text("Map/address data: © OpenStreetMap contributors. Tiles: OpenFreeMap. Walking routes: FOSSGIS / OSRM.", color = MaterialTheme.colorScheme.secondary)
                Text("Building heights may be approximated. Street levels and entrances are not confirmed by the 3D model.", color = MaterialTheme.colorScheme.secondary)
                Text("This pilot sends address searches to Photon and route requests to FOSSGIS. Those services have their own request logging and availability policies.", color = MaterialTheme.colorScheme.secondary)
            }
            if (state.deliveryDetails.isNotBlank()) Text(state.deliveryDetails)
            if (destination.precision.uncertaintyM > 5) Text("Approximate address area. Confirm the building number.", color = SceneAmber)
            else Text("The marker identifies the building. Guidance ends at the selected street spot outside it.", color = MaterialTheme.colorScheme.secondary)
            live.streetApproach?.let { Text(if (it.selectedByUser) "Street spot selected by you." else "Address street: ${it.streetName}") }
            OutlinedButton({ details = false; pendingStreet = live.streetApproach?.position; selectingStreet = true }, Modifier.fillMaxWidth()) {
                Text("Change street spot")
            }
            live.route?.warnings?.forEach { Text(it, color = SceneAmber) }
            progress?.let {
                Text("Route instruction", fontWeight = FontWeight.SemiBold)
                Text(it.instruction)
                if (it.instructionIsUpcoming) it.distanceToInstructionM?.let { distance -> Text("Next action in ${distance.roundToInt()} m") }
            }
            viewpointOffset?.let { Text("Photographed viewpoint: about $it m from GPS.", color = MaterialTheme.colorScheme.secondary) }
            Text("Street View contains recorded imagery. Image date is unavailable here; arrows and building markers are approximate.", color = MaterialTheme.colorScheme.secondary)
            Text("Street side and a safe crossing point have not been confirmed. Check traffic and signals.", color = MaterialTheme.colorScheme.secondary)
            LocationStatusCard(live, onLocation, onSettings, onAppSettings)
            if (walking) OutlinedButton(onStart, Modifier.fillMaxWidth(), enabled = !live.busy) { Text("Refresh walking route") }
            TextButton({ details = false }) { Text("Close details") }
        }
    }
}
