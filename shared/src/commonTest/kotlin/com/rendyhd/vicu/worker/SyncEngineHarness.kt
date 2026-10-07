package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.sync.LabelRefresher
import com.rendyhd.vicu.data.sync.ProjectRefresher
import com.rendyhd.vicu.data.sync.TaskRefresher
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.mapper.LabelMapper
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.ListPositioner
import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class FakeLabelDao : LabelDao {
    private val rows = LinkedHashMap<Long, LabelEntity>()

    override fun getAll(): Flow<List<LabelEntity>> = flowOf(rows.values.toList())
    override suspend fun getById(id: Long): LabelEntity? = rows[id]

    override suspend fun upsert(label: LabelEntity) {
        rows[label.id] = label
    }

    override suspend fun upsertAll(labels: List<LabelEntity>) {
        labels.forEach { rows[it.id] = it }
    }

    override suspend fun getAllSync(): List<LabelEntity> = rows.values.toList()

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
    }

    override suspend fun deleteByIdsChunk(ids: List<Long>) {
        ids.forEach { rows.remove(it) }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

class FakeCustomListRepository : CustomListRepository {
    override val lists: Flow<List<CustomList>> = flowOf(emptyList())
    override val syncStatus: StateFlow<CustomListSyncStatus> = MutableStateFlow(CustomListSyncStatus.Idle)
    var clearLocalCalls = 0
        private set

    override suspend fun upsert(customList: CustomList) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun reorder(fromIndex: Int, toIndex: Int) = Unit

    override suspend fun clearLocal() {
        clearLocalCalls++
    }
    override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
}

/** Waits in real time until the suspending [condition] holds. */
suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: suspend () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }
}

/** An empty list page in Vikunja's pagination envelope. */
const val EMPTY_PAGE = """{"items":[],"total":0,"page":1,"per_page":100,"total_pages":1}"""

/** Builds a minimal queued "create" action for an offline-created task. */
fun queuedCreate(tempId: Long, title: String, projectId: Long = 7, createdAt: String = "2026-10-06T10:00:00Z"): PendingActionEntity =
    PendingActionEntity(
        entityType = "task",
        entityId = tempId,
        actionType = "create",
        payload = authTestJson.encodeToString(
            Task.serializer(),
            Task(id = tempId, title = title, projectId = projectId, created = createdAt, updated = createdAt),
        ),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

/**
 * A [SyncEngine] on in-memory fakes with a mock HTTP engine. [handler] sees every request after
 * it has been appended to [requests] as "METHOD /path".
 */
class SyncEngineHarness(
    val taskDao: FakeTaskDao = FakeTaskDao(),
    val pendingActionDao: FakePendingActionDao = FakePendingActionDao(),
    val projectDao: FakeProjectDao = FakeProjectDao(),
    val labelDao: FakeLabelDao = FakeLabelDao(),
    val hooks: RecordingRepositoryHooks = RecordingRepositoryHooks(),
    val customLists: CustomListRepository = FakeCustomListRepository(),
    val routines: RoutineRepository? = null,
    /** Gives the engine a [ListPositioner] (over the harness's DAO), so replayed creates are put at the end of their list. */
    anchorCreates: Boolean = false,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    private val requestLock = Mutex()
    private val recorded = mutableListOf<String>()

    suspend fun requests(): List<String> = requestLock.withLock { recorded.toList() }
    suspend fun count(method: HttpMethod, path: String): Int =
        requests().count { it == "${method.value} $path" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client = HttpClient(
        MockEngine { request ->
            requestLock.withLock { recorded += "${request.method.value} ${request.url.encodedPath}" }
            handler(request)
        },
    ) {
        install(ContentNegotiation) { json(authTestJson) }
    }

    val api = VikunjaApiService(client, authTestJson)

    /** Unconfined, so a background position request starts at once; a test waits for it with awaitIdle(). */
    val positioner = ListPositioner(
        api,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        storePosition = { taskId, position -> taskDao.updatePosition(taskId, position) },
    )
    private val enginePositioner = if (anchorCreates) positioner else null
    private val storage = InMemoryTokenStorage()
    val cursorStore = SyncCursorStore(InMemoryPreferencesDataStore())
    val refresher = TaskRefresher(
        taskDao = taskDao,
        pendingActionDao = pendingActionDao,
        api = api,
        taskMapper = TaskMapper(authTestJson),
        platformHooks = hooks,
        cursorStore = cursorStore,
    )
    val authManager = AuthManager(
        platformAuthHooks = RecordingAuthHooks(),
        tokenStorage = storage,
        apiServiceProvider = { api },
        appScope = scope,
        networkMonitor = FakeNetworkMonitor(),
    )

    /** Creates another engine over the same DAOs, like a second worker in the same process. */
    fun newEngine(): SyncEngine = SyncEngine(
        pendingActionDao = pendingActionDao,
        taskDao = taskDao,
        labelDao = labelDao,
        projectDao = projectDao,
        api = api,
        taskMapper = TaskMapper(authTestJson),
        labelMapper = LabelMapper(),
        projectMapper = ProjectMapper(),
        taskRefresher = refresher,
        labelRefresher = LabelRefresher(labelDao, taskDao, pendingActionDao, api, LabelMapper(), TaskMapper(authTestJson)),
        projectRefresher = ProjectRefresher(projectDao, pendingActionDao, api, ProjectMapper()),
        platformHooks = hooks,
        json = authTestJson,
        baseUrlHolder = BaseUrlHolder(storage),
        authManager = authManager,
        customListRepository = customLists,
        routineRepository = routines,
        positioner = enginePositioner,
    )

    val engine: SyncEngine = newEngine()

    fun close() {
        scope.cancel()
    }

    companion object {
        fun MockRequestHandleScope.emptyPage(): HttpResponseData =
            respond(content = EMPTY_PAGE, status = HttpStatusCode.OK, headers = authTestJsonHeaders)

        fun MockRequestHandleScope.created(body: String): HttpResponseData =
            respond(content = body, status = HttpStatusCode.Created, headers = authTestJsonHeaders)
    }
}
