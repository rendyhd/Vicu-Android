package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Vikunja does not move subtasks with their parent; the repository moves the whole subtree. */
class TaskRepositoryMoveDescendantsTest {

    private val relationSerializer = MapSerializer(String.serializer(), ListSerializer(TaskDto.serializer()))

    private fun row(id: Long, projectId: Long, children: List<Long> = emptyList()): TaskEntity {
        val related = if (children.isEmpty()) {
            emptyMap()
        } else {
            mapOf("subtask" to children.map { TaskDto(id = it, title = "Task $it", projectId = projectId) })
        }
        return TaskEntity(
            id = id,
            title = "Task $id",
            projectId = projectId,
            relatedTasksJson = authTestJson.encodeToString(relationSerializer, related),
        )
    }

    /** 1 -> (2 -> (4), 3); task 5 is unrelated. All start in project 7. */
    private fun tree() = FakeTaskDao(
        listOf(
            row(1, 7, listOf(2, 3)),
            row(2, 7, listOf(4)),
            row(3, 7),
            row(4, 7),
            row(5, 7),
        ),
    )

    private fun movedTo(project: Long) = """{"id":0,"title":"x","project_id":$project}"""

    @Test
    fun `every descendant is moved but not the task itself or unrelated tasks`() = runTest {
        val h = TaskRepositoryHarness(taskDao = tree()) { request ->
            val id = request.url.encodedPath.substringAfterLast('/').toLong()
            jsonOk("""{"id":$id,"title":"Task $id","project_id":9}""")
        }

        val result = h.repository.moveDescendantsToProject(1, 9)

        assertEquals(NetworkResult.Success(3), result)
        assertEquals(setOf("/tasks/2", "/tasks/3", "/tasks/4"), h.patches().map { it.path }.toSet())
        h.patches().forEach { assertEquals(JsonPrimitive(9), it.bodyJson!!["project_id"]) }
        assertEquals(7L, h.taskDao.entity(1)?.projectId)
        assertEquals(7L, h.taskDao.entity(5)?.projectId)
    }

    @Test
    fun `descendants already in the target project are skipped`() = runTest {
        val dao = FakeTaskDao(
            listOf(row(1, 7, listOf(2)), row(2, 9, listOf(3)), row(3, 7)),
        )
        val h = TaskRepositoryHarness(taskDao = dao) { request ->
            val id = request.url.encodedPath.substringAfterLast('/').toLong()
            jsonOk("""{"id":$id,"title":"Task $id","project_id":9}""")
        }

        val result = h.repository.moveDescendantsToProject(1, 9)

        assertEquals(NetworkResult.Success(1), result)
        assertEquals(listOf("/tasks/3"), h.patches().map { it.path })
    }

    @Test
    fun `a task without subtasks or one that is unknown moves nothing`() = runTest {
        val h = TaskRepositoryHarness(taskDao = tree()) { jsonOk(movedTo(9)) }

        assertEquals(NetworkResult.Success(0), h.repository.moveDescendantsToProject(5, 9))
        assertEquals(NetworkResult.Success(0), h.repository.moveDescendantsToProject(404, 9))
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `one failure does not stop the others and is reported with counts`() = runTest {
        val h = TaskRepositoryHarness(taskDao = tree()) { request ->
            val id = request.url.encodedPath.substringAfterLast('/').toLong()
            if (id == 3L) {
                respond(content = """{"code":3005,"message":"forbidden"}""", status = HttpStatusCode.Forbidden)
            } else {
                jsonOk("""{"id":$id,"title":"Task $id","project_id":9}""")
            }
        }

        val result = h.repository.moveDescendantsToProject(1, 9)

        assertIs<NetworkResult.Error>(result)
        assertTrue(result.message.contains("1 of 3"), result.message)
        assertEquals(setOf("/tasks/2", "/tasks/3", "/tasks/4"), h.patches().map { it.path }.toSet())
    }
}
