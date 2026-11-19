package com.tobenamed.nav.route

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.model.LocationSample
import kotlin.math.abs
import kotlin.math.max

enum class RouteStatus { ON_ROUTE, WRONG_WAY, OFF_ROUTE, ARRIVED }

data class RouteState(
    val status: RouteStatus,
    val alongTrackM: Double,
    val remainingM: Double,
    val crossTrackM: Double,
    /** Bearing of the route where the user currently is. */
    val routeBearingDeg: Double,
    /** Best estimate of the direction the user is moving, if known. */
    val travelBearingDeg: Double?,
    val distanceToDestinationM: Double,
)

/**
 * Follows the user along a walking route and decides whether they are on route,
 * walking the wrong way, off route (needs a re-route), or have arrived.
 *
 * Works with plain GPS (baseline tier). Direction and off-route alerts are debounced;
 * arrival requires a usable fix near the end of the route and the session's permission.
 */
class RouteTracker(
    val route: Polyline,
    val destination: LatLng = route.end,
    private val config: Config = Config(),
) {
    data class Config(
        /** Time window used to judge progress / regression. */
        val windowMs: Long = 5_000,
        /** Backward travel must persist; a single bad course or fix must never raise an alert. */
        val wrongWayHoldMs: Long = 4_000,
        /** Minimum backwards movement along the route before "wrong way" can trigger. */
        val minRegressionM: Double = 4.0,
        /** Travel direction must differ from the route bearing by at least this much. */
        val wrongWayHeadingDeg: Double = 120.0,
        /** Forward progress needed to clear the wrong-way state. */
        val clearProgressM: Double = 3.0,
        /** Distance from the route considered off route (also scaled by GPS accuracy). */
        val offRouteM: Double = 20.0,
        /** How long the user must stay off route before a re-route is requested. */
        val offRouteHoldMs: Long = 6_000,
        val arrivalRadiusM: Double = 12.0,
        /** Minimum movement between window ends to derive a travel bearing from positions. */
        val minMoveForBearingM: Double = 3.0,
    )

    private data class Entry(val timeMs: Long, val along: Double, val position: LatLng)

    private val window = ArrayDeque<Entry>()
    private var wrongWay = false
    private var offRouteSince: Long? = null
    private var arrived = false
    private var backwardsSince: Long? = null
    private var lastState: RouteState? = null

    /** [arrivalAllowed] lets the session withhold arrival on a confirmed opposite sidewalk. */
    fun update(sample: LocationSample, arrivalAllowed: Boolean = true): RouteState {
        // Platform callbacks can deliver cached or reordered fixes. They must not advance timers.
        if (window.lastOrNull()?.timeMs?.let { sample.timeMs <= it } == true) return lastState!!
        val previousAlong = window.lastOrNull()?.along
        val proj = route.project(sample.position)
        val along = proj.alongTrackM
        // Projection is clamped at route endpoints. Keep signed progress before the start,
        // otherwise walking away immediately after route creation can never regress.
        val progressAlong = when {
            along <= 0.01 && proj.beyondEndsM > 0 -> -proj.beyondEndsM
            along >= route.lengthM - 0.01 && proj.beyondEndsM > 0 -> route.lengthM + proj.beyondEndsM
            else -> along
        }

        window.addLast(Entry(sample.timeMs, progressAlong, sample.position))
        while (window.size > 1 && sample.timeMs - window.first().timeMs > config.windowMs) {
            window.removeFirst()
        }

        val routeBearing = proj.segmentBearingDeg
        val travelBearing = travelBearing(sample)
        val headingOffset = travelBearing?.let { abs(Geo.angleDiff(it, routeBearing)) }

        // --- Wrong way -----------------------------------------------------------------------
        val maxAlong = window.maxOf { it.along }
        val minAlong = window.minOf { it.along }
        val regression = maxAlong - progressAlong
        val progress = progressAlong - minAlong
        val regressionNeeded = max(config.minRegressionM, sample.accuracyM * 0.5)

        val movingBackwards = previousAlong != null && progressAlong < previousAlong - 0.05 &&
            (headingOffset == null || headingOffset >= config.wrongWayHeadingDeg)
        backwardsSince = if (movingBackwards) backwardsSince ?: sample.timeMs else null
        val sustainedRegression = backwardsSince?.let { sample.timeMs - it >= config.wrongWayHoldMs } ?: false

        if (!wrongWay) {
            val directionSaysBackwards = headingOffset?.let { it >= config.wrongWayHeadingDeg }
            wrongWay = when (directionSaysBackwards) {
                true -> sustainedRegression && regression >= regressionNeeded
                false -> false
                // No usable direction: require a much clearer regression.
                null -> sustainedRegression && regression >= regressionNeeded * 2
            }
        } else {
            val directionSaysForwards = headingOffset?.let { it < 60.0 } ?: true
            if (progress >= config.clearProgressM && directionSaysForwards) {
                wrongWay = false
            }
        }

        // --- Off route -----------------------------------------------------------------------
        val offRouteLimit = max(config.offRouteM, sample.accuracyM * 1.5)
        val isFar = proj.distanceFromLineM > offRouteLimit
        offRouteSince = if (isFar) offRouteSince ?: sample.timeMs else null
        val offRoute = offRouteSince?.let { sample.timeMs - it >= config.offRouteHoldMs } ?: false

        // --- Arrival -------------------------------------------------------------------------
        val toDestination = Geo.distance(sample.position, destination)
        if (arrivalAllowed && sample.accuracyM <= config.arrivalRadiusM &&
            route.lengthM - along <= config.arrivalRadiusM * 2 && toDestination <= config.arrivalRadiusM
        ) arrived = true

        val status = when {
            arrived -> RouteStatus.ARRIVED
            offRoute -> RouteStatus.OFF_ROUTE
            wrongWay -> RouteStatus.WRONG_WAY
            else -> RouteStatus.ON_ROUTE
        }

        return RouteState(
            status = status,
            alongTrackM = along,
            remainingM = (route.lengthM - along).coerceAtLeast(0.0),
            crossTrackM = proj.crossTrackM,
            routeBearingDeg = routeBearing,
            travelBearingDeg = travelBearing,
            distanceToDestinationM = toDestination,
        ).also { lastState = it }
    }

    /** Call after fetching a new route; the tracker should be recreated with the new polyline. */
    fun reset() {
        window.clear()
        wrongWay = false
        offRouteSince = null
        arrived = false
        backwardsSince = null
        lastState = null
    }

    /**
     * Direction of travel: GNSS course if available, otherwise the bearing between the oldest
     * and newest positions in the window (if the user has moved enough), otherwise the compass.
     */
    private fun travelBearing(sample: LocationSample): Double? {
        sample.courseDeg?.let { return it }
        val first = window.first()
        val last = window.last()
        if (Geo.distance(first.position, last.position) >= config.minMoveForBearingM) {
            return Geo.bearing(first.position, last.position)
        }
        return sample.headingDeg
    }
}
