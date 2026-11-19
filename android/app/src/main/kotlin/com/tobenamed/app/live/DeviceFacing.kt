package com.tobenamed.app.live

import com.tobenamed.nav.geo.Geo
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/** Absolute device facing, separate from a GPS fix and its physical-progress clock. */
data class DeviceFacing(val bearingDeg: Double, val timeMs: Long) {
    init { require(bearingDeg.isFinite() && timeMs >= 0) }
    fun isFresh(nowMs: Long) = timeMs <= nowMs + 100 && nowMs - timeMs <= MAX_AGE_MS
    companion object { const val MAX_AGE_MS = 3_000L }
}

internal object FacingDirection {
    /** Android's rotation matrix maps device axes into East/North/Up. Use the
     * screen's top edge when flat, blending toward the rear-camera axis upright.
     * The top edge alone becomes vertical and can flip by 180 degrees upright. */
    fun magneticBearing(matrix: FloatArray, displayRotation: Int): Double? {
        if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
        val (east, north) = when (displayRotation) {
            1 -> -matrix[0].toDouble() to -matrix[3].toDouble()
            2 -> -matrix[1].toDouble() to -matrix[4].toDouble()
            3 -> matrix[0].toDouble() to matrix[3].toDouble()
            else -> matrix[1].toDouble() to matrix[4].toDouble()
        }
        val cameraEast = -matrix[2].toDouble(); val cameraNorth = -matrix[5].toDouble()
        val topLength = hypot(east, north); val cameraLength = hypot(cameraEast, cameraNorth)
        if (maxOf(topLength, cameraLength) < .1) return null
        val weight = ((cameraLength - .45) / .35).coerceIn(0.0, 1.0)
        val x = if (topLength < .1) cameraEast else if (cameraLength < .1) east else
            east / topLength * (1 - weight) + cameraEast / cameraLength * weight
        val y = if (topLength < .1) cameraNorth else if (cameraLength < .1) north else
            north / topLength * (1 - weight) + cameraNorth / cameraLength * weight
        if (hypot(x, y) < .1) return null
        return Geo.normalizeBearing(Math.toDegrees(atan2(x, y)))
    }
}

/** Time-based smoothing takes the short path across north; it never creates fixes. */
internal class FacingFilter {
    private var last: DeviceFacing? = null
    fun reset() { last = null }
    fun update(bearing: Double, timeMs: Long): DeviceFacing? {
        if (!bearing.isFinite() || timeMs < 0) return null
        val previous = last
        if (previous != null && timeMs <= previous.timeMs) return null
        val filtered = if (previous == null || timeMs - previous.timeMs > DeviceFacing.MAX_AGE_MS) bearing else {
            val alpha = 1 - exp(-(timeMs - previous.timeMs) / 80.0)
            previous.bearingDeg + Geo.angleDiff(bearing, previous.bearingDeg) * alpha
        }
        return DeviceFacing(Geo.normalizeBearing(filtered), timeMs).also { last = it }
    }
}
