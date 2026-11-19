package com.tobenamed.app.live

import com.tobenamed.app.BuildConfig
import com.tobenamed.nav.geo.EncodedPolyline
import com.tobenamed.nav.geo.LatLng
import com.tobenamed.nav.live.WalkingInstruction
import com.tobenamed.nav.live.WalkingRoute
import com.tobenamed.nav.model.GeocodePrecision
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AddressCandidate(val placeId: String, val address: String, val position: LatLng,
    val precision: GeocodePrecision, val partialMatch: Boolean, val streetNames: List<String> = emptyList(), val streetNumber: String = "")
data class StreetApproach(val position: LatLng, val streetName: String, val selectedByUser: Boolean = false)
data class AddressResults(val candidates: List<AddressCandidate>, val source: String)
class NavigationError(val code: String, message: String) : IOException(message)

interface WalkingRepository {
    val configurationError: String?
    suspend fun resolve(address: String): AddressResults
    suspend fun streetApproach(destination: AddressCandidate): StreetApproach? = null
    suspend fun route(origin: LatLng, destination: LatLng): WalkingRoute
}

class HttpWalkingRepository(
    private val baseUrl: String = BuildConfig.NAV_API_BASE_URL,
    private val token: String = BuildConfig.NAV_API_TOKEN,
    private val debug: Boolean = BuildConfig.DEBUG,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : WalkingRepository {
    private var expectedSource: String? = null
    override val configurationError: String?
        get() {
            val url = baseUrl.toHttpUrlOrNull()
            if (url == null) return "Live navigation needs an API URL in the app configuration. See the setup guide."
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
                return "Use a plain API base URL without credentials, query parameters, or a fragment."
            if (!url.isHttps && !(debug && url.host in setOf("10.0.2.2", "127.0.0.1", "localhost")))
                return "The navigation service must use HTTPS. Local HTTP is allowed only in debug builds."
            if (token.isBlank()) return "Live navigation needs the private pilot API token. See the setup guide."
            return null
        }

    override suspend fun resolve(address: String): AddressResults {
        val data = post("/v1/geocode", JSONObject().put("address", address))
        val list = data.getJSONArray("candidates")
        val provider = source(data, checkExpected = false)
        val candidates = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val point = item.getJSONObject("position")
            AddressCandidate(item.getString("placeId"), item.getString("formattedAddress"),
                LatLng(point.getDouble("lat"), point.getDouble("lng")),
                GeocodePrecision.valueOf(item.getString("precision")), item.optBoolean("partialMatch"),
                item.optJSONArray("streetNames")?.let { names -> (0 until names.length()).map(names::getString) } ?: emptyList(),
                item.optString("streetNumber"))
        }
        expectedSource = provider
        return AddressResults(candidates, provider)
    }

    override suspend fun streetApproach(destination: AddressCandidate): StreetApproach? {
        val data = post("/v1/street-approach", JSONObject().put("placeId", destination.placeId)
            .put("position", JSONObject().put("lat", destination.position.lat).put("lng", destination.position.lng))
            .put("streetNames", org.json.JSONArray(destination.streetNames)).put("streetNumber", destination.streetNumber))
        source(data)
        val approach = data.optJSONObject("approach") ?: return null
        val point = approach.getJSONObject("position")
        return StreetApproach(LatLng(point.getDouble("lat"), point.getDouble("lng")), approach.getString("streetName"))
    }

    override suspend fun route(origin: LatLng, destination: LatLng): WalkingRoute {
        fun point(p: LatLng) = JSONObject().put("lat", p.lat).put("lng", p.lng)
        val data = post("/v1/routes", JSONObject().put("origin", point(origin)).put("destination", point(destination)))
        val steps = data.getJSONArray("steps")
        val warnings = data.getJSONArray("warnings")
        return WalkingRoute(EncodedPolyline.decode(data.getString("encodedPolyline")),
            data.getDouble("distanceMeters"), data.getDouble("durationSeconds"),
            (0 until steps.length()).map { i -> steps.getJSONObject(i).let {
                WalkingInstruction(it.getDouble("distanceMeters"), it.getString("instruction"), it.getString("maneuver"))
            } }, (0 until warnings.length()).map(warnings::getString), source(data))
    }

    private fun source(data: JSONObject, checkExpected: Boolean = true): String {
        val source = data.getString("source")
        if (source !in setOf("google", "openstreetmap", "fixture")) throw NavigationError("INVALID_RESPONSE", "Unknown navigation data source.")
        if (source == "fixture" && !debug) throw NavigationError("TEST_SERVICE", "A test service cannot be used by a release build.")
        if (checkExpected && expectedSource != null && expectedSource != source)
            throw NavigationError("PROVIDER_CHANGED", "The navigation provider changed. Search and confirm the address again.")
        return source
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        configurationError?.let { throw NavigationError("NOT_CONFIGURED", it) }
        if (path != "/v1/geocode") expectedSource?.let { body.put("dataSource", it) }
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        val raw = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        NavigationError("NETWORK", "Cannot reach the navigation service. Check your connection and try again."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val responseBody = it.body ?: throw IOException("Empty response")
                            if (responseBody.contentLength() > 2_000_000) throw IOException("Oversized response")
                            val output = ByteArrayOutputStream()
                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 2_000_000) throw IOException("Oversized response")
                                    output.write(buffer, 0, count)
                                }
                            }
                            val text = output.toString("UTF-8")
                            if (!it.isSuccessful) {
                                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                                throw NavigationError(error?.optString("code") ?: "HTTP_${it.code}",
                                    error?.optString("message")?.take(400) ?: "The navigation service could not complete the request.")
                            }
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
        return try { JSONObject(raw) } catch (_: Exception) {
            throw NavigationError("INVALID_RESPONSE", "The navigation service returned an unreadable response.")
        }
    }
}
