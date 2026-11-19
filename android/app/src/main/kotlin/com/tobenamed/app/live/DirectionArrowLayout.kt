package com.tobenamed.app.live

import kotlin.math.min

internal data class DirectionArrowPlacement(val centerX: Float, val centerY: Float, val diameter: Float)

/** A readable direction cue, not a projected location on the photographed pavement. */
internal object DirectionArrowLayout {
    fun place(width: Float, height: Float, topInset: Float, bottomInset: Float, density: Float): DirectionArrowPlacement? {
        if (!listOf(width, height, topInset, bottomInset, density).all { it.isFinite() } || density <= 0) return null
        val margin = 8 * density
        val top = topInset.coerceAtLeast(0f)
        val bottom = height - bottomInset.coerceAtLeast(0f)
        val diameter = min(112 * density, min(width, bottom - top) - margin * 2)
        // On a viewport too short for a useful arrow, retain the maneuver banner.
        if (diameter < 96 * density) return null
        val radius = diameter / 2
        val y = (top + (bottom - top) * .60f).coerceIn(top + radius + margin, bottom - radius - margin)
        return DirectionArrowPlacement(width / 2, y, diameter)
    }
}
