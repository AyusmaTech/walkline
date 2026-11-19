package com.tobenamed.nav.ar

import com.tobenamed.nav.geo.Geo
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class ViewMode { MAP, AR }

/**
 * "Raise the phone to see AR": switches between Map view and AR view from device tilt.
 *
 * Uses two thresholds with a gap between them, plus hold times, so normal hand movement
 * while walking doesn't flicker between views.
 */
class RaiseToArDetector(private val config: Config = Config()) {
    data class Config(
        /** Tilt (0° = flat, 90° = upright) needed to enter AR. */
        val enterTiltDeg: Double = 65.0,
        /** Tilt below which AR is left. */
        val exitTiltDeg: Double = 40.0,
        val enterHoldMs: Long = 500,
        val exitHoldMs: Long = 300,
    ) {
        init {
            require(exitTiltDeg < enterTiltDeg) { "exit threshold must be below enter threshold" }
        }
    }

    var mode: ViewMode = ViewMode.MAP
        private set

    /** Manual toggle overrides tilt until the phone crosses the opposite threshold. */
    private var manualOverride: ViewMode? = null
    private var candidateSinceMs: Long? = null

    /**
     * @param tiltDeg device tilt, see [tiltFromGravity].
     * @param arAllowed false when AR can't or shouldn't run: unsupported device, no camera
     *   permission, proximity sensor covered (phone in pocket), app in background.
     */
    fun update(timeMs: Long, tiltDeg: Double, arAllowed: Boolean = true): ViewMode {
        if (!arAllowed) {
            mode = ViewMode.MAP
            candidateSinceMs = null
            manualOverride = null
            return mode
        }

        // Manual AR holds until the user taps again.
        // Manual MAP holds until the phone is lowered, so the next raise opens AR as normal.
        when (manualOverride) {
            ViewMode.AR -> return mode
            ViewMode.MAP -> {
                if (tiltDeg > config.exitTiltDeg) return mode
                manualOverride = null
            }
            null -> Unit
        }

        val wantsSwitch = when (mode) {
            ViewMode.MAP -> tiltDeg >= config.enterTiltDeg
            ViewMode.AR -> tiltDeg <= config.exitTiltDeg
        }
        if (!wantsSwitch) {
            candidateSinceMs = null
            return mode
        }

        val since = candidateSinceMs ?: timeMs.also { candidateSinceMs = it }
        val hold = if (mode == ViewMode.MAP) config.enterHoldMs else config.exitHoldMs
        if (timeMs - since >= hold) {
            mode = if (mode == ViewMode.MAP) ViewMode.AR else ViewMode.MAP
            candidateSinceMs = null
        }
        return mode
    }

    /**
     * Manual button. Choosing AR keeps AR until the next tap; choosing Map keeps Map until the
     * phone is lowered.
     */
    fun toggleManually(): ViewMode {
        mode = if (mode == ViewMode.MAP) ViewMode.AR else ViewMode.MAP
        manualOverride = mode
        candidateSinceMs = null
        return mode
    }

    companion object {
        /**
         * Tilt of the screen from horizontal, from a gravity vector in device coordinates
         * (Android `TYPE_GRAVITY` in m/s², iOS `CMDeviceMotion.gravity` in g; units cancel out).
         * 0° = lying flat, 90° = held upright.
         */
        fun tiltFromGravity(x: Double, y: Double, z: Double): Double {
            val norm = sqrt(x * x + y * y + z * z)
            if (norm == 0.0) return 0.0
            return Geo.toDeg(acos((abs(z) / norm).coerceIn(0.0, 1.0)))
        }
    }
}
