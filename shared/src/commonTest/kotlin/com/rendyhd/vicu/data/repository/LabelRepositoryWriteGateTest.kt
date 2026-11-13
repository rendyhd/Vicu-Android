package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.FakeLabelDao
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Label edits and deletes go through the write gate like task changes (review C-8): a change made
 * while an older one waits in the queue or is being sent joins the queue. A delete the server
 * refuses leaves the label in place (review E-3).
 */
class LabelRepositoryWriteGateTest {

    private class Rig(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val sent = mutableListOf<String>()
        val labelDao = FakeLabelDao()
        val taskDao = FakeTaskDao()
        val pendingActionDao = FakePendingActionDao()
        private val client = HttpClient(
            MockEngine { request ->
                sent += "${request.method.value} ${request.url.encodedPath}"
                handler(request)
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        private val api = VikunjaApiService(client, authTestJson)
        val repository = LabelRepositoryImpl(
            labelDao = labelDao,
            taskDao = taskDao,
            pendingActionDao = pendingActionDao,
            api = api,
            labelMapper = LabelMapper(),
            taskMapper = TaskMapper(authTestJson),
            platformHooks = RecordingRepositoryHooks(),
            json = authTestJson,
            tempIds = TempIdGenerator(InMemoryPreferencesDataStore()),
            labelRefresher = LabelRefresher(labelDao, taskDao, pendingActionDao, api, LabelMapper(), TaskMapper(authTestJson)),
            mappingDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun MockRequestHandleScope.refused(): HttpResponseData = respond(
        content = """{"title":"Forbidden","status":403,"detail":"Forbidden","code":0}""",
        status = HttpStatusCode.Forbidden,
        headers = authTestJsonHeaders,
    )

    private fun waiting(status: String) = PendingActionEntity(
        entityType = "label", entityId = 5, actionType = "update", payload = """{"title":"Older"}""",
        status = status, createdAt = "2026-11-13T08:00:00Z", updatedAt = "2026-11-13T08:00:00Z",
    )

    @Test
    fun `an edit made while an older one waits in the queue joins it`() = runTest {
        val rig = Rig { error("Nothing may be sent past the queued change") }
        rig.labelDao.upsert(LabelEntity(id = 5, title = "Older"))
        rig.pendingActionDao.insert(waiting("pending"))

        val result = rig.repository.update(Label(id = 5, title = "Newer"))

        assertIs<NetworkResult.Success<*>>(result)
        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.pendingActionDao.snapshot().single().payload.contains("Newer"))
        assertEquals("Newer", rig.labelDao.getById(5)!!.title)
    }

    @Test
    fun `an edit made while the sync sends an older one joins the queue`() = runTest {
        val rig = Rig { error("Nothing may be sent past the change in flight") }
        rig.labelDao.upsert(LabelEntity(id = 5, title = "Older"))
        rig.pendingActionDao.insert(waiting("processing"))

        rig.repository.update(Label(id = 5, title = "Newer"))

        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.pendingActionDao.snapshot().any { it.status == "pending" && it.payload.contains("Newer") })
    }

    @Test
    fun `a delete the server refuses leaves the label`() = runTest {
        val rig = Rig { refused() }
        rig.labelDao.upsert(LabelEntity(id = 5, title = "home"))

        val result = rig.repository.delete(5)

        assertIs<NetworkResult.Error>(result)
        assertNotNull(rig.labelDao.getById(5), "still shown: the server kept it")
        assertTrue(rig.pendingActionDao.snapshot().isEmpty())
    }

    @Test
    fun `a delete the server takes removes the label and offline it is queued`() = runTest {
        val online = Rig { respond("", HttpStatusCode.NoContent) }
        online.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        assertIs<NetworkResult.Success<*>>(online.repository.delete(5))
        assertNull(online.labelDao.getById(5))

        val offline = Rig { respond("", HttpStatusCode.ServiceUnavailable) }
        offline.labelDao.upsert(LabelEntity(id = 5, title = "home"))
        assertIs<NetworkResult.Success<*>>(offline.repository.delete(5))
        assertNull(offline.labelDao.getById(5))
        assertEquals("delete", offline.pendingActionDao.snapshot().single().actionType)
    }
}
