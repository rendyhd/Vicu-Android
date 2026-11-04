package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.CurrentUser
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.dao.CollaborationDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.createApiJson
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.isBrokenSearch
import com.rendyhd.vicu.data.repository.RoutineCarrierWriter
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.HiddenTasks
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.SharedRoutines
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.worker.MissingResourceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Whether a change to a cached task can change which reminder alarms it needs: its reminders, the
 * dates they count from, or whether it is done. A new task only counts when it is open and has
 * reminders. Titles and everything else are read when an alarm fires, so they are not relevant.
 */
fun remindersAffected(old: TaskEntity?, new: TaskEntity): Boolean {
    if (old == null) return !new.done && new.remindersJson != "[]"
    return old.done != new.done ||
        old.remindersJson != new.remindersJson ||
        old.dueDate != new.dueDate ||
        old.startDate != new.startDate ||
        old.endDate != new.endDate
}

/** One page of completed tasks fetched for the Logbook. */
data class CompletedPage(val loaded: Int, val hasMore: Boolean)

/**
 * The one place that pulls tasks from the server into the cache; the repository (screens) and
 * the sync engine (background) both go through it.
 *
 * - [refresh] fetches only the tasks that changed since the last refresh (`updated >= cursor`).
 *   Completed tasks are included there, because that is how a completion on another device
 *   arrives, but their history is never downloaded as such.
 * - A full reconcile also lists every open task, to notice the ones that were deleted, and runs
 *   on the first refresh, on request, after [FULL_RECONCILE_INTERVAL] and when the cursor is lost.
 *   A delta cannot show a deletion, so every incremental refresh first asks the server for a page
 *   of one open task and compares its `total` with the cached open rows; when they differ the
 *   refresh becomes a full reconcile at once.
 * - [refreshMatching] merges the tasks a filter or search returns (search, custom lists) without
 *   deleting or moving the cursor.
 * - [loadCompletedPage] pages through completed tasks for the Logbook.
 *
 * Rows with queued changes are never overwritten or deleted: the cache holds the only copy of
 * the user's edit. Alarms are only touched for tasks whose reminders, dates or completion changed.
 */
