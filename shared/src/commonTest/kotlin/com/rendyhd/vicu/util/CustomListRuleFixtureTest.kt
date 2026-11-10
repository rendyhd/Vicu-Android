package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.remote.api.SavedFilterDto
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.CustomListRevision
import com.rendyhd.vicu.domain.model.CustomListSyncDocumentV1
import com.rendyhd.vicu.domain.model.CustomListSyncOrder
import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.CustomListWireFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toDomain
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CrossAppFixture.local
import com.rendyhd.vicu.util.CrossAppFixture.readTestFixture
import com.rendyhd.vicu.util.CrossAppFixture.zones
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the `customListRule`, `customListSort` and `savedFilterImport` vectors of
 * `test-fixtures/cross-app-semantics-v1.json` (sections 3.5 to 3.7) in the three contract zones.
 */
class CustomListRuleFixtureTest {

    @Serializable
    data class RuleTask(
        val id: Long,
        val title: String,
        val description: String,
        val done: Boolean,
        val doneAt: String? = null,
        val priority: Int,
        val percentDone: Double,
        val projectId: Long,
        val index: Long,
        val hexColor: String,
        val repeatAfter: Long,
        val due: String? = null,
        val start: String? = null,
        val end: String? = null,
        val created: String,
        val updated: String,
        val labels: List<Long>,
        val assignees: List<String>,
        val createdBy: String,
        val reminders: List<String>,
    )

    @Serializable
    data class RuleVector(val name: String, val now: String, val rule: String, val includeNulls: Boolean, val expect: List<Long>)

    @Serializable
    data class RuleBlock(val tasks: List<RuleTask>, val vectors: List<RuleVector>, val invalid: List<String>)

    @Serializable
    data class SortTask(val id: Long, val title: String, val done: Boolean, val priority: Int, val due: String? = null, val position: Double)

    @Serializable
    data class SortVector(val name: String, val filter: CustomListWireFilter, val expect: List<Long>)

    @Serializable
    data class SortBlock(val tasks: List<SortTask>, val vectors: List<SortVector>)

    @Serializable
    data class ImportedList(val name: String, val icon: String, val savedFilterId: Long, val filter: CustomListWireFilter)

    @Serializable
    data class ImportVector(
        val name: String,
        val savedFilter: SavedFilterDto,
        val list: ImportedList? = null,
        val status: String,
        val reasons: List<String>,
    )

    @Serializable
    data class DuplicateList(val id: String, val savedFilterId: Long? = null)

    @Serializable
    data class DuplicateVector(val name: String, val lists: List<DuplicateList>, val kept: List<String>)

    @Serializable
    data class UnionVector(val left: List<Long>, val right: List<Long>, val merged: List<Long>)

    @Serializable
    data class ImportBlock(val filters: List<ImportVector>, val duplicates: List<DuplicateVector>, val union: List<UnionVector>)

    @Serializable
    data class Blocks(val customListRule: RuleBlock, val customListSort: SortBlock, val savedFilterImport: ImportBlock)

