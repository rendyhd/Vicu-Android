package com.rendyhd.vicu.location

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Place search with Photon (photon.komoot.io, OpenStreetMap data), for Android, which has no
 * built-in geocoder that works without Google. Its own HTTP client: nothing of the Vikunja session
 * (base URL, token) goes along. Only the typed text and, to rank nearby places first, the rounded
 * position are sent.
 */
class PhotonPlaceSearch(
    engine: HttpClientEngine,
    /** Photon asks clients to identify themselves. */
    private val userAgent: String,
    private val baseUrl: String = "https://photon.komoot.io",
) : PlaceSearch {

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
        }
    }

    override val attribution: String = "Search by Photon · © OpenStreetMap contributors"

    override suspend fun search(query: String, near: GeoPoint?): List<PlaceSuggestion> {
        val response = client.get("$baseUrl/api/") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("q", query)
            parameter("limit", LIMIT)
            near?.let {
                // Two decimals (about a kilometre) is enough to rank results.
                parameter("lat", round2(it.latitude))
                parameter("lon", round2(it.longitude))
            }
        }
        if (!response.status.isSuccess()) error("Photon answered ${response.status.value}")
        return parse(response.bodyAsText())
    }

    override suspend fun reverse(point: GeoPoint): PlaceSuggestion? {
        val response = client.get("$baseUrl/reverse") {
            header(HttpHeaders.UserAgent, userAgent)
            // Only a name is wanted back, and the caller keeps the exact point on the device, so
            // two decimals (about a kilometre) is all that is sent.
            parameter("lat", round2(point.latitude))
            parameter("lon", round2(point.longitude))
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) return null
        return parse(response.bodyAsText()).firstOrNull()
    }

    internal companion object {
        const val LIMIT = 8
        private val json = Json { ignoreUnknownKeys = true }

        private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100

        /** The GeoJSON features Photon answers with, as places; a feature without a point is skipped. */
        fun parse(body: String): List<PlaceSuggestion> {
            val root = json.parseToJsonElement(body).jsonObject
            val features = root["features"] as? JsonArray ?: return emptyList()
            return features.mapNotNull { feature ->
                val obj = feature as? JsonObject ?: return@mapNotNull null
                val coordinates = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
                val lon = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val props = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())
                fun text(key: String) = (props[key]?.jsonPrimitive?.takeIf { it.isString }?.content).orEmpty().trim()
                val street = StreetAddress.line(text("street"), text("housenumber"), text("countrycode"))
                val name = text("name").ifBlank { street }.ifBlank { text("city") }.ifBlank { return@mapNotNull null }
                val town = listOf(text("postcode"), text("city").ifBlank { text("district") }).filter { it.isNotBlank() }.joinToString(" ")
                val address = listOf(street, town, text("country"))
                    .filter { it.isNotBlank() && it != name }
                    .distinct()
                    .joinToString(", ")
                PlaceSuggestion(name = name, address = address, latitude = lat, longitude = lon)
            }
        }
    }
}
