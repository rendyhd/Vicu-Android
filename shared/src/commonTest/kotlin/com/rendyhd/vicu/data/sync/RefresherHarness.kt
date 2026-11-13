package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.CurrentUser
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.data.local.FakeCollaborationDao
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.util.MutableTimeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Instant

/** A [TaskRefresher] over in-memory fakes and a [TaskListServer]. */
class RefresherHarness(
    val server: TaskListServer = TaskListServer(),
    val taskDao: FakeTaskDao = FakeTaskDao(),
    val pendingActionDao: FakePendingActionDao = FakePendingActionDao(),
    val hooks: RecordingRepositoryHooks = RecordingRepositoryHooks(),
    val time: MutableTimeSource = MutableTimeSource(Instant.parse("2026-10-06T12:00:00Z")),
    val cursorStore: SyncCursorStore = SyncCursorStore(InMemoryPreferencesDataStore()),
    val currentUser: CurrentUser = CurrentUser.fixed(1),
    /** This device's id in routine records; with it a refresh writes lost routine changes again. */
    val routineDeviceId: String? = null,
    /** With it a refresh keeps the comment counts its reads bring. */
    val collaborationDao: FakeCollaborationDao? = null,
    /** With it a refresh writes nothing once the data was wiped after it started. */
    val accountEpoch: AccountEpoch? = null,
) {
    private val client = HttpClient(MockEngine { request -> server.handle(this, request) }) {
        install(ContentNegotiation) { json(authTestJson) }
    }
    val api = VikunjaApiService(client, authTestJson)
    val mapper = TaskMapper(authTestJson)

    val refresher = TaskRefresher(
        taskDao = taskDao,
        pendingActionDao = pendingActionDao,
        api = api,
        taskMapper = mapper,
        platformHooks = hooks,
        cursorStore = cursorStore,
        time = time,
        currentUser = currentUser,
        routineDeviceId = routineDeviceId?.let { id -> { id } },
        collaborationDao = collaborationDao,
        accountEpoch = accountEpoch,
    )

    suspend fun queueTaskAction(taskId: Long, type: String = "update", status: String = "pending") {
        pendingActionDao.insert(
            PendingActionEntity(
                entityType = "task", entityId = taskId, actionType = type, payload = "{}", status = status,
            ),
        )
    }
}
