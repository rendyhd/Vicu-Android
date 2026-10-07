package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.remote.api.TaskPositionDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * Sets the position of tasks in a project's list view on the server.
 *
 * Vikunja puts a new task at the top of the list (half the lowest position), so a created task is
 * moved to the end afterwards. That used to cost three requests inside `create()`: the views, the
 * task with the highest position, and the position itself. Now:
 *
 * - [anchorAtEndInBackground] runs after the create has returned.
 * - The list view id of a project is remembered for the process, and so is the position the last
 *   task was given, for [LAST_POSITION_TTL] (another device may add tasks further down). A create in
 *   a project that was positioned a moment ago is one request.
 * - Any failure, and a task moving between projects, forgets what was remembered about the
 *   project ([invalidate]); [clear] forgets everything (account change).
 *
 * Positioning is best effort: a failure is logged and never reaches the caller, the task is where
 * the server put it. One request chain at a time, so quick creates get increasing positions.
 */
class ListPositioner(
    private val api: VikunjaApiService,
    private val scope: CoroutineScope,
    private val time: TimeSource = SystemTimeSource,
) {
    companion object {
        private const val TAG = "ListPositioner"

        /** The gap left after the last task. The same step Vikunja itself uses. */
        const val GAP = 65_536.0

        /** How long the last position of a project is trusted without asking the server. */
        val LAST_POSITION_TTL = 5.minutes

        /** Cached for a project that has no list view, so it is not asked again and again. */
        private const val NO_LIST_VIEW = 0L
    }

    private class LastPosition(val value: Double, val atMs: Long)

    /**
     * What is remembered, as one immutable value that is swapped atomically: [invalidate] and
     * [clear] run on any thread while a positioning chain is in flight. [generation] changes on
     * [clear], so a chain that started for the old account does not write its answers back.
     */
    private data class Cache(
        val generation: Int = 0,
        val listViewIds: Map<Long, Long> = emptyMap(),
        val lastPositions: Map<Long, LastPosition> = emptyMap(),
    )

    private val mutex = Mutex()
    private val cache = MutableStateFlow(Cache())

    /** Moves [taskId] behind the last task of [projectId]'s list view, and returns at once. */
    fun anchorAtEndInBackground(projectId: Long, taskId: Long) {
        if (projectId <= 0L) return
        scope.launch { anchorAtEnd(projectId, taskId) }
    }

    private suspend fun anchorAtEnd(projectId: Long, taskId: Long) = mutex.withLock {
        val generation = cache.value.generation
        try {
            val viewId = listViewId(projectId, generation)
            if (viewId == NO_LIST_VIEW) return@withLock
            val position = lastPosition(projectId, viewId, generation) + GAP
            api.updateTaskPosition(taskId, TaskPositionDto(position = position, projectViewId = viewId))
            remember(generation) { it.copy(lastPositions = it.lastPositions + (projectId to LastPosition(position, nowMs()))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            invalidate(projectId)
            Logger.w(TAG, "Could not put task $taskId at the end of project $projectId (non-fatal): ${e.message}")
        }
    }

    /** A manual reorder: [position] is where [taskId] belongs in [projectId]'s list view. */
    suspend fun setPosition(projectId: Long, taskId: Long, position: Double) = mutex.withLock {
        val generation = cache.value.generation
        try {
            val viewId = listViewId(projectId, generation)
            if (viewId == NO_LIST_VIEW) return@withLock
            api.updateTaskPosition(taskId, TaskPositionDto(position = position, projectViewId = viewId))
            // The last position can only have grown; if it was not remembered, ask when it is needed.
            remember(generation) { current ->
                val last = current.lastPositions[projectId] ?: return@remember current
                current.copy(lastPositions = current.lastPositions + (projectId to LastPosition(maxOf(last.value, position), last.atMs)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            invalidate(projectId)
            Logger.w(TAG, "Could not set the position of task $taskId (non-fatal): ${e.message}")
        }
    }

    /** Forgets what is remembered about [projectId]: a task moved in or out, or a request failed. */
    fun invalidate(projectId: Long) {
        cache.update { it.copy(listViewIds = it.listViewIds - projectId, lastPositions = it.lastPositions - projectId) }
    }

    /** Forgets everything: the account's data was wiped, and another server's ids mean something else. */
    fun clear() {
        cache.update { Cache(generation = it.generation + 1) }
    }

    /** Waits for the background positioning that is running. For tests and for shutdown. */
    suspend fun awaitIdle() {
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    /** Whether anything is remembered about [projectId]. */
    internal fun hasCached(projectId: Long): Boolean =
        projectId in cache.value.listViewIds || projectId in cache.value.lastPositions

    private fun nowMs(): Long = time.now().toEpochMilliseconds()

    /** Applies [change] unless [clear] ran since the chain that is remembering this started. */
    private fun remember(generation: Int, change: (Cache) -> Cache) {
        cache.update { if (it.generation == generation) change(it) else it }
    }

    private suspend fun listViewId(projectId: Long, generation: Int): Long {
        cache.value.listViewIds[projectId]?.let { return it }
        val views = api.getProjectViews(projectId)
        val id = views.firstOrNull { it.viewKind == "list" }?.id ?: NO_LIST_VIEW
        remember(generation) { it.copy(listViewIds = it.listViewIds + (projectId to id)) }
        return id
    }

    private suspend fun lastPosition(projectId: Long, viewId: Long, generation: Int): Double {
        val now = nowMs()
        cache.value.lastPositions[projectId]?.let { remembered ->
            val age = now - remembered.atMs
            if (age in 0 until LAST_POSITION_TTL.inWholeMilliseconds) return remembered.value
        }
        val top = api.getViewTasksPage(
            projectId,
            viewId,
            mapOf("sort_by" to "position", "order_by" to "desc", "per_page" to "1"),
        ).items.firstOrNull()?.position ?: 0.0
        remember(generation) { it.copy(lastPositions = it.lastPositions + (projectId to LastPosition(top, now))) }
        return top
    }
}
