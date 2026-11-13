package com.rendyhd.vicu.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `repeatCompletion` vectors of docs/cross-app-semantics-v1.md section 7.5. */
class RepeatCompletionTest {

    @Serializable
    data class Server(
        val done: Boolean,
        @SerialName("due_date") val dueDate: String? = null,
        @SerialName("repeat_after") val repeatAfter: Long = 0,
        @SerialName("repeat_mode") val repeatMode: Int = 0,
    )

    @Serializable
    data class Vector(val name: String, val completedDue: String? = null, val server: Server, val expect: String)

    @Serializable
    data class Block(val vectors: List<Vector>)

    private val json = Json { ignoreUnknownKeys = true }

    private val block: Block by lazy {
        val root = json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
        json.decodeFromJsonElement(Block.serializer(), root["repeatCompletion"]!!)
    }

    @Test
    fun `every vector`() {
        assertTrue(block.vectors.isNotEmpty())
        for (v in block.vectors) {
            val decision = RepeatCompletion.decide(
                v.completedDue,
                v.server.done,
                v.server.dueDate,
                v.server.repeatAfter,
                v.server.repeatMode,
            )
            assertEquals(v.expect, decision.name.lowercase(), v.name)
        }
    }

    @Test
    fun `a queued completion keeps its completed due out of the patch`() {
        val stored = RepeatCompletion.withCompletedDue(
            JsonObject(mapOf("done" to JsonPrimitive(true))),
            "2026-11-13T22:59:59Z",
        )
        val queued = RepeatCompletion.fromQueued(stored)
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), queued.patch)
        assertEquals("2026-11-13T22:59:59Z", queued.completedDue)
    }

    @Test
    fun `a queued patch that also sets the due date is sent without the check`() {
        val stored = RepeatCompletion.withCompletedDue(
            JsonObject(mapOf("done" to JsonPrimitive(true), "due_date" to JsonPrimitive("2026-11-20T22:59:59Z"))),
            "2026-11-13T22:59:59Z",
        )
        val queued = RepeatCompletion.fromQueued(stored)
        assertNull(queued.completedDue)
        assertTrue(RepeatCompletion.COMPLETED_DUE_KEY !in queued.patch)
    }

    @Test
    fun `a reopen merged over a completion is not checked, and an old payload is sent as before`() {
        val reopened = RepeatCompletion.fromQueued(
            JsonObject(mapOf("done" to JsonPrimitive(false), RepeatCompletion.COMPLETED_DUE_KEY to JsonPrimitive(""))),
        )
        assertNull(reopened.completedDue)
        assertEquals(JsonObject(mapOf("done" to JsonPrimitive(false))), reopened.patch)

        val old = JsonObject(mapOf("done" to JsonPrimitive(true)))
        assertEquals(RepeatCompletion.Queued(old, null), RepeatCompletion.fromQueued(old))
    }

    @Test
    fun `only a completion of a repeating task remembers its due`() {
        assertNull(RepeatCompletion.completedDueFor(true, 0, 0, "2026-11-13T22:59:59Z"))
        assertNull(RepeatCompletion.completedDueFor(false, 86_400, 0, "2026-11-13T22:59:59Z"))
        assertEquals("", RepeatCompletion.completedDueFor(true, 0, 1, null))
        assertEquals("2026-11-13T22:59:59Z", RepeatCompletion.completedDueFor(true, 86_400, 0, "2026-11-13T22:59:59Z"))
    }
}
