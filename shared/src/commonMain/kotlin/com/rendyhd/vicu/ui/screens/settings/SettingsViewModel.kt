package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.util.countOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.SessionCleanup
import com.rendyhd.vicu.auth.SignOutResult
import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.BottomBarPrefsStore
import com.rendyhd.vicu.data.local.LogbookPrefs
import com.rendyhd.vicu.data.local.LogbookPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.RoutineVisibility
import com.rendyhd.vicu.data.local.SyncCursorStore
import com.rendyhd.vicu.data.local.SubprojectDisplayMode
import com.rendyhd.vicu.data.local.SubtaskDisplayMode
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxMode
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.ui.screens.settings.PlatformSettingsHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    // General
    val username: String = "",
    val email: String = "",
    val authMethod: String = "",
    val vikunjaUrl: String = "",
    val inboxProjectId: Long? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val nlpConfig: ParserConfig = ParserConfig(),
    // Labels & Custom Lists
    val labels: List<Label> = emptyList(),
    val customLists: List<CustomList> = emptyList(),
    val customListSyncStatus: CustomListSyncStatus = CustomListSyncStatus.Idle,
    val projects: List<Project> = emptyList(),
    val archivedProjects: List<Project> = emptyList(),
    // Notifications
    val notificationPrefs: NotificationPrefs = NotificationPrefs(),
    val supportsQuickAddTile: Boolean = false,
    // Behavior (delete confirm, completion sound)
    val behaviorPrefs: BehaviorPrefs = BehaviorPrefs(),
    // Sync
    val pendingActionCount: Int = 0,
    val failedActionCount: Int = 0,
    val isOnline: Boolean = true,
    // Bottom Bar
    val bottomBarSlots: List<BottomBarSlot> = BottomBarSlot.DEFAULT_SLOTS,
    // Widget
    val widgetSmartAdd: Boolean = true,
    val widgetContextNav: Boolean = true,
    // Review
    val reviewPrefs: ReviewPrefs = ReviewPrefs(),
    // Routines
    val routineVisibility: RoutineVisibility = RoutineVisibility(),
    // Logbook retention
    val logbookPrefs: LogbookPrefs = LogbookPrefs(),
    // Messages
    val error: String? = null,
    val successMessage: String? = null,
)

private data class UserInfo(
    val username: String = "",
    val email: String = "",
    val authMethod: String = "",
)

private data class ContentGroup(
    val labels: List<Label>,
    val customLists: List<CustomList>,
    val customListSync: CustomListSyncStatus,
    val projects: List<Project>,
    val notificationPrefs: NotificationPrefs,
)

private data class SyncGroup(
    val pendingCount: Int,
    val failedCount: Int,
    val isOnline: Boolean,
    val themeMode: ThemeMode,
    val nlpConfig: ParserConfig,
)

private data class AccountGroup(
    val userInfo: UserInfo,
    val vikunjaUrl: String,
    val inboxProjectId: Long?,
    val bottomBarSlots: List<BottomBarSlot>,
    /** (error, success) */
    val messages: Pair<String?, String?>,
)

private data class PrefsGroup(
    val smartAdd: Boolean,
    val contextNav: Boolean,
    val behaviorPrefs: BehaviorPrefs,
    val reviewPrefs: ReviewPrefs,
    val logbookPrefs: LogbookPrefs,
)

