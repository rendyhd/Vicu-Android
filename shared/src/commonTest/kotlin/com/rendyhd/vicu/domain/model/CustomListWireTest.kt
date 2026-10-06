package com.rendyhd.vicu.domain.model

import com.rendyhd.vicu.util.CustomListEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The synced custom-list value (docs/cross-app-semantics-v1.md, section 3): the `include_overdue`
 * flag (absent means true) and the rule that a field this version does not know survives a round
 * trip through the list value and its filter.
 */
class CustomListWireTest {
    /** The shape both the app and the tests use; defaults on so `encodeDefaults` cannot hide a bug. */
    private val withDefaults = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val withoutDefaults = Json { ignoreUnknownKeys = true }

    /** A list as a newer desktop might write it: unknown fields on the list and on the filter. */
    private val desktopValue = """
        {
          "id": "list-1",
          "name": "Work",
          "icon": "work",
          "color": "#ff8800",
          "pinned": {"slot": 2, "tags": ["a", "b"]},
          "filter": {
            "project_ids": [10, 11],
            "project_filter_mode": "include",
            "add_to_project_id": 10,
            "sort_by": "due_date",
            "order_by": "asc",
            "due_date_filter": "this_week",
            "priority_filter": [3],
            "label_ids": [7],
            "include_done": false,
            "include_today_all_projects": true,
            "include_overdue": false,
            "assignee_filter": [5, 6],
            "snooze_until": null,
            "future": {"nested": {"deep": [1, 2, 3]}}
          }
        }
    """.trimIndent()

    private fun decode(json: Json, text: String = desktopValue): CustomListWire {
        val record = json.decodeFromString<CustomListSyncRecord>(
            """{"value":$text,"revision":{"wall_time_ms":10,"counter":0,"device_id":"desktop"}}""",
        )
        return record.value!!
    }

    private fun encode(json: Json, value: CustomListWire): JsonObject {
        val record = CustomListSyncRecord(value, CustomListRevision(10, 0, "android"))
        return json.parseToJsonElement(json.encodeToString(CustomListSyncRecord.serializer(), record))
            .jsonObject.getValue("value").jsonObject
    }

