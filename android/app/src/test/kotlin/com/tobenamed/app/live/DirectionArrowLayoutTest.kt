package com.tobenamed.app.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectionArrowLayoutTest {
    @Test
    fun `direction stays centered and readable on different portrait displays`() {
        for ((width, height, top) in listOf(Triple(360f, 688f, 234f), Triple(320f, 540f, 250f), Triple(420f, 800f, 280f))) {
            for (density in listOf(1f, 2.75f, 3.5f)) {
                val placement = assertNotNull(DirectionArrowLayout.place(width * density, height * density,
                    top * density, 140 * density, density))
                assertEquals(width * density / 2, placement.centerX)
                assertTrue(placement.diameter / density >= 96)
                // Every camera-relative rotation fits inside this circle, clear of controls.
                val radius = placement.diameter / 2 + 7 * density
                assertTrue(placement.centerX - radius >= 0)
                assertTrue(placement.centerX + radius <= width * density)
                assertTrue(placement.centerY - radius >= top * density)
                assertTrue(placement.centerY + radius <= (height - 140) * density)
            }
        }
    }

    @Test
    fun `crowded and unmeasured viewports do not squeeze the arrow into an unreadable sliver`() {
        assertNull(DirectionArrowLayout.place(0f, 0f, 0f, 0f, 1f))
        assertNull(DirectionArrowLayout.place(600f, 360f, 220f, 140f, 1f))
        assertNull(DirectionArrowLayout.place(360f, 600f, Float.NaN, 140f, 1f))
    }
}
