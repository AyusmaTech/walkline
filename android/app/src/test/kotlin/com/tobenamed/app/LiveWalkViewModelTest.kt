package com.tobenamed.app

import androidx.lifecycle.SavedStateHandle
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.app.live.AddressResults
import com.tobenamed.app.live.WalkingRepository
import com.tobenamed.app.live.StreetApproach
import com.tobenamed.app.live.DeviceFacing
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.live.WalkingInstruction
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.model.LocationSample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveWalkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val target = AddressCandidate("a", "12 Main", LatLng(30.001, -95.0), GeocodePrecision.ROOFTOP, false)
    private class Repository(val target: AddressCandidate) : WalkingRepository {
        override val configurationError: String? = null
        var query = ""
        var routeCalls = 0
        var routedDestination: LatLng? = null
        var approach: StreetApproach? = StreetApproach(LatLng(30.001, -94.9996), "Main Street")
        var approachGate: CompletableDeferred<Unit>? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun resolve(address: String): AddressResults {
            query = address
            gate?.let { withContext(NonCancellable) { it.await() } }
            return AddressResults(listOf(target), "google")
        }
        override suspend fun route(origin: LatLng, destination: LatLng): WalkingRoute {
            routeCalls++
            routedDestination = destination
            return WalkingRoute(listOf(origin, destination), 110.0, 80.0,
                listOf(WalkingInstruction(110.0, "Head north", "STRAIGHT")), listOf("Check sidewalks"))
        }
        override suspend fun streetApproach(destination: AddressCandidate): StreetApproach? {
            approachGate?.let { withContext(NonCancellable) { it.await() } }
            return approach
        }
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test
    fun `turning in place updates facing without moving the fix or advancing route progress`() = runTest(dispatcher) {
        var now = 1000L
        val repo = Repository(target)
        val model = WalkViewModel(SavedStateHandle(), repo) { now }
        model.chooseDestination(target); advanceUntilIdle(); model.beginLocationTracking()
        val fix = LocationSample(now, LatLng(30.0, -95.0), 4.0)
        model.onLocation(fix); model.startLiveWalk(); advanceUntilIdle()
        val progress = model.state.value.live.progress
        for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
            now += 100
            model.onFacing(DeviceFacing(angle, now))
            assertEquals(fix, model.state.value.live.fix)
            assertEquals(progress, model.state.value.live.progress)
            assertEquals(angle, model.state.value.live.facing?.bearingDeg)
            assertEquals(1, repo.routeCalls)
        }
        model.onFacing(null)
        assertNull(model.state.value.live.facing)
        assertEquals(fix, model.state.value.live.fix)
    }

    @Test
    fun `compass activity cannot keep an old GPS fix fresh`() = runTest(dispatcher) {
        var now = 1000L
        val model = WalkViewModel(SavedStateHandle(), Repository(target)) { now }
        model.beginLocationTracking()
        val fix = LocationSample(now, LatLng(30.0, -95.0), 4.0)
        model.onLocation(fix)
        now = 20000
        model.onFacing(DeviceFacing(90.0, now)); model.refreshLocationAge()
        assertEquals(1000L, model.state.value.live.fix?.timeMs)
        assertNotNull(model.state.value.live.locationMessage)
        model.pauseLocation(); assertNull(model.state.value.live.facing)
    }

    @Test
    fun `stale future and reordered compass samples are ignored and stale facing expires`() = runTest(dispatcher) {
        var now = 5000L
        val model = WalkViewModel(SavedStateHandle(), Repository(target)) { now }
        model.onFacing(DeviceFacing(90.0, now)); assertNull(model.state.value.live.facing)
        model.beginLocationTracking(); model.onFacing(DeviceFacing(90.0, now))
        for (sample in listOf(DeviceFacing(180.0, 1000), DeviceFacing(180.0, 6000), DeviceFacing(180.0, 4999)))
            model.onFacing(sample)
        assertEquals(90.0, model.state.value.live.facing?.bearingDeg)
        now += 3001; model.refreshLocationAge(); assertNull(model.state.value.live.facing)
    }

    @Test
    fun `delivery details stay local and the user must confirm a match`() = runTest(dispatcher) {
        val repo = Repository(target)
        val model = WalkViewModel(SavedStateHandle(), repo) { 1_000L }
        model.editAddress("12 Main")
        model.editDeliveryDetails("Unit 4, code 1234")
        model.reviewAddress()
        model.resolveAddress()
        advanceUntilIdle()
        assertEquals("12 Main", repo.query)
        assertEquals(Screen.MATCHES, model.state.value.screen)
        assertEquals(0, repo.routeCalls)
        model.chooseDestination(target)
        assertEquals(Screen.LIVE_PREVIEW, model.state.value.screen)
    }

    @Test
    fun `fresh real location starts the selected route while weak location is rejected`() = runTest(dispatcher) {
        var time = 1_000L
        val repo = Repository(target)
        val model = WalkViewModel(SavedStateHandle(), repo) { time }
        model.chooseDestination(target)
        model.beginLocationTracking()
        advanceUntilIdle()
        model.onLocation(LocationSample(time, LatLng(30.0, -95.0), 100.0))
        model.startLiveWalk()
        assertEquals(0, repo.routeCalls)
        assertNotNull(model.state.value.live.error)
        time += 1_000
        model.onLocation(LocationSample(time, LatLng(30.0, -95.0), 4.0))
        model.startLiveWalk()
        advanceUntilIdle()
        assertEquals(1, repo.routeCalls)
        assertEquals(Screen.LIVE_WALK, model.state.value.screen)
        assertEquals(repo.approach?.position, repo.routedDestination)
        time += 20_000
        model.refreshLocationAge()
        assertNotNull(model.state.value.live.locationMessage)
    }

    @Test
    fun `a late lookup cannot replace a newly shared address`() = runTest(dispatcher) {
        val repo = Repository(target).apply { gate = CompletableDeferred() }
        val model = WalkViewModel(SavedStateHandle(), repo) { 1_000L }
        model.editAddress("12 Main")
        model.reviewAddress()
        model.resolveAddress()
        runCurrent()
        model.receiveAddress("55 Other Street")
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(Screen.INPUT, model.state.value.screen)
        assertEquals("55 Other Street", model.state.value.address)
        assertEquals(emptyList(), model.state.value.live.candidates)
    }

    @Test
    fun `already at the street spot needs a building check rather than an invented zero-length route`() = runTest(dispatcher) {
        val repo = Repository(target)
        val model = WalkViewModel(SavedStateHandle(), repo) { 1_000L }
        model.chooseDestination(target)
        advanceUntilIdle()
        model.beginLocationTracking()
        model.onLocation(LocationSample(1_000L, repo.approach!!.position, 4.0))
        model.startLiveWalk()
        advanceUntilIdle()
        assertEquals(0, repo.routeCalls)
        assertEquals(Screen.LIVE_PREVIEW, model.state.value.screen)
        assertNotNull(model.state.value.live.error)
    }

    @Test
    fun `unknown street cannot silently route to the building centroid`() = runTest(dispatcher) {
        val repo = Repository(target).apply { approach = null }
        val model = WalkViewModel(SavedStateHandle(), repo) { 1_000L }
        model.chooseDestination(target)
        advanceUntilIdle()
        model.beginLocationTracking()
        model.onLocation(LocationSample(1_000L, LatLng(30.0, -95.0), 4.0))
        model.startLiveWalk()
        advanceUntilIdle()
        assertEquals(0, repo.routeCalls)
        assertNotNull(model.state.value.live.approachMessage)
        assertEquals(Screen.LIVE_PREVIEW, model.state.value.screen)
    }

    @Test
    fun `manual street selection survives a late automatic lookup and becomes the routing endpoint`() = runTest(dispatcher) {
        val repo = Repository(target).apply { approachGate = CompletableDeferred() }
        val model = WalkViewModel(SavedStateHandle(), repo) { 1_000L }
        model.chooseDestination(target)
        runCurrent()
        val street = LatLng(30.001, -95.0005)
        model.selectStreet(street)
        repo.approachGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(street, model.state.value.live.streetApproach?.position)
        assertEquals(true, model.state.value.live.streetApproach?.selectedByUser)
        model.beginLocationTracking()
        model.onLocation(LocationSample(1_000L, LatLng(30.0, -95.0), 4.0))
        model.startLiveWalk()
        advanceUntilIdle()
        assertEquals(street, repo.routedDestination)
    }
}
