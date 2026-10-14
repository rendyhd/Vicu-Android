package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.ApiTokenDto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiTokenTitleTest {

    @Test
    fun `title is the device title followed by the bracketed install id`() {
        assertEquals(
            "Vicu — Samsung SM-S918B [a1b2c3]",
            ApiTokenTitle.build("Vicu — Samsung SM-S918B", "a1b2c3"),
        )
    }

    @Test
    fun `generated install ids are six lowercase hex characters`() {
        repeat(50) {
            val id = ApiTokenTitle.newInstallId()
            assertEquals(6, id.length)
            assertTrue(ApiTokenTitle.isValidInstallId(id), "Unexpected install id: $id")
        }
    }

    @Test
    fun `generated install ids differ between calls`() {
        val ids = (1..20).map { ApiTokenTitle.newInstallId(Random(it)) }.toSet()
        assertTrue(ids.size > 1)
    }

    @Test
    fun `install id validation rejects blank, short, long and non-hex values`() {
        assertFalse(ApiTokenTitle.isValidInstallId(""))
        assertFalse(ApiTokenTitle.isValidInstallId("a1b2c"))
        assertFalse(ApiTokenTitle.isValidInstallId("a1b2c3d"))
        assertFalse(ApiTokenTitle.isValidInstallId("a1b2cg"))
        assertFalse(ApiTokenTitle.isValidInstallId("A1B2C3"))
    }

    @Test
    fun `a token is owned by an install only when it carries that install id`() {
        val title = ApiTokenTitle.build("Vicu — Pixel 8", "a1b2c3")
        assertTrue(ApiTokenTitle.isOwnedByInstall(title, "a1b2c3"))
        assertFalse(ApiTokenTitle.isOwnedByInstall(title, "ffffff"))
    }

    @Test
    fun `legacy titles without an install id are never owned`() {
        assertFalse(ApiTokenTitle.isOwnedByInstall("Vicu — Pixel 8", "a1b2c3"))
    }

    @Test
    fun `tokens from other apps are never owned even with a matching suffix`() {
        assertFalse(ApiTokenTitle.isOwnedByInstall("Backup script [a1b2c3]", "a1b2c3"))
    }

    @Test
    fun `an invalid install id owns nothing`() {
        assertFalse(ApiTokenTitle.isOwnedByInstall("Vicu — Pixel 8 []", ""))
        assertFalse(ApiTokenTitle.isOwnedByInstall("Vicu — Pixel 8 [a1b2c3]", "a1b2c"))
    }

    @Test
    fun `sibling cleanup keeps the new token, other phones, legacy tokens and manual tokens`() {
        val tokens = listOf(
            ApiTokenDto(id = 10, title = "Vicu — Pixel 8 [a1b2c3]"), // the token just created
            ApiTokenDto(id = 11, title = "Vicu — Pixel 8 [a1b2c3]"), // this install, stale
            ApiTokenDto(id = 12, title = "Vicu — Pixel 8 [ffffff]"), // another phone, same model
            ApiTokenDto(id = 13, title = "Vicu — Pixel 8"), // legacy title, owner unknown
            ApiTokenDto(id = 14, title = "My own token"), // user created
            ApiTokenDto(id = 15, title = "Vicu — Galaxy S24 [a1b2c3]"), // this install, old device label
        )

        val doomed = ApiTokenTitle.siblingIds(tokens, installId = "a1b2c3", keepId = 10)

        assertEquals(listOf(11L, 15L), doomed)
    }
}
