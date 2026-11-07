package com.rendyhd.vicu.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the `subtaskOrder` vectors of `test-fixtures/cross-app-semantics-v1.json` (contract section 19). */
class SubtaskOrderFixtureTest {
    @Serializable
    private data class Vector(val name: String, val order: JsonElement = JsonNull, val ids: List<Long>, val expect: List<Long>)

    @Serializable
    private data class Root(val subtaskOrder: List<Vector>)

    @Test
    fun theVectorsHold() {
        val vectors = Json { ignoreUnknownKeys = true }
            .decodeFromString<Root>(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
            .subtaskOrder
        assertTrue(vectors.size >= 6)
        for (vector in vectors) {
            assertEquals(vector.expect, SubtaskOrder.sorted(vector.ids, SubtaskOrder.read(vector.order)) { it }, vector.name)
        }
    }
}
