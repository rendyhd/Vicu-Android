package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.linreal.cascade.editor.action.ConvertBlockType
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle
import io.github.linreal.cascade.editor.registry.BlockCallbacks
import io.github.linreal.cascade.editor.richtext.LinkActions
import io.github.linreal.cascade.editor.richtext.LinkState
import io.github.linreal.cascade.editor.richtext.LinkTarget
import io.github.linreal.cascade.editor.richtext.LinkValidationError
import io.github.linreal.cascade.editor.richtext.LinkValidationResult
import io.github.linreal.cascade.editor.richtext.StyleStatus
import io.github.linreal.cascade.editor.state.EditorStateHolder
import io.github.linreal.cascade.editor.ui.CascadeEditorToolbarController

private val ToolbarShape = RoundedCornerShape(12.dp)

private data class FormattingButton(
    val icon: ImageVector,
    val label: String,
    val style: SpanStyle,
)

private val FormattingButtons = listOf(
    FormattingButton(Icons.Default.FormatBold, "Bold", SpanStyle.Bold),
    FormattingButton(Icons.Default.FormatItalic, "Italic", SpanStyle.Italic),
    FormattingButton(Icons.Default.FormatUnderlined, "Underline", SpanStyle.Underline),
    FormattingButton(Icons.Default.StrikethroughS, "Strikethrough", SpanStyle.StrikeThrough),
    FormattingButton(Icons.Default.Code, "Inline code", SpanStyle.InlineCode),
)

private data class BlockTypeButton(
    val icon: ImageVector,
    val label: String,
    val type: BlockType,
)

private val BlockTypeButtons = listOf(
    BlockTypeButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list", BlockType.BulletList),
    BlockTypeButton(Icons.Default.FormatListNumbered, "Numbered list", BlockType.NumberedList()),
    BlockTypeButton(Icons.Default.Checklist, "Checklist", BlockType.Todo()),
)