class SettingsViewModel(
    private val authManager: AuthManager,
    private val tokenStorage: TokenStorage,
    private val labelRepository: LabelRepository,
    private val projectRepository: ProjectRepository,
    private val customListRepository: CustomListRepository,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val behaviorPrefsStore: BehaviorPrefsStore,
    private val themePrefsStore: ThemePrefsStore,
    private val nlpPrefsStore: NlpPrefsStore,
    private val bottomBarPrefsStore: BottomBarPrefsStore,
    private val widgetPrefsStore: WidgetPrefsStore,
    private val reviewPrefsStore: ReviewPrefsStore,
    private val logbookPrefsStore: LogbookPrefsStore,
    private val routinePrefsStore: RoutinePrefsStore,
    private val repositoryHooks: PlatformRepositoryHooks,
    private val pendingActionDao: PendingActionDao,
    private val networkMonitor: NetworkMonitor,
    private val sessionCleanup: SessionCleanup,
    private val apiService: VikunjaApiService,
    private val platformSettingsHooks: PlatformSettingsHooks,
    private val syncCursor: SyncCursorStore,
) : ViewModel() {

    private val _messages = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val _userInfo = MutableStateFlow(UserInfo())
    private val _vikunjaUrl = MutableStateFlow("")
    private val _inboxProjectId = MutableStateFlow<Long?>(null)

    init {
        loadAccountInfo()
        viewModelScope.launch { authManager.inboxProjectId.collect { _inboxProjectId.value = it } }
        viewModelScope.launch {
            // Settings is the management surface for archived projects, so refresh its
            // complete snapshot when opened to pick up changes made in Vikunja or desktop.
            projectRepository.refreshAll()
        }
        viewModelScope.launch { customListRepository.sync() }
    }

    // The sources are combined in typed groups of at most five flows each, then the groups are
    // combined: no positional lists and no casts, so a mismatch is a compile error.
    private val content = combine(
        labelRepository.getAll(),
        customListRepository.lists,
        customListRepository.syncStatus,
        projectRepository.getAllIncludingArchived(),
        notificationPrefsStore.getPrefs(),
    ) { labels, customLists, customListSync, projects, notificationPrefs ->
        ContentGroup(labels, customLists, customListSync, projects, notificationPrefs)
    }

    private val sync = combine(
        pendingActionDao.getPendingCount(),
        pendingActionDao.getFailedCount(),
        networkMonitor.isOnline,
        themePrefsStore.themeMode,
        nlpPrefsStore.config,
    ) { pendingCount, failedCount, isOnline, themeMode, nlpConfig ->
        SyncGroup(pendingCount, failedCount, isOnline, themeMode, nlpConfig)
    }

    private val account = combine(
        _userInfo,
        _vikunjaUrl,
        _inboxProjectId,
        bottomBarPrefsStore.slots,
        _messages,
    ) { userInfo, url, inboxId, bottomBarSlots, messages ->
        AccountGroup(userInfo, url, inboxId, bottomBarSlots, messages)
    }

    private val prefs = combine(
        widgetPrefsStore.smartAdd,
        widgetPrefsStore.contextNav,
        behaviorPrefsStore.getPrefs(),
        reviewPrefsStore.getPrefs(),
        logbookPrefsStore.getPrefs(),
    ) { smartAdd, contextNav, behaviorPrefs, reviewPrefs, logbookPrefs ->
        PrefsGroup(smartAdd, contextNav, behaviorPrefs, reviewPrefs, logbookPrefs)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        content,
        sync,
        account,
        prefs,
        routinePrefsStore.visibility,
    ) { c, s, a, p, routineVisibility ->
        SettingsUiState(
            username = a.userInfo.username,
            email = a.userInfo.email,
            authMethod = a.userInfo.authMethod,
            vikunjaUrl = a.vikunjaUrl,
            inboxProjectId = a.inboxProjectId,
            themeMode = s.themeMode,
            nlpConfig = s.nlpConfig,
            labels = c.labels.sortedBy { it.title.lowercase() },
            customLists = c.customLists,
            customListSyncStatus = c.customListSync,
            projects = c.projects.filter { !it.isArchived },
            archivedProjects = c.projects.filter { it.isArchived },
            notificationPrefs = c.notificationPrefs,
            supportsQuickAddTile = platformSettingsHooks.supportsQuickAddTile,
            behaviorPrefs = p.behaviorPrefs,
            pendingActionCount = s.pendingCount,
            failedActionCount = s.failedCount,
            isOnline = s.isOnline,
            bottomBarSlots = a.bottomBarSlots,
            widgetSmartAdd = p.smartAdd,
            widgetContextNav = p.contextNav,
            reviewPrefs = p.reviewPrefs,
            routineVisibility = routineVisibility,
            logbookPrefs = p.logbookPrefs,
            error = a.messages.first,
            successMessage = a.messages.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private fun loadAccountInfo() {
        viewModelScope.launch {
            val authMethod = tokenStorage.getAuthMethod() ?: ""
            val url = tokenStorage.getVikunjaUrl() ?: ""
            _vikunjaUrl.value = url
            _userInfo.update { it.copy(authMethod = authMethod) }

            // Fetch user info from API
            try {
                val user = apiService.getCurrentUser()
                _userInfo.value = UserInfo(
                    username = user.username.ifBlank { user.name },
                    email = user.email,
                    authMethod = authMethod,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or failed — keep empty
            }
        }
    }

    // --- Theme ---

    // Exposed separately from the main combine to avoid widening it.
    val useDeviceColors: StateFlow<Boolean> = themePrefsStore.useDeviceColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePrefsStore.setThemeMode(mode)
        }
    }

    fun setUseDeviceColors(enabled: Boolean) {
        viewModelScope.launch {
            themePrefsStore.setUseDeviceColors(enabled)
            // The widgets follow the same colours.
            platformSettingsHooks.updateWidgets()
        }
    }

    // --- NLP Parser ---

    fun setNlpEnabled(enabled: Boolean) {
        viewModelScope.launch { nlpPrefsStore.setEnabled(enabled) }
    }

    fun setNlpSyntaxMode(mode: SyntaxMode) {
        viewModelScope.launch { nlpPrefsStore.setSyntaxMode(mode) }
    }

    fun setBangToday(enabled: Boolean) {
        viewModelScope.launch { nlpPrefsStore.setBangToday(enabled) }
    }

    // --- Behavior preferences ---

    fun setConfirmBeforeDelete(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setConfirmBeforeDelete(enabled) }
    }

    fun setCompletionSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setCompletionSoundEnabled(enabled) }
    }

    fun setCompletionSoundUri(uri: String?) {
        viewModelScope.launch { behaviorPrefsStore.setCompletionSoundUri(uri) }
    }

    // --- Inbox project ---

    fun setInboxProject(projectId: Long) {
        viewModelScope.launch {
            authManager.onInboxProjectSelected(projectId)
            _inboxProjectId.value = projectId
            _messages.update { null to "Inbox project updated" }
        }
    }

    // --- Logout ---

    /** Routine history not uploaded to the server yet (sign-out deletes it). */
    val routineHistoryCount: StateFlow<Int> = sessionCleanup.routineHistoryCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Custom-list changes not on the server yet (sign-out deletes them). */
    val customListChangesUnsynced: StateFlow<Boolean> = sessionCleanup.customListChangesUnsynced
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Signs out and deletes local data. Offline changes that never reached the server are lost
     * with it, so with any queued the caller must pass [discardUnsynced] = true (the dialog makes
     * the user choose that); without it nothing happens.
     */
    fun logout(discardUnsynced: Boolean) {
        viewModelScope.launch {
            when (val result = sessionCleanup.signOut(discardUnsynced)) {
                is SignOutResult.NeedsDiscard ->
                    _messages.update {
                        "${countOf(result.unsyncedChanges, "unsynced change")} would be lost. " +
                            "Discard ${if (result.unsyncedChanges == 1) "it" else "them"} to sign out." to null
                    }
                SignOutResult.Done -> platformSettingsHooks.updateWidgets()
            }
        }
    }

    // --- Clear cache & re-sync ---

    /**
     * Clears the cached tasks, projects and labels and syncs again. The offline queue (and the
     * rows it refers to) and routine history stay unless [discardUnsynced] is set.
     */
    fun clearCacheAndResync(discardUnsynced: Boolean = false) {
        viewModelScope.launch {
            sessionCleanup.clearCaches(discardUnsynced)
            platformSettingsHooks.triggerImmediateSync()
            _messages.update { null to "Cache cleared, syncing..." }
        }
    }

    // --- Projects ---

    fun createProject(name: String, hexColor: String, parentProjectId: Long) {
        viewModelScope.launch {
            val project = Project(id = 0, title = name, hexColor = hexColor, parentProjectId = parentProjectId)
            when (val result = projectRepository.create(project)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Project created" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun updateProject(project: Project, name: String, hexColor: String, parentProjectId: Long) {
        viewModelScope.launch {
            val updated = project.copy(title = name, hexColor = hexColor, parentProjectId = parentProjectId)
            when (val result = projectRepository.update(updated)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Project updated" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            when (val result = projectRepository.delete(projectId)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Project deleted" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun archiveProject(project: Project) {
        if (project.id == _inboxProjectId.value) {
            _messages.update { "Select another Inbox project before archiving this project" to null }
            return
        }
        setProjectArchived(project, archived = true)
    }

    fun restoreProject(project: Project) {
        setProjectArchived(project, archived = false)
    }

    private fun setProjectArchived(project: Project, archived: Boolean) {
        viewModelScope.launch {
            when (val result = projectRepository.update(project.copy(isArchived = archived))) {
                is NetworkResult.Success -> {
                    platformSettingsHooks.updateWidgets()
                    _messages.update { null to if (archived) "Project archived" else "Project restored" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    // --- Labels ---

    fun createLabel(name: String, hexColor: String) {
        viewModelScope.launch {
            val label = Label(id = 0, title = name, hexColor = hexColor)
            when (val result = labelRepository.create(label)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Label created" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun updateLabel(label: Label, name: String, hexColor: String) {
        viewModelScope.launch {
            val updated = label.copy(title = name, hexColor = hexColor)
            when (val result = labelRepository.update(updated)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Label updated" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deleteLabel(labelId: Long) {
        viewModelScope.launch {
            when (val result = labelRepository.delete(labelId)) {
                is NetworkResult.Success -> {
                    _messages.update { null to "Label deleted" }
                }
                is NetworkResult.Error -> {
                    _messages.update { result.message to null }
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    // --- Bottom Bar ---

    fun updateBottomBarSlot(index: Int, slot: BottomBarSlot) {
        viewModelScope.launch {
            bottomBarPrefsStore.updateSlot(index, slot)
            _messages.update { null to "Bottom bar updated" }
        }
    }

    fun resetBottomBar() {
        viewModelScope.launch {
            bottomBarPrefsStore.saveSlots(BottomBarSlot.DEFAULT_SLOTS)
            _messages.update { null to "Bottom bar reset to defaults" }
        }
    }

    // --- Custom Lists ---

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListRepository.upsert(customList)
            _messages.update { null to "List saved" }
        }
    }

    fun deleteCustomList(id: String) {
        viewModelScope.launch {
            customListRepository.delete(id)
            _messages.update { null to "List deleted" }
        }
    }

    fun retryCustomListSync() {
        viewModelScope.launch { customListRepository.sync() }
    }

    // --- Notifications ---

    fun setTaskRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPrefsStore.setTaskRemindersEnabled(enabled)
        }
    }

    fun setDailySummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPrefsStore.setDailySummaryEnabled(enabled)
            val prefs = uiState.value.notificationPrefs
            platformSettingsHooks.scheduleDailySummary("morning", enabled, prefs.dailySummaryHour, prefs.dailySummaryMinute)
        }
    }

    fun setDailySummaryTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            notificationPrefsStore.setDailySummaryTime(hour, minute)
            val prefs = uiState.value.notificationPrefs
            if (prefs.dailySummaryEnabled) {
                platformSettingsHooks.scheduleDailySummary("morning", true, hour, minute)
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPrefsStore.setSoundEnabled(enabled)
        }
    }

    fun setAfternoonSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPrefsStore.setAfternoonSummaryEnabled(enabled)
            val prefs = uiState.value.notificationPrefs
            platformSettingsHooks.scheduleDailySummary(
                "afternoon",
                enabled,
                prefs.afternoonSummaryHour,
                prefs.afternoonSummaryMinute,
            )
        }
    }

    fun setAfternoonSummaryTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            notificationPrefsStore.setAfternoonSummaryTime(hour, minute)
            val prefs = uiState.value.notificationPrefs
            if (prefs.afternoonSummaryEnabled) {
                platformSettingsHooks.scheduleDailySummary("afternoon", true, hour, minute)
            }
        }
    }

    fun setNotifyOverdueEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPrefsStore.setNotifyOverdueEnabled(enabled) }
    }

    fun setNotifyDueTodayEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPrefsStore.setNotifyDueTodayEnabled(enabled) }
    }

    fun setNotifyUpcomingEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPrefsStore.setNotifyUpcomingEnabled(enabled) }
    }

    fun setDefaultReminderOffset(seconds: Int) {
        viewModelScope.launch { notificationPrefsStore.setDefaultReminderOffset(seconds) }
    }

    fun setDefaultReminderRelativeTo(value: String) {
        viewModelScope.launch { notificationPrefsStore.setDefaultReminderRelativeTo(value) }
    }

    // --- Review ---

    fun setReviewEnabled(enabled: Boolean) {
        viewModelScope.launch { reviewPrefsStore.setEnabled(enabled) }
    }

    fun setReviewDefaultCadence(days: Int) {
        viewModelScope.launch { reviewPrefsStore.setDefaultCadenceDays(days) }
    }

    fun setReviewExcludeInbox(enabled: Boolean) {
        viewModelScope.launch { reviewPrefsStore.setExcludeInbox(enabled) }
    }

    // --- Routines ---

    /** Turning routines off also cancels their reminders; turning them on plans them again. */
    fun setRoutinesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            routinePrefsStore.setEnabled(enabled)
            repositoryHooks.routinesChanged()
        }
    }

    fun setRoutinesShowInToday(show: Boolean) {
        viewModelScope.launch { routinePrefsStore.setShowInToday(show) }
    }

    fun setInboxExcludeDated(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setInboxExcludeDated(enabled) }
    }

    fun setScheduleAction(action: com.rendyhd.vicu.data.local.ScheduleAction) {
        viewModelScope.launch { behaviorPrefsStore.setScheduleAction(action) }
    }

    fun setKeepEntryOpen(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setKeepEntryOpen(enabled) }
    }

    fun setFabAlignStart(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setFabAlignStart(enabled) }
    }

    fun setSubtaskDisplayMode(mode: SubtaskDisplayMode) {
        viewModelScope.launch { behaviorPrefsStore.setSubtaskDisplayMode(mode) }
    }

    fun setSubprojectDisplayMode(mode: SubprojectDisplayMode) {
        viewModelScope.launch { behaviorPrefsStore.setSubprojectDisplayMode(mode) }
    }

    fun setShowProjectProgress(enabled: Boolean) {
        viewModelScope.launch { behaviorPrefsStore.setShowProjectProgress(enabled) }
    }

    fun setLogbookRetentionEnabled(enabled: Boolean) {
        viewModelScope.launch { logbookPrefsStore.setEnabled(enabled) }
    }

    fun setLogbookRetentionDays(days: Int) {
        viewModelScope.launch { logbookPrefsStore.setRetentionDays(days) }
    }

    fun sendTestNotification() {
        try {
            val result = platformSettingsHooks.sendTestNotification()
            if (result != null) {
                _messages.update { null to result }
            }
        } catch (e: Exception) {
            _messages.update { (e.message ?: "Failed to send notification") to null }
        }
    }

    fun requestQuickAddTile() {
        platformSettingsHooks.requestQuickAddTile { message ->
            _messages.update { null to message }
        }
    }

    // --- Widget ---

    fun setWidgetSmartAdd(enabled: Boolean) {
        viewModelScope.launch {
            widgetPrefsStore.setSmartAdd(enabled)
            platformSettingsHooks.updateWidgets()
        }
    }

    fun setWidgetContextNav(enabled: Boolean) {
        viewModelScope.launch {
            widgetPrefsStore.setContextNav(enabled)
            platformSettingsHooks.updateWidgets()
        }
    }

    // --- Sync ---

    fun triggerSync() {
        platformSettingsHooks.triggerImmediateSync()
        _messages.update { null to "Sync started" }
    }

    fun retryFailedActions() {
        viewModelScope.launch {
            pendingActionDao.retryAllFailed()
            platformSettingsHooks.triggerImmediateSync()
            _messages.update { null to "Retrying failed actions" }
        }
    }

    fun clearFailedActions() {
        viewModelScope.launch {
            pendingActionDao.deleteFailed()
            // The rows those changes protected may differ from the server; refresh them fully.
            syncCursor.requestFullReconcile()
            platformSettingsHooks.triggerImmediateSync()
            _messages.update { null to "Failed actions cleared" }
        }
    }

    fun clearMessages() {
        _messages.update { null to null }
    }
}
