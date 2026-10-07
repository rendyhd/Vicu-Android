package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.worker.FakeLabelDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Labels and projects are replaced by the server's list: deletions arrive, queued edits survive. */
class MetadataRefreshTest {

    private fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}"""

    private class Rig(
        labelsJson: String,
        projectsJson: String,
        val taskDao: FakeTaskDao = FakeTaskDao(),
        val labelDao: FakeLabelDao = FakeLabelDao(),
        val projectDao: FakeProjectDao = FakeProjectDao(),
        val pending: FakePendingActionDao = FakePendingActionDao(),
    ) {
        private val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/labels" -> respond(labelsJson, HttpStatusCode.OK, authTestJsonHeaders)
                    "/projects" -> respond(projectsJson, HttpStatusCode.OK, authTestJsonHeaders)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        private val api = VikunjaApiService(client, authTestJson)
        val labels = LabelRefresher(labelDao, taskDao, pending, api, LabelMapper(), TaskMapper(authTestJson))
        val projects = ProjectRefresher(projectDao, pending, api, ProjectMapper())
    }

    private val labelA = """{"id":5,"title":"home","hex_color":"ff0000"}"""
    private val labelB = """{"id":6,"title":"work","hex_color":"00ff00"}"""

    // ---- labels ----------------------------------------------------------------------------------

    @Test
    fun `a label deleted on the server is deleted locally`() = runTest {
        val rig = Rig(page(labelA), page())
        rig.labelDao.upsertAll(listOf(LabelEntity(5, "home"), LabelEntity(6, "work")))

        rig.labels.refresh()

        assertNotNull(rig.labelDao.getById(5))
        assertNull(rig.labelDao.getById(6))
    }

    @Test
    fun `a deleted label also leaves the cached tasks that carried it`() = runTest {
        val rig = Rig(page(labelA), page())
        rig.labelDao.upsertAll(listOf(LabelEntity(5, "home"), LabelEntity(6, "work")))
        rig.taskDao.upsert(
            TaskEntity(
                id = 1, title = "T", projectId = 7,
                labelsJson = "[$labelA,$labelB]",
            ),
        )
        rig.taskDao.upsert(TaskEntity(id = 2, title = "Other", projectId = 7, labelsJson = "[$labelA]"))

        rig.labels.refresh()

        val chips = rig.taskDao.entity(1)!!.labelsJson
        assertTrue("\"id\":6" !in chips && "\"id\":5" in chips, chips)
        assertTrue("\"id\":5" in rig.taskDao.entity(2)!!.labelsJson)
    }

    @Test
    fun `a label with a queued change is neither overwritten nor deleted`() = runTest {
        val rig = Rig(page(labelA), page())
        rig.labelDao.upsertAll(listOf(LabelEntity(5, "renamed offline"), LabelEntity(-3, "created offline")))
        rig.pending.insert(PendingActionEntity(entityType = "label", entityId = 5, actionType = "update", payload = "{}"))
        rig.pending.insert(PendingActionEntity(entityType = "label", entityId = -3, actionType = "create", payload = "{}"))

        rig.labels.refresh()

        assertEquals("renamed offline", rig.labelDao.getById(5)!!.title)
        assertNotNull(rig.labelDao.getById(-3))
    }

    @Test
    fun `an offline label whose create was discarded is removed`() = runTest {
        val rig = Rig(page(labelA), page())
        rig.labelDao.upsert(LabelEntity(-3, "created offline"))

        rig.labels.refresh()

        assertNull(rig.labelDao.getById(-3))
    }

    // ---- projects --------------------------------------------------------------------------------

    private val projectOne = """{"id":1,"title":"Inbox"}"""

    @Test
    fun `projects missing from the server are deleted`() = runTest {
        val rig = Rig(page(), page(projectOne))
        rig.projectDao.upsertAll(listOf(ProjectEntity(1, "Inbox"), ProjectEntity(2, "Gone")))

        rig.projects.refresh()

        assertEquals(listOf(1L), rig.projectDao.snapshot().map { it.id })
    }

    @Test
    fun `a project with a queued change keeps its local edit`() = runTest {
        val rig = Rig(page(), page(projectOne, """{"id":2,"title":"Server title"}"""))
        rig.projectDao.upsertAll(listOf(ProjectEntity(1, "Inbox"), ProjectEntity(2, "Renamed offline")))
        rig.pending.insert(PendingActionEntity(entityType = "project", entityId = 2, actionType = "update", payload = "{}"))

        rig.projects.refresh()

        assertEquals("Renamed offline", rig.projectDao.snapshot().first { it.id == 2L }.title)
    }

    @Test
    fun `a project with a queued change survives even when the server list lacks it`() = runTest {
        val rig = Rig(page(), page(projectOne))
        rig.projectDao.upsertAll(listOf(ProjectEntity(1, "Inbox"), ProjectEntity(2, "Edited offline")))
        rig.pending.insert(PendingActionEntity(entityType = "project", entityId = 2, actionType = "update", payload = "{}", status = "failed"))

        rig.projects.refresh()

        assertEquals(setOf(1L, 2L), rig.projectDao.snapshot().map { it.id }.toSet())
    }
}
