package com.tobenamed.nav.geo

import com.tobenamed.nav.TestGeo
import com.tobenamed.nav.TestGeo.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun `offset and distance agree`() {
        val p = Geo.offset(TestGeo.BASE, 37.0, 250.0)
        assertEquals(250.0, Geo.distance(TestGeo.BASE, p), 0.01)
        assertEquals(37.0, Geo.bearing(TestGeo.BASE, p), 0.01)
    }

    @Test
    fun `angleDiff is signed and wraps`() {
        assertEquals(20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
        assertEquals(-20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(270.0, 90.0), 1e-9)
        assertEquals(0.0, Geo.angleDiff(720.0, 0.0), 1e-9)
    }

    @Test
    fun `local frame round trips`() {
        val frame = LocalFrame(TestGeo.BASE)
        val p = at(123.0, -45.0)
        val back = frame.toLatLng(frame.toXy(p))
        assertTrue(Geo.distance(p, back) < 0.01)
        assertEquals(123.0, frame.toXy(p).x, 0.05)
        assertEquals(-45.0, frame.toXy(p).y, 0.05)
    }

    @Test
    fun `short walking routes across the date line remain local`() {
        val start = LatLng(0.0, 179.999)
        val end = LatLng(0.0, -179.999)
        val line = Polyline(listOf(start, end))
        assertEquals(222.39, line.lengthM, 0.1)
        assertTrue(Geo.distance(line.pointAt(line.lengthM), end) < 0.01)
    }

    @Test
    fun `projection gives along and signed cross track`() {
        // Eastward line, 100 m long. Right of an eastward line is south.
        val line = Polyline(listOf(at(0.0, 0.0), at(100.0, 0.0)))
        assertEquals(100.0, line.lengthM, 0.05)
        assertEquals(90.0, line.segmentBearing(0), 0.01)

        val north = line.project(at(50.0, 5.0))
        assertEquals(50.0, north.alongTrackM, 0.05)
        assertEquals(-5.0, north.crossTrackM, 0.05)

        val south = line.project(at(30.0, -7.0))
        assertEquals(30.0, south.alongTrackM, 0.05)
        assertEquals(7.0, south.crossTrackM, 0.05)
        assertEquals(0.0, south.beyondEndsM, 1e-9)

        val pastEnd = line.project(at(110.0, 0.0))
        assertEquals(10.0, pastEnd.beyondEndsM, 0.05)
    }

    @Test
    fun `projection on an L shaped route`() {
        val route = Polyline(listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 50.0)))
        val p = route.project(at(52.0, 20.0))
        assertEquals(1, p.segmentIndex)
        assertEquals(70.0, p.alongTrackM, 0.1)
        assertEquals(0.0, p.segmentBearingDeg, 0.01)
        assertEquals(2.0, p.crossTrackM, 0.05) // east of a northward line = right

        assertEquals(90.0, route.bearingAt(10.0), 0.01)
        assertTrue(Geo.distance(route.pointAt(75.0), at(50.0, 25.0)) < 0.1)
    }
}
