package com.rendyhd.vicu.worker

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.created
import com.rendyhd.vicu.worker.SyncEngineHarness.Companion.emptyPage
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An offline-created task has alarms under its temporary id. When the sync replays the create,
 * those alarms move to the real id: the temporary one is cancelled and the real one scheduled.
 */
class SyncEngineOfflineCreateAlarmTest {

    @Test
    fun `replaying an offline create moves the alarms from the temporary id to the real one`() = runTest {
        val h = SyncEngineHarness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks" ->
                    created("""{"id":501,"title":"Call mum","project_id":7}""")
                request.method == HttpMethod.Get -> emptyPage()
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
        h.taskDao.upsert(TaskEntity(id = -1, title = "Call mum", projectId = 7))
        h.pendingActionDao.insert(queuedCreate(tempId = -1, title = "Call mum"))

        h.engine.performSync()

        assertTrue(-1L in h.hooks.cancelled, "the alarms of the temporary id are cancelled")
        assertEquals(listOf(501L), h.hooks.scheduled.filter { it > 0 }, "the real id gets its alarms")
        h.close()
    }
}
