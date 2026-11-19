package com.tobenamed.nav.street

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.LocalFrame
import com.tobenamed.nav.geo.Polyline
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds a street centre line from Street View panorama positions.
 *
 * Street View imagery is captured from a car driving in the road, so panorama positions
 * trace the centre of the street. Positions are fetched live during the walk and are
 * not stored (Google Maps Platform terms).
 */
object Centreline {

    /**
     * @param panoramaPositions panorama locations along one street, in any order.
     * @param approxBearingDeg rough direction of the street (e.g. route bearing near the destination),
     *   used to order the points.
     * @param minSpacingM points closer than this to the previous kept point are dropped.
     * @return an ordered centre line, or null if fewer than 2 distinct points remain.
     */
    fun fromPanoramaPositions(
        panoramaPositions: List<LatLng>,
        approxBearingDeg: Double,
        minSpacingM: Double = 3.0,
    ): Polyline? {
        if (panoramaPositions.size < 2) return null
        val frame = LocalFrame(panoramaPositions.first())
        val ux = sin(Geo.toRad(approxBearingDeg))
        val uy = cos(Geo.toRad(approxBearingDeg))

        val ordered = panoramaPositions.sortedBy { p ->
            val xy = frame.toXy(p)
            xy.x * ux + xy.y * uy
        }

        val kept = mutableListOf(ordered.first())
        for (p in ordered.drop(1)) {
            if (Geo.distance(kept.last(), p) >= minSpacingM) kept += p
        }
        return if (kept.size >= 2) Polyline(kept) else null
    }

    /**
     * Fallback when there is no Street View coverage: a straight centre line through [through]
     * along [bearingDeg] (e.g. from the walking route geometry near the destination).
     */
    fun straight(through: LatLng, bearingDeg: Double, halfLengthM: Double = 150.0): Polyline =
        Polyline(
            listOf(
                Geo.offset(through, bearingDeg + 180.0, halfLengthM),
                Geo.offset(through, bearingDeg, halfLengthM),
            ),
        )
}
