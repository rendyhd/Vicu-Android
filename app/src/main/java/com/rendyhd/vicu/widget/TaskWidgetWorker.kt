package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.TaskOrderPrefsStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.domain.repository.customListTasks
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.sortProjectTasks
import kotlinx.coroutines.flow.first

class TaskWidgetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val taskRepository: TaskRepository,
    private val secureTokenStorage: TokenStorage,
    private val customListStore: CustomListStore,
    private val widgetPrefsStore: WidgetPrefsStore,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val dayClock: DayClock,
    private val time: TimeSource,
    private val collaboration: CollaborationRepository,
    private val taskOrderPrefsStore: TaskOrderPrefsStore,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TaskWidgetWorker"
        private const val MAX_WIDGET_TASKS = 20

        /** SQLite's LIMIT for no limit. */
        private const val NO_LIMIT = -1
    }

    override suspend fun doWork(): Result {
        return try {
            val manager = GlanceAppWidgetManager(applicationContext)
            val glanceIds = manager.getGlanceIds(TaskListWidget::class.java)

            if (glanceIds.isEmpty()) {
                Log.d(TAG, "No widget instances found")
                return Result.success()
            }

            // Auth guard: if not logged in, show message instead of stale data
            val isLoggedIn = secureTokenStorage.getVikunjaUrl()?.isNotBlank() == true &&
                (secureTokenStorage.getJwt()?.isNotBlank() == true || secureTokenStorage.getApiToken()?.isNotBlank() == true)

            if (!isLoggedIn) {
                Log.d(TAG, "User not logged in, showing login prompt on all widgets")
                for (glanceId in glanceIds) {
                    val state = TaskWidgetState(
                        tasks = emptyList(),
                        totalCount = 0,
                        lastUpdated = time.now().toString(),
                        error = "Log in to see your tasks",
                    )
                    updateAppWidgetState(
                        applicationContext,
                        TaskWidgetStateDefinition,
                        glanceId,
                    ) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[TaskWidgetStateDefinition.KEY_STATE] =
                                TaskWidgetStateDefinition.encodeState(state)
                        }
                    }
                    TaskListWidget().update(applicationContext, glanceId)
                }
                return Result.success()
            }

            val singleWidgetId = inputData.getInt("app_widget_id", -1)
            val updateAll = inputData.getBoolean("update_all", false)

            // Read widget behavior prefs
            val smartAddEnabled = widgetPrefsStore.smartAdd.first()
            val contextNavEnabled = widgetPrefsStore.contextNav.first()

            // Build project name lookup
            val projects = projectDao.getAllSync()
            val projectNameMap = projects.associate { it.id to it.title }
            val activeProjectIds = projectNameMap.keys
            // A project or Inbox widget lists its tasks in the order the project's screen does.
            val parentOf = projects.associate { it.id to it.parentProjectId }
            val taskOrders = taskOrderPrefsStore.settings.first()

            Log.d(TAG, "doWork: glanceIds=${glanceIds.size}, singleWidgetId=$singleWidgetId, updateAll=$updateAll")

            for (glanceId in glanceIds) {
                val appWidgetId = manager.getAppWidgetId(glanceId)

                if (!updateAll && singleWidgetId != -1 && appWidgetId != singleWidgetId) {
                    Log.d(TAG, "Skipping widget $appWidgetId (target=$singleWidgetId)")
                    continue
                }

                val config = WidgetConfigStore.getConfig(applicationContext, appWidgetId)
                Log.d(TAG, "Widget $appWidgetId config=$config (null means default TODAY)")
                val resolvedConfig = config ?: WidgetConfig()

                val projectUnavailable = resolvedConfig.viewType == WidgetViewType.PROJECT &&
                    resolvedConfig.viewId.toLongOrNull() !in activeProjectIds
                val rows = queryTasks(resolvedConfig, activeProjectIds, taskOrders, parentOf)
                    .filter { it.projectId in activeProjectIds }
                Log.d(TAG, "Widget $appWidgetId query returned ${rows.size} tasks (viewType=${resolvedConfig.viewType})")

                val totalCount = rows.size
                val widgetTasks = rows.take(MAX_WIDGET_TASKS).map { row ->
                    WidgetTaskItem(
                        id = row.id,
                        title = row.title,
                        projectName = projectNameMap[row.projectId] ?: "",
                        dueDate = row.dueDate,
                        priority = row.priority,
                        done = row.done,
                    )
                }

                // Resolve addToProjectId for custom lists
                val addToProjectId = if (resolvedConfig.viewType == WidgetViewType.CUSTOM_LIST) {
                    val cl = customListStore.getById(resolvedConfig.viewId).first()
                    cl?.filter?.addToProjectId?.takeIf { it in activeProjectIds } ?: 0L
                } else {
                    0L
                }

                val state = TaskWidgetState(
                    viewType = resolvedConfig.viewType,
                    viewId = resolvedConfig.viewId,
                    viewName = resolvedConfig.viewName,
                    tasks = widgetTasks,
                    totalCount = totalCount,
                    lastUpdated = time.now().toString(),
                    smartAdd = smartAddEnabled,
                    contextNav = contextNavEnabled,
                    addToProjectId = addToProjectId,
                    error = if (projectUnavailable) "Project archived — reconfigure widget" else null,
                )

                updateAppWidgetState(
                    applicationContext,
                    TaskWidgetStateDefinition,
                    glanceId,
                ) { prefs ->
                    prefs.toMutablePreferences().apply {
                        this[TaskWidgetStateDefinition.KEY_STATE] =
                            TaskWidgetStateDefinition.encodeState(state)
                    }
                }

                TaskListWidget().update(applicationContext, glanceId)
                Log.d(TAG, "Widget $appWidgetId updated with ${widgetTasks.size} tasks")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.retry()
        }
    }

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

    /** [tasks] (every open task of [projectId]) in the project's order, as rows. */
    private fun inProjectOrder(
        tasks: List<TaskEntity>,
        projectId: Long,
        taskOrders: TaskOrderSettings,
        parentOf: Map<Long, Long>,
    ): List<WidgetRow> =
        sortProjectTasks(tasks.map { it.toSortable() }, taskOrders.orderFor(projectId, parentOf)).map { it.toRow() }

    private suspend fun queryTasks(
        config: WidgetConfig,
        activeProjectIds: Set<Long>,
        taskOrders: TaskOrderSettings,
        parentOf: Map<Long, Long>,
    ): List<WidgetRow> {
        // The same boundary as the Today and Upcoming screens: the start of the local tomorrow. The
        // day is re-read first, because this runs from a worker that may wake long after the
        // clock last ticked.
        dayClock.refresh()
        val day = dayClock.day.value
        val startOfTomorrow = DueDates.startOfTomorrow(day.date, day.zone).toString()
        val inboxId = secureTokenStorage.getInboxProjectId() ?: 0L
        Log.d(TAG, "queryTasks: viewType=${config.viewType}, startOfTomorrow=$startOfTomorrow, inboxId=$inboxId")

        return when (config.viewType) {
            WidgetViewType.TODAY ->
                taskDao.getTodayTasksSync(startOfTomorrow, MAX_WIDGET_TASKS).map { it.toRow() }

            // Every open task, not a capped sample: the order is applied here, then the widget
            // shows the first ones and counts them all.
            WidgetViewType.INBOX -> inProjectOrder(
                taskDao.getInboxTasksSync(
                    inboxId,
                    NO_LIMIT,
                    includeDated = !behaviorPrefsStore.getPrefs().first().inboxExcludeDated,
                ),
                inboxId,
                taskOrders,
                parentOf,
            )

            WidgetViewType.UPCOMING ->
                taskDao.getUpcomingTasksSync(startOfTomorrow, MAX_WIDGET_TASKS).map { it.toRow() }

            WidgetViewType.ANYTIME ->
                taskDao.getAnytimeTasksSync(inboxId, MAX_WIDGET_TASKS).map { it.toRow() }

            WidgetViewType.PROJECT -> {
                val projectId = config.viewId.toLongOrNull() ?: 0L
                inProjectOrder(taskDao.getByProjectIdSync(projectId, NO_LIMIT), projectId, taskOrders, parentOf)
            }

            WidgetViewType.CUSTOM_LIST -> {
                val customList = customListStore.getById(config.viewId).first()
                if (customList != null) {
                    // The same source and evaluator as the list screen: every task (done ones when
                    // the list includes them), not a capped sample, filtered and sorted the same.
                    // The collaboration conditions read the members and signals stored on the phone.
                    val context = if (customList.filter.hasCollaborationCondition) {
                        CustomListContext.of(collaboration.state.first(), collaboration.signals.first())
                    } else {
                        CustomListContext.NONE
                    }
                    taskRepository.customListTasks(customList.filter, day.date, day.zone, activeProjectIds, context)
                        .map { it.toRow() }
                } else {
                    emptyList()
                }
            }
        }
    }
}
