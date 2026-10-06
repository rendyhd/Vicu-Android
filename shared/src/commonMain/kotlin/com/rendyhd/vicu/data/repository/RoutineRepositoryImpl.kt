package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.dao.RoutineArchiveDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.RoutineOccurrenceArchiveEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.RoutineCsvExport
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RoutineArchive
import com.rendyhd.vicu.util.RoutineCsv
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.RoutineScheduleEngine
import com.rendyhd.vicu.util.RoutineTime
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.isNetworkFailure
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/**
 * Routines live in hidden done tasks (the main carrier) and, for history older than 400 days,
 * in hidden done "archive part" tasks (docs/cross-app-semantics-v1.md, section 6).
 *
 * Every change goes through [writeCarrier]. When a write pushes history past the rolling window
 * the order is fixed: the old history is written to the archive parts first and read back, and
 * only then does the main carrier lose it. If the archive cannot be written the carrier is
 * written unpruned, so no history is ever lost; the next change tries again.
 *
 * Archive parts are read on demand (the History screen and the CSV export), never by the day
 * views, and they are not kept in Room.
 */
class RoutineRepositoryImpl(
    private val taskDao: TaskDao,
    /** The phone-only history older versions kept. Now only a queue waiting to be uploaded. */
    private val archiveDao: RoutineArchiveDao,
    private val taskMapper: TaskMapper,
    private val taskRepository: TaskRepository,
    private val authManager: AuthManager,
    private val prefsStore: RoutinePrefsStore,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
    private val archiveStore: RoutineArchiveStore,
    private val time: TimeSource = SystemTimeSource,
) : RoutineRepository {
    private companion object {
        const val TAG = "RoutineRepository"
    }

    private data class ParsedCarriers(
        val routines: List<Routine>,
        val issues: List<RoutineParseIssue>,
    )

    /** One archive operation at a time in this process: writes, the migration and deletes. */
    private val archiveMutex = Mutex()

    private val warning = MutableStateFlow<String?>(null)
    override val archiveWarning: StateFlow<String?> = warning.asStateFlow()

    private fun today(): LocalDate = time.now().toLocalDateTime(time.zone()).date

    private val parsedFlow: Flow<ParsedCarriers> = taskDao.getRoutineCarriersFlow().map { entities ->
        val routines = mutableListOf<Routine>()
        val issues = mutableListOf<RoutineParseIssue>()
        entities.forEach { entity ->
            val parsed = RoutineEnvelope.parse(entity.description, json)
            // An archive part is metadata too, but not a routine and not damaged.
            if (!parsed.isCarrier) return@forEach
            val payload = parsed.payload
            if (payload != null) {
                routines += Routine(entity.id, payload)
            } else {
                issues += RoutineParseIssue(
                    taskId = entity.id,
                    title = entity.title,
                    message = parsed.error ?: "Cannot read routine metadata",
                )
            }
        }
        ParsedCarriers(
            routines = routines.sortedBy { it.definition.createdAt },
            issues = issues,
        )
    }

    override fun observeActive(): Flow<List<Routine>> =
        parsedFlow.map { parsed -> parsed.routines.filterNot { it.definition.archived } }

    override fun observeArchived(): Flow<List<Routine>> =
        parsedFlow.map { parsed -> parsed.routines.filter { it.definition.archived } }

    override fun observeRoutine(routineId: String): Flow<Routine?> =
        parsedFlow.map { parsed -> parsed.routines.firstOrNull { it.definition.id == routineId } }

    override fun observeIssues(): Flow<List<RoutineParseIssue>> = parsedFlow.map { it.issues }

    override fun observeDay(date: String): Flow<RoutineDay> = parsedFlow.map { parsed ->
        val localDate = LocalDate.parse(date)
        val today = today()
        val zone = time.zone()
        val occurrences = parsed.routines
            .asSequence()
            .filterNot { it.definition.archived }
            .flatMap { routine ->
                RoutineScheduleEngine.occurrencesForDate(routine, localDate, zone, today).asSequence()
            }
            .sortedWith(
                compareBy<com.rendyhd.vicu.domain.model.RoutineOccurrence> { it.status == OccurrenceStatus.COMPLETED }
                    .thenByDescending { it.overdue }
                    .thenBy { it.slot.reminderMinutes }
                    .thenBy { it.routine.definition.name.lowercase() },
            )
            .toList()
        RoutineDay(date, occurrences)
    }

    /**
     * The whole history of a routine: its main carrier, the archive parts on the server and any
     * phone-only history that has not been uploaded yet. The main carrier and the phone-only
     * table update live; the parts are read once per subscription, and while the server cannot
     * be reached the ones this process saw earlier are shown.
     */
    override fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> {
        val parts: Flow<List<RoutineArchivePart>> = flow {
            val prunedBefore = parsedFlow.first().routines
                .firstOrNull { it.definition.id == routineId }?.payload?.prunedBefore.orEmpty()
            emit(archiveStore.cachedParts(routineId))
            if (prunedBefore.isEmpty()) return@flow
            // The read is guarded, the emit is not: an exception from the collector must reach it.
            val loaded = try {
                archiveStore.loadParts(listOf(routineId)).map { it.part }.filter { it.routineId == routineId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Could not read the archive of routine $routineId: ${e.message}")
                null
            }
            if (loaded != null) emit(loaded)
        }
        return combine(parsedFlow, parts, archiveDao.observeByRoutine(routineId)) { parsed, archived, legacy ->
            val main = parsed.routines
                .firstOrNull { it.definition.id == routineId }
                ?.payload?.occurrences.orEmpty()
            val phoneOnly = legacy.map { it.toDomain() }.associateBy { it.key }
            RoutineScheduleEngine.mergeOccurrenceMaps(
                RoutineArchive.readHistory(routineId, main, archived),
                phoneOnly,
            ).values.sortedWith(
                compareByDescending<RoutineOccurrenceRecord> { it.scheduledDate }
                    .thenByDescending { it.scheduledMinutes },
            )
        }
    }

    override suspend fun create(draft: RoutineDraft): NetworkResult<Routine> {
        val validation = validateDraft(draft)
        if (validation != null) return NetworkResult.Error(validation)
        val inboxId = authManager.getInboxProjectId()
            ?: return NetworkResult.Error("Choose an inbox project before creating routines")
        val now = RoutineTime.now(time)
        val deviceId = prefsStore.getOrCreateDeviceId()
        val routineId = randomUuid()
        val slots = draft.slots.map { slot ->
            slot.copy(id = slot.id.ifBlank(::randomUuid))
        }
        val definition = RoutineDefinition(
            id = routineId,
            name = draft.name.trim(),
            kind = draft.kind,
            healthSubtype = if (draft.kind == RoutineKind.HEALTH) draft.healthSubtype else null,
            amount = draft.amount.trim(),
            unit = draft.unit.trim(),
            iconName = draft.iconName,
            color = draft.color,
            schedule = draft.schedule,
            slots = slots,
            activeFrom = today().toString(),
            archived = false,
            createdAt = now,
            updatedAt = now,
            updatedBy = deviceId,
        )
        val payload = RoutinePayload(definition = definition)
        val description = RoutineEnvelope.upsert("", payload, json)
        // One request, already done: an open carrier would show up in the lists until a second
        // request marked it done, and would stay there if that request never came.
        val carrier = Task(
            id = 0,
            title = definition.name,
            description = description,
            done = true,
            doneAt = now,
            projectId = inboxId,
        )
        return when (val createdResult = taskRepository.create(carrier)) {
            is NetworkResult.Success -> {
                val created = createdResult.data
                val completed = if (created.done) {
                    NetworkResult.Success(created)
                } else {
                    // A server that ignored `done` on create: finish the job.
                    taskRepository.update(created.copy(done = true, doneAt = now))
                }
                when (completed) {
                    is NetworkResult.Success -> {
                        val routine = Routine(completed.data.id, payload)
                        platformHooks.routinesChanged()
                        NetworkResult.Success(routine)
                    }
                    is NetworkResult.Error -> completed
                    NetworkResult.Loading -> NetworkResult.Loading
                }
            }
            is NetworkResult.Error -> createdResult
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine> {
        val validation = validateDraft(draft)
        if (validation != null) return NetworkResult.Error(validation)
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        finalizePastHealthOccurrences(routine)
        val latest = findRoutine(routineId) ?: routine
        val now = RoutineTime.now(time)
        val deviceId = prefsStore.getOrCreateDeviceId()
        val existingSlots = latest.definition.slots.associateBy { it.id }
        val slots = draft.slots.map { slot ->
            if (slot.id.isBlank()) slot.copy(id = randomUuid()) else slot
        }.ifEmpty { existingSlots.values.toList() }
        val updatedDefinition = latest.definition.copy(
            name = draft.name.trim(),
            kind = draft.kind,
            healthSubtype = if (draft.kind == RoutineKind.HEALTH) draft.healthSubtype else null,
            amount = draft.amount.trim(),
            unit = draft.unit.trim(),
            iconName = draft.iconName,
            color = draft.color,
            schedule = draft.schedule,
            slots = slots,
            updatedAt = now,
            updatedBy = deviceId,
        )
        return writeCarrier(latest.copy(payload = latest.payload.copy(definition = updatedDefinition)))
    }

    override suspend fun setOccurrenceStatus(
        routineId: String,
        date: String,
        slotId: String,
        status: OccurrenceStatus,
        note: String,
    ): NetworkResult<Routine> {
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        val slot = routine.definition.slots.firstOrNull { it.id == slotId }
            ?: return NetworkResult.Error("Routine slot not found")
        val localDate = runCatching { LocalDate.parse(date) }.getOrNull()
            ?: return NetworkResult.Error("Invalid occurrence date")
        val now = RoutineTime.now(time)
        val deviceId = prefsStore.getOrCreateDeviceId()
        val effectiveStatus = if (
            status == OccurrenceStatus.PENDING &&
            routine.definition.kind == RoutineKind.HEALTH &&
            localDate < today()
        ) OccurrenceStatus.NOT_LOGGED else status
        val record = RoutineScheduleEngine.recordFor(
            routine = routine,
            date = localDate,
            slot = slot,
            status = effectiveStatus,
            nowIso = now,
            deviceId = deviceId,
            timeZone = time.zone(),
            note = note,
        )
        val updated = routine.copy(
            payload = routine.payload.copy(
                occurrences = routine.payload.occurrences + (record.key to record),
            ),
        )
        return writeCarrier(updated, changed = listOf(record))
    }

    override suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine> {
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        val now = RoutineTime.now(time)
        val deviceId = prefsStore.getOrCreateDeviceId()
        val updated = routine.copy(
            payload = routine.payload.copy(
                definition = routine.definition.copy(
                    archived = archived,
                    updatedAt = now,
                    updatedBy = deviceId,
                ),
            ),
        )
        return writeCarrier(updated)
    }

    override suspend fun deletePermanently(routineId: String): NetworkResult<Unit> {
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        return archiveMutex.withLock {
            // The parts go first: if one cannot be deleted the routine stays and the delete can
            // be retried, instead of leaving parts that nobody can reach. A routine that never
            // pruned anything has no parts and can be deleted offline.
            val hasParts = routine.payload.prunedBefore.isNotEmpty()
            if (hasParts) {
                try {
                    archiveStore.deleteParts(routineId, expectParts = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not delete the archive of routine $routineId: ${e.message}")
                    return@withLock NetworkResult.Error(
                        "The routine's archived history could not be deleted. Connect to your server and try again.",
                    )
                }
            }
            when (val result = taskRepository.delete(routine.taskId)) {
                is NetworkResult.Success -> {
                    archiveDao.deleteByRoutine(routineId)
                    platformHooks.routinesChanged()
                    result
                }
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Loading
            }
        }
    }

    override suspend fun finalizeAndPrune(): NetworkResult<Unit> {
        return try {
            // Phone-only history from older versions goes to the server first; if the server
            // cannot be reached now, a later run (or a sync) does it. It never stops the rest.
            try {
                migrateLocalArchive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, "Routine history upload failed: ${error.message}")
            }
            taskDao.getRoutineCarriersSync().forEach { entity ->
                val parsed = RoutineEnvelope.parse(entity.description, json)
                val payload = parsed.payload ?: return@forEach
                finalizePastHealthOccurrences(Routine(entity.id, payload))
                findRoutine(payload.definition.id)?.let { current ->
                    val pruned = RoutineArchive.prune(current.payload, today(), json, time.zone())
                    if (pruned.archived.isNotEmpty()) writeCarrier(current)
                }
            }
            platformHooks.routinesChanged()
            NetworkResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NetworkResult.Error(error.message ?: "Failed to finalize routines")
        }
    }

    override suspend fun exportCsv(): String = exportCsvWithStatus().csv

    override suspend fun exportCsvWithStatus(): RoutineCsvExport {
        val routines = loadCarrierRoutines()
        val withArchive = routines.filter { it.payload.prunedBefore.isNotEmpty() }
        var parts: List<RoutineArchivePart> = emptyList()
        var complete = true
        if (withArchive.isNotEmpty()) {
            parts = try {
                archiveStore.loadParts(withArchive.map { it.definition.id }).map { it.part }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Could not read the routine archive for the export: ${e.message}")
                complete = false
                archiveStore.cachedParts()
            }
        }
        val phoneOnly = archiveDao.getAll().map { it.toDomain() }.groupBy { it.routineId }
        val csv = RoutineCsv.build(
            routines.map { routine ->
                val id = routine.definition.id
                val history = RoutineScheduleEngine.mergeOccurrenceMaps(
                    RoutineArchive.readHistory(id, routine.payload.occurrences, parts),
                    phoneOnly[id].orEmpty().associateBy { it.key },
                )
                RoutineCsv.Entry(routine.definition.name, history.values)
            },
        )
        return RoutineCsvExport(csv, complete)
    }

    // --- Phone-only history from versions before 1.9 -------------------------------------------

    override suspend fun migrateLocalArchive(carriersAuthoritative: Boolean): NetworkResult<Unit> =
        archiveMutex.withLock {
            val rows = archiveDao.getAll()
            if (rows.isEmpty()) return@withLock NetworkResult.Success(Unit)
            val carriers = loadCarrierRoutines().associateBy { it.definition.id }
            var failure: String? = null
            for ((routineId, entities) in rows.groupBy { it.routineId }) {
                val carrier = carriers[routineId]
                if (carrier == null) {
                    // The routine is not (yet) in the carrier cache. Keep its history until it
                    // shows up, unless the cache was just refreshed from the server: then the
                    // routine was deleted and its history has nowhere to go.
                    if (carriersAuthoritative) archiveDao.deleteByRoutine(routineId)
                    continue
                }
                // A carrier that only exists on this phone (waiting for its create) has no
                // project on the server to put parts in yet.
                if (carrier.taskId <= 0L) continue
                try {
                    val projectId = taskDao.getByIdSync(carrier.taskId)?.projectId
                        ?.takeIf { it > 0L }
                        ?: continue
                    archiveStore.write(projectId, routineId, entities.map { it.toDomain() }, expectParts = true)
                    // Only after the parts are on the server and read back.
                    archiveDao.deleteByRoutine(routineId)
                    Logger.d(TAG, "Uploaded ${entities.size} phone-only history entries of routine $routineId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not upload the phone-only history of routine $routineId: ${e.message}")
                    failure = failure ?: (e.message ?: "Could not upload routine history")
                }
            }
            failure?.let { NetworkResult.Error(it) } ?: NetworkResult.Success(Unit)
        }

    // --- Reading carriers --------------------------------------------------------------------

    private suspend fun loadCarrierRoutines(): List<Routine> =
        taskDao.getRoutineCarriersSync().mapNotNull { entity ->
            RoutineEnvelope.parse(entity.description, json).payload?.let { Routine(entity.id, it) }
        }

    private suspend fun findRoutine(routineId: String): Routine? =
        loadCarrierRoutines().firstOrNull { it.definition.id == routineId }

    // --- Writing carriers --------------------------------------------------------------------

    /**
     * Saves [routine]. History older than the rolling window is archived first (see the class
     * comment). [changed] are the occurrences this write is about: one that is itself old enough
     * to belong to the archive is written to the part that holds its key, and if the archive
     * cannot be written the change is refused, because it could not live in the main carrier.
     */
    private suspend fun writeCarrier(
        routine: Routine,
        changed: List<RoutineOccurrenceRecord> = emptyList(),
    ): NetworkResult<Routine> =
        archiveMutex.withLock {
            val today = today()
            val zone = time.zone()
            var payload = routine.payload
            val planned = RoutineArchive.prune(payload, today, json, zone)
            if (planned.archived.isEmpty()) {
                warning.value = null
            } else {
                val archived = archiveOldHistory(routine, changed, today, zone)
                if (archived != null) {
                    payload = archived
                } else if (planned.archived.any { moved -> changed.any { it.key == moved.key } }) {
                    return@withLock NetworkResult.Error(
                        "That day's history is stored on your server. Connect to it and try again.",
                    )
                }
            }
            updateCarrier(routine.copy(payload = payload))
        }

    /**
     * Writes what has left the rolling window to the archive and returns the payload without it,
     * or null when that could not be done (the caller then keeps the history in the carrier).
     */
    private suspend fun archiveOldHistory(
        routine: Routine,
        changed: List<RoutineOccurrenceRecord>,
        today: LocalDate,
        zone: TimeZone,
    ): RoutinePayload? =
        try {
            // Work from the freshest carrier: another device may have moved the cutoff or added
            // occurrences since this phone last synced.
            val fresh = archiveStore.fetchTask(routine.taskId)
            val remote = RoutineEnvelope.parse(fresh.description, json).payload
            val merged = if (remote != null) RoutineEnvelope.mergePayload(routine.payload, remote) else routine.payload
            val outcome = RoutineArchive.prune(merged, today, json, zone)
            // The merge dropped everything older than the cut, a change of this write included;
            // such a change belongs to the archive, in the part that holds its key.
            val late = changed.filter { it.scheduledDate < merged.prunedBefore }
            val toArchive = outcome.archived + late.filter { change -> outcome.archived.none { it.key == change.key } }
            if (toArchive.isNotEmpty()) {
                val projectId = fresh.projectId.takeIf { it > 0L }
                    ?: taskDao.getByIdSync(routine.taskId)?.projectId
                    ?: error("The routine's project is unknown")
                archiveStore.write(
                    projectId = projectId,
                    routineId = merged.definition.id,
                    moved = toArchive,
                    expectParts = merged.prunedBefore.isNotEmpty(),
                )
            }
            warning.value = null
            outcome.payload
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Could not archive routine history, keeping it in the routine: ${e.message}")
            // Offline and server trouble sort themselves out on a later write; anything else
            // (a refused part, a part that was not stored completely) is worth telling about.
            if (!isRetriableNetworkError(e) && !isNetworkFailure(e)) {
                warning.value = "Older routine history could not be archived and stays in the routine for now."
            }
            null
        }

    private suspend fun updateCarrier(routine: Routine): NetworkResult<Routine> {
        val entity = taskDao.getByIdSync(routine.taskId)
            ?: return NetworkResult.Error("Routine carrier not found")
        val task = with(taskMapper) { entity.toDomain() }
        val parsed = RoutineEnvelope.parse(task.description, json)
        val description = try {
            RoutineEnvelope.upsert(parsed.body, routine.payload, json)
        } catch (e: IllegalArgumentException) {
            return NetworkResult.Error(
                e.message ?: "The routine's history is too large to save. Connect to your server so it can be archived.",
            )
        }
        val completeCarrier = task.copy(
            title = routine.definition.name,
            description = description,
            done = true,
            doneAt = task.doneAt.ifBlank { RoutineTime.now(time) },
            dueDate = "",
            repeatAfter = 0,
            repeatMode = 0,
            reminders = emptyList(),
        )
        return when (val result = taskRepository.update(completeCarrier)) {
            is NetworkResult.Success -> {
                platformHooks.routinesChanged()
                val returnedPayload = RoutineEnvelope.parse(result.data.description, json).payload ?: routine.payload
                NetworkResult.Success(Routine(result.data.id, returnedPayload))
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    private suspend fun finalizePastHealthOccurrences(routine: Routine) {
        if (routine.definition.kind != RoutineKind.HEALTH) return
        val today = today()
        val zone = time.zone()
        // Nothing before the cut: that history is in the archive, and a "not logged" record made
        // for one of those days would overwrite what the archive holds.
        val prunedBefore = runCatching { LocalDate.parse(routine.payload.prunedBefore) }.getOrNull()
        val oldest = listOfNotNull(
            LocalDate.parse(routine.definition.activeFrom),
            today.minus(RoutineArchive.WINDOW_DAYS, DateTimeUnit.DAY),
            prunedBefore,
        ).max()
        val deviceId = prefsStore.getOrCreateDeviceId()
        val now = RoutineTime.now(time)
        var date = oldest
        var occurrences = routine.payload.occurrences
        var changed = false
        while (date < today) {
            RoutineScheduleEngine.occurrencesForDate(
                routine.copy(payload = routine.payload.copy(occurrences = occurrences)),
                date,
                zone,
                today,
            ).forEach { occurrence ->
                if (occurrence.key !in occurrences) {
                    val record = RoutineScheduleEngine.recordFor(
                        routine,
                        date,
                        occurrence.slot,
                        OccurrenceStatus.NOT_LOGGED,
                        now,
                        deviceId,
                        zone,
                    )
                    occurrences = occurrences + (record.key to record)
                    changed = true
                }
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        if (changed) {
            writeCarrier(routine.copy(payload = routine.payload.copy(occurrences = occurrences)))
        }
    }

    private fun validateDraft(draft: RoutineDraft): String? = when {
        draft.name.isBlank() -> "Give the routine a name"
        draft.slots.isEmpty() -> "Add at least one time or chore slot"
        draft.slots.any { it.reminderMinutes !in 0..1439 } -> "Reminder time is invalid"
        else -> null
    }

    private fun RoutineOccurrenceArchiveEntity.toDomain() = RoutineOccurrenceRecord(
        key = occurrenceKey,
        routineId = routineId,
        slotId = slotId,
        scheduledDate = scheduledDate,
        scheduledMinutes = scheduledMinutes,
        timeZoneId = timeZoneId,
        status = runCatching { OccurrenceStatus.valueOf(status) }.getOrDefault(OccurrenceStatus.NOT_LOGGED),
        loggedAt = loggedAt,
        modifiedAt = modifiedAt,
        modifiedBy = modifiedBy,
        note = note,
    )
}
