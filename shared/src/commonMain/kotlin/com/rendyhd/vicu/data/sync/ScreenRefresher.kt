package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The refresh every list screen runs when it opens or is pulled down: tasks, then projects, then
 * labels. It hands back the result instead of swallowing it, and records that the app is fresh
 * ([SyncStaleness.markSynced]) only when all three succeeded, so a failed or offline refresh does
 * not make the next screen skip its own.
 *
 * One refresh at a time app-wide: a screen that asks while another is running waits, and then
 * does nothing if that one just brought the app up to date.
 */
class ScreenRefresher(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val staleness: SyncStaleness,
) {
    private val mutex = Mutex()

    /** True when no refresh succeeded recently; screens call this when they open. */
    fun isStale(): Boolean = staleness.isStale()

    /**
     * Refreshes tasks, projects and labels. A [manual] refresh (pull to refresh, Retry) always
     * runs and asks for a full reconcile; an automatic one is skipped when the app turned fresh
     * while it waited. With [tasks] the task step is replaced (a custom list fetches the tasks its
     * filter matches); that does not bring every task up to date, so it does not mark the app fresh.
     */
    suspend fun refresh(
        manual: Boolean = false,
        tasks: (suspend () -> NetworkResult<Unit>)? = null,
    ): NetworkResult<Unit> = mutex.withLock {
        if (!manual && tasks == null && !staleness.isStale()) return@withLock NetworkResult.Success(Unit)

        val taskResult = tasks?.invoke() ?: taskRepository.refreshAll(full = manual)
        if (taskResult is NetworkResult.Error) return@withLock taskResult
        val projectResult = projectRepository.refreshAll()
        if (projectResult is NetworkResult.Error) return@withLock projectResult
        val labelResult = labelRepository.refreshAll()
        if (labelResult is NetworkResult.Error) return@withLock labelResult

        if (tasks == null) staleness.markSynced()
        NetworkResult.Success(Unit)
    }
}

/**
 * The message a screen should show for this refresh result, or null for none. A refresh the user
 * started always reports its failure; an automatic one stays quiet when the cause is only that the
 * device is offline (the sync indicator says so, and the screen shows what is cached).
 */
fun NetworkResult<*>.refreshErrorToShow(manual: Boolean): String? =
    (this as? NetworkResult.Error)?.takeIf { manual || !it.offline }?.message
