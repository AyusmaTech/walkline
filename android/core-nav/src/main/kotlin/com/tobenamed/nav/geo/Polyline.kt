package com.tobenamed.nav.geo

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Result of projecting a point onto a [Polyline].
 *
 * @property alongTrackM distance along the line from its start to the closest point.
 * @property crossTrackM signed distance from the line: **positive = right** of the line's
 *   direction of travel, negative = left.
 * @property distanceFromLineM unsigned distance to the closest point (equals |crossTrackM|).
 * @property beyondEndsM how far the point lies before the start / after the end of the line
 *   (0 when it projects within the line).
 */
data class Projection(
    val alongTrackM: Double,
    val crossTrackM: Double,
    val distanceFromLineM: Double,
    val segmentIndex: Int,
    val segmentBearingDeg: Double,
    val snapped: LatLng,
    val beyondEndsM: Double,
)

/** An ordered list of points (a route, or a street centre line) with projection helpers. */
class Polyline(val points: List<LatLng>) {
    init {
        require(points.size >= 2) { "A polyline needs at least 2 points" }
    }

    private val frame = LocalFrame(points.first())
    private val xy: List<Xy> = points.map(frame::toXy)

    /** cumulative[i] = distance along the line from the start to points[i]. */
    private val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) {
            c[i] = c[i - 1] + hypot(xy[i].x - xy[i - 1].x, xy[i].y - xy[i - 1].y)
        }
    }

    val lengthM: Double get() = cumulative.last()

    val start: LatLng get() = points.first()
    val end: LatLng get() = points.last()

    fun segmentBearing(i: Int): Double {
        val a = xy[i]
        val b = xy[i + 1]
        return Geo.normalizeBearing(Geo.toDeg(atan2(b.x - a.x, b.y - a.y)))
    }

    fun project(p: LatLng): Projection {
        val q = frame.toXy(p)
        var best: Projection? = null
        var bestDist = Double.MAX_VALUE

        for (i in 0 until xy.size - 1) {
            val a = xy[i]
            val b = xy[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val segLen2 = dx * dx + dy * dy
            if (segLen2 == 0.0) continue

            val tRaw = ((q.x - a.x) * dx + (q.y - a.y) * dy) / segLen2
            val t = tRaw.coerceIn(0.0, 1.0)
            val cx = a.x + t * dx
            val cy = a.y + t * dy
            val dist = hypot(q.x - cx, q.y - cy)

            if (dist < bestDist) {
                bestDist = dist
                val segLen = kotlin.math.sqrt(segLen2)
                // 2D cross product: positive means q is LEFT of a->b, so negate for "positive = right".
                val cross = dx * (q.y - a.y) - dy * (q.x - a.x)
                val sign = if (cross > 0) -1.0 else 1.0
                val beyond = when {
                    i == 0 && tRaw < 0 -> -tRaw * segLen
                    i == xy.size - 2 && tRaw > 1 -> (tRaw - 1) * segLen
                    else -> 0.0
                }
                best = Projection(
                    alongTrackM = cumulative[i] + t * segLen,
                    crossTrackM = sign * dist,
                    distanceFromLineM = dist,
                    segmentIndex = i,
                    segmentBearingDeg = segmentBearing(i),
                    snapped = frame.toLatLng(Xy(cx, cy)),
                    beyondEndsM = beyond,
                )
            }
        }
        return best ?: error("Polyline has only zero-length segments")
    }

    /** Bearing of the line at [alongM] metres from the start. */
    fun bearingAt(alongM: Double): Double = segmentBearing(segmentIndexAt(alongM))

    /** Point at [alongM] metres from the start (clamped to the line). */
    fun pointAt(alongM: Double): LatLng {
        val d = alongM.coerceIn(0.0, lengthM)
        val i = segmentIndexAt(d)
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen == 0.0) 0.0 else (d - cumulative[i]) / segLen
        val a = xy[i]
        val b = xy[i + 1]
        return frame.toLatLng(Xy(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
    }

    private fun segmentIndexAt(alongM: Double): Int {
        val d = alongM.coerceIn(0.0, lengthM)
        for (i in 0 until cumulative.size - 1) {
            if (d <= cumulative[i + 1]) return i
        }
        return cumulative.size - 2
    }
}
