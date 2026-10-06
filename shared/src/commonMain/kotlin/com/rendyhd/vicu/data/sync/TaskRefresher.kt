package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.CustomListEnvelope
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.RoutineEnvelope
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.worker.MissingResourceCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

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
) {
    companion object {
        private const val TAG = "TaskRefresher"

        /** A full reconcile (the only way a deletion is noticed) is due this long after the last one. */
        val FULL_RECONCILE_INTERVAL = 24.hours

        /** Re-fetched on purpose: a write that raced the last refresh has the same `updated`. */
        private const val OVERLAP_SECONDS = 5L

        /** Routine carriers are found by their marker name; the server searches descriptions too. */
        const val ROUTINE_CARRIER_QUERY = "vicu-routine"

        /** At most this many cached carriers the search did not return are asked for one by one. */
        private const val MAX_CARRIER_CHECKS = 20

        /** More changed alarms than this are cheaper as one full pass than as one call each. */
        private const val ALARM_BATCH_LIMIT = 100
    }

    /** What a refresh did. [carriersAuthoritative]: the cached routine carriers now match the server's. */
    data class Outcome(
        val full: Boolean,
        val changed: Int,
        val removed: Int,
        val carriersAuthoritative: Boolean,
    )

    private val mutex = Mutex()
    private val missing = MissingResourceCheck(api)

    suspend fun requestFullReconcile() = cursorStore.requestFullReconcile()

    suspend fun refresh(forceFull: Boolean = false): Outcome = mutex.withLock {
        val ticket = cursorStore.begin()
        val state = ticket.cursor
        val now = time.now()
        val since = state.tasksUpdatedSince?.let { DateUtils.parseIsoDate(it) }
        val full = forceFull || since == null || state.fullReconcileRequested ||
            isFullReconcileDue(state.lastFullReconcileMs, now)
        if (full) reconcile(ticket, now) else incremental(ticket, checkNotNull(since))
    }

    private fun isFullReconcileDue(lastMs: Long, now: Instant): Boolean {
        if (lastMs <= 0L) return true
        val age = now.toEpochMilliseconds() - lastMs
        // A negative age means the clock moved back; the stored time says nothing then.
        return age < 0 || age >= FULL_RECONCILE_INTERVAL.inWholeMilliseconds
    }

    // ---- incremental ---------------------------------------------------------------------------

    private suspend fun incremental(ticket: SyncCursorStore.Ticket, since: Instant): Outcome {
        val from = Instant.fromEpochSeconds(since.epochSeconds - OVERLAP_SECONDS)
        val dtos = api.getAllTasks(mapOf("filter" to "updated >= '$from'"), expandSubtasks = false)
        val acc = Accumulator()
        merge(dtos, acc)
        finish(acc)
        val newest = (dtos.mapNotNull { DateUtils.parseIsoDate(it.updated) } + since).max()
        cursorStore.commit(ticket, newest.toString())
        Logger.d(TAG, "Incremental refresh: ${dtos.size} tasks changed since $from, ${acc.changed} differed from the cache")
        return Outcome(full = false, changed = acc.changed, removed = 0, carriersAuthoritative = false)
    }

    // ---- full reconcile ---------------------------------------------------------------------------

    private suspend fun reconcile(ticket: SyncCursorStore.Ticket, now: Instant): Outcome {
        // The newest change anywhere, read first: it becomes the cursor, so whatever changes while
        // the lists below download is fetched again by the next incremental refresh.
        val newest = api.getTasksPage(
            mapOf("sort_by" to "updated", "order_by" to "desc", "per_page" to "1"),
            expandSubtasks = false,
        ).items.firstOrNull()?.updated?.let { DateUtils.parseIsoDate(it) }

        val open = api.getAllTasks(mapOf("filter" to "done = false"), expandSubtasks = false)
        val carriers = api.getAllTasks(
            mapOf("q" to ROUTINE_CARRIER_QUERY, "filter" to "done = true"),
            expandSubtasks = false,
        ).filter { RoutineEnvelope.hasCarrierMarker(it.description) }

        val acc = Accumulator()
        merge(open + carriers, acc)

        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val serverIds = HashSet<Long>().apply {
            open.forEach { add(it.id) }
            carriers.forEach { add(it.id) }
        }

        // Rows the server no longer lists as open, and rows that only existed on this device
        // (an offline create whose queued action is gone). Completed tasks are not swept here.
        val vanished = taskDao.getOpenOrLocalOnlyIds().filter { it !in serverIds && it !in protectedIds }
        remove(vanished, acc)

        // Archive parts are read on demand and never cached; an older build may have cached some.
        val cachedCarriers = taskDao.getRoutineCarriersSync()
        remove(cachedCarriers.filter { RoutineEnvelope.hasArchiveMarker(it.description) }.map { it.id }, acc)

        // Cached routine carriers the search did not return may be deleted on the server, or the
        // search may have missed them: ask the server about each one.
        val unlisted = cachedCarriers.filter {
            it.id > 0L && !RoutineEnvelope.hasArchiveMarker(it.description) &&
                it.id !in serverIds && it.id !in protectedIds
        }
        verifyCarriers(unlisted.take(MAX_CARRIER_CHECKS), acc)
        // The search found carriers, so it works and the cache now matches the server's; or there
        // was nothing cached the search could have missed. An empty search next to cached carriers
        // proves nothing (the server may not search descriptions), so the history waits.
        val carriersAuthoritative = carriers.isNotEmpty() || unlisted.isEmpty()

        finish(acc)
        cursorStore.commit(ticket, newest?.toString(), fullReconcileAtMs = now.toEpochMilliseconds())
        Logger.d(TAG, "Full reconcile: ${open.size} open tasks, ${carriers.size} routine carriers, ${acc.changed} changed, ${acc.removed} removed")
        return Outcome(full = true, changed = acc.changed, removed = acc.removed, carriersAuthoritative = carriersAuthoritative)
    }

    private suspend fun verifyCarriers(candidates: List<TaskEntity>, acc: Accumulator) {
        for (entity in candidates) {
            try {
                merge(listOf(api.getTask(entity.id)), acc)
            } catch (c: CancellationException) {
                throw c
            } catch (e: VikunjaApiException) {
                if (missing.taskGone(entity.id, e)) remove(listOf(entity.id), acc)
            } catch (e: Exception) {
                Logger.w(TAG, "Could not check routine carrier ${entity.id}: ${e.message}")
            }
        }
    }

    // ---- targeted refresh and completed pages -------------------------------------------------------

    /** Fetches the tasks [filters] match (a search, a custom list) and merges them. Deletes nothing. */
    suspend fun refreshMatching(filters: Map<String, String>) = mutex.withLock {
        val acc = Accumulator()
        merge(api.getAllTasks(filters, expandSubtasks = false), acc)
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
        )
        val dtos = response.items
        val hasMore = page < response.totalPages || (response.totalPages == 0 && dtos.size >= pageSize)
        val acc = Accumulator()
        merge(dtos, acc)
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

    /** Stores what the server returned, except rows with queued changes and metadata tasks. */
    private suspend fun merge(dtos: List<TaskDto>, acc: Accumulator) {
        // Routine archive parts are read on demand and never cached; custom-list carriers belong
        // to CustomListRepository and must never enter user task data.
        val visible = dtos.filterNot {
            CustomListEnvelope.hasMarker(it.description) || RoutineEnvelope.hasArchiveMarker(it.description)
        }
        if (visible.isEmpty()) return
        val entities = visible.map { with(taskMapper) { it.toEntity() } }.distinctBy { it.id }
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val existing = taskDao.getByIds(entities.map { it.id }).associateBy { it.id }
        val changed = entities.filter { it.id !in protectedIds && existing[it.id] != it }
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