@Composable
internal fun DescriptionEditorToolbar(
    controller: CascadeEditorToolbarController,
    editorState: EditorStateHolder,
    callbacks: BlockCallbacks,
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatting = controller.formattingState.value
    val indentation = controller.indentationState.value
    val link = controller.linkState.value
    val focusedType = editorState.state.focusedBlock?.type
    val canConvert = !editorState.state.hasSelection &&
        editorState.state.dragState == null &&
        focusedType?.isConvertible == true
    val linkEditor = remember { DescriptionLinkEditorState() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ToolbarShape),
        shape = ToolbarShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormattingButtons.forEach { button ->
                    ToolbarButton(
                        icon = button.icon,
                        label = button.label,
                        status = formatting.styleStatusOf(button.style),
                        enabled = formatting.canFormat,
                        onClick = { controller.formattingActions.toggleStyle(button.style) },
                    )
                }

                ToolbarDivider()

                BlockTypeButtons.forEach { button ->
                    ToolbarButton(
                        icon = button.icon,
                        label = button.label,
                        status = focusedType.listStatus(button.type),
                        enabled = canConvert,
                        onClick = {
                            toggleFocusedBlockType(
                                editorState = editorState,
                                callbacks = callbacks,
                                requestedType = button.type,
                            )
                        },
                    )
                }

                ToolbarDivider()

                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                    label = "Decrease indent",
                    enabled = indentation.canIndentBackward,
                    onClick = controller.indentationActions::indentBackward,
                )
                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.FormatIndentIncrease,
                    label = "Increase indent",
                    enabled = indentation.canIndentForward,
                    onClick = controller.indentationActions::indentForward,
                )

                ToolbarDivider()

                ToolbarButton(
                    icon = Icons.Default.Link,
                    label = "Link",
                    status = when {
                        link.existingUrl != null -> StyleStatus.FullyActive
                        link.intersectsLink -> StyleStatus.Partial
                        else -> StyleStatus.Absent
                    },
                    enabled = link.canLink && link.target != null,
                    onClick = { linkEditor.open(link) },
                )
                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    label = "Undo",
                    enabled = editorState.canUndo,
                    onClick = editorState::undo,
                )
                ToolbarButton(
                    icon = Icons.AutoMirrored.Filled.Redo,
                    label = "Redo",
                    enabled = editorState.canRedo,
                    onClick = editorState::redo,
                )
            }
            ToolbarDivider()
            ToolbarButton(
                icon = Icons.Default.KeyboardHide,
                label = "Hide keyboard",
                enabled = editorState.state.focusedBlockId != null,
                onClick = onHideKeyboard,
            )
        }
    }

    if (linkEditor.visible) {
        DescriptionLinkDialog(
            state = linkEditor,
            linkActions = controller.linkActions,
        )
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    status: StyleStatus? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (status) {
        StyleStatus.FullyActive -> colors.primaryContainer
        StyleStatus.Partial -> colors.secondaryContainer
        StyleStatus.Absent, null -> Color.Transparent
    }
    val tint = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        status == StyleStatus.FullyActive -> colors.onPrimaryContainer
        status == StyleStatus.Partial -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .padding(4.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .focusProperties { canFocus = false }
            .semantics {
                contentDescription = label
                role = Role.Button
                if (status != null) {
                    if (status != StyleStatus.Partial) {
                        selected = status == StyleStatus.FullyActive
                    }
                    stateDescription = when (status) {
                        StyleStatus.FullyActive -> "On"
                        StyleStatus.Partial -> "Mixed"
                        StyleStatus.Absent -> "Off"
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun ToolbarDivider() {
    Spacer(modifier = Modifier.width(3.dp))
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Spacer(modifier = Modifier.width(3.dp))
}

private fun toggleFocusedBlockType(
    editorState: EditorStateHolder,
    callbacks: BlockCallbacks,
    requestedType: BlockType,
) {
    val state = editorState.state
    if (state.hasSelection || state.dragState != null) return
    val block = state.focusedBlock ?: return
    if (!block.type.isConvertible) return

    val target = if (block.type.matchesToolbarType(requestedType)) {
        BlockType.Paragraph
    } else {
        requestedType
    }
    callbacks.dispatch(ConvertBlockType(block.id, target))
}

private fun BlockType?.listStatus(requestedType: BlockType): StyleStatus =
    if (this?.matchesToolbarType(requestedType) == true) {
        StyleStatus.FullyActive
    } else {
        StyleStatus.Absent
    }

private fun BlockType.matchesToolbarType(other: BlockType): Boolean =
    (this == BlockType.BulletList && other == BlockType.BulletList) ||
        (this is BlockType.NumberedList && other is BlockType.NumberedList) ||
        (this is BlockType.Todo && other is BlockType.Todo)

@Stable
private class DescriptionLinkEditorState {
    var visible by mutableStateOf(false)
        private set
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var validationError by mutableStateOf<LinkValidationError?>(null)
        private set
    var canRemove by mutableStateOf(false)
        private set
    private var target by mutableStateOf<LinkTarget?>(null)

    fun open(linkState: LinkState) {
        val editingExistingLink =
            linkState.selectionCollapsed && linkState.existingLinkRange != null
        target = if (editingExistingLink) {
            linkState.existingLinkRange
        } else {
            linkState.target
        }
        url = linkState.existingUrl
            ?: linkState.targetText.takeIf(String::looksLikeUrl)
            ?: ""
        title = if (editingExistingLink) {
            linkState.existingLinkText ?: linkState.targetText
        } else {
            linkState.targetText
        }
        canRemove = linkState.existingUrl != null || linkState.intersectsLink
        validationError = null
        visible = target != null
    }

    fun apply(linkActions: LinkActions) {
        val capturedTarget = target ?: return dismiss()
        when (
            val result = linkActions.applyLink(
                target = capturedTarget,
                url = url,
                title = title.takeIf { it.isNotBlank() },
            )
        ) {
            is LinkValidationResult.Valid -> dismiss()
            is LinkValidationResult.Invalid -> validationError = result.error
        }
    }

    fun updateUrl(value: String) {
        url = value
        validationError = null
    }

    fun remove(linkActions: LinkActions) {
        target?.let(linkActions::removeLink)
        dismiss()
    }

    fun dismiss() {
        target = null
        validationError = null
        visible = false
    }
}

@Composable
private fun DescriptionLinkDialog(
    state: DescriptionLinkEditorState,
    linkActions: LinkActions,
) {
    AlertDialog(
        onDismissRequest = state::dismiss,
        title = { Text(if (state.canRemove) "Edit link" else "Add link") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = state::updateUrl,
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.validationError != null,
                    supportingText = state.validationError?.let {
                        {
                            Text(
                                when (it) {
                                    LinkValidationError.Blank -> "Enter a URL"
                                },
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { state.title = it },
                    label = { Text("Text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { state.apply(linkActions) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.canRemove) {
                    TextButton(onClick = { state.remove(linkActions) }) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = state::dismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

private fun String.looksLikeUrl(): Boolean {
    val candidate = trim()
    return candidate.isNotEmpty() &&
        candidate.none(Char::isWhitespace) &&
        (candidate.contains("://") || candidate.contains('.'))
}
