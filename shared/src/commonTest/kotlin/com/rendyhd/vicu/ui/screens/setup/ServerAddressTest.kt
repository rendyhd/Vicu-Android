package com.rendyhd.vicu.ui.screens.setup

import kotlin.test.Test
import kotlin.test.assertEquals

/** The server address typed in Setup (review finding C-13): an API path at its end is dropped. */
class ServerAddressTest {

    @Test
    fun `a trailing API path and trailing slashes are dropped`() {
        val cases = mapOf(
            "https://tasks.example.com" to "https://tasks.example.com",
            "https://tasks.example.com/" to "https://tasks.example.com",
            "https://tasks.example.com/api/v1" to "https://tasks.example.com",
            "https://tasks.example.com/api/v1/" to "https://tasks.example.com",
            "https://tasks.example.com/api/v2//" to "https://tasks.example.com",
            "https://tasks.example.com/api" to "https://tasks.example.com",
            "https://tasks.example.com/API/V1" to "https://tasks.example.com",
            "https://example.com/vikunja/api/v1" to "https://example.com/vikunja",
            "https://example.com/vikunja/" to "https://example.com/vikunja",
        )
        for ((typed, expected) in cases) assertEquals(expected, normalizeServerAddress(typed), typed)
    }

    @Test
    fun `an address without a scheme gets https and keeps a typed http`() {
        assertEquals("https://tasks.example.com", normalizeServerAddress("  tasks.example.com/api/v1 "))
        assertEquals("http://192.168.1.5:3456", normalizeServerAddress("http://192.168.1.5:3456/api/v1"))
    }

    @Test
    fun `a path that only looks like the API is kept`() {
        assertEquals("https://example.com/apis", normalizeServerAddress("https://example.com/apis"))
        assertEquals("https://example.com/api/v3", normalizeServerAddress("https://example.com/api/v3"))
    }
}
