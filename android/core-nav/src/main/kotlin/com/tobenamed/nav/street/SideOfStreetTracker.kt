package com.tobenamed.nav.street

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.model.StepEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Side of the street relative to the centre line's direction. */
enum class StreetSide {
    LEFT, RIGHT;

    fun opposite() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun ofCrossTrack(crossTrackM: Double) = if (crossTrackM >= 0) RIGHT else LEFT
    }
}

/** Left / right from the walker's point of view (what we show on screen). */
enum class RelativeSide { LEFT, RIGHT }

/** How the current side estimate was obtained. */
enum class SideSource {
    UNKNOWN,

    /** Set by the app, e.g. from which side of the road the walk started on. */
    SEEDED,

    /** Several GPS fixes whose accuracy circle lay entirely on one side. */
    GPS,

    /** Last known side, updated by detected street crossings (motion sensors). */
    CROSSING,

    /** Visual positioning / very accurate fix. */
    PRECISE,
}

sealed interface SideGuidance {
    /** User is not near the destination's street; side logic doesn't apply yet. */
    data object NotApplicable : SideGuidance

    /** We don't know which side the user is on: show a soft hint only. */
    data class Hint(val destinationOn: RelativeSide) : SideGuidance

    /** User is on the destination's side. */
    data class OnCorrectSide(val destinationOn: RelativeSide) : SideGuidance

    /** User is on the opposite side: show the "cross here" alert. */
    data class CrossStreet(val destinationOn: RelativeSide, val source: SideSource) : SideGuidance
}

/**
 * Works out which side of the destination's street the user is on, combining:
 *
 * 1. **Confident GPS fixes**: the whole accuracy circle lies on one side of the centre line,
 *    confirmed by [Config.fixesToConfirm] consecutive fixes.
 * 2. **Crossing tracking**: once the side is known, it only flips when a street crossing is
 *    detected from step events (one-sided CUSUM on movement perpendicular to the street).
 *    This keeps working in dense cities and at night, since it doesn't need the camera.
 * 3. **Precise fixes** (visual positioning or accuracy ≤ [Config.preciseAccuracyM]) set the side
 *    immediately.
 *
 * When unsure, it never raises an alert, it only offers a [SideGuidance.Hint].
 */
