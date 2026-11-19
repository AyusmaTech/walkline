package com.tobenamed.nav.geo

/** Google's encoded polyline format, with bounded input and strict truncated-data checks. */
object EncodedPolyline {
    fun decode(encoded: String): List<LatLng> {
        require(encoded.length <= 500_000) { "Route geometry is too large" }
        var index = 0
        var latitude = 0L
        var longitude = 0L
        fun coordinate(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length && shift <= 30) { "Invalid route geometry" }
                val value = encoded[index++].code - 63
                require(value in 0..63) { "Invalid route geometry" }
                result = result or ((value and 31).toLong() shl shift)
                shift += 5
                if (value < 32) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        return buildList {
            while (index < encoded.length) {
                latitude += coordinate()
                longitude += coordinate()
                add(LatLng(latitude / 100_000.0, longitude / 100_000.0))
            }
        }.also { points ->
            require(points.size >= 2 && points.any { it != points.first() }) { "Route has no usable path" }
        }
    }
}
