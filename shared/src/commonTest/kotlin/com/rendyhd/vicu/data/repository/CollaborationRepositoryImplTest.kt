package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.FakeCollaborationDao
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.dao.CollabQueue
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.ProjectMemberEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Assignees and comments are optimistic, queued offline and rolled back when refused (cross-app semantics 9). */
class CollaborationRepositoryImplTest {

    /** The fake project DAO lists nothing; the repository reads the active projects from this one. */
    private class ListingProjectDao(val inner: FakeProjectDao) : ProjectDao by inner {
        override fun getAll(): Flow<List<ProjectEntity>> = flowOf(inner.snapshot().filter { !it.isArchived })
    }

    private val maya = User(2, "maya", "Maya")

    private inner class Rig(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val sent = mutableListOf<String>()
        val taskDao = FakeTaskDao(listOf(TaskEntity(id = 42, title = "Flights", projectId = 7)))
        val pendingActionDao = FakePendingActionDao()
        val collaborationDao = FakeCollaborationDao()
        val projectDao = ListingProjectDao(
            FakeProjectDao(listOf(ProjectEntity(id = 7, title = "Lisbon", ownerId = 1, maxPermission = 2), ProjectEntity(id = 8, title = "Mine", ownerId = 1))),
        )
        val hooks = RecordingRepositoryHooks()
        val storage = InMemoryTokenStorage()
        private val client = HttpClient(
            MockEngine { request ->
                sent += "${request.method.value} ${request.url.encodedPath}"
                handler(request)
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        val api = VikunjaApiService(client, authTestJson)
        val mapper = TaskMapper(authTestJson)
        val repository = CollaborationRepositoryImpl(
            collaborationDao = collaborationDao,
            taskDao = taskDao,
            projectDao = projectDao,
            pendingActionDao = pendingActionDao,
            api = api,
            taskMapper = mapper,
            projectMapper = ProjectMapper(),
            tokenStorage = storage,
            tempIds = TempIdGenerator(InMemoryPreferencesDataStore()),
            platformHooks = hooks,
            mappingDispatcher = Dispatchers.Unconfined,
        )

        suspend fun task(): Task = with(mapper) { taskDao.entity(42)!!.toDomain() }
    }

    private fun MockRequestHandleScope.unavailable(): HttpResponseData = respond("", HttpStatusCode.ServiceUnavailable)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, authTestJsonHeaders)

    @Test
    fun `an assignment shows at once and is sent`() = runTest {
        val rig = Rig { request ->
            if (request.url.encodedPath == "/tasks/42/assignees") json("""{"user_id":2}""", HttpStatusCode.Created) else error("unexpected")
        }

        val result = rig.repository.setAssigned(rig.task(), maya, assigned = true)

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf(maya), rig.task().assignees)
        assertTrue(rig.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `an assignment while the server is unreachable is queued and stays on the task`() = runTest {
        val rig = Rig { unavailable() }

        val result = rig.repository.setAssigned(rig.task(), maya, assigned = true)

        assertTrue(result is NetworkResult.Success, "the change counts as made")
        assertEquals(listOf(maya), rig.task().assignees)
        val action = rig.pendingActionDao.snapshot().single()
        assertEquals(CollabQueue.ASSIGNEE to CollabQueue.ADD, action.entityType to action.actionType)
        assertEquals(42L, action.entityId)
        assertEquals(2L, CollabQueue.assignee(action.payload)?.userId)
        assertEquals(1, rig.hooks.syncTriggers)
    }

    @Test
    fun `a refused assignment is rolled back`() = runTest {
        val rig = Rig { json("""{"title":"Forbidden","status":403,"code":1}""", HttpStatusCode.Forbidden) }

        val result = rig.repository.setAssigned(rig.task(), maya, assigned = true)

        assertTrue(result is NetworkResult.Error)
        assertTrue(rig.task().assignees.isEmpty())
        assertTrue(rig.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `a comment written offline shows at once as pending and is queued`() = runTest {
        val rig = Rig { unavailable() }
        rig.storage.storeUserId(1)
        rig.collaborationDao.upsertMembers(listOf(ProjectMemberEntity(7, 1, "rendy", "Rendy", 0), ProjectMemberEntity(7, 2, "maya", "Maya", 1)))

        rig.repository.postComment(42, "<p>Booked</p>")

        val thread = rig.repository.comments(42).first()
        assertEquals(1, thread.size)
        assertTrue(thread.single().pending)
        assertTrue(thread.single().id < 0, "a temporary id until it is sent")
        assertEquals("rendy", thread.single().author?.username)
        assertEquals(1, rig.collaborationDao.getSignal(42)?.commentCount)
        val action = rig.pendingActionDao.snapshot().single()
        assertEquals(CollabQueue.COMMENT to CollabQueue.CREATE, action.entityType to action.actionType)
        assertEquals(thread.single().id, CollabQueue.comment(action.payload)?.commentId)
    }

    @Test
    fun `a sent comment takes its real id`() = runTest {
        val rig = Rig { request ->
            if (request.url.encodedPath == "/tasks/42/comments") {
                json(
                    """{"id":70,"comment":"<p>Booked</p>","author":{"id":1,"username":"rendy","name":"Rendy"},"created":"2026-10-10T08:00:00Z","updated":"2026-10-10T08:00:00Z"}""",
                    HttpStatusCode.Created,
                )
            } else {
                error("unexpected")
            }
        }

        rig.repository.postComment(42, "<p>Booked</p>")

        val thread = rig.repository.comments(42).first()
        assertEquals(listOf(70L), thread.map { it.id })
        assertTrue(!thread.single().pending)
        assertTrue(rig.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `opening the comments clears the unread mark here at once`() = runTest {
        val rig = Rig { unavailable() }
        rig.collaborationDao.upsertSignals(listOf(TaskSignalEntity(42, commentCount = 3, isUnread = true)))

        rig.repository.markRead(42)

        assertEquals(TaskSignalEntity(42, 3, false), rig.collaborationDao.getSignal(42))
    }

    @Test
    fun `signals are read only for shared projects`() = runTest {
        var filter: String? = null
        val rig = Rig { request ->
            when (request.url.encodedPath) {
                "/tasks" -> {
                    filter = request.url.parameters["filter"]
                    assertEquals(listOf("comment_count", "is_unread"), request.url.parameters.getAll("expand")?.filter { it != "subtasks" })
                    json(
                        """{"items":[{"id":42,"title":"Flights","project_id":7,"comment_count":4,"is_unread":true,"assignees":[{"id":2,"username":"maya","name":"Maya"}]}],"total":1,"page":1,"per_page":1000,"total_pages":1}""",
                    )
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        rig.storage.storeUserId(1)
        rig.collaborationDao.upsertMembers(
            listOf(
                ProjectMemberEntity(7, 1, "rendy", "Rendy", 0),
                ProjectMemberEntity(7, 2, "maya", "Maya", 1),
                ProjectMemberEntity(8, 1, "rendy", "Rendy", 0),
            ),
        )
        rig.repository.state.first()

        rig.repository.refreshSignals()

        assertEquals("done = false && project in 7", filter)
        assertEquals(TaskSignalEntity(42, 4, true), rig.collaborationDao.getSignal(42))
        assertEquals(listOf(maya), rig.task().assignees)
    }

    @Test
    fun `a project's access follows its members and your permission`() = runTest {
        val rig = Rig { unavailable() }
        rig.storage.storeUserId(1)
        rig.collaborationDao.upsertMembers(listOf(ProjectMemberEntity(7, 1, "rendy", "Rendy", 0), ProjectMemberEntity(7, 2, "maya", "Maya", 1)))

        val state = rig.repository.state.first()

        assertTrue(state.access(7).shared)
        assertTrue(state.access(7).isAdmin)
        assertEquals(listOf(1L, 2L), state.access(7).members.map { it.id })
        assertTrue(!state.access(8).shared, "nobody else can see it")
    }
}
