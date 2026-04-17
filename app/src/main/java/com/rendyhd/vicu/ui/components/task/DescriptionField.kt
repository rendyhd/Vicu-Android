package com.rendyhd.vicu.ui.components.task

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.util.ImageTokens

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DescriptionField(
    value: String,
    onValueChange: (String) -> Unit,
    taskId: Long,
    isUploadingImage: Boolean,
    onAddImageClick: () -> Unit,
    onRemoveImageAttachment: (attachmentId: Long) -> Unit,
    onImagePasted: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    pendingImages: Map<String, Uri> = emptyMap(),
    onRemovePending: (uuid: String) -> Unit = {},
) {
    val (externalText, allImageRefs) = remember(value) { ImageTokens.parseValue(value) }

    val textFieldState = rememberTextFieldState(externalText)

    // Sync ViewModel-driven changes (e.g., token appended after upload) into the field.
    LaunchedEffect(externalText) {
        val current = textFieldState.text.toString()
        if (current != externalText) {
            textFieldState.edit { replace(0, length, externalText) }
        }
    }

    // Propagate user typing back to the ViewModel.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentImageRefs by rememberUpdatedState(allImageRefs)
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { typed ->
                currentOnValueChange(ImageTokens.buildValue(typed, currentImageRefs))
            }
    }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val imageRefs = allImageRefs.filterIsInstance<ImageTokens.ImageRef.Image>()

    val onImagePastedState by rememberUpdatedState(onImagePasted)
    val pasteListener = remember {
        object : ReceiveContentListener {
            override fun onReceive(content: TransferableContent): TransferableContent? {
                if (!content.hasMediaType(MediaType.Image)) return content
                return content.consume { item ->
                    val uri = item.uri
                    if (uri != null) {
                        onImagePastedState(uri)
                        true
                    } else false
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            state = textFieldState,
            placeholder = { Text("Add notes") },
            modifier = Modifier
                .fillMaxWidth()
                .contentReceiver(pasteListener),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
        )

        if (allImageRefs.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                allImageRefs.forEach { ref ->
                    when (ref) {
                        is ImageTokens.ImageRef.Image -> {
                            val index = imageRefs.indexOf(ref)
                            ImageThumb(
                                taskId = taskId,
                                attachmentId = ref.attachmentId,
                                onClick = { viewerIndex = index },
                                onRemove = {
                                    val next = allImageRefs.filterNot {
                                        it is ImageTokens.ImageRef.Image && it.attachmentId == ref.attachmentId
                                    }
                                    currentOnValueChange(
                                        ImageTokens.buildValue(textFieldState.text.toString(), next),
                                    )
                                    onRemoveImageAttachment(ref.attachmentId)
                                },
                            )
                        }
                        is ImageTokens.ImageRef.Pending -> {
                            val uri = pendingImages[ref.uuid]
                            if (uri != null) {
                                PendingThumb(
                                    uri = uri,
                                    onRemove = {
                                        val next = allImageRefs.filterNot {
                                            it is ImageTokens.ImageRef.Pending && it.uuid == ref.uuid
                                        }
                                        currentOnValueChange(
                                            ImageTokens.buildValue(textFieldState.text.toString(), next),
                                        )
                                        onRemovePending(ref.uuid)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onAddImageClick) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add image")
        }

        if (isUploadingImage) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Uploading image…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    viewerIndex?.let { idx ->
        ImageViewerDialog(
            taskId = taskId,
            images = imageRefs,
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }
}

@Composable
private fun ImageThumb(
    taskId: Long,
    attachmentId: Long,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Box {
        // BaseUrlInterceptor rewrites localhost → real server + `/api/v1/` prefix.
        AsyncImage(
            model = "http://localhost/tasks/$taskId/attachments/$attachmentId",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .heightIn(min = 80.dp, max = 160.dp)
                .width(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        )
        RemoveBadge(onClick = onRemove)
    }
}

@Composable
private fun PendingThumb(
    uri: Uri,
    onRemove: () -> Unit,
) {
    Box {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .heightIn(min = 80.dp, max = 160.dp)
                .width(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        RemoveBadge(onClick = onRemove)
    }
}

@Composable
private fun BoxScope.RemoveBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Remove image",
            tint = Color.White,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(3.dp),
        )
    }
}
