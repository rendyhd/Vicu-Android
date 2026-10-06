package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.dao.RoutineArchiveDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.RoutineOccurrenceArchiveEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDefinition
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineKind
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.RoutinePayload
import com.rendyhd.vicu.domain.model.RoutineSlot
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.RoutineCsv
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.RoutineScheduleEngine
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json

class RoutineRepositoryImpl(
    private val taskDao: TaskDao,
    private val archiveDao: RoutineArchiveDao,
    private val taskMapper: TaskMapper,
    private val taskRepository: TaskRepository,
    private val authManager: AuthManager,
    private val prefsStore: RoutinePrefsStore,
    private val platformHooks: PlatformRepositoryHooks,
    private val json: Json,
) : RoutineRepository {
    companion object {
        private const val SYNC_HISTORY_DAYS = 400
    }

    private data class ParsedCarriers(
        val routines: List<Routine>,
        val issues: List<RoutineParseIssue>,
    )

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
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val occurrences = parsed.routines
            .asSequence()
            .filterNot { it.definition.archived }
            .flatMap { routine ->
                occurrencesForSelectedDate(routine, localDate, today).asSequence()
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

    override fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> =
        combine(parsedFlow, archiveDao.observeByRoutine(routineId)) { parsed, archived ->
            val synced = parsed.routines
                .firstOrNull { it.definition.id == routineId }
                ?.payload
                ?.occurrences
                ?.values
                .orEmpty()
            (archived.map { it.toDomain() } + synced)
                .associateBy { it.key }
                .values
                .sortedWith(compareByDescending<RoutineOccurrenceRecord> { it.scheduledDate }.thenByDescending { it.scheduledMinutes })
        }

    override suspend fun create(draft: RoutineDraft): NetworkResult<Routine> {
        val validation = validateDraft(draft)
        if (validation != null) return NetworkResult.Error(validation)
        val inboxId = authManager.getInboxProjectId()
            ?: return NetworkResult.Error("Choose an inbox project before creating routines")
        val now = DateUtils.nowIso()
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
            activeFrom = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString(),
            archived = false,
            createdAt = now,
            updatedAt = now,
            updatedBy = deviceId,
        )
        val payload = RoutinePayload(definition = definition)
        val description = RoutineEnvelope.upsert("", payload, json)
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
        val now = DateUtils.nowIso()
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
        return updateCarrier(latest.copy(payload = latest.payload.copy(definition = updatedDefinition)))
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
        val now = DateUtils.nowIso()
        val deviceId = prefsStore.getOrCreateDeviceId()
        val effectiveStatus = if (
            status == OccurrenceStatus.PENDING &&
            routine.definition.kind == RoutineKind.HEALTH &&
            localDate < Clock.System.todayIn(TimeZone.currentSystemDefault())
        ) OccurrenceStatus.NOT_LOGGED else status
        val record = RoutineScheduleEngine.recordFor(
            routine = routine,
            date = localDate,
            slot = slot,
            status = effectiveStatus,
            nowIso = now,
            deviceId = deviceId,
            note = note,
        )
        val updated = routine.copy(
            payload = routine.payload.copy(
                occurrences = routine.payload.occurrences + (record.key to record),
            ),
        )
        return updateCarrier(prunePayload(updated))
    }

    override suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine> {
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        val now = DateUtils.nowIso()
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
        return updateCarrier(updated)
    }

    override suspend fun deletePermanently(routineId: String): NetworkResult<Unit> {
        val routine = findRoutine(routineId) ?: return NetworkResult.Error("Routine not found")
        return when (val result = taskRepository.delete(routine.taskId)) {
            is NetworkResult.Success -> {
                archiveDao.deleteByRoutine(routineId)
                platformHooks.routinesChanged()
                result
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun finalizeAndPrune(): NetworkResult<Unit> {
        return try {
            taskDao.getRoutineCarriersSync().forEach { entity ->
                val parsed = RoutineEnvelope.parse(entity.description, json)
                val payload = parsed.payload ?: return@forEach
                val routine = Routine(entity.id, payload)
                finalizePastHealthOccurrences(routine)
                findRoutine(payload.definition.id)?.let { current ->
                    val pruned = prunePayload(current)
                    if (pruned.payload != current.payload) updateCarrier(pruned)
                }
            }
            platformHooks.routinesChanged()
            NetworkResult.Success(Unit)
        } catch (error: Exception) {
            NetworkResult.Error(error.message ?: "Failed to finalize routines")
        }
    }

    override suspend fun exportCsv(): String {
        val routines = taskDao.getRoutineCarriersSync().mapNotNull { entity ->
            RoutineEnvelope.parse(entity.description, json).payload?.let { Routine(entity.id, it) }
        }
        val archived = archiveDao.getAll().map { it.toDomain() }
        return RoutineCsv.build(
            routines.map { routine ->
                val all = (archived.filter { it.routineId == routine.definition.id } + routine.payload.occurrences.values)
                    .associateBy { it.key }
                    .values
                RoutineCsv.Entry(routine.definition.name, all)
            },
        )
    }

    private fun occurrencesForSelectedDate(routine: Routine, date: LocalDate, today: LocalDate) =
        RoutineScheduleEngine.occurrencesForDate(routine, date, today = today)

    private suspend fun findRoutine(routineId: String): Routine? =
        taskDao.getRoutineCarriersSync().firstNotNullOfOrNull { entity ->
            RoutineEnvelope.parse(entity.description, json).payload
                ?.takeIf { it.definition.id == routineId }
                ?.let { Routine(entity.id, it) }
        }

    private suspend fun updateCarrier(routine: Routine): NetworkResult<Routine> {
        val entity = taskDao.getByIdSync(routine.taskId)
            ?: return NetworkResult.Error("Routine carrier not found")
        val task = with(taskMapper) { entity.toDomain() }
        val parsed = RoutineEnvelope.parse(task.description, json)
        val description = RoutineEnvelope.upsert(parsed.body, routine.payload, json)
        val completeCarrier = task.copy(
            title = routine.definition.name,
            description = description,
            done = true,
            doneAt = task.doneAt.ifBlank { DateUtils.nowIso() },
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
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val oldest = maxOf(
            LocalDate.parse(routine.definition.activeFrom),
            today.minus(SYNC_HISTORY_DAYS, DateTimeUnit.DAY),
        )
        val deviceId = prefsStore.getOrCreateDeviceId()
        val now = DateUtils.nowIso()
        var date = oldest
        var occurrences = routine.payload.occurrences
        var changed = false
        while (date < today) {
            RoutineScheduleEngine.occurrencesForDate(routine.copy(payload = routine.payload.copy(occurrences = occurrences)), date, today = today)
                .forEach { occurrence ->
                    if (occurrence.key !in occurrences) {
                        val record = RoutineScheduleEngine.recordFor(
                            routine,
                            date,
                            occurrence.slot,
                            OccurrenceStatus.NOT_LOGGED,
                            now,
                            deviceId,
                        )
                        occurrences = occurrences + (record.key to record)
                        changed = true
                    }
                }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        if (changed) updateCarrier(prunePayload(routine.copy(payload = routine.payload.copy(occurrences = occurrences))))
    }

    private suspend fun prunePayload(routine: Routine): Routine {
        val cutoff = Clock.System.todayIn(TimeZone.currentSystemDefault())
            .minus(SYNC_HISTORY_DAYS, DateTimeUnit.DAY)
        val (old, current) = routine.payload.occurrences.values.partition { record ->
            runCatching { LocalDate.parse(record.scheduledDate) < cutoff }.getOrDefault(false)
        }
        if (old.isNotEmpty()) archiveDao.upsertAll(old.map { it.toEntity() })
        return routine.copy(
            payload = routine.payload.copy(
                occurrences = current.associateBy { it.key },
                prunedBefore = cutoff.toString(),
            ),
        )
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

    private fun RoutineOccurrenceRecord.toEntity() = RoutineOccurrenceArchiveEntity(
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

}
