package com.tobenamed.nav.model

import com.tobenamed.nav.geo.LatLng

/**
 * One position fix, already converted from the platform type
 * (Android `Location` / iOS `CLLocation`, or an ARCore Geospatial pose).
 *
 * @property accuracyM horizontal accuracy radius in metres (both platforms report ~68% confidence).
 * @property courseDeg direction of travel reported by the GNSS chip, if valid.
 * @property headingDeg direction the device is facing (compass / AR pose), if known.
 */
data class LocationSample(
    val timeMs: Long,
    val position: LatLng,
    val accuracyM: Double,
    val courseDeg: Double? = null,
    val headingDeg: Double? = null,
    val source: Source = Source.GNSS,
) {
    init {
        require(timeMs >= 0) { "Location timestamp must be nonnegative" }
        require(accuracyM.isFinite() && accuracyM >= 0) { "Invalid horizontal accuracy" }
        require(courseDeg == null || courseDeg.isFinite()) { "Invalid course" }
        require(headingDeg == null || headingDeg.isFinite()) { "Invalid heading" }
    }

    enum class Source {
        /** Fused GPS / Wi-Fi / cell location. */
        GNSS,

        /** Visual positioning (ARCore Geospatial). */
        VPS,
    }
}

/** A detected walking step (Android step detector / iOS CMPedometer + device heading). */
data class StepEvent(
    val timeMs: Long,
    /** Direction of the step, degrees from true north. Usually the device heading while held in front. */
    val headingDeg: Double,
    val lengthM: Double = DEFAULT_STEP_LENGTH_M,
) {
    init {
        require(timeMs >= 0) { "Step timestamp must be nonnegative" }
        require(headingDeg.isFinite()) { "Invalid step heading" }
        require(lengthM.isFinite() && lengthM > 0 && lengthM <= 2.0) { "Invalid walking step length" }
    }

    companion object {
        const val DEFAULT_STEP_LENGTH_M = 0.7
    }
}

/** How precisely the geocoder located the address. Drives the destination marker style. */
enum class GeocodePrecision(val uncertaintyM: Double) {
    /** Building entrance data available. */
    ENTRANCE(2.0),

    /** Geocoder `ROOFTOP` result. */
    ROOFTOP(5.0),

    /** Geocoder `RANGE_INTERPOLATED` result: address estimated along the street. */
    INTERPOLATED(20.0),

    /** Geocoder `GEOMETRIC_CENTER` / `APPROXIMATE` result. */
    APPROXIMATE(40.0),
}
