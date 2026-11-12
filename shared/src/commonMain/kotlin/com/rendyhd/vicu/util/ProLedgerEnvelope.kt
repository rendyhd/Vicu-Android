@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.rendyhd.vicu.util

import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.ProLedger
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The Pro ledger's envelope (docs/cross-app-semantics-v1.md section 21.7), the desktop's
 * `pro-ledger-protocol.ts`: a hidden done task titled [CARRIER_TITLE] whose description is
 * `<!-- vicu-pro:v1:<base64url JSON> -->`, and its archive parts `vicu-pro-archive:v1`, one per
 * quarter. The document rules are in `util/pro/Ledger.kt`.
 */
object ProLedgerEnvelope {
    const val CURRENT_VERSION = LedgerRules.VERSION
    const val CARRIER_TITLE = "Vicu Pro ledger (sync metadata — do not delete)"

    /** Finds the ledger and not its archive parts: `vicu-pro:` is not in `vicu-pro-archive:`. */
    const val SEARCH = "vicu-pro:v"
    const val ARCHIVE_SEARCH = "vicu-pro-archive"

    /** The most a carrier may hold when written (section 6.1). */
    const val WRITE_MAX_BYTES = 384 * 1024

    /** The most a carrier read is accepted at. */
    private const val READ_MAX_BYTES = 512 * 1024

    /** The sync status warns from here (section 21.7). */
    const val WARN_BYTES = WRITE_MAX_BYTES * 7 / 10

    fun archiveTitle(quarter: String): String = "Vicu Pro archive $quarter (sync metadata — do not delete)"

    private val ledgerStart = Regex("<!--[ \\t\\r\\n]*vicu-pro:")
    private val archiveStart = Regex("<!--[ \\t\\r\\n]*vicu-pro-archive:")
    private val ledgerMarker = Regex("<!--[ \\t\\r\\n]*vicu-pro:v(\\d+):([A-Za-z0-9_-]+={0,2})[ \\t\\r\\n]*-->")
    private val archiveMarker = Regex("<!--[ \\t\\r\\n]*vicu-pro-archive:v(\\d+):([A-Za-z0-9_-]+={0,2})[ \\t\\r\\n]*-->")

    private val json = Json { ignoreUnknownKeys = true }

    /** A carrier read: the document, or why there is none; [newerVersion] when a later app wrote it. */
    data class Parsed<T>(
        val isCarrier: Boolean,
        val document: T? = null,
        val newerVersion: Boolean = false,
        val error: String? = null,
    )

    fun hasMarker(description: String?): Boolean = !description.isNullOrEmpty() && ledgerStart.containsMatchIn(description)

    fun hasArchiveMarker(description: String?): Boolean = !description.isNullOrEmpty() && archiveStart.containsMatchIn(description)

    private class Decoded(val version: Int, val payload: JsonObject?, val error: String?)

    private fun decode(match: MatchResult, what: String): Decoded {
        val version = match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        return try {
            val encoded = match.groupValues[2].trimEnd('=')
            val bytes = Base64.UrlSafe.decode(encoded + "=".repeat((4 - encoded.length % 4) % 4))
            if (bytes.size > READ_MAX_BYTES) return Decoded(version, null, "$what is too large")
            Decoded(version, json.parseToJsonElement(bytes.decodeToString()) as? JsonObject, null)
        } catch (e: Exception) {
            Decoded(version, null, "Cannot decode $what")
        }
    }

    private fun payloadVersion(payload: JsonObject?): Long? =
        (payload?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun parse(description: String?): Parsed<ProLedger> {
        if (!hasMarker(description)) return Parsed(isCarrier = false)
        val match = ledgerMarker.find(description!!) ?: return Parsed(true, error = "Malformed Pro ledger")
        val decoded = decode(match, "the Pro ledger")
        if (decoded.version > CURRENT_VERSION) {
            return Parsed(true, newerVersion = true, error = "Pro ledger version ${decoded.version} is not supported")
        }
        decoded.error?.let { return Parsed(true, error = it) }
        val payload = decoded.payload ?: return Parsed(true, error = "Malformed Pro ledger")
        val version = payloadVersion(payload)
        if (version != null && version > CURRENT_VERSION) {
            return Parsed(true, newerVersion = true, error = "Pro ledger version $version is not supported")
        }
        return try {
            Parsed(true, document = LedgerRules.normalize(payload))
        } catch (e: Exception) {
            Parsed(true, error = e.message ?: "Malformed Pro ledger")
        }
    }

    private fun encode(kind: String, document: JsonObject, what: String): String {
        val bytes = document.toString().encodeToByteArray()
        require(bytes.size <= WRITE_MAX_BYTES) { "$what is too large" }
        return "<!-- vicu-$kind:v$CURRENT_VERSION:${Base64.UrlSafe.encode(bytes).trimEnd('=')} -->"
    }

    fun encode(ledger: ProLedger): String {
        LedgerRules.normalize(ledger.json)
        return encode("pro", ledger.json, "The Pro ledger")
    }

    /** The size of the ledger's JSON in bytes, for the 70 percent warning. */
    fun jsonBytes(ledger: ProLedger): Int = ledger.json.toString().encodeToByteArray().size

    /** An archive part: `{ version, quarter, <collection>: { <id>: record } }`. */
    fun parseArchive(description: String?): Parsed<JsonObject> {
        if (!hasArchiveMarker(description)) return Parsed(isCarrier = false)
        val match = archiveMarker.find(description!!) ?: return Parsed(true, error = "Malformed Pro archive part")
        val decoded = decode(match, "a Pro archive part")
        if (decoded.version > CURRENT_VERSION) return Parsed(true, newerVersion = true)
        decoded.error?.let { return Parsed(true, error = it) }
        val part = decoded.payload
        val quarter = (part?.get("quarter") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (part == null || quarter == null) return Parsed(true, error = "Malformed Pro archive part")
        val version = payloadVersion(part)
        if (version != null && version > CURRENT_VERSION) return Parsed(true, newerVersion = true)
        return Parsed(true, document = part)
    }

    fun encodeArchive(part: JsonObject): String = encode("pro-archive", part, "A Pro archive part")

    /** The quarter an archive part holds, or null. */
    fun quarterOf(part: JsonObject): String? = (part["quarter"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