    private fun parsed(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `include_overdue is a boolean that is absent by default`() {
        val absent = decode(withDefaults, """{"id":"a","name":"A","filter":{"due_date_filter":"today"}}""")
        assertNull(absent.filter.includeOverdue)
        assertNull(absent.toDomain().filter.includeOverdue)

        val on = decode(withDefaults, """{"id":"a","name":"A","filter":{"include_overdue":true}}""")
        assertEquals(true, on.filter.includeOverdue)

        val off = decode(withDefaults, """{"id":"a","name":"A","filter":{"include_overdue":false}}""")
        assertEquals(false, off.filter.includeOverdue)
        assertEquals(false, off.toDomain().filter.includeOverdue)
    }

    @Test
    fun `an absent include_overdue is never written, with or without encodeDefaults`() {
        val value = decode(withDefaults, """{"id":"a","name":"A","filter":{"due_date_filter":"today"}}""")
        for (json in listOf(withDefaults, withoutDefaults)) {
            assertFalse("include_overdue" in encode(json, value).getValue("filter").jsonObject, "encodeDefaults=${json.configuration.encodeDefaults}")
        }
    }

    @Test
    fun `include_overdue false is written as false`() {
        val value = decode(withDefaults, """{"id":"a","name":"A","filter":{"include_overdue":false}}""")
        for (json in listOf(withDefaults, withoutDefaults)) {
            val filter = encode(json, value).getValue("filter").jsonObject
            assertFalse(filter.getValue("include_overdue").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `the domain flag stays absent unless the user turned it off`() {
        val untouched = CustomList("a", "A", "", CustomListFilter(dueDateFilter = "today"))
        assertNull(untouched.toWire().filter.includeOverdue)

        // The editor writes null for "on"; an explicit true from an older edit also stays absent.
        val on = untouched.copy(filter = untouched.filter.copy(includeOverdue = true))
        assertNull(on.toWire().filter.includeOverdue, "on is written as an absent key")

        val off = untouched.copy(filter = untouched.filter.copy(includeOverdue = false))
        assertEquals(false, off.toWire().filter.includeOverdue)
    }

    @Test
    fun `fields this version does not know survive a decode and encode`() {
        val original = parsed(desktopValue).jsonObject

        // Every typed field is present in the sample, so with defaults on the output is the input.
        assertEquals(original, encode(withDefaults, decode(withDefaults)))

        // With defaults off the typed fields that equal their default are left out; the unknown
        // ones are still all there.
        val written = encode(withoutDefaults, decode(withoutDefaults))
        for ((key, value) in original) if (key != "filter") assertEquals(value, written[key], key)
        val originalFilter = original.getValue("filter").jsonObject
        val writtenFilter = written.getValue("filter").jsonObject
        for (key in listOf("assignee_filter", "snooze_until", "future", "include_overdue", "due_date_filter")) {
            assertEquals(originalFilter[key], writtenFilter[key], key)
        }
    }

    @Test
    fun `unknown fields are kept as raw json, including nulls and nested values`() {
        val value = decode(withDefaults)

        assertEquals(setOf("color", "pinned"), value.unknown.keys)
        assertEquals(setOf("assignee_filter", "snooze_until", "future"), value.filter.unknown.keys)
        assertEquals(parsed("""{"nested":{"deep":[1,2,3]}}"""), value.filter.unknown.getValue("future"))
        assertTrue(value.filter.unknown.getValue("snooze_until") is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `an android edit keeps the unknown fields of the list it replaces`() {
        val existing = decode(withDefaults)
        val edited = existing.toDomain().let {
            it.copy(
                name = "Work, edited",
                filter = it.filter.copy(dueDateFilter = "today", includeOverdue = null),
            )
        }

        val rewritten = encode(withDefaults, edited.toWire(preserving = existing))

        assertEquals("Work, edited", rewritten.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive("#ff8800"), rewritten.getValue("color"))
        assertEquals(parsed("""{"slot":2,"tags":["a","b"]}"""), rewritten.getValue("pinned"))
        val filter = rewritten.getValue("filter").jsonObject
        assertEquals("today", filter.getValue("due_date_filter").jsonPrimitive.content)
        assertFalse("include_overdue" in filter, "the user turned overdue back on: the key is removed")
        assertEquals(parsed("[5,6]"), filter.getValue("assignee_filter"))
        assertEquals(parsed("""{"nested":{"deep":[1,2,3]}}"""), filter.getValue("future"))
        assertTrue(filter.getValue("snooze_until") is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a field the typed model owns wins over a stale copy among the unknown fields`() {
        val existing = decode(withDefaults)
        val stale = existing.copy(
            filter = existing.filter.copy(unknown = JsonObject(existing.filter.unknown + ("sort_by" to JsonPrimitive("title")))),
        )

        val written = encode(withDefaults, stale)

        assertEquals("due_date", written.getValue("filter").jsonObject.getValue("sort_by").jsonPrimitive.content)
    }

    @Test
    fun `the carrier marker round trips unknown fields and merging keeps them with the winning record`() {
        val record = decode(withDefaults)
        val document = CustomListEnvelope.fromLists(listOf(record), deviceId = "desktop", now = 10)

        val marker = CustomListEnvelope.encode(document, withDefaults)
        val parsedDocument = CustomListEnvelope.parse(marker, withDefaults).document!!

        assertEquals(record, parsedDocument.lists.getValue("list-1").value)

        val androidEdit = CustomListSyncRecord(
            value = record.toDomain().copy(name = "Renamed").toWire(preserving = record),
            revision = CustomListRevision(20, 0, "android"),
        )
        val androidDocument = parsedDocument.copy(lists = mapOf("list-1" to androidEdit))
        val merged = CustomListEnvelope.merge(parsedDocument, androidDocument)

        val winner = merged.lists.getValue("list-1").value!!
        assertEquals("Renamed", winner.name)
        assertEquals(record.unknown, winner.unknown)
        assertEquals(record.filter.unknown, winner.filter.unknown)
    }

    @Test
    fun `the cached copy of the document keeps unknown fields too`() {
        // The local sync state is stored through the same serializers.
        val record = decode(withDefaults)
        val state = CustomListSyncLocalState(
            deviceId = "android",
            document = CustomListEnvelope.fromLists(listOf(record), "desktop", 10),
        )

        val stored = withoutDefaults.encodeToString(CustomListSyncLocalState.serializer(), state)
        val restored = withoutDefaults.decodeFromString<CustomListSyncLocalState>(stored)

        assertEquals(record, restored.document.lists.getValue("list-1").value)
    }

    @Test
    fun `a list written without any unknown fields stays exactly as it was`() {
        val plain = buildJsonObject {
            put("id", "p")
            put("name", "Plain")
            put("icon", "list")
            put(
                "filter",
                buildJsonObject {
                    put("project_ids", buildJsonArray { })
                    put("due_date_filter", "all")
                },
            )
        }
        val written = encode(withoutDefaults, decode(withoutDefaults, plain.toString()))

        assertEquals("p", written.getValue("id").jsonPrimitive.content)
        assertFalse("__unknown__" in written)
        assertFalse("__unknown__" in written.getValue("filter").jsonObject)
    }
}
