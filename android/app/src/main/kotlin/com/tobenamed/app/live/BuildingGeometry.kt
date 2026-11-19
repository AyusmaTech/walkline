package com.tobenamed.app.live

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal data class BuildingMesh(val edges: FeatureCollection, val bodies: FeatureCollection,
    val selected: FeatureCollection, val count: Int, val maxHeightM: Double)

/** Stylized facade ribs follow actual footprint walls; they are decoration, not mapped windows. */
internal object BuildingGeometry {
    fun build(features: List<Feature>, center: LatLng, destination: LatLng, edgeHalfWidthM: Double = .16,
        solidScene: Boolean = false): BuildingMesh {
        val stroke = if (edgeHalfWidthM.isFinite()) edgeHalfWidthM.coerceIn(.16, 2.0) else .16
        val edges = mutableListOf<Feature>()
        val bodies = mutableListOf<Feature>()
        val selected = mutableListOf<Feature>()
        val seen = mutableSetOf<String>()
        var count = 0
        var maxHeight = 0.0
        var accents = 0
        val polygons = features.flatMap { feature ->
            when (val geometry = feature.geometry()) {
                is Polygon -> listOf(feature to geometry.coordinates())
                is MultiPolygon -> geometry.coordinates().map { feature to it }
                else -> emptyList()
            }
        }.sortedBy { (_, rings) -> rings.firstOrNull()?.firstOrNull()?.let { Geo.distance(center, it.nav()) } ?: Double.MAX_VALUE }
        for ((feature, rings) in polygons) {
            if (count >= 90) break
            if (runCatching { feature.getProperty("hide_3d")?.asString }.getOrNull() in listOf("true", "1")) continue
            val outer = rings.firstOrNull()?.takeIf { it.size >= 4 } ?: continue
            if (outer.any { !it.latitude().isFinite() || !it.longitude().isFinite() || kotlin.math.abs(it.latitude()) > 90 || kotlin.math.abs(it.longitude()) > 180 }) continue
            if (Geo.distance(center, outer.first().nav()) > 280) continue
            val identity = outer.joinToString(";") { "${it.longitude()},${it.latitude()}" }
            if (!seen.add(identity)) continue
            val height = number(feature, "render_height") ?: number(feature, "height") ?: 6.0
            if (!height.isFinite() || height <= 0) continue
            val top = height.coerceIn(3.0, 700.0)
            val base = (number(feature, "render_min_height") ?: 0.0).coerceIn(0.0, top)
            if (!base.isFinite()) continue
            maxHeight = maxOf(maxHeight, top)
            val isDestination = contains(outer, destination) && rings.drop(1).none { contains(it, destination) }
            val vertices = outer.dropLast(1)
            val distance = Geo.distance(center, LatLng(vertices.map { it.latitude() }.average(), vertices.map { it.longitude() }.average()))
            val accent = isDestination || (accents < 3 && distance < 130 && top in 12.0..180.0)
            if (accent && !isDestination) accents++
            val tileId = feature.id().orEmpty()
            val body = Feature.fromGeometry(Polygon.fromLngLats(rings)).apply {
                addNumberProperty("top", top); addNumberProperty("base", base)
                addNumberProperty("distance", distance)
                addStringProperty("depth", if (distance < 85) "near" else if (distance < 170) "middle" else "far")
                addStringProperty("tileId", tileId); addBooleanProperty("selected", isDestination)
            }
            bodies += body
            if (isDestination) selected += body
            val firstEdge = edges.size
            // Limit geometry for complex buildings and clipped tile fragments.
            val stride = (vertices.size / 8).coerceAtLeast(1)
            vertices.filterIndexed { index, _ -> index % stride == 0 }.take(10).forEach { point ->
                edges += column(point.nav(), stroke * .75, base, top, isDestination, distance, "corner")
            }
            outer.zipWithNext().take(40).forEach { (start, end) ->
                val a = start.nav(); val b = end.nav()
                if (Geo.distance(a, b) > .5) {
                    edges += strip(a, b, stroke * 2.4, top - .15, top + .05, isDestination, distance, "halo")
                    edges += strip(a, b, stroke * .65, top + .08, top + .28, isDestination, distance, "roof")
                }
            }
            if (top - base >= 5 && (!solidScene || accent)) {
                var remaining = 40
                // Long walls get regular mullions first. Tiny corners and courtyards stay uncluttered.
                outer.zipWithNext().sortedByDescending { (a, b) -> Geo.distance(a.nav(), b.nav()) }.take(40).forEach { (a, b) ->
                    val length = Geo.distance(a.nav(), b.nav())
                    val divisions = (length / maxOf(5.0, stroke * 3)).toInt().coerceIn(1, 12)
                    val bearing = Geo.bearing(a.nav(), b.nav())
                    for (index in 1 until divisions) {
                        if (remaining <= 0) break
                        val point = Geo.offset(a.nav(), bearing, length * index / divisions)
                        edges += column(point, stroke * .50, base, top - .12, isDestination, distance, "facade")
                        remaining--
                    }
                }
            }
            if (solidScene && top - base >= 5) {
                val levels = ((top - base) / 3.2).toInt().coerceIn(1, 12)
                val walls = outer.zipWithNext().sortedByDescending { (a, b) -> Geo.distance(a.nav(), b.nav()) }.take(4)
                walls.forEachIndexed { wallIndex, (a, b) ->
                    val length = Geo.distance(a.nav(), b.nav())
                    if (length >= 5) {
                        val bearing = Geo.bearing(a.nav(), b.nav())
                        for (level in 1..levels) {
                            val phase = (wallIndex + level) % 3
                            val start = Geo.offset(a.nav(), bearing, length * (.12 + phase * .12))
                            val end = Geo.offset(a.nav(), bearing, length * (.70 + phase * .08))
                            val altitude = base + (top - base) * level / (levels + 1)
                            edges += strip(start, end, stroke * .48, altitude, altitude + .12,
                                isDestination, distance, "floor")
                        }
                    }
                }
            }
            for (index in firstEdge until edges.size) {
                edges[index].addStringProperty("tileId", tileId)
                edges[index].addBooleanProperty("accent", accent)
            }
            count++
        }
        return BuildingMesh(FeatureCollection.fromFeatures(edges), FeatureCollection.fromFeatures(bodies),
            FeatureCollection.fromFeatures(selected), count, maxHeight)
    }

