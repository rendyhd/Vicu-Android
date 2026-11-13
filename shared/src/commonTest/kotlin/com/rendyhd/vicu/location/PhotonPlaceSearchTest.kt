package com.rendyhd.vicu.location

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhotonPlaceSearchTest {

    private val body = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[4.8932,52.3731]},
           "properties":{"name":"Dam Square","street":"Dam","postcode":"1012","city":"Amsterdam","country":"Netherlands"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[4.9,52.36]},
           "properties":{"street":"Keizersgracht","housenumber":"12","city":"Amsterdam","country":"Netherlands"}},
          {"type":"Feature","properties":{"name":"No point"}}
        ]}
    """.trimIndent()

    @Test
    fun `features become places with a readable address`() {
        val places = PhotonPlaceSearch.parse(body)

        assertEquals(2, places.size)
        assertEquals(PlaceSuggestion("Dam Square", "Dam, 1012 Amsterdam, Netherlands", 52.3731, 4.8932), places[0])
        assertEquals("Keizersgracht 12", places[1].name)
        assertEquals("Amsterdam, Netherlands", places[1].address)
    }

    @Test
    fun `a street address is written in the order of the place's country`() {
        val places = PhotonPlaceSearch.parse(
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-122.03,37.33]},
               "properties":{"street":"Infinite Loop","housenumber":"1","postcode":"95014","city":"Cupertino","country":"United States","countrycode":"US"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[4.9,52.36]},
               "properties":{"street":"Keizersgracht","housenumber":"12","city":"Amsterdam","country":"Netherlands","countrycode":"NL"}}
            ]}
            """.trimIndent(),
        )

        assertEquals("1 Infinite Loop", places[0].name)
        assertEquals("95014 Cupertino, United States", places[0].address)
        assertEquals("Keizersgracht 12", places[1].name)
    }

    @Test
    fun `the search sends the text - a rounded position and a user agent`() = runTest {
        var url = ""
        var agent: String? = null
        val engine = MockEngine { request ->
            url = request.url.toString()
            agent = request.headers[HttpHeaders.UserAgent]
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val search = PhotonPlaceSearch(engine, userAgent = "Vicu/test")

        val places = search.search("dam", GeoPoint(52.37022, 4.89517))

        assertEquals(2, places.size)
        assertTrue(url.startsWith("https://photon.komoot.io/api/?q=dam"), url)
        assertTrue("lat=52.37" in url && "lon=4.9" in url && "4.895" !in url, url)
        assertEquals("Vicu/test", agent)
    }

    @Test
    fun `the reverse lookup sends a rounded position`() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val search = PhotonPlaceSearch(engine, userAgent = "Vicu/test")

        search.reverse(GeoPoint(52.37022, 4.89517))

        assertTrue("lat=52.37" in url && "lon=4.9" in url && "4.895" !in url && "52.370" !in url, url)
    }

    @Test
    fun `a failed search throws so the dialog can say so`() = runTest {
        val search = PhotonPlaceSearch(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }, userAgent = "Vicu/test")

        assertFailsWith<IllegalStateException> { search.search("dam", null) }
    }
}