class SideOfStreetTracker(
    val centreline: Polyline,
    destination: LatLng,
    private val config: Config = Config(),
) {
    data class Config(
        /** Assumed kerb-to-kerb width when nothing better is known. */
        val streetWidthM: Double = 10.0,
        /** Fraction of the street width the user must move across to count as a crossing. */
        val crossingFraction: Double = 0.7,
        /** Per-step allowance that absorbs compass noise while walking along the street. */
        val cusumSlackPerStepM: Double = 0.1,
        /** The accuracy circle (radius × z) must fit entirely on one side. */
        val confidenceZ: Double = 1.0,
        /** Extra margin from the centre line on top of the accuracy circle. */
        val minMarginM: Double = 0.5,
        /** Fixes worse than this are ignored for side decisions. */
        val maxUsableAccuracyM: Double = 15.0,
        val fixesToConfirm: Int = 2,
        /** Pending confirmations expire after this long. */
        val confirmWindowMs: Long = 10_000,
        val preciseAccuracyM: Double = 3.0,
        /** GPS fixes can't overturn a precise fix this recent. */
        val preciseHoldMs: Long = 20_000,
        /** Beyond this distance from the centre line, the user isn't "on" this street. */
        val maxDistanceFromCentreM: Double = 25.0,
        /** How far past either end of the centre line still counts as on the street. */
        val maxBeyondEndsM: Double = 15.0,
        /** Crossing evidence needs a current street reference and consecutive walking steps. */
        val maxLocationAgeForStepsMs: Long = 10_000,
        val maxStepGapMs: Long = 3_000,
    )

    val destinationSide: StreetSide = StreetSide.ofCrossTrack(centreline.project(destination).crossTrackM)

    var side: StreetSide? = null
        private set
    var source: SideSource = SideSource.UNKNOWN
        private set

    private var pendingSide: StreetSide? = null
    private var pendingCount = 0
    private var pendingSinceMs = 0L
    private var lastPreciseMs: Long? = null

    /** Movement toward the other side of the street, accumulated (CUSUM). */
    private var towardOtherM = 0.0

    private var lastStreetBearingDeg: Double = centreline.bearingAt(0.0)
    private var onStreet = false
    private var lastLocationMs: Long? = null
    private var lastStepMs: Long? = null
    private val destinationHasSide = abs(centreline.project(destination).crossTrackM) > config.minMarginM

    /** Seed the side, e.g. from the side of the road the driver parked on. */
    fun seed(side: StreetSide) {
        setSide(side, SideSource.SEEDED)
    }

    fun onLocation(sample: LocationSample) {
        if (lastLocationMs?.let { sample.timeMs <= it } == true) return
        lastLocationMs = sample.timeMs
        val proj = centreline.project(sample.position)
        lastStreetBearingDeg = proj.segmentBearingDeg
        onStreet = proj.distanceFromLineM <= max(config.maxDistanceFromCentreM, config.streetWidthM) &&
            proj.beyondEndsM <= config.maxBeyondEndsM

        if (!onStreet) {
            side = null
            source = SideSource.UNKNOWN
            lastPreciseMs = null
            clearPending()
            towardOtherM = 0.0
            return
        }

        val d = proj.crossTrackM
        val candidate = StreetSide.ofCrossTrack(d)
        val isPrecise = sample.source == LocationSample.Source.VPS || sample.accuracyM <= config.preciseAccuracyM

        if (isPrecise) {
            clearPending()
            if (abs(d) > sample.accuracyM + config.minMarginM) {
                setSide(candidate, SideSource.PRECISE)
                lastPreciseMs = sample.timeMs
            }
            return
        }

        if (sample.accuracyM > config.maxUsableAccuracyM) {
            clearPending()
            return
        }
        val confident = abs(d) - config.confidenceZ * sample.accuracyM >= config.minMarginM
        if (!confident) {
            clearPending()
            return
        }

        val preciseIsRecent = lastPreciseMs?.let { sample.timeMs - it < config.preciseHoldMs } ?: false
        if (preciseIsRecent && candidate != side) return

        // Once established, baseline GPS cannot invent a crossing when it drifts across the road.
        if (side != null && candidate != side) {
            clearPending()
            return
        }

        if (candidate == pendingSide && sample.timeMs - pendingSinceMs <= config.confirmWindowMs) {
            pendingCount++
        } else {
            pendingSide = candidate
            pendingCount = 1
            pendingSinceMs = sample.timeMs
        }

        if (pendingCount >= config.fixesToConfirm) {
            if (side != candidate || source == SideSource.UNKNOWN) {
                setSide(candidate, SideSource.GPS)
            } else {
                // Confirms what we already believed: clear crossing evidence.
                towardOtherM = 0.0
            }
        }
    }

    fun onStep(step: StepEvent) {
        val previousStepMs = lastStepMs
        if (previousStepMs != null && step.timeMs <= previousStepMs) return
        lastStepMs = step.timeMs
        val locationAge = lastLocationMs?.let { step.timeMs - it }
        if (!onStreet || locationAge == null || locationAge !in 0..config.maxLocationAgeForStepsMs) {
            towardOtherM = 0.0
            return
        }
        if (previousStepMs != null && step.timeMs - previousStepMs > config.maxStepGapMs) towardOtherM = 0.0
        val current = side ?: return
        val offsetRad = Geo.toRad(Geo.angleDiff(step.headingDeg, lastStreetBearingDeg))
        // Positive lateral = moving toward the RIGHT side of the centre line.
        val lateral = step.lengthM * sin(offsetRad)
        val towardOther = if (current == StreetSide.RIGHT) -lateral else lateral

        towardOtherM = max(0.0, towardOtherM + towardOther - config.cusumSlackPerStepM)
        if (towardOtherM >= config.streetWidthM * config.crossingFraction) {
            setSide(current.opposite(), SideSource.CROSSING)
        }
    }

    /**
     * @param walkingBearingDeg direction the user is walking or facing; used to turn the
     *   street side into left/right from their point of view.
     */
    fun guidance(walkingBearingDeg: Double): SideGuidance {
        if (!onStreet || !destinationHasSide) return SideGuidance.NotApplicable

        val walkingWithLine = abs(Geo.angleDiff(walkingBearingDeg, lastStreetBearingDeg)) < 90.0
        val sideFromWalker = if (walkingWithLine) destinationSide else destinationSide.opposite()
        val relative = if (sideFromWalker == StreetSide.RIGHT) RelativeSide.RIGHT else RelativeSide.LEFT

        val current = side ?: return SideGuidance.Hint(relative)
        return if (current == destinationSide) {
            SideGuidance.OnCorrectSide(relative)
        } else {
            SideGuidance.CrossStreet(relative, source)
        }
    }

    private fun setSide(newSide: StreetSide, newSource: SideSource) {
        side = newSide
        source = newSource
        towardOtherM = 0.0
        lastPreciseMs = null
        clearPending()
    }

    private fun clearPending() {
        pendingSide = null
        pendingCount = 0
    }
}
