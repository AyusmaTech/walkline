package com.tobenamed.app

import com.tobenamed.nav.route.RouteStatus
import com.tobenamed.nav.street.SideGuidance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DemoWalkTest {
    @Test
    fun `forward walk arrives and stays complete`() {
        val demo = DemoWalk()
        assertEquals(100.0, demo.snapshot.route.remainingM, 0.1)
        repeat(20) { demo.advance(true) }
        assertEquals(RouteStatus.ARRIVED, demo.snapshot.route.status)
        val arrived = demo.snapshot
        assertEquals(arrived, demo.advance(false))
    }

    @Test
    fun `sustained backwards movement alerts and forward travel clears it`() {
        val demo = DemoWalk()
        assertEquals(RouteStatus.ON_ROUTE, demo.advance(false).route.status)
        assertEquals(RouteStatus.WRONG_WAY, demo.advance(false).route.status)
        assertEquals(RouteStatus.ON_ROUTE, demo.advance(true).route.status)
    }

    @Test
    fun `crossing warns about the sidewalk and blocks premature arrival`() {
        val demo = DemoWalk()
        assertIs<SideGuidance.CrossStreet>(demo.crossStreet().side)
        repeat(20) { demo.advance(true) }
        assertNotEquals(RouteStatus.ARRIVED, demo.snapshot.route.status)
        assertEquals(RouteStatus.ARRIVED, demo.crossStreet().route.status)
        assertIs<SideGuidance.OnCorrectSide>(demo.snapshot.side)
    }
}
