package com.tobenamed.nav.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManeuverCueTest {
    @Test
    fun `u-turns and roundabouts do not look like ordinary left and right turns`() {
        assertEquals(ManeuverCue.U_TURN, ManeuverCue.fromProvider("UTURN_LEFT"))
        assertEquals(ManeuverCue.U_TURN, ManeuverCue.fromProvider("UTURN_RIGHT"))
        assertEquals(ManeuverCue.ROUNDABOUT_RIGHT, ManeuverCue.fromProvider("ROUNDABOUT_RIGHT"))
        assertEquals(ManeuverCue.BEAR_LEFT, ManeuverCue.fromProvider("FORK_LEFT"))
        assertEquals(ManeuverCue.FOLLOW_ROUTE, ManeuverCue.fromProvider("FERRY"))
    }

    @Test
    fun `compact street labels keep the actual maneuver road and do not invent one`() {
        assertEquals("N Oak Park Ave", ManeuverCue.streetLabel("Head north on N Oak Park Ave toward Superior St"))
        assertEquals("W Adams St/U.S. Route 66", ManeuverCue.streetLabel("Turn right onto W Adams St/U.S. Route 66"))
        assertNull(ManeuverCue.streetLabel("Continue along the path"))
    }
}
