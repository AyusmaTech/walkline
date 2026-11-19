package com.tobenamed.app.live

import com.tobenamed.nav.geo.Geo
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceFacingTest {
    private fun pose(yaw: Double, tilt: Double = 0.0): FloatArray {
        val y = Math.toRadians(yaw); val t = Math.toRadians(tilt)
        // Device -> East/North/Up, rotated clockwise about Up then raised about X.
        return floatArrayOf(cos(y).toFloat(), (sin(y)*cos(t)).toFloat(), (-sin(y)*sin(t)).toFloat(),
            (-sin(y)).toFloat(), (cos(y)*cos(t)).toFloat(), (-cos(y)*sin(t)).toFloat(),
            0f, sin(t).toFloat(), cos(t).toFloat())
    }
    private fun near(expected: Double, actual: Double?) = assertTrue(actual != null && kotlin.math.abs(Geo.angleDiff(actual, expected)) < .01)

    @Test fun `flat phone uses the display top edge in each screen rotation`() {
        near(0.0, FacingDirection.magneticBearing(pose(0.0), 0))
        near(270.0, FacingDirection.magneticBearing(pose(0.0), 1))
        near(180.0, FacingDirection.magneticBearing(pose(0.0), 2))
        near(90.0, FacingDirection.magneticBearing(pose(0.0), 3))
    }
    @Test fun `raising the phone through vertical does not reverse the facing direction`() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0, 359.0))
            for (tilt in listOf(0.0, 20.0, 45.0, 80.0, 90.0, 100.0))
                near(heading, FacingDirection.magneticBearing(pose(heading, tilt), 0))
    }
    @Test fun `upright rear camera facing is independent of screen rotation`() {
        for (rotation in 0..3) near(90.0, FacingDirection.magneticBearing(pose(90.0, 90.0), rotation))
    }
    @Test fun `invalid sensor matrices cannot supply a heading`() {
        assertNull(FacingDirection.magneticBearing(FloatArray(8), 0))
        assertNull(FacingDirection.magneticBearing(FloatArray(9), 0))
        assertNull(FacingDirection.magneticBearing(FloatArray(9) { Float.NaN }, 0))
    }
    @Test fun `filter crosses north along the short arc and rejects reordered readings`() {
        val filter = FacingFilter()
        filter.update(359.0, 1000)
        val reading = filter.update(1.0, 1080)!!
        assertTrue(kotlin.math.abs(Geo.angleDiff(reading.bearingDeg, 0.0)) < 1.0)
        assertNull(filter.update(180.0, 1070))
        assertNull(filter.update(Double.NaN, 1100))
        near(180.0, filter.update(180.0, 5000)?.bearingDeg)
        filter.reset(); near(90.0, filter.update(90.0, 5100)?.bearingDeg)
    }
    @Test fun `heading has its own freshness clock`() {
        val heading = DeviceFacing(45.0, 1000)
        assertTrue(heading.isFresh(3999));assertEquals(false, heading.isFresh(4001))
        assertEquals(false, heading.isFresh(800))
    }
}
