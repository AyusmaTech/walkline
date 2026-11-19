package com.tobenamed.app

import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.geo.LocalFrame
import com.tobenamed.nav.geo.Polyline
import com.tobenamed.nav.geo.Xy
import com.tobenamed.nav.model.LocationSample
import com.tobenamed.nav.model.StepEvent
import com.tobenamed.nav.route.RouteState
import com.tobenamed.nav.route.RouteStatus
import com.tobenamed.nav.route.RouteTracker
import com.tobenamed.nav.street.SideGuidance
import com.tobenamed.nav.street.SideOfStreetTracker
import com.tobenamed.nav.street.StreetSide

/** A deliberately synthetic street. Never associated with an imported real address. */
class DemoWalk {
    private val frame = LocalFrame(LatLng(29.7604, -95.3698))
    val route = Polyline(listOf(point(0.0, 6.0), point(120.0, 6.0)))
    private val tracker = RouteTracker(route)
    private val sides = SideOfStreetTracker(
        Polyline(listOf(point(0.0, 0.0), point(160.0, 0.0))), route.end,
    )
    private var timeMs = 0L
    private var eastM = 20.0
    private var northM = 6.0
    private var courseDeg = 90.0

    data class Snapshot(
        val route: RouteState,
        val side: SideGuidance,
        val eastM: Double,
        val northM: Double,
    )

    init { sides.seed(StreetSide.LEFT) }

    var snapshot = update()
        private set

    fun advance(forward: Boolean): Snapshot {
        if (snapshot.route.status == RouteStatus.ARRIVED) return snapshot
        timeMs += 5_000
        courseDeg = if (forward) 90.0 else 270.0
        eastM = (eastM + if (forward) 6.0 else -6.0).coerceIn(0.0, 120.0)
        return update().also { snapshot = it }
    }

    fun crossStreet(): Snapshot {
        if (snapshot.route.status == RouteStatus.ARRIVED) return snapshot
        val crossingHeading = if (northM > 0) 180.0 else 0.0
        // Feed the same engine that will receive real step-sensor events later.
        repeat(12) {
            timeMs += 500
            sides.onStep(StepEvent(timeMs, crossingHeading, lengthM = 1.0))
        }
        northM = -northM
        return update().also { snapshot = it }
    }

    private fun update(): Snapshot {
        val fix = LocationSample(timeMs, point(eastM, northM), accuracyM = 4.0, courseDeg = courseDeg)
        sides.onLocation(fix)
        val side = sides.guidance(courseDeg)
        val state = tracker.update(fix, arrivalAllowed = side !is SideGuidance.CrossStreet)
        return Snapshot(state, side, eastM, northM)
    }

    private fun point(east: Double, north: Double) = frame.toLatLng(Xy(east, north))
}
