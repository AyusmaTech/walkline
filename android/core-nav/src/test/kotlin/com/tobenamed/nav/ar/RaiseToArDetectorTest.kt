package com.tobenamed.nav.ar

import kotlin.test.Test
import kotlin.test.assertEquals

class RaiseToArDetectorTest {
    @Test
    fun `raise and lower use hold times and hysteresis`() {
        val detector = RaiseToArDetector()
        assertEquals(ViewMode.MAP, detector.update(0, 75.0))
        assertEquals(ViewMode.MAP, detector.update(499, 75.0))
        assertEquals(ViewMode.AR, detector.update(500, 75.0))
        assertEquals(ViewMode.AR, detector.update(600, 50.0))
        assertEquals(ViewMode.AR, detector.update(700, 25.0))
        assertEquals(ViewMode.MAP, detector.update(1000, 25.0))
    }

    @Test
    fun `brief raises and forbidden camera never enable AR`() {
        val detector = RaiseToArDetector()
        detector.update(0, 80.0)
        detector.update(400, 30.0)
        assertEquals(ViewMode.MAP, detector.update(600, 80.0))
        assertEquals(ViewMode.MAP, detector.update(1200, 80.0, arAllowed = false))
    }

    @Test
    fun `manual map stays closed until the phone is lowered again`() {
        val detector = RaiseToArDetector()
        assertEquals(ViewMode.AR, detector.toggleManually())
        assertEquals(ViewMode.MAP, detector.toggleManually())
        assertEquals(ViewMode.MAP, detector.update(0, 80.0))
        detector.update(1000, 20.0)
        detector.update(2000, 80.0)
        assertEquals(ViewMode.AR, detector.update(2500, 80.0))
        assertEquals(ViewMode.MAP, detector.update(3000, 80.0, arAllowed = false))
    }

    @Test
    fun `gravity tilt does not depend on sensor units`() {
        assertEquals(0.0, RaiseToArDetector.tiltFromGravity(0.0, 0.0, 9.81), 0.001)
        assertEquals(90.0, RaiseToArDetector.tiltFromGravity(0.0, 1.0, 0.0), 0.001)
        assertEquals(45.0, RaiseToArDetector.tiltFromGravity(0.0, 1.0, 1.0), 0.001)
    }
}
