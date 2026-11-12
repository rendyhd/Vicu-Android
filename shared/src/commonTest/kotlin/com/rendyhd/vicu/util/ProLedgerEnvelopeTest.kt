package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.pro.LedgerRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pro ledger's envelope (cross-app semantics 21.7), as the desktop's `pro-ledger-protocol.ts`. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ProLedgerEnvelopeTest {

    private fun ledgerWithPromise() = LedgerRules.setRecord(
        LedgerRules.empty(),
        "promises",
        "42",
        JsonObject(mapOf("dir" to JsonPrimitive("theirs"), "person" to JsonPrimitive(7))),
        "phone",
        1_794_421_060_000,
    )

    private fun marker(kind: String, version: Int, json: String): String =
        "<!-- vicu-$kind:v$version:${kotlin.io.encoding.Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')} -->"

    @Test
    fun `a ledger survives the envelope`() {
        val ledger = ledgerWithPromise()
        val description = ProLedgerEnvelope.encode(ledger)

        assertTrue(description.startsWith("<!-- vicu-pro:v1:"))
        assertTrue(HiddenTasks.isHidden(description))
        val parsed = ProLedgerEnvelope.parse(description)
        assertTrue(parsed.isCarrier)
        assertEquals(ledger, parsed.document)
    }

    @Test
    fun `the ledger and its archive parts are told apart by their markers`() {
        val part = ProLedgerEnvelope.encodeArchive(LedgerRules.emptyArchivePart("2026-Q2"))
        val ledger = ProLedgerEnvelope.encode(LedgerRules.empty())

        assertTrue(ProLedgerEnvelope.hasArchiveMarker(part))
        assertFalse(ProLedgerEnvelope.hasMarker(part))
        assertTrue(ProLedgerEnvelope.hasMarker(ledger))
        assertFalse(ProLedgerEnvelope.hasArchiveMarker(ledger))
        // The search text finds the ledger and not the parts.
        assertTrue(ProLedgerEnvelope.SEARCH in ledger)
        assertFalse(ProLedgerEnvelope.SEARCH in part)
        assertEquals("2026-Q2", ProLedgerEnvelope.parseArchive(part).document?.let(ProLedgerEnvelope::quarterOf))
    }

    @Test
    fun `a newer version is not read and says so`() {
        val newer = ProLedgerEnvelope.parse(marker("pro", 2, """{"version":2}"""))
        assertTrue(newer.isCarrier)
        assertTrue(newer.newerVersion)
        assertNull(newer.document)

        val newerPayload = ProLedgerEnvelope.parse(marker("pro", 1, """{"version":3,"promises":{}}"""))
        assertTrue(newerPayload.newerVersion)
    }

    @Test
    fun `fields this version does not know are kept`() {
        val parsed = ProLedgerEnvelope.parse(marker("pro", 1, """{"version":1,"later":{"a":1},"estimates":{}}"""))
        val document = assertNotNull(parsed.document)
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), document.json["later"])
        assertTrue(ProLedgerEnvelope.encode(document).let { ProLedgerEnvelope.parse(it).document?.json?.get("later") } != null)
    }

    @Test
    fun `a damaged carrier is a carrier without a document`() {
        val parsed = ProLedgerEnvelope.parse("<!-- vicu-pro:v1:!!! -->")
        assertTrue(parsed.isCarrier)
        assertNull(parsed.document)
        assertFalse(parsed.newerVersion)
        assertFalse(ProLedgerEnvelope.parse("Just notes").isCarrier)
    }
}
