package com.tobenamed.app

import com.tobenamed.app.live.HttpWalkingRepository
import com.tobenamed.app.live.NavigationError
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.nav.model.GeocodePrecision
import com.tobenamed.nav.geo.LatLng
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WalkingRepositoryTest {
    @Test
    fun `open map content keeps its provenance and unknown sources are rejected`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"source":"openstreetmap","candidates":[{"placeId":"osm_W_123","formattedAddress":"12 Main St","position":{"lat":30,"lng":-95},"precision":"ROOFTOP","partialMatch":false,"streetNames":["Main Street"],"streetNumber":"12"}]}"""))
            val repo = HttpWalkingRepository(server.url("/").toString(), "pilot", true)
            val result = repo.resolve("12 Main")
            assertEquals("openstreetmap", result.source)
            assertEquals("osm_W_123", result.candidates.single().placeId)
            server.enqueue(MockResponse().setBody("""{"source":"untrusted","candidates":[]}"""))
            assertEquals("INVALID_RESPONSE", assertFailsWith<NavigationError> { repo.resolve("12 Main") }.code)
        }
    }

    @Test
    fun `provider changes cannot silently mix a confirmed open-map address with a Google route`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"source":"openstreetmap","candidates":[]}"""))
            server.enqueue(MockResponse().setBody("""{"source":"google","encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","distanceMeters":120,"durationSeconds":90,"warnings":[],"steps":[]}"""))
            val repo = HttpWalkingRepository(server.url("/").toString(), "pilot", true)
            repo.resolve("12 Main")
            assertEquals("PROVIDER_CHANGED", assertFailsWith<NavigationError> {
                repo.route(LatLng(30.0, -95.0), LatLng(30.1, -95.0))
            }.code)
            server.takeRequest()
            assertEquals("openstreetmap", JSONObject(server.takeRequest().body.readUtf8()).getString("dataSource"))
        }
    }

    @Test
    fun `client sends a private authenticated JSON lookup and handles chunked responses`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("""{"source":"google","candidates":[{"placeId":"a","formattedAddress":"12 Main","position":{"lat":30,"lng":-95},"precision":"ROOFTOP","partialMatch":true,"streetNames":["Main Street","Main St"],"streetNumber":"12"}]}""", 7))
            val repo = HttpWalkingRepository(server.url("/").toString(), "pilot", debug = true)
            val result = repo.resolve("12 Main")
            assertEquals(true, result.candidates.single().partialMatch)
            assertEquals(listOf("Main Street", "Main St"), result.candidates.single().streetNames)
            assertEquals("12", result.candidates.single().streetNumber)
            val request = server.takeRequest()
            assertEquals("/v1/geocode", request.path)
            assertEquals("Bearer pilot", request.getHeader("Authorization"))
            assertEquals("12 Main", JSONObject(request.body.readUtf8()).getString("address"))
        }
    }

    @Test
    fun `walking responses decode the real route format and retain provider warnings`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"source":"google","encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","distanceMeters":120,"durationSeconds":90,"warnings":["Check sidewalks"],"steps":[{"distanceMeters":120,"instruction":"Turn left","maneuver":"TURN_LEFT"}]}"""))
            val result = HttpWalkingRepository(server.url("/").toString(), "pilot", true).route(LatLng(30.0, -95.0), LatLng(30.1, -95.0))
            assertEquals(3, result.points.size)
            assertEquals(listOf("Check sidewalks"), result.warnings)
            assertEquals("TURN_LEFT", result.instructions.single().maneuver)
        }
    }

    @Test
    fun `street approach lookup retains the selected place and street while parsing a separate viewing point`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"source":"google","approach":{"position":{"lat":30,"lng":-94.9996},"streetName":"Main Street"}}"""))
            val candidate = AddressCandidate("house", "12 Main St", LatLng(30.0, -95.0), GeocodePrecision.ROOFTOP, false, listOf("Main St"), streetNumber = "12")
            val result = HttpWalkingRepository(server.url("/").toString(), "pilot", true).streetApproach(candidate)
            assertEquals(LatLng(30.0, -94.9996), result?.position)
            val request = server.takeRequest()
            assertEquals("/v1/street-approach", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("house", body.getString("placeId"))
            assertEquals("12", body.getString("streetNumber"))
            assertEquals("Main St", body.getJSONArray("streetNames").getString(0))
            assertEquals(-95.0, body.getJSONObject("position").getDouble("lng"))
        }
    }

    @Test
    fun `server errors remain explicit and local HTTP is forbidden in release configuration`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"RATE_LIMITED","message":"Try again later"}}"""))
            val error = assertFailsWith<NavigationError> {
                HttpWalkingRepository(server.url("/").toString(), "pilot", true).resolve("12 Main")
            }
            assertEquals("RATE_LIMITED", error.code)
            assertNotNull(HttpWalkingRepository(server.url("/").toString(), "pilot", false).configurationError)
            assertNotNull(HttpWalkingRepository("https://user:secret@example.test", "pilot", true).configurationError)
        }
    }
}
