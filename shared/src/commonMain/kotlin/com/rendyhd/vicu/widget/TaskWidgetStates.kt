package com.rendyhd.vicu.widget

import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.TaskOrderPreferences
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.domain.repository.customListTasks
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.sortProjectTasks
import kotlinx.coroutines.flow.first

/**
 * What a task list widget shows for its configured view, from the data on the device: the same
 * queries as the list screens, at most [MAX_WIDGET_TASKS] rows with the total, tasks of archived
 * projects left out, and the project the + button adds to. Android's Glance widget and the iOS
 * widget snapshots are both built here.
 */
class TaskWidgetStates(
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val taskRepository: TaskRepository,
    private val tokenStorage: TokenStorage,
    private val customListStore: CustomListStore,
    private val widgetPrefsStore: WidgetPrefsStore,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val time: TimeSource,
    /** The members and signals stored on the device, for lists with collaboration conditions. */
    private val collaborationContext: suspend () -> CustomListContext,
    /** A project or Inbox widget lists in its project's task order (cross-app semantics 12); none: the default. */
    private val taskOrders: TaskOrderPreferences? = null,
) {
    /** Signed in with a token the widget's data can belong to. */
    suspend fun isSignedIn(): Boolean =
        tokenStorage.getVikunjaUrl()?.isNotBlank() == true &&
            (tokenStorage.getJwt()?.isNotBlank() == true || tokenStorage.getApiToken()?.isNotBlank() == true)

    /** What a widget shows while nobody is signed in, instead of stale data. */
    fun signedOut(): TaskWidgetState = TaskWidgetState(
        tasks = emptyList(),
        totalCount = 0,
        lastUpdated = time.now().toString(),
        error = SIGNED_OUT,
    )

    /** Reads what every widget needs once (projects, preferences) for the local [day]. */
    suspend fun prepare(day: ClockDay): Batch {
        val projects = projectDao.getAllSync()
        return Batch(
            day = day,
            projectNames = projects.associate { it.id to it.title },
            parentOf = projects.associate { it.id to it.parentProjectId },
            orders = taskOrders?.settings?.first() ?: TaskOrderSettings(),
            smartAdd = widgetPrefsStore.smartAdd.first(),
            contextNav = widgetPrefsStore.contextNav.first(),
            voiceButton = widgetPrefsStore.voiceButton.first(),
            inboxId = tokenStorage.getInboxProjectId() ?: 0L,
        )
    }

    inner class Batch internal constructor(
        val day: ClockDay,
        /** The projects that are not archived, by id. */
        val projectNames: Map<Long, String>,
        private val parentOf: Map<Long, Long>,
        private val orders: TaskOrderSettings,
        private val smartAdd: Boolean,
        private val contextNav: Boolean,
        private val voiceButton: Boolean,
        private val inboxId: Long,
    ) {
        private val activeProjectIds: Set<Long> get() = projectNames.keys

        /** The order [projectId] uses: its own, a parent's, or the global one. */
        fun orderOf(projectId: Long): TaskOrder = orders.orderFor(projectId, parentOf)

        suspend fun build(config: WidgetConfig): TaskWidgetState {
            val projectUnavailable = config.viewType == WidgetViewType.PROJECT &&
                config.viewId.toLongOrNull() !in activeProjectIds
            val rows = query(config).filter { it.projectId in activeProjectIds }
            val addToProjectId = if (config.viewType == WidgetViewType.CUSTOM_LIST) {
                customListStore.getById(config.viewId).first()?.filter?.addToProjectId?.takeIf { it in activeProjectIds } ?: 0L
            } else {
                0L
            }
            return TaskWidgetState(
                viewType = config.viewType,
                viewId = config.viewId,
                viewName = config.viewName,
                tasks = rows.take(MAX_WIDGET_TASKS).map { row ->
                    WidgetTaskItem(
                        id = row.id,
                        title = row.title,
                        projectName = projectNames[row.projectId] ?: "",
                        dueDate = row.dueDate,
                        priority = row.priority,
                        done = row.done,
                    )
                },
                totalCount = rows.size,
                lastUpdated = time.now().toString(),
                smartAdd = smartAdd,
                contextNav = contextNav,
                voiceButton = voiceButton,
                addToProjectId = addToProjectId,
                error = if (projectUnavailable) PROJECT_UNAVAILABLE else null,
            )
        }

        private suspend fun query(config: WidgetConfig): List<WidgetRow> {
            // The same boundary as the Today and Upcoming screens: the start of the local tomorrow.
            val startOfTomorrow = DueDates.startOfTomorrow(day.date, day.zone).toString()
            return when (config.viewType) {
                // The same list as the Today screen, the exact rule included (contract section 2).
                WidgetViewType.TODAY -> taskDao
                    .getTodayTasksSync(DueDates.startOfDay(day.date, day.zone).toString(), startOfTomorrow)
                    .filter { TimeBlocks.isOnToday(it.dueDate, it.startDate, it.endDate, day.date, day.zone) }
                    .map { it.toRow() }
                WidgetViewType.INBOX -> inProjectOrder(
                    taskDao.getInboxTasksSync(inboxId, NO_LIMIT, includeDated = !behaviorPrefsStore.getPrefs().first().inboxExcludeDated),
                    inboxId,
                )
                WidgetViewType.UPCOMING -> taskDao.getUpcomingTasksSync(startOfTomorrow, MAX_WIDGET_TASKS).map { it.toRow() }
                WidgetViewType.ANYTIME -> taskDao.getAnytimeTasksSync(inboxId, MAX_WIDGET_TASKS).map { it.toRow() }
                WidgetViewType.PROJECT -> {
                    val projectId = config.viewId.toLongOrNull() ?: 0L
                    inProjectOrder(taskDao.getByProjectIdSync(projectId, NO_LIMIT), projectId)
                }
                WidgetViewType.CUSTOM_LIST -> {
                    val customList = customListStore.getById(config.viewId).first() ?: return emptyList()
                    // The same source and evaluator as the list screen: every task (done ones when
                    // the list includes them), not a capped sample, filtered and sorted the same.
                    // The collaboration conditions read the members and signals stored on the device.
                    val context = if (customList.filter.hasCollaborationCondition) collaborationContext() else CustomListContext.NONE
                    taskRepository.customListTasks(customList.filter, day.date, day.zone, activeProjectIds, context, time.now())
                        .map { it.toRow() }
                }
            }
        }
    }

    /**
     * Every open task of [projectId] in the order its screen shows them, as rows: the whole list is
     * read and sorted before the widget takes its first rows.
     */
    private fun Batch.inProjectOrder(tasks: List<TaskEntity>, projectId: Long): List<WidgetRow> =
        sortProjectTasks(tasks.map { it.toSortable() }, orderOf(projectId)).map { it.toRow() }

    /** What the widget needs of a task, whichever query it came from. */
    private class WidgetRow(
        val id: Long,
        val title: String,
        val projectId: Long,
        val dueDate: String,
        val priority: Int,
        val done: Boolean,
    )

    private fun TaskEntity.toRow() = WidgetRow(id, title, projectId, dueDate, priority, done)

    private fun Task.toRow() = WidgetRow(id, title, projectId, dueDate, priority, done)

    /** What sorting a project's tasks reads of a stored task. */
    private fun TaskEntity.toSortable() = Task(
        id = id,
        title = title,
        done = done,
        dueDate = dueDate,
        priority = priority,
        projectId = projectId,
        position = position,
        created = created,
    )

    companion object {
        const val MAX_WIDGET_TASKS = 20

        /** SQLite's LIMIT for no limit. */
        private const val NO_LIMIT = -1
        const val SIGNED_OUT = "Log in to see your tasks"
        const val PROJECT_UNAVAILABLE = "Project archived — reconfigure widget"
    }
}
