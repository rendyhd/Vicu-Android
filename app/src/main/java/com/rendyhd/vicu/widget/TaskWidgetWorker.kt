package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.auth.SecureTokenStorage
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.DateUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class TaskWidgetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val taskMapper: TaskMapper,
    private val secureTokenStorage: SecureTokenStorage,
    private val customListStore: CustomListStore,
    private val widgetPrefsStore: WidgetPrefsStore,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TaskWidgetWorker"
        private const val MAX_WIDGET_TASKS = 20
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
                        lastUpdated = DateUtils.nowIso(),
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

                val entities = queryTasks(resolvedConfig)
                Log.d(TAG, "Widget $appWidgetId query returned ${entities.size} tasks (viewType=${resolvedConfig.viewType})")

                val totalCount = entities.size
                val widgetTasks = entities.take(MAX_WIDGET_TASKS).map { entity ->
                    WidgetTaskItem(
                        id = entity.id,
                        title = entity.title,
                        projectName = projectNameMap[entity.projectId] ?: "",
                        dueDate = entity.dueDate,
                        priority = entity.priority,
                        done = entity.done,
                    )
                }

                // Resolve addToProjectId for custom lists
                val addToProjectId = if (resolvedConfig.viewType == WidgetViewType.CUSTOM_LIST) {
                    val cl = customListStore.getById(resolvedConfig.viewId).first()
                    cl?.filter?.addToProjectId ?: 0L
                } else {
                    0L
                }

                val state = TaskWidgetState(
                    viewType = resolvedConfig.viewType,
                    viewId = resolvedConfig.viewId,
                    viewName = resolvedConfig.viewName,
                    tasks = widgetTasks,
                    totalCount = totalCount,
                    lastUpdated = DateUtils.nowIso(),
                    smartAdd = smartAddEnabled,
                    contextNav = contextNavEnabled,
                    addToProjectId = addToProjectId,
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

    private suspend fun queryTasks(config: WidgetConfig): List<TaskEntity> {
        val endOfToday = DateUtils.getEndOfToday()
        val inboxId = secureTokenStorage.getInboxProjectId() ?: 0L
        Log.d(TAG, "queryTasks: viewType=${config.viewType}, endOfToday=$endOfToday, inboxId=$inboxId")

        // Debug: count all tasks in Room
        val allCount = taskDao.getAllOpenTasksSync(999).size
        Log.d(TAG, "queryTasks: total open tasks in Room=$allCount")

        return when (config.viewType) {
            WidgetViewType.TODAY ->
                taskDao.getTodayTasksSync(endOfToday, MAX_WIDGET_TASKS)

            WidgetViewType.INBOX ->
                taskDao.getInboxTasksSync(inboxId, MAX_WIDGET_TASKS)

            WidgetViewType.UPCOMING ->
                taskDao.getUpcomingTasksSync(endOfToday, MAX_WIDGET_TASKS)

            WidgetViewType.ANYTIME ->
                taskDao.getAnytimeTasksSync(inboxId, MAX_WIDGET_TASKS)

            WidgetViewType.PROJECT -> {
                val projectId = config.viewId.toLongOrNull() ?: 0L
                taskDao.getByProjectIdSync(projectId, MAX_WIDGET_TASKS)
            }

            WidgetViewType.CUSTOM_LIST -> {
                val customList = customListStore.getById(config.viewId).first()
                if (customList != null) {
                    val allTasks = taskDao.getAllOpenTasksSync(200)
                    val domainTasks = allTasks.map { with(taskMapper) { it.toDomain() } }
                    val filtered = CustomListFilterBuilder.applyClientSideFilters(
                        domainTasks, customList.filter
                    )
                    // Convert back to entities for uniform handling
                    val filteredIds = filtered.map { it.id }.toSet()
                    allTasks.filter { it.id in filteredIds }.take(MAX_WIDGET_TASKS)
                } else {
                    emptyList()
                }
            }
        }
    }
}
