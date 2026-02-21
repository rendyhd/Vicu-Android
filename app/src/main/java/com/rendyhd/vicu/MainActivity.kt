package com.rendyhd.vicu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import com.rendyhd.vicu.ui.VicuApp
import com.rendyhd.vicu.ui.theme.VicuTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var baseUrlHolder: BaseUrlHolder
    @Inject lateinit var themePrefsStore: ThemePrefsStore

    private val _initialTaskId = MutableStateFlow<Long?>(null)
    private val _showTaskEntry = MutableStateFlow(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: we schedule alarms regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)
        requestNotificationPermission()

        lifecycleScope.launch {
            val storedUrl = authManager.getVikunjaUrl()
            if (!storedUrl.isNullOrBlank()) {
                baseUrlHolder.baseUrl = storedUrl
            }
            authManager.initialize()
        }

        setContent {
            val themeMode = themePrefsStore.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.System,
            )
            VicuTheme(themeMode = themeMode.value) {
                VicuApp(
                    authManager = authManager,
                    initialTaskId = _initialTaskId,
                    onInitialTaskConsumed = { _initialTaskId.value = null },
                    showTaskEntry = _showTaskEntry,
                    onShowTaskEntryConsumed = { _showTaskEntry.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val taskId = intent?.getLongExtra("task_id", 0L) ?: 0L
        if (taskId != 0L) {
            _initialTaskId.value = taskId
        }
        if (intent?.getBooleanExtra("show_task_entry", false) == true) {
            _showTaskEntry.value = true
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
