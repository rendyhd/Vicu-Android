package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.ui.imagePasteReceiver
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.Logger
import io.github.linreal.cascade.editor.action.ClearFocus
import io.github.linreal.cascade.editor.registry.DefaultBlockCallbacks
import io.github.linreal.cascade.editor.theme.CascadeEditorDimensions
import io.github.linreal.cascade.editor.theme.CascadeEditorStrings
import io.github.linreal.cascade.editor.theme.CascadeEditorTheme
import io.github.linreal.cascade.editor.ui.CascadeEditor
import io.github.linreal.cascade.editor.ui.CascadeEditorConfig
import io.github.linreal.cascade.editor.ui.LinkPopupSlot
import io.github.linreal.cascade.editor.ui.SlashCommandSlot
import io.github.linreal.cascade.editor.ui.ToolbarSlot
import io.github.linreal.cascade.editor.ui.rememberCascadeEditorToolbarController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
    FlowPreview::class,
)
@Composable
internal fun DescriptionField(
    value: String,
    onValueChange: (String) -> Unit,
    taskId: Long,
    isUploadingImage: Boolean,
    onAddImageClick: () -> Unit,
    onRemoveImageAttachment: (attachmentId: Long) -> Unit,
    onImagePasted: (String) -> Unit,
    editorController: DescriptionEditorController,
    modifier: Modifier = Modifier,
    pendingImages: Map<String, String> = emptyMap(),
    onRemovePending: (uuid: String) -> Unit = {},
) {
    val (externalHtml, allImageRefs) = remember(value) { ImageTokens.parseValue(value) }
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val latestImageRefs = rememberUpdatedState(allImageRefs)
    val latestOnImagePasted = rememberUpdatedState(onImagePasted)
    val publishHtml = rememberUpdatedState<(String) -> Unit> { html ->
        latestOnValueChange.value(
            ImageTokens.buildValue(
                text = html,
                images = latestImageRefs.value,
            ),
        )
    }

    val session = remember(taskId) {
        DescriptionEditorSession(
            initialHtml = externalHtml,
            warningReporter = { message ->
                Logger.w("CascadeDescription", message)
            },
        )
    }
    LaunchedEffect(session, externalHtml) {
        session.syncExternalHtml(externalHtml)
        session.flush(publishHtml.value)
    }
    LaunchedEffect(session) {
        snapshotFlow { session.resolveBlocks() }
            .distinctUntilChanged()
            .debounce(150)
            .collect { blocks ->
                session.publishResolvedIfChanged(
                    blocks = blocks,
                    onHtmlChange = publishHtml.value,
                )
            }
    }

    val editorConfig = remember {
        CascadeEditorConfig(
            blockSelectionEnabled = false,
            blockDraggingEnabled = false,
            emptyDocumentPlaceholderEnabled = true,
            onInternalError = { error ->
                Logger.e(
                    tag = "CascadeDescription",
                    msg = "Contained editor failure (${error.context})",
                    tr = error.cause,
                )
            },
        )
    }
    val toolbarController = rememberCascadeEditorToolbarController(
        stateHolder = session.stateHolder,
        textStates = session.textStates,
        spanStates = session.spanStates,
        config = editorConfig,
    )
    val callbacks = remember(session) {
        DefaultBlockCallbacks(
            dispatchFn = session.stateHolder::dispatch,
            stateProvider = { session.stateHolder.state },
            textStates = session.textStates,
            spanStates = session.spanStates,
            stateHolder = session.stateHolder,
        )
    }
    val focusManager = LocalFocusManager.current
    val clearEditorFocus = remember(session, focusManager) {
        {
            session.stateHolder.dispatch(ClearFocus)
            focusManager.clearFocus()
        }
    }
    val editorFocused by remember(session) {
        derivedStateOf {
            !session.isImportBlocked && session.stateHolder.state.focusedBlockId != null
        }
    }
    SideEffect {
        editorController.bind(
            owner = session,
            flushAction = { session.flush(publishHtml.value) },
            clearFocusAction = clearEditorFocus,
            isFocused = editorFocused,
        )
        if (session.isImportBlocked) editorController.updateEditorBounds(null)
    }
    DisposableEffect(session, editorController) {
        onDispose {
            // Lazy containers may dispose this field while it is off-screen
            session.flush(publishHtml.value)
            editorController.unbind(session)
        }
    }
    val editorTheme = vicuCascadeEditorTheme()
    val editorStrings = remember {
        CascadeEditorStrings.default().copy(
            emptyDocumentPlaceholder = "Add notes",
        )
    }
    val editorRegistry = remember { createVikunjaDescriptionEditorRegistry() }

    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val imageRefs = allImageRefs.filterIsInstance<ImageTokens.ImageRef.Image>()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Description",
            style = MaterialTheme.typography.labelMedium,
            color = if (editorFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (session.isImportBlocked) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.error,
                        RoundedCornerShape(12.dp),
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null)
                    Column {
                        Text(
                            text = "Description too large to edit",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "The original description will be kept unchanged.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 144.dp, max = 210.dp)
                    .onGloballyPositioned {
                        editorController.updateEditorBounds(it.boundsInRoot())
                    }
                    .border(
                        width = 1.dp,
                        color = if (editorFocused) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(12.dp),
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CascadeEditor(
                    stateHolder = session.stateHolder,
                    textStates = session.textStates,
                    spanStates = session.spanStates,
                    registry = editorRegistry,
                    theme = editorTheme,
                    strings = editorStrings,
                    toolbar = ToolbarSlot.None,
                    slashCommand = SlashCommandSlot.None,
                    linkPopup = LinkPopupSlot.None,
                    config = editorConfig,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .imagePasteReceiver { uri ->
                            editorController.flush()
                            latestOnImagePasted.value(uri)
                        },
                )
            }
        }

        AnimatedVisibility(
            visible = editorFocused,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            DescriptionEditorToolbar(
                controller = toolbarController,
                editorState = session.stateHolder,
                callbacks = callbacks,
                onHideKeyboard = clearEditorFocus,
                modifier = Modifier.onGloballyPositioned {
                    editorController.updateToolbarBounds(it.boundsInRoot())
                },
            )
        }

        if (allImageRefs.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (ref in allImageRefs) {
                    when (ref) {
                        is ImageTokens.ImageRef.Image -> {
                            val index = imageRefs.indexOf(ref)
                            ImageThumb(
                                taskId = taskId,
                                attachmentId = ref.attachmentId,
                                onClick = { viewerIndex = index },
                                onRemove = {
                                    val next = allImageRefs.filterNot {
                                        it is ImageTokens.ImageRef.Image &&
                                            it.attachmentId == ref.attachmentId
                                    }
                                    latestOnValueChange.value(
                                        ImageTokens.buildValue(
                                            text = session.currentHtmlForHost(),
                                            images = next,
                                        ),
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
                                            it is ImageTokens.ImageRef.Pending &&
                                                it.uuid == ref.uuid
                                        }
                                        latestOnValueChange.value(
                                            ImageTokens.buildValue(
                                                text = session.currentHtmlForHost(),
                                                images = next,
                                            ),
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

        TextButton(
            onClick = {
                editorController.flush()
                onAddImageClick()
            },
        ) {
            Icon(
                Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add image")
        }

        if (isUploadingImage) {
            Row(
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
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

    viewerIndex?.let { index ->
        ImageViewerDialog(
            taskId = taskId,
            images = imageRefs,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

@Composable
private fun vicuCascadeEditorTheme(): CascadeEditorTheme {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val isDark = scheme.background.luminance() < 0.5f
    val base = if (isDark) CascadeEditorTheme.dark() else CascadeEditorTheme.light()

    return remember(scheme, typography, isDark) {
        base.copy(
            colors = base.colors.copy(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                text = scheme.onSurface,
                contentDivider = scheme.outlineVariant,
                inlineCodeBackground = scheme.surfaceContainerHighest,
                highlight = scheme.tertiaryContainer,
                cursor = scheme.primary,
                textSelectionBackground = scheme.primary.copy(alpha = 0.32f),
                quoteBorder = scheme.outline,
                quoteBackground = scheme.surfaceContainer,
                selectionOverlay = scheme.primary.copy(alpha = 0.12f),
                linkText = scheme.primary,
                error = scheme.error,
                codeBlockBackground = scheme.surfaceContainer,
                placeholderText = scheme.onSurfaceVariant,
            ),
            typography = base.typography.copy(
                body = typography.bodyLarge,
                heading1 = typography.headlineLarge,
                heading2 = typography.headlineMedium,
                heading3 = typography.headlineSmall,
                heading4 = typography.titleLarge,
                heading5 = typography.titleMedium,
                heading6 = typography.titleSmall,
                code = base.typography.code.copy(
                    fontSize = typography.bodyMedium.fontSize,
                ),
            ),
            dimensions = CascadeEditorDimensions(
                indentUnit = 20.dp,
                blockHorizontalPadding = 8.dp,
            ),
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
        // BaseUrlInterceptor rewrites localhost → real server + `/api/v2/` prefix.
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
    uri: String,
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
