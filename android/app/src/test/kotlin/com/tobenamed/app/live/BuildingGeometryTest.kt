package com.tobenamed.app.live

import com.tobenamed.nav.geo.Geo
import com.tobenamed.nav.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class BuildingGeometryTest {
    private val center = LatLng(41.89, -87.79)
    private fun ring(radius: Double, at: LatLng = center): List<Point> {
        val points = listOf(-radius to -radius, -radius to radius, radius to radius, radius to -radius, -radius to -radius)
        return points.map { (east, north) -> Geo.offset(Geo.offset(at, 0.0, north), 90.0, east).let { Point.fromLngLat(it.lng, it.lat) } }
    }
    private fun building(rings: List<List<Point>> = listOf(ring(10.0)), height: Double = 18.0) =
        Feature.fromGeometry(Polygon.fromLngLats(rings)).apply { addNumberProperty("render_height", height) }

    @Test
    fun `roof rails and corner columns preserve the real footprint and height`() {
        val result = BuildingGeometry.build(listOf(building()), center, center)
        assertEquals(1, result.count)
        assertEquals(1, result.selected.features()!!.size)
        val edges = result.edges.features()!!
        assertEquals(4, edges.count { it.getStringProperty("kind") == "corner" })
        assertEquals(4, edges.count { it.getStringProperty("kind") == "roof" })
        assertEquals(4, edges.count { it.getStringProperty("kind") == "halo" })
        assertTrue(edges.all { it.getNumberProperty("top").toDouble() in 17.8..18.3 })
        assertTrue(edges.all { it.getBooleanProperty("selected") })
        assertTrue(edges.flatMap { (it.geometry() as Polygon).coordinates().flatten() }.all {
            Geo.distance(center, LatLng(it.latitude(), it.longitude())) < 15
        })
    }

    @Test
    fun `courtyards and a nearby different building do not become the destination`() {
        val courtyard = BuildingGeometry.build(listOf(building(listOf(ring(10.0), ring(2.0)))), center, center)
        assertTrue(courtyard.selected.features()!!.isEmpty())
        val outside = BuildingGeometry.build(listOf(building()), center, Geo.offset(center, 90.0, 30.0))
        assertTrue(outside.selected.features()!!.isEmpty())
        assertTrue(outside.edges.features()!!.none { it.getBooleanProperty("selected") })
    }

    @Test
    fun `unmapped heights have a modest visual fallback while invalid heights are skipped`() {
        val noHeight = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring(10.0))))
        val fallback = BuildingGeometry.build(listOf(noHeight), center, center)
        assertEquals(6.0, fallback.selected.features()!!.single().getNumberProperty("top").toDouble())
        assertEquals(0, BuildingGeometry.build(listOf(building(height = Double.NaN)), center, center).count)
        assertEquals(0, BuildingGeometry.build(listOf(building(height = -20.0)), center, center).count)
    }

    @Test
    fun `duplicate tiles and distant geometry cannot expand the edge mesh without bounds`() {
        val repeated = BuildingGeometry.build(List(20) { building() }, center, center)
        assertEquals(1, repeated.count)
        val many = (0 until 140).map {
            val shifted = Geo.offset(center, 90.0, it.toDouble())
            building(listOf(ring(1.0, shifted)))
        }
        val bounded = BuildingGeometry.build(many, center, center)
        assertEquals(90, bounded.count)
        assertTrue(bounded.edges.features()!!.size <= 90 * 130)
        val far = building(listOf(ring(5.0, Geo.offset(center, 0.0, 1000.0))))
        assertEquals(0, BuildingGeometry.build(listOf(far), center, center).count)
    }

    @Test
    fun `empty routes cannot send null geometries through the native GeoJSON bridge`() {
        val point = Feature.fromGeometry(Point.fromLngLat(center.lng, center.lat))
        val collection = mapFeatures(null, Feature.fromGeometry(null), point)
        assertEquals(1, collection.features()!!.size)
        assertTrue(collection.features()!!.all { it.geometry() != null })
        assertTrue(mapFeatures().features()!!.isEmpty())
    }

    @Test
    fun `building outlines marked flat cannot cover their detailed tower parts`() {
        val outline = building(height = 300.0).apply { addBooleanProperty("hide_3d", true) }
        val part = building(listOf(ring(5.0)), height = 150.0)
        val mesh = BuildingGeometry.build(listOf(outline, part), center, center)
        assertEquals(1, mesh.count)
        assertEquals(150.0, mesh.maxHeightM)
        assertTrue(mesh.edges.features()!!.all { it.getNumberProperty("top").toDouble() <= 150.3 })
    }

    @Test
    fun `zoom-dependent stroke width changes visibility without changing building identity or height`() {
        val thin = BuildingGeometry.build(listOf(building()), center, center)
        val broad = BuildingGeometry.build(listOf(building()), center, center, 1.2)
        assertEquals(thin.selected.features()!!.single().geometry(), broad.selected.features()!!.single().geometry())
        assertEquals(thin.maxHeightM, broad.maxHeightM)
        for (kind in listOf("corner", "roof", "halo")) {
            assertEquals(thin.edges.features()!!.count { it.getStringProperty("kind") == kind },
                broad.edges.features()!!.count { it.getStringProperty("kind") == kind })
        }
        assertTrue(broad.edges.features()!!.count { it.getStringProperty("kind") == "facade" } <=
            thin.edges.features()!!.count { it.getStringProperty("kind") == "facade" })
        assertTrue(thin.edges.features()!!.first().geometry() != broad.edges.features()!!.first().geometry())
    }

    @Test
    fun `facade ribs stay on exterior walls and within the mapped elevated building span`() {
        val raised = building(listOf(ring(10.0), ring(3.0)), 30.0).apply { addNumberProperty("render_min_height", 6.0) }
        val mesh = BuildingGeometry.build(listOf(raised), center, center)
        val ribs = mesh.edges.features()!!.filter { it.getStringProperty("kind") == "facade" }
        assertTrue(ribs.size in 8..12)
        assertTrue(ribs.all { it.getNumberProperty("base").toDouble() == 6.0 && it.getNumberProperty("top").toDouble() < 30.0 })
        assertTrue(ribs.flatMap { (it.geometry() as Polygon).coordinates().flatten() }.all {
            Geo.distance(center, LatLng(it.latitude(), it.longitude())) in 9.5..14.5
        })
        assertTrue(BuildingGeometry.build(listOf(building(height = 3.0)), center, center).edges.features()!!.none {
            it.getStringProperty("kind") == "facade"
        })
    }

    @Test
    fun `depth fading changes styling without changing footprint heights or destination identity`() {
        val shape = building()
        val near = BuildingGeometry.build(listOf(shape), center, center)
        val far = BuildingGeometry.build(listOf(shape), Geo.offset(center, 0.0, 220.0), center)
        assertEquals(near.bodies.features()!!.single().geometry(), far.bodies.features()!!.single().geometry())
        assertEquals(near.maxHeightM, far.maxHeightM)
        assertEquals("near", near.bodies.features()!!.single().getStringProperty("depth"))
        assertEquals("far", far.bodies.features()!!.single().getStringProperty("depth"))
        assertEquals(1, far.selected.features()!!.size)
        assertTrue(far.edges.features()!!.all { it.getNumberProperty("distance").toDouble() > 200 })
    }

    @Test
    fun `solid scene floor detailing preserves source identity mapped heights and a bounded mesh`() {
        val shape = building(height = 120.0)
        val identified = Feature.fromGeometry(shape.geometry(), shape.properties(), "123")
        val mesh = BuildingGeometry.build(listOf(identified), center, center, solidScene = true)
        val floors = mesh.edges.features()!!.filter { it.getStringProperty("kind") == "floor" }
        assertTrue(floors.size in 1..48)
        assertTrue(mesh.edges.features()!!.size <= 178)
        assertTrue(floors.all { it.getNumberProperty("base").toDouble() > 0 && it.getNumberProperty("top").toDouble() < 120 })
        assertTrue(mesh.edges.features()!!.all { it.getStringProperty("tileId") == "123" })
        assertEquals(120.0, mesh.bodies.features()!!.single().getNumberProperty("top").toDouble())
        assertEquals(shape.geometry(), mesh.selected.features()!!.single().geometry())
    }
}
