package com.tobenamed.nav.live

import com.tobenamed.nav.TestGeo.at
import com.tobenamed.nav.TestGeo.fix
import com.tobenamed.nav.geo.EncodedPolyline
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.route.RouteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class WalkingSessionTest {
    private val route = WalkingRoute(listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 50.0)), 100.0, 80.0,
        listOf(WalkingInstruction(50.0, "Head east", "STRAIGHT"), WalkingInstruction(50.0, "Turn left", "TURN_LEFT")), emptyList())

    @Test
    fun `polyline decoder reads Google's documented example and rejects truncated input`() {
        assertEquals(listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            EncodedPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
        assertFailsWith<IllegalArgumentException> { EncodedPolyline.decode("_p~iF") }
        assertFailsWith<IllegalArgumentException> { EncodedPolyline.decode("????") }
    }

    @Test
    fun `real route progress surfaces the next instruction near a turn`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        assertEquals("Head east", session.update(fix(0, 0.0, 0.0, courseDeg = 90.0), 0).instruction)
        val turn = session.update(fix(1000, 35.0, 0.0, courseDeg = 90.0), 1000)
        assertEquals("Turn left", turn.instruction)
        assertEquals(true, turn.instructionIsUpcoming)
        assertEquals("TURN_LEFT", turn.maneuver)
        assertEquals(15.0, turn.distanceToInstructionM!!, 0.1)
    }

    @Test
    fun `a completed turn is not displayed as an instruction to turn again`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        session.update(fix(0, 0.0, 0.0), 0)
        val before = session.update(fix(1000, 49.0, 0.0), 1000)
        assertEquals(true, before.instructionIsUpcoming)
        assertEquals("TURN_LEFT", before.maneuver)
        val after = session.update(fix(2000, 50.0, 8.0), 2000)
        assertEquals(false, after.instructionIsUpcoming)
        assertEquals("STRAIGHT", after.maneuver)
    }

    @Test
    fun `an unsupported current maneuver keeps its instruction instead of inventing straight guidance`() {
        val ferry = route.copy(instructions = listOf(WalkingInstruction(100.0, "Board the ferry", "FERRY")))
        val session = WalkingSession(ferry, ferry.line.end, GeocodePrecision.ROOFTOP)
        val progress = session.update(fix(0, 5.0, 0.0), 0)
        assertEquals("FERRY", progress.maneuver)
        assertEquals("Board the ferry", progress.instruction)
        assertEquals(ManeuverCue.FOLLOW_ROUTE, ManeuverCue.fromProvider(progress.maneuver))
    }

    @Test
    fun `weak and stale fixes do not advance progress or arrival`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        val good = session.update(fix(0, 10.0, 0.0), 0)
        val weak = session.update(fix(1000, 50.0, 50.0, accuracyM = 100.0), 1000)
        assertEquals(FixQuality.WEAK, weak.quality)
        assertEquals(good.route, weak.route)
        assertNull(weak.arrowOffsetDeg)
        assertEquals(FixQuality.STALE, session.expire(20_000).quality)
    }

    @Test
    fun `approximate geocodes never claim the correct building has been reached`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.APPROXIMATE)
        val end = session.update(fix(0, 50.0, 48.0), 0)
        assertNotEquals(RouteStatus.ARRIVED, end.route?.status)
    }

    @Test
    fun `a tracking gap resets off-route timers instead of causing an instant reroute`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        session.update(fix(0, 10.0, 0.0), 0)
        session.update(fix(1000, 10.0, -40.0), 1000)
        val afterGap = session.update(fix(30_000, 10.0, -40.0), 30_000)
        assertEquals(RouteStatus.ON_ROUTE, afterGap.route?.status)
        session.pause()
        assertEquals(FixQuality.STALE, session.progress.quality)
    }

    @Test
    fun `a snapped route start gets entry guidance instead of repeated off-route alerts`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        for (i in 0..40) {
            val joining = session.update(fix(i * 1000L, 0.0, -35.0), i * 1000L)
            assertEquals(true, joining.joiningRoute)
            assertNull(joining.route)
            assertEquals(35.0, joining.distanceToInstructionM!!, 0.1)
        }
        // A fix accepted by the guidance accuracy gate must also be able to join the path.
        val entered = session.update(fix(41_000, 2.0, 0.0, accuracyM = 20.0), 41_000)
        assertEquals(false, entered.joiningRoute)
        assertEquals(RouteStatus.ON_ROUTE, entered.route?.status)
        var drifted = entered
        for (i in 42..50) drifted = session.update(fix(i * 1000L, 2.0, -40.0), i * 1000L)
        assertEquals(RouteStatus.OFF_ROUTE, drifted.route?.status)
    }
}
