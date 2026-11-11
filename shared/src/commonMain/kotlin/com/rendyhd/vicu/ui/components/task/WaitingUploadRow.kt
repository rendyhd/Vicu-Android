package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.WaitingUpload
import com.rendyhd.vicu.util.WaitingUploadText

/**
 * A file attached while the server could not be reached, in the task's attachments: the copy's
 * thumbnail (dimmed) or a file icon with a clock, "Waiting to upload", and Remove. Refused by the
 * server, it says why in the error colour and offers Retry where retrying can help.
 */
@Composable
fun WaitingUploadRow(
    upload: WaitingUpload,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (upload.failed) Modifier.background(colors.errorContainer.copy(alpha = 0.45f)) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var thumbnailFailed by remember(upload.id) { mutableStateOf(false) }
        val showsImage = upload.isImage && upload.localPath != null && !thumbnailFailed
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics(mergeDescendants = true) { contentDescription = WaitingUploadText.description(upload) },
        ) {
            if (showsImage) {
                AsyncImage(
                    model = upload.localPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { thumbnailFailed = true },
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(0.55f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceContainer),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (upload.failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (upload.failed) colors.error else colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = upload.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = WaitingUploadText.status(upload),
                style = MaterialTheme.typography.bodySmall,
                color = if (upload.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (upload.failed && upload.canRetry) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove ${upload.fileName}", modifier = Modifier.size(18.dp))
        }
    }
}
