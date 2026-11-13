package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

class LogScrubTest {
    @Test
    fun pathAndQueryAreCutToTheHost() {
        assertEquals(
            "Failed GET https://vikunja.example.com for the user",
            LogScrub.addresses("Failed GET https://vikunja.example.com/api/v1/tasks?filter=done&s=secret for the user"),
        )
    }

    @Test
    fun userInfoAndPortAreDropped() {
        assertEquals("http://host.lan", LogScrub.addresses("http://bob:pw@host.lan:3456/x"))
        assertEquals("https://[::1]", LogScrub.addresses("https://[::1]:8080/x"))
    }

    @Test
    fun severalAddressesAndNoAddress() {
        assertEquals("a http://x b https://y", LogScrub.addresses("a http://x/1 b HTTPS://y/2".replace("HTTPS", "https")))
        assertEquals("nothing to see: 404", LogScrub.addresses("nothing to see: 404"))
    }

    @Test
    fun throwableLineHasTypeAndCleanMessageOnly() {
        val line = LogScrub.throwable(IllegalStateException("bad https://h.example/api/v1/x?q=1"))
        assertEquals(" - IllegalStateException: bad https://h.example", line)
    }
}
