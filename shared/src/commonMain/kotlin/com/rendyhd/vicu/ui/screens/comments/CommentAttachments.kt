package com.rendyhd.vicu.ui.screens.comments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.ui.rememberFilePicker
import com.rendyhd.vicu.ui.rememberImagePicker
import com.rendyhd.vicu.util.CommentSegment
import com.rendyhd.vicu.util.formatByteSize

/** What a comment's attachments need from the screen. */
internal class CommentFiles(
    val taskId: Long,
    private val known: Map<Long, Attachment>,
    private val read: Boolean,
    val downloading: Set<Long>,
    val onOpen: (CommentSegment.Attachment) -> Unit,
    val onSave: (CommentSegment.Attachment, ((String) -> Unit)?) -> Unit,
    val onShare: (CommentSegment.Attachment, ((String) -> Unit)?) -> Unit,
) {
    /** The name to show: the file's own when the task's list holds it, else what the comment says. */
    fun nameOf(segment: CommentSegment.Attachment): String =
        known[segment.attachmentId]?.fileName?.takeIf { it.isNotBlank() }
            ?: segment.name.ifBlank { if (segment.image) "Image" else "File" }

    fun sizeOf(segment: CommentSegment.Attachment): Long? = known[segment.attachmentId]?.fileSize?.takeIf { it > 0 }

    /** The server just listed the task's attachments and this one is not among them. */
    fun isGone(segment: CommentSegment.Attachment): Boolean = read && segment.attachmentId !in known
}

/** The height of a preview, set before the image arrives so the thread does not move when it does. */
private val PREVIEW_HEIGHT = 200.dp

/**
 * An image in a comment: a preview from the server at a fixed height, as wide as the image needs. A
 * tap opens it full screen. One that cannot be shown (offline, not really an image) is a file, and so
 * is one the task no longer has: asked first, because the image loader would still have it in its cache.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommentImage(
    segment: CommentSegment.Attachment,
    files: CommentFiles,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    var failed by remember(segment.attachmentId) { mutableStateOf(false) }
    var ratio by remember(segment.attachmentId) { mutableFloatStateOf(0f) }
    if (failed || files.isGone(segment)) {
        CommentFile(segment, files, onLongPress)
        return
    }
    val name = files.nameOf(segment)
    val shape = RoundedCornerShape(12.dp)
    val width = if (ratio > 0f) Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth()
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .height(PREVIEW_HEIGHT)
            .then(width)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClickLabel = "Open image",
                onClick = onOpen,
                onLongClickLabel = if (onLongPress != null) "Show comment actions" else null,
                onLongClick = onLongPress,
            ),
    ) {
        AsyncImage(
            // VikunjaImages rewrites the placeholder host to the server and adds the sign-in.
            model = "http://localhost/tasks/${files.taskId}/attachments/${segment.attachmentId}?preview_size=xl",
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.isSpecified && size.height > 0f) ratio = size.width / size.height
            },
            onError = { failed = true },
        )
    }
}

/** A file in a comment: its name and size. A tap opens it in another app; Save and Share beside it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommentFile(
    segment: CommentSegment.Attachment,
    files: CommentFiles,
    onLongPress: (() -> Unit)?,
) {
    val name = files.nameOf(segment)
    val gone = files.isGone(segment)
    val downloading = segment.attachmentId in files.downloading
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val detail = when {
        gone -> "No longer available"
        else -> files.sizeOf(segment)?.let(::formatByteSize)
    }
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .combinedClickable(
                enabled = !gone && !downloading,
                onClickLabel = "Open $name",
                onClick = { files.onOpen(segment) },
                onLongClickLabel = if (onLongPress != null) "Show comment actions" else null,
                onLongClick = onLongPress,
            )
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (segment.image) Icons.Outlined.BrokenImage else Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (gone) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
        when {
            gone -> Unit
            downloading -> CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else -> {
                IconButton(onClick = { files.onSave(segment, null) }) {
                    Icon(Icons.Default.Download, contentDescription = "Save $name", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { files.onShare(segment, null) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share $name", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** The composer's paperclip: a photo or any file. */
@Composable
internal fun AttachButton(onPicked: (String) -> Unit) {
    val pickImage = rememberImagePicker(onPicked)
    val pickFile = rememberFilePicker(onPicked)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach a photo or file")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Photo") },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                onClick = {
                    open = false
                    pickImage()
                },
            )
            DropdownMenuItem(
                text = { Text("File") },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                onClick = {
                    open = false
                    pickFile()
                },
            )
        }
    }
}

/** Offered while the clipboard holds an image that has not been pasted yet. */
@Composable
internal fun PasteImageChip(onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text("Paste image") },
        leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.height(32.dp),
    )
}

/** The files added to the comment being written: uploading with a spinner, a tap on the chip removes one. */
@Composable
internal fun StagedAttachments(
    staged: List<StagedAttachment>,
    onRemove: (Long) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        staged.forEach { item ->
            InputChip(
                selected = false,
                onClick = { onRemove(item.key) },
                label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                avatar = {
                    if (item.attachment == null) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Remove ${item.name}", modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.height(32.dp),
            )
        }
        trailing()
    }
}
