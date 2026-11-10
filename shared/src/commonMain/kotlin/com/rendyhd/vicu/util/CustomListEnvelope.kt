@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListRevision
import com.rendyhd.vicu.domain.model.CustomListSyncDocumentSerializer
import com.rendyhd.vicu.domain.model.CustomListSyncDocumentV1
import com.rendyhd.vicu.domain.model.CustomListSyncOrder
import com.rendyhd.vicu.domain.model.CustomListSyncRecord
import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.toDomain
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object CustomListEnvelope {
    const val CURRENT_VERSION = 1
    const val CARRIER_TITLE = "Vicu custom lists (sync metadata — do not delete)"
    private const val MAX_DECODED_BYTES = 512 * 1024
    private val markerRegex = Regex("""<!--\s*vicu-custom-lists:v(\d+):([A-Za-z0-9_-]+={0,2})\s*-->""")
    private val anyMarkerRegex = Regex("""<!--\s*vicu-custom-lists:[\s\S]*?-->""")

    data class Parsed(
        val isCarrier: Boolean,
        val body: String,
        val document: CustomListSyncDocumentV1? = null,
        val error: String? = null,
        val version: Int? = null,
    )

    fun hasMarker(description: String?): Boolean =
        !description.isNullOrEmpty() && anyMarkerRegex.containsMatchIn(description)

    fun compareRevision(left: CustomListRevision, right: CustomListRevision): Int =
        compareValues(left.wallTimeMs, right.wallTimeMs).takeIf { it != 0 }
            ?: compareValues(left.counter, right.counter).takeIf { it != 0 }
            ?: left.deviceId.compareTo(right.deviceId)

    fun empty(deviceId: String, now: Long): CustomListSyncDocumentV1 = CustomListSyncDocumentV1(
        order = CustomListSyncOrder(emptyList(), CustomListRevision(now, 0, deviceId)),
    )

    fun fromLists(lists: List<CustomListWire>, deviceId: String, now: Long): CustomListSyncDocumentV1 {
        val records = lists.mapIndexed { index, list ->
            list.id to CustomListSyncRecord(list, CustomListRevision(now, index.toLong(), deviceId))
        }.toMap()
        return CustomListSyncDocumentV1(
            lists = records,
            order = CustomListSyncOrder(lists.map { it.id }, CustomListRevision(now, lists.size.toLong(), deviceId)),
        )
    }

    fun nextRevision(document: CustomListSyncDocumentV1, deviceId: String, now: Long): CustomListRevision {
        val revisions = document.lists.values.map { it.revision } + document.order.revision
        val maxWall = revisions.maxOfOrNull { it.wallTimeMs } ?: 0L
        val wall = maxOf(now, maxWall)
        val counter = if (now > maxWall) 0 else revisions.filter { it.wallTimeMs == wall }.maxOfOrNull { it.counter }?.plus(1) ?: 0
        return CustomListRevision(wall, counter, deviceId)
    }

    fun activeLists(document: CustomListSyncDocumentV1): List<CustomListWire> {
        val active = document.lists.values.filter { it.value != null }
        val byId = active.associateBy { it.value!!.id }
        val seen = mutableSetOf<String>()
        val ordered = document.order.ids.mapNotNull { id -> byId[id]?.takeIf { seen.add(id) }?.value }
        val missing = active.filter { it.value!!.id !in seen }
            .sortedWith { left, right ->
                compareRevision(left.revision, right.revision).takeIf { it != 0 }
                    ?: left.value!!.id.compareTo(right.value!!.id)
            }.map { it.value!! }
        return withoutDuplicateImports(ordered + missing)
    }

    /**
     * [lists] without the lists that repeat another list's saved filter: per `saved_filter_id`,
     * the list whose id sorts first stays (section 3.7).
     */
    fun withoutDuplicateImports(lists: List<CustomListWire>): List<CustomListWire> {
        val winners = lists.filter { it.savedFilterId != null }
            .groupBy { it.savedFilterId }
            .mapValues { (_, same) -> same.minOf { it.id } }
        return lists.filter { list -> list.savedFilterId == null || winners[list.savedFilterId] == list.id }
    }

    /** The lists [activeLists] hides as duplicate imports, which the next write turns into tombstones. */
    fun duplicateImports(document: CustomListSyncDocumentV1): List<String> {
        val active = document.lists.values.mapNotNull { it.value }
        val kept = withoutDuplicateImports(active).mapTo(HashSet()) { it.id }
        return active.map { it.id }.filter { it !in kept }.sorted()
    }

    /**
     * [document] with every hidden duplicate import deleted (a tombstone stamped [deviceId] at
     * [now]); the same document when there is none.
     */
    fun dropDuplicateImports(document: CustomListSyncDocumentV1, deviceId: String, now: Long): CustomListSyncDocumentV1 {
        val duplicates = duplicateImports(document)
        if (duplicates.isEmpty()) return document
        var doc = document
        for (id in duplicates) {
            val revision = nextRevision(doc, deviceId, now)
            doc = doc.copy(lists = doc.lists + (id to CustomListSyncRecord(null, revision)))
        }
        return doc
    }

    /** The union of two `imported_saved_filters`, ascending; null when empty. */
    fun unionImported(left: List<Long>?, right: List<Long>?): List<Long>? =
        (left.orEmpty() + right.orEmpty()).distinct().sorted().takeIf { it.isNotEmpty() }

    fun normalize(document: CustomListSyncDocumentV1): CustomListSyncDocumentV1 {
        require(document.version == CURRENT_VERSION) { "Custom-list payload version mismatch" }
        document.lists.forEach { (id, record) ->
            require(record.revision.deviceId.isNotBlank()) { "Custom-list revision is invalid" }
            record.value?.let {
                require(it.id == id && it.name.isNotBlank()) { "Custom-list value $id is invalid" }
                it.toDomain()
            }
        }
        require(document.order.revision.deviceId.isNotBlank()) { "Custom-list order revision is invalid" }
        val normalizedOrder = activeLists(document).map { it.id }
        return document.copy(order = document.order.copy(ids = normalizedOrder))
    }

    fun merge(left: CustomListSyncDocumentV1, right: CustomListSyncDocumentV1): CustomListSyncDocumentV1 {
        val records = (left.lists.keys + right.lists.keys).associateWith { id ->
            val a = left.lists[id]
            val b = right.lists[id]
            when {
                a == null -> b!!
                b == null -> a
                compareRevision(a.revision, b.revision) >= 0 -> a
                else -> b
            }
        }
        val order = if (compareRevision(left.order.revision, right.order.revision) >= 0) left.order else right.order
        // Fields this version does not know at the top: both sides', [right] (the server's copy) wins.
        return normalize(
            CustomListSyncDocumentV1(
                lists = records,
                order = order.copy(ids = order.ids.toList()),
                importedSavedFilters = unionImported(left.importedSavedFilters, right.importedSavedFilters),
                unknown = JsonObject(left.unknown + right.unknown),
            ),
        )
    }

    /** Drops deleted lists older than 90 days (section 10.6). Kept lists and the order stay. */
    fun pruneTombstones(document: CustomListSyncDocumentV1, nowMs: Long): CustomListSyncDocumentV1 {
        val kept = document.lists.filterValues { it.value != null || !HiddenTasks.isExpiredTombstone(it.revision.wallTimeMs, nowMs) }
        return if (kept.size == document.lists.size) document else document.copy(lists = kept)
    }

    fun parse(description: String?, json: Json): Parsed {
        if (description.isNullOrEmpty()) return Parsed(false, "")
        val any = anyMarkerRegex.find(description) ?: return Parsed(false, description)
        val body = description.removeRange(any.range).trimEnd()
        val marker = markerRegex.matchEntire(any.value)
            ?: return Parsed(true, body, error = "Malformed custom-list metadata")
        val version = marker.groupValues[1].toIntOrNull()
            ?: return Parsed(true, body, error = "Invalid custom-list version")
        if (version != CURRENT_VERSION) return Parsed(true, body, error = "Custom-list metadata version $version is not supported", version = version)
        return runCatching {
            val encoded = marker.groupValues[2]
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            val bytes = Base64.UrlSafe.decode(padded)
            require(bytes.size <= MAX_DECODED_BYTES) { "Custom-list metadata is too large" }
            val decoded = json.decodeFromString(CustomListSyncDocumentSerializer, bytes.decodeToString())
            if (decoded.version > CURRENT_VERSION) {
                return Parsed(true, body, error = "Custom-list metadata version ${decoded.version} is not supported", version = decoded.version)
            }
            Parsed(true, body, document = normalize(decoded), version = version)
        }.getOrElse { Parsed(true, body, error = it.message ?: "Cannot decode custom-list metadata", version = version) }
    }

    fun encode(document: CustomListSyncDocumentV1, json: Json): String {
        val bytes = json.encodeToString(CustomListSyncDocumentSerializer, normalize(document)).encodeToByteArray()
        require(bytes.size <= MAX_DECODED_BYTES) { "Custom-list metadata is too large" }
        return "<!-- vicu-custom-lists:v1:${Base64.UrlSafe.encode(bytes).trimEnd('=')} -->"
    }
}
