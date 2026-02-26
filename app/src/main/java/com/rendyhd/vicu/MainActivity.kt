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
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.data.remote.interceptor.BaseUrlHolder
import com.rendyhd.vicu.domain.model.SharedContent
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
    private val _sharedContent = MutableStateFlow<SharedContent?>(null)

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
                    sharedContent = _sharedContent,
                    onSharedContentConsumed = { _sharedContent.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val taskId = intent.getLongExtra("task_id", 0L)
        if (taskId != 0L) {
            _initialTaskId.value = taskId
        }
        if (intent.getBooleanExtra("show_task_entry", false)) {
            _showTaskEntry.value = true
        }

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
            fileUris = listOfNotNull(streamUri),
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
            fileUris = uris,
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
