package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.local.dao.mergePatchPayloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `notesConflict` vectors of test-fixtures/description-format-v1.json (description format,
 * "Notes changed in two places"). The desktop runs the same file in
 * src/shared/__tests__/notes-merge.test.ts.
 */
class NotesMergeTest {

    @Serializable
    private data class Expect(val action: String, val description: String? = null)

    @Serializable
    private data class Vector(
        val name: String,
        val base: String? = null,
        val local: String? = null,
        val server: String? = null,
        val expect: Expect,
    )

    @Serializable
    private data class Fixture(val notesConflict: List<Vector>)

    private val vectors: List<Vector> by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(Fixture.serializer(), CrossAppFixture.readTestFixture("description-format-v1.json"))
            .notesConflict
    }

    private fun actual(decision: NotesMerge.Decision): Expect = when (decision) {
        NotesMerge.Decision.NoChange -> Expect("nothing")
        is NotesMerge.Decision.Send -> Expect("send", decision.description)
        is NotesMerge.Decision.Merge -> Expect("merge", decision.description)
    }

    @Test
    fun everyVectorDecidesAsTheFixtureSays() {
        assertTrue(vectors.size >= 15, "vectors: ${vectors.size}")
        assertTrue(setOf("nothing", "send", "merge").all { action -> vectors.any { it.expect.action == action } })
        val failures = vectors.mapNotNull { v ->
            val got = actual(NotesMerge.decideNotesSave(v.base, v.local, v.server))
            if (got == v.expect) null else "${v.name}: expected ${v.expect}, got $got"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun aMergeSentTwiceDoesNotGrow() {
        // The answer to a merged send was lost: the second decision finds the merged notes there.
        for (v in vectors.filter { it.expect.action == "merge" }) {
            val merged = v.expect.description!!
            val again = NotesMerge.decideNotesSave(v.base, v.local, merged)
            assertEquals(NotesMerge.Decision.NoChange, again, v.name)
        }
    }

    @Test
    fun onlySpaceTabAndLineEndsAreTrimmed() {
        assertEquals("a", NotesMerge.trimmed(" \t\r\na\n\r\t "))
        // A no-break space is text, not an edge (Kotlin's own trim() would take it away).
        assertEquals(" a ", NotesMerge.trimmed(" a "))
    }

    @Test
    fun theBaseTravelsInTheQueuedPayloadAndIsNeverSent() {
        val patch = JsonObject(mapOf("description" to JsonPrimitive("<p>Mine</p>")))
        val stored = NotesMerge.withBase(patch, "<p>Base</p>")
        assertEquals(JsonPrimitive("<p>Base</p>"), stored[NotesMerge.BASE_KEY])

        val queued = NotesMerge.fromQueued(stored)
        assertEquals(patch, queued.patch)
        assertEquals("<p>Base</p>", queued.base)
    }

    @Test
    fun aPatchWithoutNotesCarriesNoBase() {
        val patch = JsonObject(mapOf("priority" to JsonPrimitive(3)))
        assertEquals(patch, NotesMerge.withBase(patch, "<p>Base</p>"))
        assertNull(NotesMerge.fromQueued(patch).base)
        assertEquals(patch, NotesMerge.withBase(patch, null))
    }

    @Test
    fun foldedEditsKeepTheBaseOfTheFirst() {
        val first = """{"description":"<p>One</p>","${NotesMerge.BASE_KEY}":"<p>Base</p>"}"""
        val second = """{"description":"<p>Two</p>","${NotesMerge.BASE_KEY}":"<p>One</p>"}"""

        val merged = Json.parseToJsonElement(mergePatchPayloads(first, second, "task")).jsonObject

        assertEquals(JsonPrimitive("<p>Two</p>"), merged["description"])
        assertEquals(JsonPrimitive("<p>Base</p>"), merged[NotesMerge.BASE_KEY])
    }

    @Test
    fun aNotesEditFoldedBehindAnotherChangeBringsItsBase() {
        val first = """{"priority":3}"""
        val second = """{"description":"<p>Two</p>","${NotesMerge.BASE_KEY}":"<p>Base</p>"}"""

        val merged = Json.parseToJsonElement(mergePatchPayloads(first, second, "task")).jsonObject

        assertEquals(JsonPrimitive("<p>Base</p>"), merged[NotesMerge.BASE_KEY])
        assertEquals(JsonPrimitive(3), merged["priority"])
    }

    @Test
    fun aFirstNotesEditWithoutABaseLeavesTheFoldWithout() {
        val first = """{"description":"<p>One</p>"}"""
        val second = """{"description":"<p>Two</p>","${NotesMerge.BASE_KEY}":"<p>One</p>"}"""

        val merged = Json.parseToJsonElement(mergePatchPayloads(first, second, "task")).jsonObject

        assertNull(merged[NotesMerge.BASE_KEY], "the base of a later edit is the unsent text of the first")
    }
}