    private fun column(point: LatLng, width: Double, base: Double, top: Double, selected: Boolean, distance: Double, kind: String): Feature {
        val a = Geo.offset(Geo.offset(point, 0.0, width), 90.0, width)
        val b = Geo.offset(Geo.offset(point, 0.0, width), 90.0, -width)
        val c = Geo.offset(Geo.offset(point, 0.0, -width), 90.0, -width)
        val d = Geo.offset(Geo.offset(point, 0.0, -width), 90.0, width)
        return rail(listOf(a, b, c, d, a), base, top, selected, distance, kind)
    }

    private fun strip(a: LatLng, b: LatLng, width: Double, base: Double, top: Double, selected: Boolean, distance: Double, kind: String): Feature {
        val bearing = Geo.bearing(a, b)
        val p = Geo.offset(a, bearing + 90, width); val q = Geo.offset(b, bearing + 90, width)
        val r = Geo.offset(b, bearing - 90, width); val s = Geo.offset(a, bearing - 90, width)
        return rail(listOf(p, q, r, s, p), base, top, selected, distance, kind)
    }

    private fun rail(points: List<LatLng>, base: Double, top: Double, selected: Boolean, distance: Double, kind: String) =
        Feature.fromGeometry(Polygon.fromLngLats(listOf(points.map { Point.fromLngLat(it.lng, it.lat) }))).apply {
            addNumberProperty("base", base); addNumberProperty("top", top); addBooleanProperty("selected", selected)
            addNumberProperty("distance", distance); addStringProperty("kind", kind)
        }

    private fun number(feature: Feature, key: String): Double? = runCatching { feature.getNumberProperty(key)?.toDouble() }.getOrNull()
    private fun Point.nav() = LatLng(latitude(), longitude())

    /** Even/odd containment retains courtyards, so a nearby building is never guessed by distance. */
    internal fun contains(ring: List<Point>, point: LatLng): Boolean {
        var inside = false
        if (ring.size < 4) return false
        var previous = ring.last()
        for (current in ring) {
            if ((current.latitude() > point.lat) != (previous.latitude() > point.lat) &&
                point.lng < (previous.longitude() - current.longitude()) * (point.lat - current.latitude()) /
                (previous.latitude() - current.latitude()) + current.longitude()) inside = !inside
            previous = current
        }
        return inside
    }
}
