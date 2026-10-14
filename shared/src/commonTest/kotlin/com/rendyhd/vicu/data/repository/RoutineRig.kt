package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthHarness
import com.rendyhd.vicu.auth.authHarness
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.respondJson
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.sync.TaskRefresher
import com.rendyhd.vicu.data.local.dao.RoutineArchiveDao
import com.rendyhd.vicu.data.local.entity.RoutineOccurrenceArchiveEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.HealthSubtype
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutinePeriod
import com.rendyhd.vicu.domain.model.RoutineSchedule
import com.rendyhd.vicu.domain.model.RoutineSlot
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.RoutineEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** In-memory [RoutineArchiveDao]: the phone-only history table older versions kept. */
class FakeRoutineArchiveDao : RoutineArchiveDao {
    private val rows = MutableStateFlow<List<RoutineOccurrenceArchiveEntity>>(emptyList())

    val all: List<RoutineOccurrenceArchiveEntity> get() = rows.value

    /** Makes the table unreadable, like a database that fails. */
    var failReads = false

    fun add(vararg entities: RoutineOccurrenceArchiveEntity) {
        rows.value = rows.value + entities
    }

    override fun observeByRoutine(routineId: String): Flow<List<RoutineOccurrenceArchiveEntity>> =
        rows.map { list -> list.filter { it.routineId == routineId }.sortedByDescending { it.scheduledDate } }

    override suspend fun getByRoutine(routineId: String) =
        rows.value.filter { it.routineId == routineId }.sortedByDescending { it.scheduledDate }

    override suspend fun getAll(): List<RoutineOccurrenceArchiveEntity> {
        check(!failReads) { "The history table cannot be read" }
        return rows.value.sortedByDescending { it.scheduledDate }
    }

    override suspend fun upsertAll(items: List<RoutineOccurrenceArchiveEntity>) {
        val keys = items.map { it.routineId to it.occurrenceKey }.toSet()
        rows.value = rows.value.filterNot { (it.routineId to it.occurrenceKey) in keys } + items
    }

