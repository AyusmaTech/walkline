package com.tobenamed.nav.street

import com.tobenamed.nav.TestGeo.at
import com.tobenamed.nav.TestGeo.fix
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.model.StepEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SideOfStreetTrackerTest {
    private val line = Polyline(listOf(at(0.0, 0.0), at(160.0, 0.0)))
    private fun tracker() = SideOfStreetTracker(line, at(120.0, 8.0))

    @Test
    fun `uncertain GPS offers a hint instead of a crossing alert`() {
        val tracker = tracker()
        tracker.onLocation(fix(0, 50.0, -3.0, accuracyM = 12.0))
        assertNull(tracker.side)
        assertEquals(SideGuidance.Hint(RelativeSide.LEFT), tracker.guidance(90.0))
    }

    @Test
    fun `initial side requires consecutive confident fixes`() {
        val tracker = tracker()
        tracker.onLocation(fix(0, 50.0, -8.0))
        tracker.onLocation(fix(1000, 50.0, 0.0, accuracyM = 12.0))
        tracker.onLocation(fix(2000, 50.0, -8.0))
        assertNull(tracker.side)
        tracker.onLocation(fix(3000, 50.0, -8.0))
        assertEquals(StreetSide.RIGHT, tracker.side)
        assertIs<SideGuidance.CrossStreet>(tracker.guidance(90.0))
    }

    @Test
    fun `ordinary GPS drift cannot flip an established sidewalk`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        repeat(6) { tracker.onLocation(fix(it * 1000L, 50.0, -8.0)) }
        assertEquals(StreetSide.LEFT, tracker.side)
        assertIs<SideGuidance.OnCorrectSide>(tracker.guidance(90.0))
    }

    @Test
    fun `an ambiguous precise fix breaks a pending GPS confirmation`() {
        val tracker = tracker()
        tracker.onLocation(fix(0, 50.0, -8.0))
        tracker.onLocation(fix(1000, 50.0, 0.0, accuracyM = 1.0, source = LocationSample.Source.VPS))
        tracker.onLocation(fix(2000, 50.0, -8.0))
        assertNull(tracker.side)
    }

    @Test
    fun `walking along the street does not invent a crossing`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        tracker.onLocation(fix(0, 50.0, 6.0, accuracyM = 12.0))
        repeat(15) { tracker.onStep(StepEvent((it + 1) * 500L, 90.0)) }
        assertEquals(StreetSide.LEFT, tracker.side)
    }

    @Test
    fun `lateral steps flip the sidewalk and the next crossing restores it`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        tracker.onLocation(fix(0, 50.0, 6.0, accuracyM = 12.0))
        repeat(12) { tracker.onStep(StepEvent((it + 1) * 500L, 180.0, 1.0)) }
        assertEquals(StreetSide.RIGHT, tracker.side)
        assertEquals(SideSource.CROSSING, tracker.source)
        tracker.onLocation(fix(6000, 50.0, -6.0, accuracyM = 12.0))
        repeat(12) { tracker.onStep(StepEvent(6500 + it * 500L, 0.0, 1.0)) }
        assertEquals(StreetSide.LEFT, tracker.side)
    }

    @Test
    fun `stale location and interrupted steps cannot accumulate crossings`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        tracker.onLocation(fix(0, 50.0, 6.0, accuracyM = 12.0))
        repeat(5) { tracker.onStep(StepEvent(500 + it * 500L, 180.0, 1.0)) }
        repeat(5) { tracker.onStep(StepEvent(7000 + it * 500L, 180.0, 1.0)) }
        assertEquals(StreetSide.LEFT, tracker.side)
        repeat(20) { tracker.onStep(StepEvent(20_000 + it * 500L, 180.0, 1.0)) }
        assertEquals(StreetSide.LEFT, tracker.side)
    }

    @Test
    fun `leaving the street invalidates the side and crossing evidence`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        tracker.onLocation(fix(0, 50.0, 50.0))
        assertNull(tracker.side)
        assertEquals(SideGuidance.NotApplicable, tracker.guidance(90.0))
        tracker.onLocation(fix(1000, 50.0, -3.0, accuracyM = 12.0))
        assertIs<SideGuidance.Hint>(tracker.guidance(90.0))
    }

    @Test
    fun `precise location can override the old side immediately`() {
        val tracker = tracker()
        tracker.seed(StreetSide.LEFT)
        tracker.onLocation(fix(0, 50.0, -8.0, accuracyM = 1.0, source = LocationSample.Source.VPS))
        assertEquals(StreetSide.RIGHT, tracker.side)
        assertEquals(SideSource.PRECISE, tracker.source)
        tracker.onLocation(fix(1000, 50.0, 8.0))
        assertEquals(StreetSide.RIGHT, tracker.side)
    }

    @Test
    fun `guidance changes left and right when the walker turns around`() {
        val tracker = tracker()
        tracker.onLocation(fix(0, 50.0, 0.0, accuracyM = 12.0))
        assertEquals(SideGuidance.Hint(RelativeSide.LEFT), tracker.guidance(90.0))
        assertEquals(SideGuidance.Hint(RelativeSide.RIGHT), tracker.guidance(270.0))
    }

    @Test
    fun `a destination on the centre line has no assumed street side`() {
        val tracker = SideOfStreetTracker(line, at(120.0, 0.0))
        tracker.onLocation(fix(0, 50.0, -8.0))
        assertEquals(SideGuidance.NotApplicable, tracker.guidance(90.0))
    }
}
