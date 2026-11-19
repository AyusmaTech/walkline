package com.tobenamed.nav

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.model.LocationSample

/** Helpers for building synthetic walks in tests. */
object TestGeo {
    /** Arbitrary reference point (Houston, TX). */
    val BASE = LatLng(29.7604, -95.3698)

    /** Point [eastM] east and [northM] north of [origin]. */
    fun at(eastM: Double, northM: Double, origin: LatLng = BASE): LatLng =
        Geo.offset(Geo.offset(origin, 90.0, eastM), 0.0, northM)

    fun fix(
        timeMs: Long,
        eastM: Double,
        northM: Double,
        accuracyM: Double = 4.0,
        courseDeg: Double? = null,
        source: LocationSample.Source = LocationSample.Source.GNSS,
    ) = LocationSample(timeMs, at(eastM, northM), accuracyM, courseDeg = courseDeg, source = source)
}
