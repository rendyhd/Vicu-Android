package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.CrossAppFixture.fixture
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the `searchFilters` vectors of `test-fixtures/cross-app-semantics-v1.json` (contract section 17). */
class SearchFiltersFixtureTest {
    @Serializable
    private data class Filters(
        val date: String = SearchFilters.DATE_ALL,
        val from: String? = null,
        val to: String? = null,
        val status: String = SearchFilters.STATUS_ALL,
        val label_ids: List<Long> = emptyList(),
        val priorities: List<Int> = emptyList(),
    )

    @Serializable
    private data class Vector(
        val name: String,
        val today: String,
        val filters: Filters,
        val expect: List<Long>,
        val weekStart: String? = null,
    )

    @Serializable
    private data class Vectors(val searchFilters: List<Vector>)

    private val vectors: List<Vector> = Json { ignoreUnknownKeys = true }
        .decodeFromString<Vectors>(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .searchFilters

    private fun tasks(zone: TimeZone): List<Task> = fixture.tasks.map {
        Task(
            id = it.id,
            title = "Task ${it.id}",
            done = it.done,
            projectId = it.projectId,
            priority = it.priority,
            dueDate = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            labels = it.labelIds.map { id -> Label(id = id, title = "Label $id") },
        )
    }

    private fun Filters.toDomain() = SearchFilters(
        date = date,
        from = from?.let(LocalDate::parse),
        to = to?.let(LocalDate::parse),
        status = status,
        labelIds = label_ids.toSet(),
        priorities = priorities.toSet(),
    )

    @Test
    fun theVectorsHold() {
        assertTrue(vectors.size >= 18, "found ${vectors.size} vectors")
        for (zone in zones) {
            for (vector in vectors) {
                val today = LocalDate.parse(vector.today)
                val filters = vector.filters.toDomain()
                assertEquals(
                    vector.expect,
                    tasks(zone).filter { filters.matches(it, today, zone, WeekStart.fromKey(vector.weekStart)) }.map { it.id },
                    "${vector.name} in $zone",
                )
            }
        }
    }

    @Test
    fun noFilterIsNotActive() {
        assertFalse(SearchFilters().isActive)
        assertTrue(SearchFilters(status = SearchFilters.STATUS_DONE).isActive)
    }

    @Test
    fun textAloneAsksTheServerAsBefore() {
        val zone = TimeZone.UTC
        assertEquals(mapOf("q" to "paint"), SearchFilters().serverParams("paint", LocalDate(2026, 10, 6), zone))
    }

    @Test
    fun labelsAndPriorityOnlyGoToTheServerWithoutText() {
        val zone = TimeZone.UTC
        val today = LocalDate(2026, 10, 6)
        val filters = SearchFilters(labelIds = setOf(7), priorities = setOf(4, 3))
        assertEquals(mapOf("q" to "paint"), filters.serverParams("paint", today, zone))
        assertEquals(mapOf("filter" to "labels in 7 && priority in 3,4"), filters.serverParams("", today, zone))
    }

    @Test
    fun aRangeAsksForItsDaysInOrder() {
        val zone = TimeZone.UTC
        val filters = SearchFilters(date = SearchFilters.DATE_RANGE, from = LocalDate(2026, 10, 12), to = LocalDate(2026, 10, 7))
        assertEquals(
            mapOf("filter" to "due_date >= '2026-10-07T00:00:00Z' && due_date < '2026-10-13T00:00:00Z'"),
            filters.serverParams("", LocalDate(2026, 10, 6), zone),
        )
    }
}
