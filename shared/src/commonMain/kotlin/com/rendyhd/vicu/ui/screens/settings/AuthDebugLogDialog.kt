package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.auth.AuthDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AuthDebugLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // The log is a file: read it off the main thread. Null while it loads.
    val logText by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { AuthDebugLog.readLog() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auth Debug Log") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f),
            ) {
                val loaded = logText
                if (loaded != null) {
                    val vScroll = rememberScrollState(Int.MAX_VALUE) // scroll to bottom
                    val hScroll = rememberScrollState()
                    Text(
                        text = loaded,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = logText != null,
                onClick = { clipboardManager.setText(AnnotatedString(logText.orEmpty())) },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    AuthDebugLog.clear()
                    onDismiss()
                }) {
                    Text("Clear")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}