    override suspend fun deleteByRoutine(routineId: String) {
        rows.value = rows.value.filterNot { it.routineId == routineId }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** A legacy phone-only history row for [record]. */
fun RoutineOccurrenceRecord.toLegacyRow() = RoutineOccurrenceArchiveEntity(
    routineId = routineId,
    occurrenceKey = key,
    slotId = slotId,
    scheduledDate = scheduledDate,
    scheduledMinutes = scheduledMinutes,
    timeZoneId = timeZoneId,
    status = status.name,
    loggedAt = loggedAt,
    modifiedAt = modifiedAt,
    modifiedBy = modifiedBy,
    note = note,
)

/**
 * A [RoutineRepositoryImpl] over the real [TaskRepositoryImpl], the real [RoutineArchiveStore]
 * and a [FakeTaskServer], with the clock frozen at [now] in [zone].
 */
class RoutineRig(
    appScope: CoroutineScope,
    now: String = "2026-10-06T08:00:00Z",
    val zone: TimeZone = TimeZone.of("Europe/Amsterdam"),
    val inboxProjectId: Long = 5,
) {
    val json = authTestJson
    val server = FakeTaskServer()
    val taskDao = FakeTaskDao()
    val pendingActionDao = FakePendingActionDao()
    val archiveDao = FakeRoutineArchiveDao()
    val hooks = RecordingRepositoryHooks()
    val time = FixedTimeSource(Instant.parse(now), zone)
    val prefs = RoutinePrefsStore(InMemoryPreferencesDataStore())
    private val mapper = TaskMapper(json)

    val api = VikunjaApiService(
        HttpClient(MockEngine { request -> server.handle(this, request) }) {
            install(ContentNegotiation) { json(authTestJson) }
        },
        authTestJson,
    )
    val auth: AuthHarness = authHarness(appScope) { respondJson("{}") }

    val taskRepository = TaskRepositoryImpl(
        taskDao = taskDao,
        api = api,
        pendingActionDao = pendingActionDao,
        taskMapper = mapper,
        platformHooks = hooks,
        json = json,
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        logbookPrefsStore = LogbookPrefsStore(InMemoryPreferencesDataStore()),
        dayClock = DayClock(CoroutineScope(Job()), ticking = false),
        tempIds = TempIdGenerator(InMemoryPreferencesDataStore()),
        positioner = ListPositioner(api, appScope, time),
        refresher = TaskRefresher(
            taskDao = taskDao,
            pendingActionDao = pendingActionDao,
            api = api,
            taskMapper = mapper,
            platformHooks = hooks,
            cursorStore = SyncCursorStore(InMemoryPreferencesDataStore()),
            time = time,
        ),
    )
    val store = RoutineArchiveStore(api, json)
    val repository = RoutineRepositoryImpl(
        taskDao = taskDao,
        archiveDao = archiveDao,
        taskMapper = mapper,
        taskRepository = taskRepository,
        authManager = auth.manager,
        prefsStore = prefs,
        platformHooks = hooks,
        json = json,
        archiveStore = store,
        time = time,
        mappingDispatcher = Dispatchers.Unconfined,
    )

    suspend fun signIn() {
        auth.storage.storeInboxProjectId(inboxProjectId)
    }

    /** A routine carrier on the server and in Room, the way a sync leaves it. */
    suspend fun seedCarrier(taskId: Long, payload: RoutinePayload, projectId: Long = inboxProjectId): Routine {
        val description = RoutineEnvelope.upsert("", payload, json)
        server.seed(taskId, payload.definition.name, description, done = true, projectId = projectId)
        taskDao.upsert(
            TaskEntity(
                id = taskId,
                title = payload.definition.name,
                description = description,
                done = true,
                projectId = projectId,
            ),
        )
        return Routine(taskId, payload)
    }

    /** An archive part task on the server (not in Room: the sync keeps parts out of it). */
    fun seedPart(taskId: Long, part: RoutineArchivePart, projectId: Long = inboxProjectId) {
        server.seed(taskId, "Vicu routine archive", RoutineEnvelope.encodeArchive(part, json), true, projectId)
    }

    /** The routine payload the server holds for [taskId] right now. */
    fun serverPayload(taskId: Long): RoutinePayload =
        checkNotNull(RoutineEnvelope.parse(server.row(taskId).description, json).payload)

    /** The archive part the server holds for [taskId] right now. */
    fun serverPart(taskId: Long): RoutineArchivePart =
        checkNotNull(RoutineEnvelope.parseArchive(server.row(taskId).description, json).part)

    companion object {
        const val ROUTINE_ID = "r1"
        const val SLOT_ID = "s1"

        fun definition(
            kind: RoutineKind = RoutineKind.HEALTH,
            schedule: RoutineSchedule = RoutineSchedule.Calendar(anchorDate = "2024-01-01"),
            activeFrom: String = "2024-01-01",
        ) = RoutineDefinition(
            id = ROUTINE_ID,
            name = "Vitamin D",
            kind = kind,
            healthSubtype = if (kind == RoutineKind.HEALTH) HealthSubtype.SUPPLEMENT else null,
            schedule = schedule,
            slots = listOf(RoutineSlot(SLOT_ID, "Morning", RoutinePeriod.MORNING, reminderMinutes = 480)),
            activeFrom = activeFrom,
            createdAt = "2024-01-01T08:00:00.000Z",
            updatedAt = "2024-01-01T08:00:00.000Z",
            updatedBy = "phone",
        )

        fun record(
            date: String,
            status: OccurrenceStatus = OccurrenceStatus.COMPLETED,
            modifiedAt: String = "${date}T08:00:00.000Z",
            modifiedBy: String = "phone",
            routineId: String = ROUTINE_ID,
        ) = RoutineOccurrenceRecord(
            key = "$routineId:$date:$SLOT_ID",
            routineId = routineId,
            slotId = SLOT_ID,
            scheduledDate = date,
            scheduledMinutes = 480,
            timeZoneId = "Europe/Amsterdam",
            status = status,
            loggedAt = if (status == OccurrenceStatus.COMPLETED) "${date}T07:00:00.000Z" else "",
            modifiedAt = modifiedAt,
            modifiedBy = modifiedBy,
        )

        fun payload(
            vararg records: RoutineOccurrenceRecord,
            prunedBefore: String = "",
            definition: RoutineDefinition = definition(),
        ) = RoutinePayload(
            definition = definition,
            occurrences = records.associateBy { it.key },
            prunedBefore = prunedBefore,
        )
    }
}
