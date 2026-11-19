package com.tobenamed.nav.streetview

import com.tobenamed.nav.TestGeo.at
import com.tobenamed.nav.TestGeo.fix
import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.live.WalkingSession
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.route.RouteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreetSceneTest {
    private val route = WalkingRoute(listOf(at(0.0, 0.0), at(100.0, 0.0)), 100.0, 80.0, emptyList(), emptyList())

    @Test
    fun `walking imagery advances along the route instead of staying at the building`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        val first = StreetScenePlanner.plan(route.line.end, route, session.update(fix(0, 10.0, 0.0), 0), false)
        val next = StreetScenePlanner.plan(route.line.end, route, session.update(fix(1000, 30.0, 0.0), 1000), false)
        assertTrue(Geo.distance(first.queryPosition, at(10.0, 0.0)) < 0.1)
        assertTrue(Geo.distance(next.queryPosition, at(30.0, 0.0)) < 0.1)
        assertEquals(90.0, next.guidanceBearingDeg!!, .1)
        assertEquals(10.0, first.guidanceProgressM!!, .1)
        assertEquals(30.0, next.guidanceProgressM!!, .1)
    }

    @Test
    fun `walking panorama lookup retains measured position beside the mapped route`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        val sample = fix(1000, 30.0, 6.0)
        val progress = session.update(sample, 1000)
        val scene = StreetScenePlanner.plan(route.line.end, route, progress, false)
        assertEquals(sample.position, scene.queryPosition)
        assertTrue(route.line.project(scene.queryPosition).distanceFromLineM > 5.9)
        assertEquals(30.0, scene.guidanceProgressM!!, .1)
        assertTrue(scene.ribbon.flatMap { it.outline }.all { route.line.project(it).distanceFromLineM < .5 })
    }

    @Test
    fun `building view uses the route approach and looking around cannot move the walker`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        val progress = session.update(fix(0, 10.0, 0.0), 0)
        val building = StreetScenePlanner.plan(route.line.end, route, progress, true)
        assertTrue(Geo.distance(building.queryPosition, at(90.0, 0.0)) < 0.1)
        assertEquals(progress, session.progress)
        assertEquals(route.line.end, building.focus)
    }

    @Test
    fun `preview stands on the selected street and faces the building before a route exists`() {
        val building = at(0.0, 0.0)
        val street = at(0.0, -30.0)
        val preview = StreetScenePlanner.plan(building, null, null, true, streetPosition = street)
        assertEquals(street, preview.queryPosition)
        assertEquals(building, preview.focus)
        assertEquals(20, preview.searchRadiusM)
        val peek = StreetScenePlanner.plan(building, route, null, true, streetPosition = street)
        assertEquals(street, peek.queryPosition)
    }

    @Test
    fun `arrival faces the building from the street instead of looking past it along the route`() {
        val street = route.line.end
        val building = at(100.0, 20.0)
        val session = WalkingSession(route, street, GeocodePrecision.ROOFTOP)
        session.update(fix(0, 0.0, 0.0), 0)
        val arrived = session.update(fix(80_000, 100.0, 0.0), 80_000)
        assertEquals(RouteStatus.ARRIVED, arrived.route?.status)
        val scene = StreetScenePlanner.plan(building, route, arrived, false, street)
        assertEquals(StreetSceneMode.BUILDING, scene.mode)
        assertEquals(street, scene.queryPosition)
        assertEquals(building, scene.focus)
        assertNull(scene.guidanceBearingDeg)
        assertNull(scene.guidanceProgressM)
    }

    @Test
    fun `street view follows walking distance without playing forward while stationary`() {
        val gate = StreetSceneRequestGate()
        fun scene(east: Double) = StreetScene(StreetSceneMode.WALK, at(east, 0.0), route.line.end, 90.0)
        assertTrue(gate.shouldRequest(scene(0.0), 0))
        assertFalse(gate.shouldRequest(scene(6.1), 1_999))
        assertTrue(gate.shouldRequest(scene(6.1), 2_000))
        assertTrue(gate.shouldRequest(scene(12.2), 6_500))
        assertFalse(gate.shouldRequest(scene(12.2), 60_000))
        assertFalse(gate.shouldRequest(scene(15.0), 61_000))
        assertTrue(gate.shouldRequest(scene(18.3), 65_000))
    }

    @Test
    fun `ground ribbon follows a route bend and weak GPS hides guidance`() {
        val bent = WalkingRoute(listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 100.0)), 150.0, 100.0, emptyList(), emptyList())
        val session = WalkingSession(bent, bent.line.end, GeocodePrecision.ROOFTOP)
        val scene = StreetScenePlanner.plan(bent.line.end, bent, session.update(fix(0, 25.0, 0.0), 0), false)
        assertTrue(scene.ribbon.isNotEmpty())
        assertTrue(scene.ribbon.flatMap { it.outline }.any { bent.line.project(it).alongTrackM > 51 })
        assertTrue(scene.ribbon.flatMap { it.outline }.all { bent.line.project(it).distanceFromLineM < .5 })
        val weak = session.update(fix(1000, 35.0, 0.0, accuracyM = 100.0), 1000)
        val paused = StreetScenePlanner.plan(bent.line.end, bent, weak, false)
        assertNull(paused.guidanceBearingDeg)
        assertNull(paused.guidanceProgressM)
        assertTrue(paused.ribbon.isEmpty())
    }

    @Test
    fun `panorama requests wait for movement and are bounded during noisy GPS updates`() {
        val gate = StreetSceneRequestGate()
        fun scene(east: Double) = StreetScene(StreetSceneMode.WALK, at(east, 0.0), route.line.end, 90.0)
        assertTrue(gate.shouldRequest(scene(0.0), 0))
        assertFalse(gate.shouldRequest(scene(3.0), 6000))
        assertFalse(gate.shouldRequest(scene(14.0), 1000))
        assertTrue(gate.shouldRequest(scene(14.0), 6000))
        assertFalse(gate.shouldRequest(scene(14.0), 20_000))
        assertTrue(gate.shouldRequest(scene(14.0), 20_000, force = true))
        assertTrue(StreetScenePlanner.groundPitchDeg(10.0) < 0)
    }

    @Test
    fun `direction follows a bend from the walking route instead of the offset panorama`() {
        val bent = WalkingRoute(listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 100.0)), 150.0, 100.0, emptyList(), emptyList())
        val session = WalkingSession(bent, bent.line.end, GeocodePrecision.ROOFTOP)
        val before = StreetScenePlanner.plan(bent.line.end, bent, session.update(fix(0, 20.0, -7.0), 0), false)
        assertEquals(90.0, before.guidanceBearingDeg!!, .1)
        val approaching = StreetScenePlanner.plan(bent.line.end, bent, session.update(fix(1000, 45.0, -7.0), 1000), false)
        assertTrue(approaching.guidanceBearingDeg!! in 15.0..30.0)
        val after = StreetScenePlanner.plan(bent.line.end, bent, session.update(fix(2000, 50.0, 20.0), 2000), false)
        assertEquals(0.0, after.guidanceBearingDeg!!, .1)
    }

    @Test
    fun `joining and off-route states withhold the affirmative route arrow`() {
        val session = WalkingSession(route, route.line.end, GeocodePrecision.ROOFTOP)
        val joining = session.update(fix(0, 10.0, -35.0), 0)
        assertNull(StreetScenePlanner.plan(route.line.end, route, joining, false).guidanceBearingDeg)
        session.update(fix(1000, 10.0, 0.0), 1000)
        session.update(fix(2000, 20.0, -40.0), 2000)
        val off = session.update(fix(9000, 20.0, -40.0), 9000)
        assertEquals(RouteStatus.OFF_ROUTE, off.route?.status)
        assertNull(StreetScenePlanner.plan(route.line.end, route, off, false).guidanceBearingDeg)
    }
}
