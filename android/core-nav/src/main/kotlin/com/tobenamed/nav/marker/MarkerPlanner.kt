package com.tobenamed.nav.marker

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.model.GeocodePrecision
import kotlin.math.atan2

/** A Street View panorama the app could show (id + position from the Street View SDK / metadata). */
data class PanoCandidate(val id: String, val position: LatLng)

sealed interface MarkerStyle {
    /** Building-level location. A rooftop coordinate does not assert an entrance. */
    data object Pin : MarkerStyle

    /** Approximate location: highlight a band ±[halfWidthDeg] around the heading. */
    data class Band(val halfWidthDeg: Double) : MarkerStyle
}

/** How to set up the Street View camera and where to draw the destination marker. */
data class MarkerPlan(
    val panoId: String,
    /** Camera heading so the destination is centred; also the marker's heading. */
    val headingDeg: Double,
    /** Suggested camera pitch (positive = looking up). */
    val pitchDeg: Double,
    val style: MarkerStyle,
    val distanceM: Double,
)

/**
 * Places the destination marker on Street View without needing entrance data:
 * pick a nearby panorama, point the camera at the geocoded point, and choose a pin or a
 * band depending on how precise the geocode is.
 */
object MarkerPlanner {
    /** Approximate Street View camera height above the ground. */
    private const val CAMERA_HEIGHT_M = 2.5

    /** Height on the building front to aim at (around the top of a door / ground floor). */
    private const val AIM_HEIGHT_M = 3.0

    /**
     * Picks the closest panorama that isn't uncomfortably close (very close panoramas give a
     * steep, distorted view of the building). Falls back to the closest one overall.
     */
    fun choosePanorama(
        candidates: List<PanoCandidate>,
        destination: LatLng,
        minDistanceM: Double = 6.0,
        maxDistanceM: Double = 60.0,
    ): PanoCandidate? {
        val withDistance = candidates
            .map { it to Geo.distance(it.position, destination) }
            .filter { it.second <= maxDistanceM }
        return (withDistance.filter { it.second >= minDistanceM }.minByOrNull { it.second }
            ?: withDistance.minByOrNull { it.second })?.first
    }

    fun plan(
        pano: PanoCandidate,
        destination: LatLng,
        precision: GeocodePrecision,
    ): MarkerPlan {
        val distance = Geo.distance(pano.position, destination)
        val heading = Geo.bearing(pano.position, destination)
        val pitch = Geo.toDeg(atan2(AIM_HEIGHT_M - CAMERA_HEIGHT_M, distance.coerceAtLeast(1.0)))
            .coerceIn(0.0, 15.0)

        val style = when (precision) {
            GeocodePrecision.ENTRANCE, GeocodePrecision.ROOFTOP -> MarkerStyle.Pin
            GeocodePrecision.INTERPOLATED, GeocodePrecision.APPROXIMATE -> MarkerStyle.Band(
                halfWidthDeg = Geo.toDeg(atan2(precision.uncertaintyM, distance.coerceAtLeast(1.0)))
                    .coerceIn(5.0, 60.0),
            )
        }

        return MarkerPlan(
            panoId = pano.id,
            headingDeg = heading,
            pitchDeg = pitch,
            style = style,
            distanceM = distance,
        )
    }
}
