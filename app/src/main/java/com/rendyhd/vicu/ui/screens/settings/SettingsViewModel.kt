package com.rendyhd.vicu.ui.screens.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.R
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.SecureTokenStorage
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefs
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.SyntaxMode
import com.rendyhd.vicu.data.local.VikunjaDatabase
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.notification.NotificationChannelManager
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val projects: List<Project> = emptyList(),
    // Notifications
    val notificationPrefs: NotificationPrefs = NotificationPrefs(),
    // Sync
    val pendingActionCount: Int = 0,
    val failedActionCount: Int = 0,
    val isOnline: Boolean = true,
    // Messages
    val error: String? = null,
    val successMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authManager: AuthManager,
    private val tokenStorage: SecureTokenStorage,
    private val labelRepository: LabelRepository,
    private val projectRepository: ProjectRepository,
    private val customListStore: CustomListStore,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val themePrefsStore: ThemePrefsStore,
    private val nlpPrefsStore: NlpPrefsStore,
    private val dailySummaryScheduler: DailySummaryScheduler,
    private val pendingActionDao: PendingActionDao,
    private val networkMonitor: NetworkMonitor,
    private val database: VikunjaDatabase,
    private val apiService: dagger.Lazy<VikunjaApiService>,
) : ViewModel() {

    private val _messages = MutableStateFlow<Pair<String?, String?>>(null to null)
    private val _userInfo = MutableStateFlow(Triple("", "", "")) // username, email, authMethod
    private val _vikunjaUrl = MutableStateFlow("")
    private val _inboxProjectId = MutableStateFlow<Long?>(null)

    init {
        loadAccountInfo()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            labelRepository.getAll(),
            customListStore.getAll(),
            projectRepository.getAll(),
            notificationPrefsStore.getPrefs(),
            _messages,
        ) { labels, customLists, projects, notifPrefs, messages ->
            listOf(labels, customLists, projects, notifPrefs, messages)
        },
        combine(
            pendingActionDao.getPendingCount(),
            pendingActionDao.getFailedCount(),
            networkMonitor.isOnline,
            themePrefsStore.themeMode,
            nlpPrefsStore.config,
        ) { pendingCount, failedCount, isOnline, themeMode, nlpConfig ->
            listOf(pendingCount, failedCount, isOnline, themeMode, nlpConfig)
        },
        _userInfo,
        _vikunjaUrl,
        _inboxProjectId,
    ) { base, syncTheme, userInfo, url, inboxId ->
        @Suppress("UNCHECKED_CAST")
        val labels = base[0] as List<Label>
        val customLists = base[1] as List<CustomList>
        val projects = base[2] as List<Project>
        val notifPrefs = base[3] as NotificationPrefs
        val messages = base[4] as Pair<String?, String?>
        val pendingCount = syncTheme[0] as Int
        val failedCount = syncTheme[1] as Int
        val isOnline = syncTheme[2] as Boolean
        val themeMode = syncTheme[3] as ThemeMode
        val nlpConfig = syncTheme[4] as ParserConfig
        SettingsUiState(
            username = userInfo.first,
            email = userInfo.second,
            authMethod = userInfo.third,
            vikunjaUrl = url,
            inboxProjectId = inboxId,
            themeMode = themeMode,
            nlpConfig = nlpConfig,
            labels = labels.sortedBy { it.title.lowercase() },
            customLists = customLists,
            projects = projects.filter { !it.isArchived },
            notificationPrefs = notifPrefs,
            pendingActionCount = pendingCount,
            failedActionCount = failedCount,
            isOnline = isOnline,
            error = messages.first,
            successMessage = messages.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private fun loadAccountInfo() {
        viewModelScope.launch {
            val authMethod = tokenStorage.getAuthMethod() ?: ""
            val url = tokenStorage.getVikunjaUrl() ?: ""
            val inboxId = tokenStorage.getInboxProjectId()
            _vikunjaUrl.value = url
            _inboxProjectId.value = inboxId
            _userInfo.update { it.copy(third = authMethod) }

            // Fetch user info from API
            try {
                val user = apiService.get().getCurrentUser()
                _userInfo.value = Triple(
                    user.username.ifBlank { user.name },
                    user.email,
                    authMethod,
                )
            } catch (_: Exception) {
                // Offline or failed — keep empty
            }
        }
    }

    // --- Theme ---

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePrefsStore.setThemeMode(mode)
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

    // --- Inbox project ---

    fun setInboxProject(projectId: Long) {
        viewModelScope.launch {
            authManager.onInboxProjectSelected(projectId)
            _inboxProjectId.value = projectId
            _messages.update { null to "Inbox project updated" }
        }
    }

    // --- Logout ---

    fun logout() {
        viewModelScope.launch {
            database.clearAllTables()
            authManager.logout()
        }
    }

    // --- Clear cache & re-sync ---

    fun clearCacheAndResync() {
        viewModelScope.launch {
            database.clearAllTables()
            SyncScheduler.enqueueImmediate(appContext)
            _messages.update { null to "Cache cleared, syncing..." }
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

    // --- Custom Lists ---

    fun saveCustomList(customList: CustomList) {
        viewModelScope.launch {
            customListStore.save(customList)
            _messages.update { null to "List saved" }
        }
    }

    fun deleteCustomList(id: String) {
        viewModelScope.launch {
            customListStore.delete(id)
            _messages.update { null to "List deleted" }
        }
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
            dailySummaryScheduler.scheduleIfEnabled(enabled, prefs.dailySummaryHour, prefs.dailySummaryMinute)
        }
    }

    fun setDailySummaryTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            notificationPrefsStore.setDailySummaryTime(hour, minute)
            val prefs = uiState.value.notificationPrefs
            if (prefs.dailySummaryEnabled) {
                dailySummaryScheduler.schedule(hour, minute)
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPrefsStore.setSoundEnabled(enabled)
        }
    }

    fun sendTestNotification() {
        val tapIntent = Intent(appContext, com.rendyhd.vicu.MainActivity::class.java)
        val tapPending = PendingIntent.getActivity(
            appContext,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            appContext,
            NotificationChannelManager.CHANNEL_TASK_REMINDERS,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test Notification")
            .setContentText("Task reminders are working!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(888_888, notification)
            _messages.update { null to "Test notification sent" }
        } catch (_: SecurityException) {
            _messages.update { "Notification permission not granted" to null }
        }
    }

    // --- Sync ---

    fun triggerSync() {
        SyncScheduler.enqueueImmediate(appContext)
        _messages.update { null to "Sync started" }
    }

    fun retryFailedActions() {
        viewModelScope.launch {
            pendingActionDao.retryAllFailed()
            SyncScheduler.enqueueImmediate(appContext)
            _messages.update { null to "Retrying failed actions" }
        }
    }

    fun clearFailedActions() {
        viewModelScope.launch {
            pendingActionDao.deleteFailed()
            _messages.update { null to "Failed actions cleared" }
        }
    }

    fun clearMessages() {
        _messages.update { null to null }
    }
}
