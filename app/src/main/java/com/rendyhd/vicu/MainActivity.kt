package com.rendyhd.vicu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rendyhd.vicu.auth.AuthDebugLog
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.data.remote.BaseUrlHolder
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.notification.DailySummaryScheduler
import com.rendyhd.vicu.ui.VicuApp
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.worker.PeriodicSyncScheduler
import com.rendyhd.vicu.worker.RoutineMaintenanceScheduler
import com.rendyhd.vicu.worker.TokenRefreshScheduler
import com.rendyhd.vicu.worker.SyncScheduler
import com.rendyhd.vicu.ui.theme.VicuTheme
import com.rendyhd.vicu.widget.WidgetUpdateScheduler
import org.koin.android.ext.android.inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_TASK_ENTRY = "show_task_entry"
        const val EXTRA_DEFAULT_PROJECT_ID = "default_project_id"
    }

    private val authManager: AuthManager by inject()
    private val baseUrlHolder: BaseUrlHolder by inject()
    private val themePrefsStore: ThemePrefsStore by inject()
    private val notificationPrefsStore: NotificationPrefsStore by inject()
    private val dailySummaryScheduler: DailySummaryScheduler by inject()

    private val _initialTaskId = MutableStateFlow<Long?>(null)
    private val _showTaskEntry = MutableStateFlow(false)
    private val _showTaskEntryProjectId = MutableStateFlow<Long?>(null)
    private val _navigateToView = MutableStateFlow<ViewTarget?>(null)
    private val _sharedContent = MutableStateFlow<SharedContent?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: we schedule alarms regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AuthDebugLog.init(applicationContext)
        AuthDebugLog.lifecycle("onCreate (savedState=${savedInstanceState != null})")

        // The launch intent is still the activity's intent after a rotation or a restore. Handling
        // it again would reopen a share, a task or a widget "add" the user had already finished
        // with; what was in progress comes back through the saved state instead.
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermission()

        lifecycleScope.launch {
            val storedUrl = authManager.getVikunjaUrl()
            if (!storedUrl.isNullOrBlank()) {
                baseUrlHolder.baseUrl = storedUrl
            }
            authManager.initialize()
            // Schedule periodic token refresh if authenticated
            if (authManager.authState.value == com.rendyhd.vicu.auth.AuthState.Authenticated) {
                TokenRefreshScheduler.schedule(this@MainActivity)
                SyncScheduler.enqueueWhenOnline(this@MainActivity)
                PeriodicSyncScheduler.schedule(this@MainActivity)
            }
        }

        // A sync that stopped because the session ended (401 after a failed token refresh) left
        // the queued changes pending; signing in again resumes it.
        lifecycleScope.launch {
            var neededReAuth = false
            var signedOut = false
            var ensuredAtStart = false
            authManager.authState.collect { state ->
                if (state == AuthState.Authenticated && !ensuredAtStart) {
                    // A cheap check on every start: a summary queued already is left alone.
                    ensuredAtStart = true
                    dailySummaryScheduler.ensureScheduled(notificationPrefsStore.getPrefs().first())
                }
                if (state == AuthState.Authenticated && neededReAuth) {
                    SyncScheduler.enqueueImmediate(this@MainActivity)
                }
                if (state == AuthState.Authenticated && signedOut) {
                    // Sign-out cancelled the daily summaries, the routine maintenance and the periodic sync.
                    // A device that started signed out never scheduled the widget refresh either.
                    RoutineMaintenanceScheduler.schedule(this@MainActivity)
                    WidgetUpdateScheduler.schedulePeriodicRefresh(this@MainActivity)
                    PeriodicSyncScheduler.schedule(this@MainActivity)
                    dailySummaryScheduler.scheduleFromPrefs(notificationPrefsStore.getPrefs().first())
                }
                neededReAuth = state == AuthState.NeedsReAuth
                signedOut = state == AuthState.Unauthenticated
            }
        }

        setContent {
            val themeMode = themePrefsStore.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.System,
            )
            val useDeviceColors = themePrefsStore.useDeviceColors.collectAsStateWithLifecycle(
                initialValue = true,
            )
            VicuTheme(themeMode = themeMode.value, dynamicColor = useDeviceColors.value) {
                VicuApp(
                    authManager = authManager,
                    initialTaskId = _initialTaskId,
                    onInitialTaskConsumed = { _initialTaskId.value = null },
                    showTaskEntry = _showTaskEntry,
                    showTaskEntryProjectId = _showTaskEntryProjectId,
                    onShowTaskEntryConsumed = {
                        _showTaskEntry.value = false
                        _showTaskEntryProjectId.value = null
                    },
                    navigateToView = _navigateToView,
                    onNavigateToViewConsumed = { _navigateToView.value = null },
                    sharedContent = _sharedContent,
                    onSharedContentConsumed = { _sharedContent.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AuthDebugLog.lifecycle("onNewIntent")
        handleIntent(intent)
        // Re-check auth state — JWT may have expired while app was in background
        lifecycleScope.launch { authManager.initialize() }
    }

    override fun onResume() {
        super.onResume()
        AuthDebugLog.lifecycle("onResume (authState=${authManager.authState.value})")
        if (authManager.authState.value == com.rendyhd.vicu.auth.AuthState.Authenticated) {
            SyncScheduler.enqueueWhenOnline(this)
        }
    }

    override fun onStop() {
        super.onStop()
        AuthDebugLog.lifecycle("onStop (authState=${authManager.authState.value})")
    }

    override fun onDestroy() {
        AuthDebugLog.lifecycle("onDestroy (isFinishing=$isFinishing)")
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val taskId = intent.getLongExtra("task_id", 0L)
        if (taskId != 0L) {
            _initialTaskId.value = taskId
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TASK_ENTRY, false)) {
            _showTaskEntry.value = true
            val projectId = intent.getLongExtra(EXTRA_DEFAULT_PROJECT_ID, 0L)
            if (projectId != 0L) {
                _showTaskEntryProjectId.value = projectId
            }
        }

        intent.viewTargetOrNull()?.let { _navigateToView.value = it }

        when (intent.action) {
            Intent.ACTION_SEND -> handleSendIntent(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultipleIntent(intent)
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        grantUriReadPermission(streamUri)

        _sharedContent.value = SharedContent(
            text = text,
            subject = subject,
            fileUris = listOfNotNull(streamUri).map { it.toString() },
            mimeType = intent.type,
        )
    }

    private fun handleSendMultipleIntent(intent: Intent) {
        val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        } ?: emptyList()

        for (uri in uris) {
            grantUriReadPermission(uri)
        }

        _sharedContent.value = SharedContent(
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            fileUris = uris.map { it.toString() },
            mimeType = intent.type,
        )
    }

    private fun grantUriReadPermission(uri: Uri?) {
        uri ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Not all URIs support persistable permissions — that's OK,
            // the temporary grant from the share intent is sufficient.
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
