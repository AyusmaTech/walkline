package com.tobenamed.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.app.live.HttpWalkingRepository
import com.tobenamed.app.live.LiveUiState
import com.tobenamed.app.live.DeviceFacing
import com.tobenamed.app.live.WalkingRepository
import com.tobenamed.app.live.NavigationError
import com.tobenamed.app.live.StreetApproach
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.WalkingSession
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.route.RouteStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen { INPUT, ADDRESS, PREVIEW, WALK, MATCHES, LIVE_PREVIEW, LIVE_WALK }

data class WalkUiState(
    val screen: Screen = Screen.INPUT,
    val address: String = "",
    val inputError: String? = null,
    val walk: DemoWalk.Snapshot? = null,
    val deliveryDetails: String = "",
    val live: LiveUiState = LiveUiState(),
)

class WalkViewModel(
    private val savedState: SavedStateHandle,
    private val repository: WalkingRepository,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    constructor(savedState: SavedStateHandle) : this(savedState, HttpWalkingRepository())

    private val mutableState = MutableStateFlow(WalkUiState(address = savedState["address"] ?: "",
        deliveryDetails = savedState["deliveryDetails"] ?: ""))
    val state = mutableState.asStateFlow()
    private var demo: DemoWalk? = null
    private var liveSession: WalkingSession? = null
    private var request: Job? = null
    private var generation = 0L
    private var lastRouteRequestMs: Long? = null

    fun editAddress(text: String) {
        val address = text.take(MAX_ADDRESS_LENGTH)
        savedState["address"] = address
        mutableState.value = mutableState.value.copy(address = address, inputError = null)
    }

    fun receiveAddress(text: String) {
        // Shares are reviewed before use, including when the activity is already open.
        demo = null
        cancelLive()
        editAddress(text)
        editDeliveryDetails("")
        mutableState.value = mutableState.value.copy(screen = Screen.INPUT, walk = null, live = LiveUiState())
    }

    fun reviewAddress() {
        val address = state.value.address.trim()
        if (address.isBlank()) {
            mutableState.value = state.value.copy(inputError = "Paste or enter a delivery address.")
            return
        }
        editAddress(address)
        mutableState.value = state.value.copy(screen = Screen.ADDRESS)
    }

    fun showDemo() {
        cancelLive()
        mutableState.value = state.value.copy(screen = Screen.PREVIEW, inputError = null, live = LiveUiState())
    }

    fun startDemo() {
        val session = DemoWalk().also { demo = it }
        mutableState.value = state.value.copy(screen = Screen.WALK, walk = session.snapshot)
    }

    fun advance(forward: Boolean) {
        mutableState.value = state.value.copy(walk = demo?.advance(forward))
    }

    fun crossStreet() { mutableState.value = state.value.copy(walk = demo?.crossStreet()) }

    fun back() {
        val previousScreen = state.value.screen
        cancelLive()
        mutableState.value = state.value.copy(
            screen = when (previousScreen) {
                Screen.WALK -> Screen.PREVIEW
                Screen.LIVE_WALK -> Screen.LIVE_PREVIEW
                Screen.LIVE_PREVIEW -> Screen.MATCHES
                Screen.MATCHES -> Screen.ADDRESS
                else -> Screen.INPUT
            },
            walk = null,
            live = state.value.live.copy(busy = false, locationRequested = false, route = null, progress = null, error = null),
        )
        demo = null
    }

    fun editDeliveryDetails(text: String) {
        val details = text.take(MAX_ADDRESS_LENGTH)
        savedState["deliveryDetails"] = details
        mutableState.value = state.value.copy(deliveryDetails = details)
    }

    fun resolveAddress() {
        if (state.value.address.trim().length < 3) {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = "Enter a complete street address."))
            return
        }
        repository.configurationError?.let {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = it))
            return
        }
        cancelLive()
        val requestGeneration = generation
        val address = state.value.address.trim()
        mutableState.value = state.value.copy(live = state.value.live.copy(busy = true, error = null, candidates = emptyList()))
        request = viewModelScope.launch {
            try {
                val result = repository.resolve(address)
                if (generation != requestGeneration) return@launch
                mutableState.value = state.value.copy(
                    screen = if (result.candidates.isEmpty()) Screen.ADDRESS else Screen.MATCHES,
                    live = state.value.live.copy(candidates = result.candidates, source = result.source, busy = false,
                        error = if (result.candidates.isEmpty()) "No address matched. Check the street, city, and postcode." else null),
                )
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == requestGeneration) setError(error) }
        }
    }

    fun chooseDestination(candidate: AddressCandidate) {
        cancelLive()
        val requestGeneration = generation
        mutableState.value = state.value.copy(screen = Screen.LIVE_PREVIEW,
            live = state.value.live.copy(destination = candidate, streetApproach = null, approachMessage = null,
                busy = true, route = null, progress = null, error = null))
        request = viewModelScope.launch {
            try {
                val approach = repository.streetApproach(candidate)
                if (generation != requestGeneration) return@launch
                mutableState.value = state.value.copy(live = state.value.live.copy(streetApproach = approach, busy = false,
                    approachMessage = if (approach == null) "Choose the street outside the building on the map." else null))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == requestGeneration) mutableState.value = state.value.copy(live = state.value.live.copy(busy = false,
                    approachMessage = "Couldn't identify the street automatically. Choose it on the map."))
            }
        }
    }

    fun selectStreet(position: LatLng) {
        val destination = state.value.live.destination ?: return
        if (Geo.distance(position, destination.position) > 150) {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = "Choose a street spot within 150 m of the building."))
            return
        }
        cancelLive()
        mutableState.value = state.value.copy(screen = Screen.LIVE_PREVIEW, live = state.value.live.copy(
            streetApproach = StreetApproach(position, destination.streetNames.firstOrNull() ?: "Selected street", selectedByUser = true),
            approachMessage = null, route = null, progress = null, busy = false, error = null))
    }

    fun beginLocationTracking() {
        mutableState.value = state.value.copy(live = state.value.live.copy(locationRequested = true,
            locationMessage = "Waiting for a fresh location…", error = null, facing = null))
    }

    fun locationProblem(message: String) {
        mutableState.value = state.value.copy(live = state.value.live.copy(locationMessage = message))
    }

    fun onLocation(sample: LocationSample, testLocation: Boolean = false) {
        if (!state.value.live.locationRequested) return
        val now = clockMs()
        if (sample.timeMs > now + 1_000 || now - sample.timeMs > WalkingSession.MAX_FIX_AGE_MS) return
        if (state.value.live.fix?.let { sample.timeMs <= it.timeMs } == true) return
        val progress = liveSession?.update(sample, now)
        mutableState.value = state.value.copy(live = state.value.live.copy(fix = sample, testLocation = testLocation,
            progress = progress, locationMessage = if (sample.accuracyM > WalkingSession.MAX_GUIDANCE_ACCURACY_M)
                "Location is approximate or weak. Enable precise location and wait for GPS to improve." else null))
        if (state.value.screen == Screen.LIVE_WALK && progress?.quality == FixQuality.GOOD &&
            progress.route?.status == RouteStatus.OFF_ROUTE && !state.value.live.busy &&
            lastRouteRequestMs?.let { now - it >= 30_000 } != false) requestRoute(rerouting = true)
    }

    fun onFacing(sample: DeviceFacing?) {
        val live = state.value.live
        if (!live.locationRequested) return
        if (sample != null && (!sample.isFresh(clockMs()) || live.facing?.let { sample.timeMs <= it.timeMs } == true)) return
        // A compass event must never refresh GPS age, feed the route tracker, or reroute.
        mutableState.value = state.value.copy(live = live.copy(facing = sample))
    }

    fun refreshLocationAge() {
        val now = clockMs()
        if (state.value.live.facing?.isFresh(now) == false)
            mutableState.value = state.value.copy(live = state.value.live.copy(facing = null))
        val progress = liveSession?.expire(now)
        if (state.value.live.fix?.let { now - it.timeMs > WalkingSession.MAX_FIX_AGE_MS } == true) {
            mutableState.value = state.value.copy(live = state.value.live.copy(progress = progress,
                locationMessage = "Location is stale. Waiting for a fresh GPS fix."))
        }
    }

    fun pauseLocation() {
        liveSession?.pause()
        mutableState.value = state.value.copy(live = state.value.live.copy(facing = null))
        if (state.value.live.locationRequested) mutableState.value = state.value.copy(live = state.value.live.copy(
            progress = liveSession?.progress, locationMessage = "Location updates pause while the app is in the background."))
    }

    fun startLiveWalk() = requestRoute(rerouting = false)

    private fun requestRoute(rerouting: Boolean) {
        val destination = state.value.live.destination ?: return
        val approach = state.value.live.streetApproach ?: run {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = "Choose the street outside the building first."))
            return
        }
        val fix = state.value.live.fix
        val now = clockMs()
        if (!state.value.live.locationRequested || fix == null || now - fix.timeMs > WalkingSession.MAX_FIX_AGE_MS || fix.accuracyM > WalkingSession.MAX_GUIDANCE_ACCURACY_M) {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = "A fresh, precise location is needed to start a walking route. Tap Get my location."))
            return
        }
        repository.configurationError?.let {
            mutableState.value = state.value.copy(live = state.value.live.copy(error = it)); return
        }
        if (!rerouting && fix.accuracyM <= 12 && Geo.distance(fix.position, approach.position) <= 8) {
            mutableState.value = state.value.copy(live = state.value.live.copy(
                error = "You're already near the selected street spot. Check the building number from here."))
            return
        }
        request?.cancel()
        generation++
        val requestGeneration = generation
        lastRouteRequestMs = now
        mutableState.value = state.value.copy(live = state.value.live.copy(busy = true, error = null))
        request = viewModelScope.launch {
            try {
                val route = repository.route(fix.position, approach.position)
                if (generation != requestGeneration) return@launch
                val session = WalkingSession(route, approach.position, destination.precision).also { liveSession = it }
                val latestFix = state.value.live.fix ?: fix
                val progress = session.update(latestFix, clockMs())
                mutableState.value = state.value.copy(screen = Screen.LIVE_WALK, live = state.value.live.copy(
                    route = route, source = route.source, progress = progress, busy = false, error = null))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == requestGeneration) setError(error,
                    if (rerouting) "Could not update the route. Check your connection; the previous route is still shown." else null)
            }
        }
    }

    private fun setError(error: Exception, fallback: String? = null) {
        mutableState.value = state.value.copy(live = state.value.live.copy(busy = false,
            error = fallback ?: (error as? NavigationError)?.message ?: "The navigation service returned an invalid result. Please try again."))
    }

    private fun cancelLive() {
        generation++
        request?.cancel()
        request = null
        liveSession = null
        lastRouteRequestMs = null
    }

    companion object { const val MAX_ADDRESS_LENGTH = 2_000 }
}
