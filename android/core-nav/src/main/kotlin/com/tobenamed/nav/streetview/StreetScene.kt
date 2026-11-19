package com.tobenamed.nav.streetview

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.WalkingProgress
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.route.RouteStatus
import kotlin.math.atan2

enum class StreetSceneMode { BUILDING, WALK }

data class StreetRibbon(val outline: List<LatLng>)

data class StreetScene(
    val mode: StreetSceneMode,
    val queryPosition: LatLng,
    val focus: LatLng,
    val fallbackBearingDeg: Double,
    /** Route direction from the walker's route position, independent of the photographed viewpoint. */
    val guidanceBearingDeg: Double? = null,
    val searchRadiusM: Int = 25,
    val ribbon: List<StreetRibbon> = emptyList(),
    val guidanceProgressM: Double? = null,
)

/** Geometry for the Street View viewport. Panning the image never changes physical progress. */
object StreetScenePlanner {
    fun plan(destination: LatLng, route: WalkingRoute?, progress: WalkingProgress?, buildingView: Boolean,
        streetPosition: LatLng? = null): StreetScene {
        val hasFix = route != null && progress?.fix != null
        if (buildingView || !hasFix || progress.route?.status == RouteStatus.ARRIVED) {
            // Once a route exists, search from its approach rather than inside the rooftop geocode.
            val approach = streetPosition ?: route?.line?.let { it.pointAt((it.lengthM - 10).coerceAtLeast(0.0)) } ?: destination
            return StreetScene(StreetSceneMode.BUILDING, approach, destination, 0.0, searchRadiusM = 20)
        }
        val line = route.line
        val projection = line.project(progress.fix.position)
        val along = progress.route?.alongTrackM ?: progress.routeEntry?.let { line.project(it).alongTrackM } ?: projection.alongTrackM
        val focus = line.pointAt((along + 18).coerceAtMost(line.lengthM))
        val showRoute = progress.quality == FixQuality.GOOD && progress.route?.status == RouteStatus.ON_ROUTE && !progress.joiningRoute
        val routePosition = line.pointAt(along)
        val guidanceBearing = if (!showRoute) null else if (Geo.distance(routePosition, focus) > 1)
            Geo.bearing(routePosition, focus) else line.bearingAt(along)
        val ribbon = if (!showRoute) emptyList() else {
            val samples = generateSequence(along + 4) { it + 2.0 }.takeWhile { it < minOf(along + 35, line.lengthM - 1) }
                .map { distance ->
                    val point = line.pointAt(distance)
                    val bearing = line.bearingAt(distance)
                    Geo.offset(point, bearing - 90, .48) to Geo.offset(point, bearing + 90, .48)
                }.toList()
            samples.zipWithNext { a, b -> StreetRibbon(listOf(a.first, b.first, b.second, a.second)) }
        }
        val query = progress.fix.position
        return StreetScene(StreetSceneMode.WALK, query,
            focus, line.bearingAt(along), guidanceBearingDeg = guidanceBearing, ribbon = ribbon,
            guidanceProgressM = along.takeIf { showRoute })
    }

    /** Approximate ground plane only; this is not an AR/entrance accuracy claim. */
    fun groundPitchDeg(distanceM: Double): Double = -Geo.toDeg(atan2(2.5, distanceM.coerceAtLeast(1.0)))
}

/** Avoid a new panorama search for every GPS fix. Manual resume/retry can bypass the gate. */
class StreetSceneRequestGate {
    private var lastPosition: LatLng? = null
    private var lastTimeMs: Long? = null
    private var lastMode: StreetSceneMode? = null

    fun shouldRequest(scene: StreetScene, nowMs: Long, force: Boolean = false): Boolean {
        val previous = lastPosition
        val modeChanged = lastMode != scene.mode
        val moved = previous == null || Geo.distance(previous, scene.queryPosition) >= 6.0
        val held = lastTimeMs?.let { nowMs - it >= 2_000 } ?: true
        if (!force && previous != null && !modeChanged && !(moved && held)) return false
        lastPosition = scene.queryPosition
        lastTimeMs = nowMs
        lastMode = scene.mode
        return true
    }
}