class TaskRefresher(
    private val taskDao: TaskDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val taskMapper: TaskMapper,
    private val platformHooks: PlatformRepositoryHooks,
    private val cursorStore: SyncCursorStore,
    private val time: TimeSource = SystemTimeSource,
    /**
     * Whose routine carriers are whose: another user's, in a shared project, enter the cache only
     * when its owner shared it (cross-app semantics 6.9).
     */
    private val currentUser: CurrentUser = CurrentUser.fixed(null),
    /**
     * This device's id in routine records. With it a refresh writes again the routine changes
     * the server lost to a write made at the same moment (cross-app semantics 6.8).
     */
    private val routineDeviceId: (suspend () -> String)? = null,
    /** Where the comment counts the task reads bring are kept (cross-app semantics 9.4). */
    private val collaborationDao: CollaborationDao? = null,
) {
    companion object {
        private const val TAG = "TaskRefresher"

        /** A full reconcile (the only way a deletion is noticed) is due this long after the last one. */
        val FULL_RECONCILE_INTERVAL = 24.hours

        /** Re-fetched on purpose: a write that raced the last refresh has the same `updated`. */
        private const val OVERLAP_SECONDS = 5L

        /** Routine carriers are found by their marker name; the server searches descriptions too. */
        const val ROUTINE_CARRIER_QUERY = "vicu-routine"

        /** Page size when every done task is listed; a smaller server maximum just means more pages. */
        private const val SCAN_PAGE_SIZE = "1000"

        /** At most this many cached carriers the search did not return are asked for one by one. */
        private const val MAX_CARRIER_CHECKS = 20

        /** More changed alarms than this are cheaper as one full pass than as one call each. */
        private const val ALARM_BATCH_LIMIT = 100

        /**
         * How often an incremental refresh asks whether the cached routines other people shared
         * can still be read. Losing access never shows up as a change: the task just stops coming.
         */
        val SHARED_CARRIER_CHECK_INTERVAL = 10.minutes
    }

    /** Reads routine payloads, to see whether another user's carrier is shared. */
    private val json = createApiJson()
    private val carrierWriter = RoutineCarrierWriter(api, json)

    /** When the cached shared routines were last checked; guarded by [mutex]. */
    private var sharedCheckedAt: Instant? = null

    /** What a refresh did. [carriersAuthoritative]: the cached routine carriers now match the server's. */
    data class Outcome(
        val full: Boolean,
        val changed: Int,
        val removed: Int,
        val carriersAuthoritative: Boolean,
    )

    private val mutex = Mutex()
    private val missing = MissingResourceCheck(api)

    /**
     * Server open total minus cached open rows, as the last full reconcile left it: zero, except
     * for rows kept because of queued changes and for hidden tasks the cache does not store.
     * Guarded by [mutex]. Unknown after a restart (0), so a nonzero difference costs one extra
     * reconcile there, which then records it.
     */
    private var openOffset = 0L

    suspend fun requestFullReconcile() = cursorStore.requestFullReconcile()

    suspend fun refresh(forceFull: Boolean = false): Outcome = mutex.withLock {
        val ticket = cursorStore.begin()
        val state = ticket.cursor
        val now = time.now()
        val since = state.tasksUpdatedSince?.let { DateUtils.parseIsoDate(it) }
        val full = forceFull || since == null || state.fullReconcileRequested ||
            isFullReconcileDue(state.lastFullReconcileMs, now) ||
            (collaborationDao != null && !state.signalsRead)
        if (full) reconcile(ticket, now) else incremental(ticket, checkNotNull(since), now)
    }

    private fun isFullReconcileDue(lastMs: Long, now: Instant): Boolean {
        if (lastMs <= 0L) return true
        val age = now.toEpochMilliseconds() - lastMs
        // A negative age means the clock moved back; the stored time says nothing then.
        return age < 0 || age >= FULL_RECONCILE_INTERVAL.inWholeMilliseconds
    }

    // ---- incremental ---------------------------------------------------------------------------

    private suspend fun incremental(ticket: SyncCursorStore.Ticket, since: Instant, now: Instant): Outcome {
        val from = Instant.fromEpochSeconds(since.epochSeconds - OVERLAP_SECONDS)
        val dtos = api.getAllTasks(mapOf("filter" to "updated >= '$from'"), expandSubtasks = false, collabSignals = true)
        val acc = Accumulator()
        merge(dtos, acc)
        storeSignals(dtos)
        checkSharedCarriers(now, acc)
        finish(acc)
        val newest = (dtos.mapNotNull { DateUtils.parseIsoDate(it.updated) } + since).max()
        cursorStore.commit(ticket, newest.toString())
        Logger.d(TAG, "Incremental refresh: ${dtos.size} tasks changed since $from, ${acc.changed} differed from the cache")
        // A delta never lists a deletion. After it has brought in every completion and creation
        // (which change the cached open rows too), one request for a page of one open task tells
        // whether the server still holds as many open tasks as the cache does. When it does not,
        // something was deleted: the full reconcile removes it now instead of at the next daily one.
        if (openSetDiffers()) {
            Logger.d(TAG, "The server's open task count differs from the cache's: full reconcile")
            val full = reconcile(ticket, now)
            return full.copy(changed = full.changed + acc.changed)
        }
        return Outcome(full = false, changed = acc.changed, removed = 0, carriersAuthoritative = false)
    }

    /**
     * True when the server's number of open tasks is not the cache's plus [openOffset], the
     * difference the last reconcile left (rows with queued changes, open hidden tasks the cache
     * does not keep). A probe that cannot be answered says nothing and counts as "same".
     */
    private suspend fun openSetDiffers(): Boolean {
        val total = try {
            api.getTasksPage(mapOf("filter" to "done = false", "per_page" to "1"), expandSubtasks = false).total
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Logger.w(TAG, "Could not count the open tasks on the server: ${e.message}")
            return false
        }
        return total - taskDao.countOpenServerTasks() != openOffset
    }

    // ---- full reconcile ---------------------------------------------------------------------------

    private suspend fun reconcile(ticket: SyncCursorStore.Ticket, now: Instant): Outcome {
        // The newest change anywhere, read first: it becomes the cursor, so whatever changes while
        // the lists below download is fetched again by the next incremental refresh.
        val newest = api.getTasksPage(
            mapOf("sort_by" to "updated", "order_by" to "desc", "per_page" to "1"),
            expandSubtasks = false,
        ).items.firstOrNull()?.updated?.let { DateUtils.parseIsoDate(it) }

        val open = api.getAllTasks(mapOf("filter" to "done = false"), expandSubtasks = false, collabSignals = true)
        val carriers = doneRoutineCarriers()

        val acc = Accumulator()
        merge(open + carriers, acc)
        storeSignals(open)

        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val serverIds = HashSet<Long>().apply {
            open.forEach { add(it.id) }
            carriers.forEach { add(it.id) }
        }

        // Rows the server no longer lists as open, and rows that only existed on this device
        // (an offline create whose queued action is gone). Completed tasks are not swept here.
        val vanished = taskDao.getOpenOrLocalOnlyIds().filter { it !in serverIds && it !in protectedIds }
        remove(vanished, acc)

        // Archive parts are read on demand and never cached; an older build may have cached some,
        // and another user's carriers from a shared project too.
        val cachedCarriers = taskDao.getRoutineCarriersSync()
        val me = currentUser.knownId()
        val foreign = cachedCarriers.filter { it.id > 0L && !readsRow(it, me) }.map { it.id }
        remove(cachedCarriers.filter { RoutineEnvelope.hasArchiveMarker(it.description) }.map { it.id } + foreign, acc)

        // Cached routine carriers the search did not return may be deleted on the server, or the
        // search may have missed them: ask the server about each one.
        val unlisted = cachedCarriers.filter {
            it.id > 0L && !RoutineEnvelope.hasArchiveMarker(it.description) && it.id !in foreign &&
                it.id !in serverIds && it.id !in protectedIds
        }
        verifyCarriers(unlisted.take(MAX_CARRIER_CHECKS), acc)
        // The search found carriers, so it works and the cache now matches the server's; or there
        // was nothing cached the search could have missed. An empty search next to cached carriers
        // proves nothing (the server may not search descriptions), so the history waits.
        val carriersAuthoritative = carriers.isNotEmpty() || unlisted.isEmpty()

        finish(acc)
        openOffset = open.size - taskDao.countOpenServerTasks().toLong()
        cursorStore.commit(ticket, newest?.toString(), fullReconcileAtMs = now.toEpochMilliseconds())
        Logger.d(TAG, "Full reconcile: ${open.size} open tasks, ${carriers.size} routine carriers, ${acc.changed} changed, ${acc.removed} removed")
        return Outcome(full = true, changed = acc.changed, removed = acc.removed, carriersAuthoritative = carriersAuthoritative)
    }

    /**
     * The done routine carriers, found by a search for the marker name. When the server answers the
     * search with an error ([isBrokenSearch]), every done task is listed instead: a longer download
     * per full reconcile, where otherwise no full reconcile could finish and the cache would stay empty.
     */
    private suspend fun doneRoutineCarriers(): List<TaskDto> {
        val listing = try {
            api.getAllTasks(mapOf("q" to ROUTINE_CARRIER_QUERY, "filter" to "done = true"), expandSubtasks = false)
        } catch (e: VikunjaApiException) {
            if (!e.isBrokenSearch) throw e
            Logger.w(TAG, "Routine carrier search failed; listing the done tasks instead: ${e.message}")
            api.getAllTasks(mapOf("filter" to "done = true", "per_page" to SCAN_PAGE_SIZE), expandSubtasks = false)
        }
        return listing.filter { RoutineEnvelope.hasCarrierMarker(it.description) }
    }

    private suspend fun verifyCarriers(candidates: List<TaskEntity>, acc: Accumulator) {
        for (entity in candidates) {
            try {
                merge(listOf(api.getTask(entity.id)), acc)
            } catch (c: CancellationException) {
                throw c
            } catch (e: VikunjaApiException) {
                // A shared routine nobody let this account read any more (its project stopped
                // being shared with it, or the routine moved out) is gone for this account.
                val noAccess = e.httpStatus == 403 && !e.tokenPermission && isSharedRow(entity)
                if (noAccess || missing.taskGone(entity.id, e)) remove(listOf(entity.id), acc)
            } catch (e: Exception) {
                Logger.w(TAG, "Could not check routine carrier ${entity.id}: ${e.message}")
            }
        }
    }

    /**
     * Every [SHARED_CARRIER_CHECK_INTERVAL], asks for each cached shared routine another user owns
     * that this refresh did not bring: when its owner stops sharing it, or the project stops being
     * shared with this account, the task stops coming without any change to see.
     */
    private suspend fun checkSharedCarriers(now: Instant, acc: Accumulator) {
        val elapsed = sharedCheckedAt?.let { now - it }
        // A clock that moved back says nothing about when the last check ran.
        if (elapsed != null && elapsed.isPositive() && elapsed < SHARED_CARRIER_CHECK_INTERVAL) return
        sharedCheckedAt = now
        val me = currentUser.knownId() ?: return
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val shared = taskDao.getRoutineCarriersSync().filter {
            it.id > 0L && it.createdById > 0L && it.createdById != me && it.id !in protectedIds &&
                !RoutineEnvelope.hasArchiveMarker(it.description) && isSharedRow(it)
        }
        verifyCarriers(shared, acc)
    }

    // ---- targeted refresh and completed pages -------------------------------------------------------

    /**
     * Reads one routine carrier into the cache (before a shared routine's reminder rings). A
     * carrier this account can no longer read leaves the cache. False when the server could not
     * be asked.
     */
    suspend fun refreshCarrier(taskId: Long): Boolean = mutex.withLock {
        val entity = taskDao.getByIdSync(taskId) ?: return@withLock false
        val acc = Accumulator()
        val asked = try {
            merge(listOf(api.getTask(taskId)), acc)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: VikunjaApiException) {
            if (e.httpStatus == 403 && !e.tokenPermission && isSharedRow(entity)) remove(listOf(taskId), acc)
            e.httpStatus == 403 || e.httpStatus == 404
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read routine carrier $taskId: ${e.message}")
            false
        }
        finish(acc)
        asked
    }

    /** Fetches the tasks [filters] match (a search, a custom list) and merges them. Deletes nothing. */
    suspend fun refreshMatching(filters: Map<String, String>) = mutex.withLock {
        val acc = Accumulator()
        val dtos = api.getAllTasks(filters, expandSubtasks = false, collabSignals = true)
        merge(dtos, acc)
        storeSignals(dtos)
        finish(acc)
    }

    /**
     * One page of completed tasks, newest first, optionally only those finished at or after
     * [completedSince] (the Logbook retention window). The first page also removes cached completed
     * tasks that are newer than its oldest row and no longer on the server (deleted or reopened
     * elsewhere): everything newer than the page's last row must be on the page.
     */
    suspend fun loadCompletedPage(page: Int, pageSize: Int, completedSince: String?): CompletedPage = mutex.withLock {
        val filter = buildString {
            append("done = true")
            if (!completedSince.isNullOrBlank()) append(" && done_at >= '${completedSince}'")
        }
        val response = api.getTasksPage(
            mapOf(
                "filter" to filter,
                "sort_by" to "done_at",
                "order_by" to "desc",
                "per_page" to pageSize.toString(),
                "page" to page.toString(),
            ),
            expandSubtasks = false,
            collabSignals = true,
        )
        val dtos = response.items
        val hasMore = page < response.totalPages || (response.totalPages == 0 && dtos.size >= pageSize)
        val acc = Accumulator()
        merge(dtos, acc)
        storeSignals(dtos)
        if (page == 1) reconcileCompletedHead(dtos, hasMore, completedSince, acc)
        finish(acc)
        CompletedPage(loaded = dtos.size, hasMore = hasMore)
    }

    private suspend fun reconcileCompletedHead(
        page: List<TaskDto>,
        hasMore: Boolean,
        completedSince: String?,
        acc: Accumulator,
    ) {
        // With more pages, only rows strictly newer than the page's oldest are certain to be on it.
        val lowerBound = if (hasMore) {
            page.mapNotNull { DateUtils.parseIsoDate(it.doneAt) }.minOrNull()?.toString() ?: return
        } else {
            completedSince.orEmpty()
        }
        val onPage = page.mapTo(HashSet()) { it.id }
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val stale = taskDao.getCompletedAfter(lowerBound)
            .filter { it.id !in onPage && it.id !in protectedIds && !RoutineEnvelope.hasMarker(it.description) }
            .map { it.id }
        remove(stale, acc)
    }

    // ---- shared pieces --------------------------------------------------------------------------------

    private class Accumulator {
        val alarmTasks = mutableListOf<Task>()
        val removedIds = LinkedHashSet<Long>()
        var routinesTouched = false
        var changed = 0
        var removed = 0
    }

    /** True for a cached routine carrier this account reads: its own, or a shared one (6.9). */
    private fun readsRow(entity: TaskEntity, me: Long?): Boolean =
        SharedRoutines.readsCarrier(entity.createdById, me, shared = false) || isSharedRow(entity)

    private fun isSharedRow(entity: TaskEntity): Boolean = isShared(entity.description)

    private fun isShared(description: String): Boolean =
        RoutineEnvelope.parse(description, json).payload?.definition?.shared == true

    /** True for a routine carrier on the server this account reads; false for anything else. */
    private fun readsCarrier(dto: TaskDto, me: Long?): Boolean =
        RoutineEnvelope.hasCarrierMarker(dto.description) &&
            (SharedRoutines.readsCarrier(dto.createdBy?.id ?: 0L, me, shared = false) || isShared(dto.description))

    /**
     * Stores what the server returned, except rows with queued changes and hidden tasks. Of the
     * hidden tasks only the routine carriers this account reads are cached (the routines live
     * there): its own, and the ones other users shared (cross-app semantics 6.9). Archive parts
     * are read on demand, the custom-list and preferences carriers belong to their own sync, and
     * a kind this version does not know, or another user's private carrier, is no business of
     * the cache. A cached routine its owner stopped sharing leaves it.
     */
    private suspend fun merge(dtos: List<TaskDto>, acc: Accumulator) {
        val me = currentUser.knownId()
        val (visible, hidden) = dtos.partition {
            !HiddenTasks.isHidden(it.description) || readsCarrier(it, me)
        }
        val unshared = hidden.filter { RoutineEnvelope.hasCarrierMarker(it.description) }.map { it.id }
        if (unshared.isNotEmpty()) {
            val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
            val cached = taskDao.getByIds(unshared).map { it.id }.filter { it !in protectedIds }
            if (cached.isNotEmpty()) remove(cached, acc)
        }
        if (visible.isEmpty()) return
        val entities = visible.map { with(taskMapper) { it.toEntity() } }.distinctBy { it.id }
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val existing = taskDao.getByIds(entities.map { it.id }).associateBy { it.id }
        val changed = entities.filter { it.id !in protectedIds && existing[it.id] != it }
            .map { writeLostRoutineChanges(it, existing[it.id]) }
        if (changed.isEmpty()) return
        taskDao.upsertAll(changed)
        for (entity in changed) {
            val old = existing[entity.id]
            if (RoutineEnvelope.hasMarker(entity.description) || RoutineEnvelope.hasMarker(old?.description)) {
                acc.routinesTouched = true
            }
            if (remindersAffected(old, entity)) acc.alarmTasks += with(taskMapper) { entity.toDomain() }
        }
        acc.changed += changed.size
    }

    /**
     * Keeps the comment counts and unread marks [dtos] came with, in place of a request of their
     * own. A task without comments writes a row only to replace one that said otherwise, and a task
     * with changes queued keeps its count, which may include a comment not sent yet.
     */
    private suspend fun storeSignals(dtos: List<TaskDto>) {
        val dao = collaborationDao ?: return
        if (dtos.isEmpty()) return
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val known = dao.observeSignals().first().associateBy { it.taskId }
        val changed = dtos.mapNotNull { dto ->
            val signal = TaskSignalEntity(dto.id, dto.commentCount ?: 0, dto.isUnread ?: false)
            val old = known[dto.id]
            when {
                dto.id in protectedIds || old == signal -> null
                old == null && signal.commentCount == 0 && !signal.isUnread -> null
                else -> signal
            }
        }
        if (changed.isNotEmpty()) dao.upsertSignals(changed)
    }

    /**
     * A routine carrier the server sends back without this device's recent changes, which the
     * cached row still has, lost them to a write made at the same moment by another device or
     * person (cross-app semantics 6.8). They are written again, merged with the server's copy;
     * when that fails the merge is kept here and queued for the sync.
     */
    private suspend fun writeLostRoutineChanges(entity: TaskEntity, old: TaskEntity?): TaskEntity {
        val deviceId = routineDeviceId ?: return entity
        if (old == null || !RoutineEnvelope.hasCarrierMarker(old.description)) return entity
        val local = RoutineEnvelope.parse(old.description, json).payload ?: return entity
        val server = RoutineEnvelope.parse(entity.description, json)
        val serverPayload = server.payload ?: return entity
        val lost = SharedRoutines.lostOwnChanges(
            local = local,
            server = serverPayload,
            deviceId = deviceId(),
            since = time.now() - SharedRoutines.REASSERT_WINDOW,
        ) ?: return entity
        Logger.w(TAG, "Routine carrier ${entity.id} lost changes of this device; writing them again")
        val description = RoutineEnvelope.upsert(server.body, lost, json)
        return try {
            carrierWriter.write(entity.id, description).task?.let { with(taskMapper) { it.toEntity() } } ?: entity
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Could not write routine carrier ${entity.id} again: ${e.message}")
            val merged = try {
                RoutineEnvelope.mergeForWrite(RoutineEnvelope.parse(description, json), server)
            } catch (_: RoutineEnvelope.UnreadableCarrierException) {
                null
            } ?: return entity
            val now = time.now().toString()
            pendingActionDao.queuePatchActionMerging(
                PendingActionEntity(
                    entityType = "task",
                    entityId = entity.id,
                    actionType = "update",
                    payload = buildJsonObject { put("description", RoutineEnvelope.upsert(server.body, merged, json)) }.toString(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            platformHooks.triggerSync()
            entity.copy(description = RoutineEnvelope.upsert(server.body, merged, json))
        }
    }

    private suspend fun remove(ids: List<Long>, acc: Accumulator) {
        if (ids.isEmpty()) return
        val rows = taskDao.getByIds(ids)
        if (rows.any { RoutineEnvelope.hasMarker(it.description) }) acc.routinesTouched = true
        taskDao.deleteByIds(ids)
        // Only tasks that could have an alarm need it cancelled.
        acc.removedIds += rows.filter { it.remindersJson != "[]" }.map { it.id }
        acc.removed += ids.size
    }

    /** The side effects of a refresh, once: alarms for the tasks that need them, routines, widgets. */
    private suspend fun finish(acc: Accumulator) {
        val alarmWork = acc.alarmTasks.size + acc.removedIds.size
        when {
            alarmWork == 0 -> Unit
            alarmWork > ALARM_BATCH_LIMIT -> platformHooks.rescheduleAlarms()
            else -> platformHooks.updateAlarms(acc.alarmTasks, acc.removedIds)
        }
        if (acc.routinesTouched) platformHooks.routinesChanged()
        if (acc.changed > 0 || acc.removed > 0) platformHooks.updateWidgets()
    }
}
