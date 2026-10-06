package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.MergePatches
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.RoutineArchivePart
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RoutineArchive
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.RoutineScheduleEngine
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
) {
    private companion object {
        const val TAG = "RoutineArchive"
        const val NOT_FOUND = 404
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

    /** The task as the server holds it right now (the freshest copy of a main carrier). */
    suspend fun fetchTask(taskId: Long): TaskDto = api.getTask(taskId)

    private suspend fun fetchParts(fullScan: Boolean): List<RoutineArchive.PartRef> {
        val filters = buildMap {
            put("filter", "done = true")
            if (!fullScan) put("q", RoutineArchive.SEARCH)
        }
        return api.getAllTasks(filters).mapNotNull { task ->
            RoutineEnvelope.parseArchive(task.description, json).part
                ?.let { RoutineArchive.PartRef(task.id, it) }
        }
    }

    /**
     * Every archive part on the server. The marker search comes first. When a routine that is
     * known to have archived history ([expectedRoutineIds]) shows no part in it, one scan of all
     * done tasks double-checks before the caller concludes the archive is empty. Parts this
     * process knew about that the search missed are fetched by id.
     */
    suspend fun loadParts(expectedRoutineIds: Collection<String> = emptyList()): List<RoutineArchive.PartRef> {
        var parts = fetchParts(fullScan = false)
        if (expectedRoutineIds.any { id -> parts.none { it.part.routineId == id } }) {
            parts = fetchParts(fullScan = true)
        }
        val byTask = LinkedHashMap<Long, RoutineArchive.PartRef>().apply { parts.forEach { put(it.taskId, it) } }
        for (taskId in known.keys) {
            if (taskId in byTask) continue
            try {
                RoutineEnvelope.parseArchive(api.getTask(taskId).description, json).part
                    ?.let { byTask[taskId] = RoutineArchive.PartRef(taskId, it) }
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
                // Read the part again right before writing so an append another device made
                // since the search is kept.
                val fresh = api.getTask(op.taskId)
                val current = RoutineEnvelope.parseArchive(fresh.description, json).part
                wanted = if (current != null && current.routineId == routineId) {
                    op.part.copy(
                        occurrences = RoutineScheduleEngine.mergeOccurrenceMaps(
                            current.occurrences,
                            op.part.occurrences,
                        ),
                    )
                } else {
                    op.part
                }
                if (RoutineArchive.partBytes(wanted, json) > RoutineArchive.BUDGET_BYTES) {
                    throw IllegalStateException("The routine archive part is full")
                }
                stored = api.updateTask(
                    op.taskId,
                    buildJsonObject { put("description", RoutineEnvelope.encodeArchive(wanted, json)) },
                )
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
