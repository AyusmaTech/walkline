package com.tobenamed.nav.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate in decimal degrees. */
data class LatLng(val lat: Double, val lng: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }
}

/** Spherical-earth helpers. Accurate to well under a metre at the distances this app cares about. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** Great-circle distance in metres (haversine). */
    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLng = toRad(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from true north in [0, 360). */
    fun bearing(from: LatLng, to: LatLng): Double {
        val lat1 = toRad(from.lat)
        val lat2 = toRad(to.lat)
        val dLng = toRad(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return normalizeBearing(toDeg(atan2(y, x)))
    }

    /** Point reached by travelling [distanceM] from [from] on initial bearing [bearingDeg]. */
    fun offset(from: LatLng, bearingDeg: Double, distanceM: Double): LatLng {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = toRad(bearingDeg)
        val lat1 = toRad(from.lat)
        val lng1 = toRad(from.lng)
        val lat2 = asin(sin(lat1) * cos(delta) + cos(lat1) * sin(delta) * cos(theta))
        val lng2 = lng1 + atan2(sin(theta) * sin(delta) * cos(lat1), cos(delta) - sin(lat1) * sin(lat2))
        val lngDeg = ((toDeg(lng2) + 540.0) % 360.0) - 180.0
        return LatLng(toDeg(lat2), lngDeg)
    }

    /** Normalises any angle to [0, 360). */
    fun normalizeBearing(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Signed smallest difference `a - b`, in (-180, 180]. Positive means [a] is clockwise of [b]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = normalizeBearing(a - b)
        if (d > 180.0) d -= 360.0
        return d
    }
}

/** Planar x/y position in metres: x = east, y = north. */
data class Xy(val x: Double, val y: Double)

/**
 * Local equirectangular projection around [origin].
 * Good to centimetres within a few kilometres, which covers any walking session.
 */
class LocalFrame(val origin: LatLng) {
    private val cosLat = cos(Geo.toRad(origin.lat))

    fun toXy(p: LatLng): Xy = Xy(
        x = Geo.toRad(Geo.angleDiff(p.lng, origin.lng)) * cosLat * Geo.EARTH_RADIUS_M,
        y = Geo.toRad(p.lat - origin.lat) * Geo.EARTH_RADIUS_M,
    )

    fun toLatLng(p: Xy): LatLng = LatLng(
        lat = origin.lat + Geo.toDeg(p.y / Geo.EARTH_RADIUS_M),
        lng = Geo.normalizeBearing(origin.lng + Geo.toDeg(p.x / (Geo.EARTH_RADIUS_M * cosLat)) + 180.0) - 180.0,
    )
}
