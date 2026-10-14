package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "all tasks" queries do not hide a subtask whose parent is in the same result, so Tag and
 * custom-list screens can apply their label and list conditions first and hide nested subtasks
 * only among the tasks that are left (cross-app semantics, section 3.2).
 */
class TaskRepositoryFlatTasksTest {

    private val relationsSerializer = MapSerializer(String.serializer(), ListSerializer(TaskDto.serializer()))

    private fun entity(id: Long, done: Boolean = false, parentId: Long? = null, description: String = "") = TaskEntity(
        id = id,
        title = "Task $id",
        description = description,
        done = done,
        projectId = 7,
        relatedTasksJson = if (parentId == null) {
            "{}"
        } else {
            authTestJson.encodeToString(
                relationsSerializer,
                mapOf(RelationKind.PARENTTASK to listOf(TaskDto(id = parentId, title = "Parent", projectId = 7))),
            )
        },
    )

    private fun harness(vararg entities: TaskEntity) =
        TaskRepositoryHarness(taskDao = FakeTaskDao(entities.toList())) { serviceUnavailable() }

    @Test
    fun `a subtask whose parent is listed stays a row`() = runTest {
        val h = harness(entity(1), entity(2, parentId = 1), entity(3))

        assertEquals(listOf(1L, 2L, 3L), h.repository.getAllOpenTasksFlat().first().map { it.id })
    }

    @Test
    fun `the flat open query leaves out completed tasks and the flat all query keeps them`() = runTest {
        val h = harness(entity(1), entity(2, done = true), entity(3, parentId = 1))

        assertEquals(listOf(1L, 3L), h.repository.getAllOpenTasksFlat().first().map { it.id })
        assertEquals(listOf(1L, 2L, 3L), h.repository.getAllTasksFlat().first().map { it.id })
    }

    @Test
    fun `sync metadata tasks never show up`() = runTest {
        val carrier = "<!-- vicu-custom-lists:v1:e30 -->"
        val h = harness(entity(1), entity(2, description = carrier))

        assertEquals(listOf(1L), h.repository.getAllOpenTasksFlat().first().map { it.id })
        assertEquals(listOf(1L), h.repository.getAllTasksFlat().first().map { it.id })
        assertEquals(true, CustomListEnvelope.hasMarker(carrier))
    }
}
