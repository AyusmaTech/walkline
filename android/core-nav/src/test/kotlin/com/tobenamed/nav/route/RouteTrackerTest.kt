package com.tobenamed.nav.route

import com.tobenamed.nav.TestGeo.at
import com.tobenamed.nav.TestGeo.fix
import com.tobenamed.nav.geo.Polyline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTrackerTest {

    private val route = Polyline(listOf(at(0.0, 0.0), at(200.0, 0.0)))

    @Test
    fun `walking forward stays on route`() {
        val tracker = RouteTracker(route)
        var state: RouteState? = null
        for (s in 0..20) {
            state = tracker.update(fix(s * 1000L, s * 1.4, 1.0, courseDeg = 90.0))
        }
        assertEquals(RouteStatus.ON_ROUTE, state!!.status)
        assertEquals(28.0, state.alongTrackM, 0.1)
        assertEquals(172.0, state.remainingM, 0.1)
    }

    @Test
    fun `turning around triggers wrong way, turning back clears it`() {
        val tracker = RouteTracker(route)
        var t = 0L
        var x = 0.0
        repeat(15) { x += 1.4; tracker.update(fix(t, x, 0.0, courseDeg = 90.0)); t += 1000 }

        val statuses = mutableListOf<RouteStatus>()
        repeat(6) { x -= 1.4; statuses += tracker.update(fix(t, x, 0.0, courseDeg = 270.0)).status; t += 1000 }
        assertEquals(RouteStatus.ON_ROUTE, statuses.first(), "a single step back must not trigger")
        assertEquals(RouteStatus.WRONG_WAY, statuses.last())

        var last = RouteStatus.WRONG_WAY
        repeat(6) { x += 1.4; last = tracker.update(fix(t, x, 0.0, courseDeg = 90.0)).status; t += 1000 }
        assertEquals(RouteStatus.ON_ROUTE, last)
    }

    @Test
    fun `a single backwards GPS jump does not trigger wrong way`() {
        val tracker = RouteTracker(route)
        var t = 0L
        repeat(10) { i -> tracker.update(fix(t, i * 1.4, 0.0, accuracyM = 12.0, courseDeg = 90.0)); t += 1000 }
        // GPS jumps 5 m back, but the user is still walking forward (course 90).
        val state = tracker.update(fix(t, 7.6, 0.0, accuracyM = 12.0, courseDeg = 90.0))
        assertEquals(RouteStatus.ON_ROUTE, state.status)
    }

    @Test
    fun `staying far from the route requests a re-route only after the hold time`() {
        val tracker = RouteTracker(route)
        tracker.update(fix(0, 10.0, 0.0))
        val early = tracker.update(fix(1000, 12.0, 40.0))
        assertEquals(RouteStatus.ON_ROUTE, early.status)
        var state = early
        for (t in 2..8) state = tracker.update(fix(t * 1000L, 12.0, 40.0))
        assertEquals(RouteStatus.OFF_ROUTE, state.status)
    }

    @Test
    fun `arrival near destination`() {
        val tracker = RouteTracker(route)
        val state = tracker.update(fix(0, 192.0, 2.0))
        assertEquals(RouteStatus.ARRIVED, state.status)
        assertTrue(state.distanceToDestinationM < 12.0)
    }

    @Test
    fun `a backwards jump with a bad course still needs sustained travel`() {
        val tracker = RouteTracker(route)
        tracker.update(fix(0, 50.0, 0.0, courseDeg = 90.0))
        val jump = tracker.update(fix(1000, 35.0, 0.0, courseDeg = 270.0))
        assertEquals(RouteStatus.ON_ROUTE, jump.status)
        val recovered = tracker.update(fix(2000, 52.0, 0.0, courseDeg = 90.0))
        assertEquals(RouteStatus.ON_ROUTE, recovered.status)
    }

    @Test
    fun `walking away before the route start still triggers wrong way`() {
        val tracker = RouteTracker(route)
        tracker.update(fix(0, 0.0, 0.0, courseDeg = 90.0))
        var state: RouteState? = null
        for (i in 1..6) state = tracker.update(fix(i * 1000L, -i * 1.4, 0.0, courseDeg = 270.0))
        assertEquals(RouteStatus.WRONG_WAY, state?.status)
        assertEquals(0.0, state!!.alongTrackM, 0.01)
    }

    @Test
    fun `late or duplicate fixes cannot advance alert timers`() {
        val tracker = RouteTracker(route)
        val latest = tracker.update(fix(5000, 50.0, 0.0, courseDeg = 90.0))
        assertEquals(latest, tracker.update(fix(1000, 20.0, 40.0, courseDeg = 270.0)))
        assertEquals(latest, tracker.update(fix(5000, 192.0, 0.0)))
    }

    @Test
    fun `poor GPS cannot declare arrival`() {
        val tracker = RouteTracker(route)
        assertEquals(RouteStatus.ON_ROUTE, tracker.update(fix(0, 198.0, 0.0, accuracyM = 40.0)).status)
    }

    @Test
    fun `session can withhold arrival until the walker crosses`() {
        val tracker = RouteTracker(route)
        assertEquals(RouteStatus.ON_ROUTE, tracker.update(fix(0, 198.0, -8.0), arrivalAllowed = false).status)
        assertEquals(RouteStatus.ARRIVED, tracker.update(fix(1000, 198.0, 0.0), arrivalAllowed = true).status)
    }

    @Test
    fun `passing close to the destination early on a loop is not arrival`() {
        val loop = Polyline(listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0), at(0.0, 100.0), at(0.0, 5.0)))
        assertEquals(RouteStatus.ON_ROUTE, RouteTracker(loop).update(fix(0, 0.0, 0.0)).status)
    }
}
