package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.CurrentUser
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.isBrokenSearch
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RoutineArchive
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.RoutineScheduleEngine
import com.rendyhd.vicu.util.SharedRoutines
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reads and writes the routine archive parts on the server (docs/cross-app-semantics-v1.md,
 * section 6.4): done tasks titled [RoutineArchive.TITLE], one marker each, in the project of the
 * routine's main carrier.
 *
 * Parts go straight through the API and never into Room. They can be hundreds of kilobytes, they
 * are only read for History and CSV export, and every list already hides them; keeping them out
 * of the task cache keeps every Room query and every sync cheap.
 *
 * Parts are found with the server's `q` search on the marker name plus `done = true`. The ids
 * found are remembered for the life of the process: they are looked up again when the search
 * misses them, and they let History show something while the server cannot be reached.
 */
class RoutineArchiveStore(
    private val api: VikunjaApiService,
    private val json: Json,
    /** Only the user's own parts count, and other users' parts of a shared routine. */
    private val currentUser: CurrentUser = CurrentUser.fixed(null),
    /**
     * The routines this account reads, routine id to whether it is shared (section 6.9), or null
     * to count every part of the account's own (a store without a routine source, in tests).
     */
    private val readRoutines: (suspend () -> Map<String, Boolean>)? = null,
) {
    /** Conditional carrier writes (section 6.8), for the routine repository's sharing. */
    val carrierWriter = RoutineCarrierWriter(api, json)

    companion object {
        private const val TAG = "RoutineArchive"
        private const val NOT_FOUND = 404

        /**
         * The routines in the task cache this account reads (its own, and other users' shared
         * ones), with whether each is shared. Another user's carrier that is not shared, or that
         * carries the id of one of the account's own routines, does not make its parts count
         * (review S-5).
         */
        fun cachedRoutines(
            taskDao: TaskDao,
            json: Json,
            currentUser: CurrentUser = CurrentUser.fixed(null),
        ): suspend () -> Map<String, Boolean> = {
            val me = currentUser.knownId()
            val readable = taskDao.getRoutineCarriersSync().mapNotNull { entity ->
                RoutineEnvelope.parse(entity.description, json).payload?.definition
                    ?.takeIf { SharedRoutines.readsCarrier(entity.createdById, me, it.shared) }
                    ?.let { entity to it }
            }
            SharedRoutines.preferOwnCarriers(
                readable,
                me,
                taskId = { it.first.id },
                createdBy = { it.first.createdById },
                routineId = { it.second.id },
            ).associate { it.second.id to it.second.shared }
        }
    }

    /**
     * Part task id to the part as last read or written. Replaced as a whole, never edited, so a
     * screen reading it while a write updates it sees one state or the other.
     */
    @Volatile
    private var known: Map<Long, RoutineArchivePart> = emptyMap()

    /** The parts of [routineId] this process has seen, for when the server cannot be reached. */
    fun cachedParts(routineId: String? = null): List<RoutineArchivePart> =
        known.values.filter { routineId == null || it.routineId == routineId }

    /** The archive part tasks this process has seen, with the routine each belongs to; no request. */
    fun knownPartRoutines(): Map<Long, String> = known.mapValues { it.value.routineId }

    /** True for a part [me] created, or when either side is not known. */
    private fun isMine(task: TaskDto, me: Long?): Boolean =
        me == null || task.createdBy == null || task.createdBy.id <= 0L || task.createdBy.id == me

    /**
     * The parts this account reads (section 6.9): every part of a routine it reads, whoever
     * wrote it. Without a routine source, its own only.
     */
    private suspend fun counted(tasks: List<TaskDto>, me: Long?): List<RoutineArchive.PartRef> {
        val routines = readRoutines?.invoke()
        return tasks.mapNotNull { task ->
            val part = RoutineEnvelope.parseArchive(task.description, json).part ?: return@mapNotNull null
            val counts = if (routines == null) {
                isMine(task, me)
            } else {
                SharedRoutines.countsPart(part.routineId, routines)
            }
            if (counts) RoutineArchive.PartRef(task.id, part) else null
        }
    }

    /** The task as the server holds it right now (the freshest copy of a main carrier). */
    suspend fun fetchTask(taskId: Long): TaskDto = api.getTask(taskId)

    private suspend fun fetchParts(fullScan: Boolean): List<RoutineArchive.PartRef> {
        val filters = buildMap {
            put("filter", "done = true")
            if (!fullScan) put("q", RoutineArchive.SEARCH)
        }
        return counted(api.getAllTasks(filters), currentUser.id())
    }

    /**
     * Every archive part on the server. The marker search comes first. When a routine that is
     * known to have archived history ([expectedRoutineIds]) shows no part in it, one scan of all
     * done tasks double-checks before the caller concludes the archive is empty; a search the
     * server answers with an error is replaced by that scan. Parts this process knew about that
     * the search missed are fetched by id.
     */
    suspend fun loadParts(expectedRoutineIds: Collection<String> = emptyList()): List<RoutineArchive.PartRef> {
        var scanned = false
        var parts = try {
            fetchParts(fullScan = false)
        } catch (e: VikunjaApiException) {
            // The server answered the search with an error: the scan finds the parts without it.
            if (!e.isBrokenSearch) throw e
            Logger.w(TAG, "Archive part search failed; scanning the done tasks instead: ${e.message}")
            scanned = true
            fetchParts(fullScan = true)
        }
        if (!scanned && expectedRoutineIds.any { id -> parts.none { it.part.routineId == id } }) {
            parts = fetchParts(fullScan = true)
        }
        val byTask = LinkedHashMap<Long, RoutineArchive.PartRef>().apply { parts.forEach { put(it.taskId, it) } }
        for (taskId in known.keys) {
            if (taskId in byTask) continue
            try {
                val task = api.getTask(taskId)
                counted(listOf(task), currentUser.knownId()).firstOrNull()?.let { byTask[taskId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VikunjaApiException) {
                if (e.httpStatus != NOT_FOUND) throw e
            }
        }
        known = byTask.values.associate { it.taskId to it.part }
        return byTask.values.toList()
    }

    /**
     * Writes [moved] to the archive and checks what the server stored. Returns normally only when
     * every moved record is on the server, so the caller may then drop it from the main carrier.
     */
    suspend fun write(
        projectId: Long,
        routineId: String,
        moved: Collection<RoutineOccurrenceRecord>,
        expectParts: Boolean,
    ) {
        if (moved.isEmpty()) return
        val existing = loadParts(if (expectParts) listOf(routineId) else emptyList())
        for (op in RoutineArchive.planWrites(routineId, existing, moved, json)) {
            apply(projectId, routineId, op)
        }
    }

    private suspend fun apply(projectId: Long, routineId: String, op: RoutineArchive.Op) {
        val wanted: RoutineArchivePart
        val stored: TaskDto
        when (op) {
            is RoutineArchive.Op.Update -> {
                // Read the part again right before writing, and write only if nobody wrote it
                // since (section 6.8), so an append another device or person made is kept. The
                // writer reads it back once more; a part that holds the records already is done.
                var merged = op.part
                stored = carrierWriter.writeArchivePart(op.taskId) { fresh ->
                    val current = RoutineEnvelope.parseArchive(fresh.description, json).part
                    merged = if (current != null && current.routineId == routineId) {
                        op.part.copy(
                            occurrences = RoutineScheduleEngine.mergeOccurrenceMaps(
                                current.occurrences,
                                op.part.occurrences,
                            ),
                        )
                    } else {
                        op.part
                    }
                    if (current != null && current.routineId == routineId && current.occurrences == merged.occurrences) {
                        return@writeArchivePart null
                    }
                    if (RoutineArchive.partBytes(merged, json) > RoutineArchive.BUDGET_BYTES) {
                        throw IllegalStateException("The routine archive part is full")
                    }
                    RoutineEnvelope.encodeArchive(merged, json)
                }
                wanted = merged
            }
            is RoutineArchive.Op.Create -> {
                wanted = op.part
                // One request, already done: an open archive part would show up as a task.
                val created = api.createTask(
                    projectId,
                    CreateTaskDto(
                        title = RoutineArchive.TITLE,
                        description = RoutineEnvelope.encodeArchive(wanted, json),
                        done = true,
                    ),
                )
                stored = if (created.done) created else api.updateTask(created.id, MergePatches.taskDone(true))
            }
        }
        // Verify what the server kept before the main carrier is allowed to drop this history.
        val storedPart = RoutineEnvelope.parseArchive(stored.description, json).part
            ?: throw IllegalStateException("The routine archive was not stored completely")
        if (wanted.occurrences.keys.any { it !in storedPart.occurrences }) {
            throw IllegalStateException("The routine archive was not stored completely")
        }
        known = known + (stored.id to storedPart)
    }

    /**
     * Moves the archive parts of [routineId] into [projectId], for sharing a routine or stopping
     * (section 6.9). A part already there is left alone. Online only: it throws when a part
     * cannot be moved, and the caller does not move the carrier then.
     */
    suspend fun moveParts(routineId: String, projectId: Long, expectParts: Boolean) {
        moveParts(partTaskIds(routineId, expectParts), projectId)
    }

    /** The tasks holding the archive parts of [routineId], whoever wrote them, as this account reads them now. */
    suspend fun partTaskIds(routineId: String, expectParts: Boolean): List<Long> =
        loadParts(if (expectParts) listOf(routineId) else emptyList())
            .filter { it.part.routineId == routineId }
            .map { it.taskId }

    /**
     * Moves the part tasks [taskIds] into [projectId]. Stopping to share reads them before the
     * routine turns private, since another member's parts stop counting then.
     */
    suspend fun moveParts(taskIds: List<Long>, projectId: Long) {
        for (taskId in taskIds) {
            val task = api.getTask(taskId)
            if (task.projectId == projectId) continue
            api.updateTask(taskId, buildJsonObject { put("project_id", projectId) })
        }
    }

    /**
     * Deletes the archive parts of [routineId]. A part that is already gone counts as deleted.
     * Meant to run before the main carrier is deleted: if a part cannot be removed the routine
     * stays and the delete can be retried, instead of leaving parts nobody can reach.
     */
    suspend fun deleteParts(routineId: String, expectParts: Boolean) {
        val parts = loadParts(if (expectParts) listOf(routineId) else emptyList())
            .filter { it.part.routineId == routineId }
        for (ref in parts) {
            try {
                api.deleteTask(ref.taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VikunjaApiException) {
                if (e.httpStatus != NOT_FOUND) throw e
            }
            known = known - ref.taskId
        }
        if (parts.isNotEmpty()) Logger.d(TAG, "Deleted ${parts.size} archive part(s) of routine $routineId")
    }
}