    private val blocks: Blocks by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(Blocks.serializer(), readTestFixture("cross-app-semantics-v1.json"))
    }

    private fun at(value: String?, zone: TimeZone): String = value?.let { local(it, zone).toString() } ?: ""

    private fun ruleTasks(zone: TimeZone): List<Task> = blocks.customListRule.tasks.map {
        Task(
            id = it.id,
            title = it.title,
            description = it.description,
            done = it.done,
            doneAt = at(it.doneAt, zone),
            priority = it.priority,
            percentDone = it.percentDone,
            projectId = it.projectId,
            index = it.index,
            hexColor = it.hexColor,
            repeatAfter = it.repeatAfter,
            dueDate = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            startDate = at(it.start, zone),
            endDate = at(it.end, zone),
            created = at(it.created, zone),
            updated = at(it.updated, zone),
            labels = it.labels.map { id -> Label(id = id, title = "Label $id") },
            assignees = it.assignees.mapIndexed { i, name -> User(id = 100L + i, username = name, name = "") },
            createdBy = User(id = 1, username = it.createdBy, name = ""),
            reminders = it.reminders.map { r -> TaskReminder(reminder = local(r, zone).toString()) },
        )
    }

    @Test
    fun `the fixture has the rule vectors`() {
        assertTrue(blocks.customListRule.vectors.size >= 40)
        assertTrue(blocks.customListRule.invalid.size >= 15)
    }

    @Test
    fun `every rule vector in every zone`() {
        for (zone in zones) {
            val tasks = ruleTasks(zone)
            for (vector in blocks.customListRule.vectors) {
                val now = local(vector.now, zone)
                assertNull(CustomListRule.validate(vector.rule), "${vector.name} should be valid")
                val match = CustomListRule.compile(vector.rule, vector.includeNulls, now, zone)
                assertEquals(vector.expect, tasks.filter(match).map { it.id }, "${vector.name} in $zone: ${vector.rule}")
            }
        }
    }

    @Test
    fun `the rule runs through the list evaluator`() {
        for (zone in zones) {
            val tasks = ruleTasks(zone)
            for (vector in blocks.customListRule.vectors) {
                val now = local(vector.now, zone)
                val filter = CustomListFilter(includeDone = true, rule = vector.rule, ruleIncludeNulls = vector.includeNulls)
                val today = CrossAppFixture.date(vector.now.substringBefore('T'))
                val shown = CustomListFilterBuilder.applyClientSideFilters(tasks, filter, today, zone, now = now)
                assertEquals(vector.expect, shown.map { it.id }, "${vector.name} in $zone")
            }
        }
    }

    @Test
    fun `every invalid rule is rejected and matches nothing`() {
        val zone = zones.first()
        val tasks = ruleTasks(zone)
        for (rule in blocks.customListRule.invalid) {
            assertNotNull(CustomListRule.validate(rule), "\"$rule\" should be invalid")
            val match = CustomListRule.compile(rule, false, local("2026-10-06T10:00:00", zone), zone)
            assertEquals(emptyList(), tasks.filter(match).map { it.id }, "\"$rule\" matches nothing")
        }
    }

    @Test
    fun `a rule survives the wire`() {
        for (vector in blocks.customListRule.vectors) {
            val list = CustomList("v", "v", filter = CustomListFilter(rule = vector.rule, ruleIncludeNulls = vector.includeNulls))
            val back = list.toWire().toDomain()
            assertEquals(vector.rule.takeIf { it.isNotBlank() }, back.filter.rule)
            assertEquals(vector.includeNulls, back.filter.ruleIncludeNulls)
        }
    }

    @Test
    fun `the server is asked for the rule, unless it uses like`() {
        val zone = zones.first()
        val today = CrossAppFixture.date("2026-10-06")
        val all = CustomListFilter(includeDone = true, rule = "priority > 2", ruleIncludeNulls = true)
        assertEquals("(priority > 2)", CustomListFilterBuilder.buildFilterString(all, today, zone))
        val params = CustomListFilterBuilder.buildQueryParams(all, today, zone)
        assertEquals(zone.id, params["filter_timezone"])
        assertEquals("true", params["filter_include_nulls"])

        val open = CustomListFilter(rule = "priority > 2")
        assertEquals("(done = false) && (priority > 2)", CustomListFilterBuilder.buildFilterString(open, today, zone))

        val like = CustomListFilter(includeDone = true, rule = "title like plumb")
        assertEquals("", CustomListFilterBuilder.buildFilterString(like, today, zone))
        assertNull(CustomListFilterBuilder.buildQueryParams(like, today, zone)["filter_timezone"])

        val invalid = CustomListFilter(rule = "lables = 1")
        assertEquals("done = false", CustomListFilterBuilder.buildFilterString(invalid, today, zone))
    }

    @Test
    fun `every sort vector`() {
        val zone = zones.first()
        val tasks = blocks.customListSort.tasks.map {
            Task(
                id = it.id,
                title = it.title,
                done = it.done,
                priority = it.priority,
                position = it.position,
                dueDate = it.due?.let { due -> local(due, zone).toString() } ?: Constants.NULL_DATE_STRING,
            )
        }
        for (vector in blocks.customListSort.vectors) {
            val filter = CustomListWire("v", "v", filter = vector.filter).toDomain().filter
            val sorted = CustomListFilterBuilder.sortTasks(tasks, filter.sortBy, filter.orderBy, filter.thenBy)
            assertEquals(vector.expect, sorted.map { it.id }, vector.name)
        }
    }

    @Test
    fun `every saved filter import`() {
        for (vector in blocks.savedFilterImport.filters) {
            val result = SavedFilterImport.convert(vector.savedFilter, listId = "new")
            assertEquals(vector.status, result.status.name.lowercase(), vector.name)
            assertEquals(vector.reasons, result.reasons.map { it.wire }, vector.name)
            val expected = vector.list
            if (expected == null) {
                assertNull(result.list, vector.name)
                continue
            }
            val list = assertNotNull(result.list, vector.name)
            assertEquals(expected.name, list.name, vector.name)
            assertEquals(expected.icon, list.icon, vector.name)
            assertEquals(expected.savedFilterId, list.savedFilterId, vector.name)
            val want = CustomListWire("new", expected.name, filter = expected.filter).toDomain().filter
            assertEquals(want, list.filter, vector.name)
        }
    }

    @Test
    fun `duplicate imports keep the first id`() {
        for (vector in blocks.savedFilterImport.duplicates) {
            val lists = vector.lists.map { CustomListWire(it.id, it.id, filter = CustomListWireFilter(), savedFilterId = it.savedFilterId) }
            val document = CustomListEnvelope.fromLists(lists, "device", 1_000L)
            assertEquals(vector.kept, CustomListEnvelope.activeLists(document).map { it.id }, vector.name)

            val cleaned = CustomListEnvelope.dropDuplicateImports(document, "device", 2_000L)
            val removed = vector.lists.map { it.id } - vector.kept.toSet()
            for (id in removed) assertNull(cleaned.lists.getValue(id).value, "$id becomes a tombstone")
            assertEquals(vector.kept, CustomListEnvelope.activeLists(cleaned).map { it.id })
        }
    }

    @Test
    fun `imported saved filters merge as a union`() {
        val revision = CustomListRevision(1, 0, "d")
        for (vector in blocks.savedFilterImport.union) {
            val left = CustomListSyncDocumentV1(order = CustomListSyncOrder(emptyList(), revision), importedSavedFilters = vector.left.ifEmpty { null })
            val right = CustomListSyncDocumentV1(order = CustomListSyncOrder(emptyList(), revision), importedSavedFilters = vector.right.ifEmpty { null })
            assertEquals(vector.merged, CustomListEnvelope.merge(left, right).importedSavedFilters)
        }
    }
}
