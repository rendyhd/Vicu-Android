package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.CarrierIdStore
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.CarrierFinder
import com.rendyhd.vicu.data.sync.CarrierSpec
import com.rendyhd.vicu.data.sync.ProjectTaskCounts
import com.rendyhd.vicu.domain.model.ProjectTally
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The progress ring of a project: the open side from the local tasks, the done side from the
 * server's total minus the hidden carrier tasks that are known without asking.
 */
class ProjectProgressSourceImplTest {

    private val server = "https://vikunja.example"
    private val customListMarker = "<!-- vicu-custom-lists:v1:e30 -->"
    private val routineMarker = "<!-- vicu-routine:v1:e30 -->"

    private class Rig(
        val source: ProjectProgressSourceImpl,
        val taskDao: FakeTaskDao,
        val carriers: CarrierFinder,
        val requests: MutableList<String>,
        private val authScope: CoroutineScope,
    ) {
        fun close() = authScope.cancel()
    }

    private suspend fun TestScope.rig(
        tasks: List<TaskEntity>,
        doneOnServer: Map<Long, Long>,
    ): Rig {
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                val projectId = Regex("/projects/(\\d+)/tasks").find(request.url.encodedPath)!!.groupValues[1].toLong()
                val total = doneOnServer[projectId] ?: 0L
                val item = if (total > 0) """{"id":1,"title":"A","done":true,"project_id":$projectId}""" else ""
                respond(
                    """{"items":[$item],"total":$total,"page":1,"per_page":1,"total_pages":$total}""",
                    HttpStatusCode.OK,
                    authTestJsonHeaders,
                )
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        val api = VikunjaApiService(client, authTestJson)
        val time = MutableTimeSource(Instant.parse("2026-10-08T10:00:00Z"))
        val authScope = CoroutineScope(SupervisorJob())
        val storage = InMemoryTokenStorage().also { it.storeVikunjaUrl(server) }
        val auth = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("no network in this test") },
            appScope = authScope,
            networkMonitor = FakeNetworkMonitor(),
        )
        val taskDao = FakeTaskDao(tasks)
        val carriers = CarrierFinder(api, CarrierIdStore(InMemoryPreferencesDataStore()), time)
        val source = ProjectProgressSourceImpl(
            taskDao = taskDao,
            counts = ProjectTaskCounts(api, { server }, time, backgroundScope),
            carrierFinder = carriers,
            archiveStore = RoutineArchiveStore(api, authTestJson),
            authManager = auth,
            json = authTestJson,
        )
        return Rig(source, taskDao, carriers, requests, authScope)
    }

    private fun task(id: Long, project: Long, done: Boolean = false, description: String = "") =
        TaskEntity(id = id, projectId = project, done = done, description = description)

    @Test
    fun `the tallies count the user's open and done tasks of each project, hidden tasks left out`() = runTest {
        val rig = rig(
            listOf(
                task(1, project = 7),
                task(2, project = 7),
                task(3, project = 7, done = true),
                task(4, project = 8, done = true),
                task(5, project = 7, done = true, description = routineMarker),
                task(6, project = 9, done = true, description = customListMarker),
            ),
            doneOnServer = emptyMap(),
        )

        val tallies = rig.source.observeTallies().first()

        assertEquals(ProjectTally(open = 2, doneOnPhone = 1), tallies[7L])
        assertEquals(ProjectTally(open = 0, doneOnPhone = 1), tallies[8L])
        assertEquals(null, tallies[9L], "a project that only holds a hidden task has no row")
        rig.close()
    }

    @Test
    fun `the done count is the server's total`() = runTest {
        val rig = rig(emptyList(), doneOnServer = mapOf(7L to 12L))

        assertEquals(12L, rig.source.doneCount(7, ProjectTally.EMPTY))
        assertEquals(listOf("/projects/7/tasks"), rig.requests)
        rig.close()
    }

    @Test
    fun `a routine carrier the phone holds is not a done task of its project`() = runTest {
        val rig = rig(listOf(task(5, project = 7, done = true, description = routineMarker)), doneOnServer = mapOf(7L to 4L))

        assertEquals(3L, rig.source.doneCount(7, ProjectTally.EMPTY))
        rig.close()
    }

    @Test
    fun `the synced custom list carrier is not a done task of its project`() = runTest {
        val rig = rig(emptyList(), doneOnServer = mapOf(7L to 4L, 8L to 4L))
        rig.carriers.remember(CarrierSpec.CUSTOM_LISTS, server, id = 900, projectId = 7)

        assertEquals(3L, rig.source.doneCount(7, ProjectTally.EMPTY))
        assertEquals(4L, rig.source.doneCount(8, ProjectTally.EMPTY), "only the project the carrier lives in")
        rig.close()
    }

    @Test
    fun `a carrier known from two places is taken off once`() = runTest {
        val rig = rig(listOf(task(900, project = 7, done = true, description = customListMarker)), doneOnServer = mapOf(7L to 4L))
        rig.carriers.remember(CarrierSpec.CUSTOM_LISTS, server, id = 900, projectId = 7)

        assertEquals(3L, rig.source.doneCount(7, ProjectTally.EMPTY))
        rig.close()
    }

    @Test
    fun `the count never goes below zero`() = runTest {
        val rig = rig(listOf(task(5, project = 7, done = true, description = routineMarker)), doneOnServer = emptyMap())

        assertEquals(0L, rig.source.doneCount(7, ProjectTally.EMPTY))
        rig.close()
    }
}
