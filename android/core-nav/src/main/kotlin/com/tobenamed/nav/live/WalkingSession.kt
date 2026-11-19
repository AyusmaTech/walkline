package com.tobenamed.nav.live

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.route.RouteState
import com.tobenamed.nav.route.RouteTracker

data class WalkingInstruction(val distanceM: Double, val text: String, val maneuver: String)

data class WalkingRoute(
    val points: List<LatLng>,
    val distanceM: Double,
    val durationSeconds: Double,
    val instructions: List<WalkingInstruction>,
    val warnings: List<String>,
    val source: String = "google",
) {
    init {
        require(distanceM.isFinite() && distanceM >= 0 && durationSeconds.isFinite() && durationSeconds >= 0)
        require(instructions.all { it.distanceM.isFinite() && it.distanceM >= 0 })
    }
    val line = Polyline(points)
}

enum class FixQuality { WAITING, GOOD, WEAK, STALE }

data class WalkingProgress(
    val route: RouteState?,
    val fix: LocationSample?,
    val quality: FixQuality,
    val instruction: String,
    val maneuver: String,
    val distanceToInstructionM: Double?,
    val arrowOffsetDeg: Double?,
    /** A provider can snap its route start away from the requested GPS origin. */
    val joiningRoute: Boolean = false,
    val routeEntry: LatLng? = null,
    val instructionIsUpcoming: Boolean = false,
    val instructionIndex: Int? = null,
)

/** Foreground baseline navigation. No street side is inferred from a sidewalk route. */
class WalkingSession(val route: WalkingRoute, val destination: LatLng, precision: GeocodePrecision) {
    private val tracker = RouteTracker(route.line, destination)
    private val canConfirmBuilding = precision == GeocodePrecision.ROOFTOP || precision == GeocodePrecision.ENTRANCE
    private var lastAcceptedTime: Long? = null
    private var lastObservedTime: Long? = null
    private var joinedRoute = false
    var progress = WalkingProgress(null, null, FixQuality.WAITING, "Waiting for your location", "STRAIGHT", null, null)
        private set

    fun update(sample: LocationSample, nowMs: Long): WalkingProgress {
        if (lastObservedTime?.let { sample.timeMs <= it } == true) return progress
        if (sample.timeMs > nowMs + 1_000) return progress
        lastObservedTime = sample.timeMs
        val age = nowMs - sample.timeMs
        if (age > MAX_FIX_AGE_MS) {
            return progress.copy(quality = FixQuality.STALE, instruction = "Waiting for a fresh location", arrowOffsetDeg = null).also { progress = it }
        }
        if (sample.accuracyM > MAX_GUIDANCE_ACCURACY_M) {
            return progress.copy(fix = sample, quality = FixQuality.WEAK,
                instruction = "Location is uncertain. Pause and let GPS improve.", arrowOffsetDeg = null).also { progress = it }
        }
        // Timers and regression history must not bridge a background pause or loss of GPS.
        if (lastAcceptedTime?.let { sample.timeMs - it > 10_000 } == true) tracker.reset()
        lastAcceptedTime = sample.timeMs
        val projection = route.line.project(sample.position)
        if (!joinedRoute && projection.distanceFromLineM <= 12.0) joinedRoute = true
        if (!joinedRoute) {
            val entry = projection.snapped
            val facing = sample.headingDeg ?: sample.courseDeg
            return WalkingProgress(null, sample, FixQuality.GOOD, "Join the walking route", "STRAIGHT",
                Geo.distance(sample.position, entry), facing?.let { Geo.angleDiff(Geo.bearing(sample.position, entry), it) },
                joiningRoute = true, routeEntry = entry).also { progress = it }
        }
        val state = tracker.update(sample, arrivalAllowed = canConfirmBuilding)
        val totalSteps = route.instructions.sumOf { it.distanceM }
        var stepEnd = 0.0
        var index = route.instructions.lastIndex
        var endAlong = route.line.lengthM
        for ((i, instruction) in route.instructions.withIndex()) {
            stepEnd += instruction.distanceM
            val end = if (totalSteps > 0) stepEnd / totalSteps * route.line.lengthM else route.line.lengthM
            if (end > state.alongTrackM + .5) { index = i; endAlong = end; break }
        }
        val distanceToTurn = (endAlong - state.alongTrackM).coerceAtLeast(0.0)
        val upcoming = distanceToTurn <= 30 && index + 1 <= route.instructions.lastIndex
        val next = if (upcoming) index + 1 else index
        val instruction = route.instructions.getOrNull(next)
        val maneuver = instruction?.maneuver ?: "STRAIGHT"
        // A turn describes entering a step. Preserve unsupported actions (for example
        // boarding a ferry) rather than inventing straight-ahead guidance for them.
        val displayedManeuver = if (upcoming || ManeuverCue.fromProvider(maneuver) == ManeuverCue.FOLLOW_ROUTE) maneuver else "STRAIGHT"
        val target = route.line.pointAt((state.alongTrackM + 12).coerceAtMost(route.line.lengthM))
        val facing = sample.headingDeg ?: state.travelBearingDeg
        val direction = if (Geo.distance(sample.position, target) > 1.0 && facing != null)
            Geo.angleDiff(Geo.bearing(sample.position, target), facing) else null
        return WalkingProgress(state, sample, FixQuality.GOOD,
            instruction?.text ?: "Continue along the walking route", displayedManeuver,
            distanceToTurn, direction, instructionIsUpcoming = upcoming, instructionIndex = next.takeIf { it >= 0 }).also { progress = it }
    }

    fun expire(nowMs: Long): WalkingProgress {
        if (progress.fix?.let { nowMs - it.timeMs > MAX_FIX_AGE_MS } == true) pause()
        return progress
    }

    fun pause() {
        progress = progress.copy(quality = FixQuality.STALE, instruction = "Guidance paused. Waiting for a fresh location.", arrowOffsetDeg = null)
        tracker.reset()
        lastAcceptedTime = null
    }

    companion object {
        const val MAX_FIX_AGE_MS = 15_000L
        const val MAX_GUIDANCE_ACCURACY_M = 25.0
    }
}
