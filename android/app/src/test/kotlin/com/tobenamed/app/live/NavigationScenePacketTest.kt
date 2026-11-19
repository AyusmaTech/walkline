package com.tobenamed.app.live

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.WalkingProgress
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.route.RouteState
import com.tobenamed.nav.route.RouteStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationScenePacketTest {
    private val start = LatLng(41.89, -87.79)
    private val street = Geo.offset(start, 0.0, 100.0)
    private val building = Geo.offset(street, 270.0, 20.0)
    private val destination = AddressCandidate("osm_W_123", "123 Example Street", building, GeocodePrecision.ROOFTOP, false, streetNumber = "123")
    private val route = WalkingRoute(listOf(start, street), 100.0, 72.0, emptyList(), emptyList(), "openstreetmap")
    private fun JSONArray.point() = LatLng(getDouble(1), getDouble(0))
    private fun progress(status: RouteStatus, fix: LocationSample, quality: FixQuality = FixQuality.GOOD) = WalkingProgress(
        RouteState(status, 30.0, 70.0, 0.0, 0.0, 0.0, 70.0), fix, quality, "Continue", "STRAIGHT", null, 0.0)

    @Test
    fun `viewpoint retains measured lateral position while the route projection stays separate`() {
        val measured = Geo.offset(Geo.offset(start, 0.0, 30.0), 90.0, 2.0)
        val fix = LocationSample(1000, measured, 3.0)
        val packet = navigationScenePacket(destination, route, progress(RouteStatus.ON_ROUTE, fix), fix, 0, street, true, true, false, 80f)
        val walker = packet.getJSONArray("walker").point()
        assertTrue(Geo.distance(walker, measured) < .1)
        assertTrue(Geo.distance(walker, packet.getJSONArray("focus").point()) in 5.9..6.1)
        assertTrue(packet.getBoolean("guiding"))
        assertEquals(0.0, packet.getDouble("cameraTurn"))
        assertTrue(Geo.distance(walker, packet.getJSONArray("future").getJSONArray(0).point()) in 1.9..2.1)
        assertTrue(kotlin.math.abs(packet.getDouble("bearing")) < .1)
    }

    @Test
    fun `off route and weak fixes never silently snap the walker onto the planned path`() {
        val fix = LocationSample(1000, Geo.offset(start, 90.0, 45.0), 3.0, courseDeg = 90.0)
        for ((status, quality) in listOf(RouteStatus.OFF_ROUTE to FixQuality.GOOD, RouteStatus.ON_ROUTE to FixQuality.WEAK)) {
            val packet = navigationScenePacket(destination, route, progress(status, fix, quality), fix, 0, street, true, true, false, 0f)
            assertTrue(Geo.distance(fix.position, packet.getJSONArray("walker").point()) < .1)
            assertEquals(packet.getJSONArray("walker").toString(), packet.getJSONArray("focus").toString())
            assertFalse(packet.getBoolean("guiding"))
            assertEquals(0.0, packet.getDouble("cameraTurn"))
        }
    }

    @Test
    fun `camera waits until the last ten metres to rotate and keeps the walker centred`() {
        val corner = Geo.offset(start, 0.0, 60.0)
        val bend = WalkingRoute(listOf(start, corner, Geo.offset(corner, 90.0, 60.0)), 120.0, 90.0, emptyList(), emptyList(), "openstreetmap")
        fun packet(distance: Double): JSONObject {
            val fix = LocationSample(1000, bend.line.pointAt(distance), 3.0)
            return navigationScenePacket(destination, bend, progress(RouteStatus.ON_ROUTE, fix), fix, 0, street, true, true, false, 0f)
        }
        val before = packet(48.0)
        assertTrue(kotlin.math.abs(Geo.angleDiff(before.getDouble("bearing"), 0.0)) < .1)
        val turning = packet(56.0)
        assertTrue(turning.getDouble("bearing") in 50.0..65.0)
        val walker = turning.getJSONArray("walker").point()
        val focus = turning.getJSONArray("focus").point()
        assertTrue(kotlin.math.abs(Geo.angleDiff(Geo.bearing(walker, focus), turning.getDouble("bearing"))) < .1)
        assertTrue(Geo.distance(walker, focus) in 5.9..6.1)
        assertTrue(Geo.distance(walker, bend.line.pointAt(56.0)) < .1)
    }

    @Test
    fun `turn framing opens gradually and settles after the corner regardless of vertex density`() {
        val corner = Geo.offset(start, 0.0, 60.0)
        val end = Geo.offset(corner, 90.0, 60.0)
        val bend = Polyline(listOf(start, corner, end))
        assertEquals(0.0, walkingCameraTurn(bend, 10.0))
        assertTrue(walkingCameraTurn(bend, 42.0) in .2.. .7)
        assertTrue(walkingCameraTurn(bend, 50.0) > .95)
        assertTrue(walkingCameraTurn(bend, 62.0) in .2.. .7)
        assertEquals(0.0, walkingCameraTurn(bend, 70.0))
        assertEquals(0.0, walkingCameraTurn(bend, 119.0))
        val dense = Polyline((0..12).map { Geo.offset(start, 0.0, it * 5.0) } + (1..12).map { Geo.offset(corner, 90.0, it * 5.0) })
        for (distance in listOf(10.0, 42.0, 50.0, 62.0, 70.0))
            assertEquals(walkingCameraTurn(bend, distance), walkingCameraTurn(dense, distance), .001)
    }

    @Test
    fun `arrival faces the building from the street and removes the forward trail`() {
        val fix = LocationSample(1000, street, 3.0)
        val packet = navigationScenePacket(destination, route, progress(RouteStatus.ARRIVED, fix), fix, 0, street, true, true, false, 0f)
        assertTrue(packet.getBoolean("arrived"))
        assertTrue(Geo.distance(street, packet.getJSONArray("focus").point()) < .1)
        assertEquals(0, packet.getJSONArray("future").length())
        assertTrue(kotlin.math.abs(Geo.angleDiff(packet.getDouble("bearing"), 270.0)) < .1)
    }

    @Test
    fun `arrival does not pull measured GPS onto the selected street point`() {
        val measured = Geo.offset(street, 90.0, 6.0)
        val fix = LocationSample(1000, measured, 3.0, headingDeg = 180.0)
        val packet = navigationScenePacket(destination, route, progress(RouteStatus.ARRIVED, fix), fix, 0, street, true, true, false, 0f)
        assertTrue(Geo.distance(measured, packet.getJSONArray("walker").point()) < .1)
        assertTrue(Geo.distance(measured, packet.getJSONArray("focus").point()) < .1)
        assertTrue(Geo.distance(street, packet.getJSONArray("walker").point()) > 5.9)
        assertEquals(0, packet.getJSONArray("future").length())
    }

    @Test
    fun `preview without GPS has no invented walker or walking route`() {
        val packet = navigationScenePacket(destination, null, null, null, 2, street, false, false, true, 70f)
        assertTrue(packet.isNull("walker"))
        assertEquals(0, packet.getJSONArray("route").length())
        assertEquals(0, packet.getJSONArray("future").length())
        assertTrue(packet.getBoolean("picking"))
        assertEquals(2, packet.getInt("recenter"))
    }
}
