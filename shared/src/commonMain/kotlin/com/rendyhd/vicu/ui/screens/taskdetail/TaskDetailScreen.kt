package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.activity.compose.BackHandler
import com.rendyhd.vicu.ui.rememberImagePicker
import com.rendyhd.vicu.ui.rememberFilePicker
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.RelationTaskPickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.picker.RecurrencePickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.ui.components.task.DescriptionField
import com.rendyhd.vicu.ui.components.task.clearDescriptionEditorFocusOnHostTap
import com.rendyhd.vicu.ui.components.task.rememberDescriptionEditorController
import com.rendyhd.vicu.ui.components.task.NlpAutocompleteDropdown
import com.rendyhd.vicu.ui.components.task.NlpVisualTransformation
import com.rendyhd.vicu.ui.components.task.ParseChipRow
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.descendantsDepthFirst
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.ReminderFormat
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.parser.getPrefixes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showPriorityPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }
    var subtaskInput by remember { mutableStateOf("") }
    var showSubtaskInput by remember { mutableStateOf(false) }
    var showRelationPicker by remember { mutableStateOf(false) }
    val relationSearchResults by viewModel.relationSearchResults.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    var titleFieldValue by remember(taskId) {
        mutableStateOf(TextFieldValue(state.task?.title.orEmpty()))
    }
    val descriptionEditorController = rememberDescriptionEditorController()
    val dismissEditor = {
        descriptionEditorController.flush()
        if (state.descriptionConflict == null) {
            onDismiss()
        } else {
            viewModel.requireDescriptionConflictResolution()
        }
    }

    var attachmentPendingDelete by remember { mutableStateOf<Attachment?>(null) }

    val filePickerLauncher = rememberFilePicker(viewModel::uploadAttachment)
    val imagePickerLauncher = rememberImagePicker(viewModel::addImageAttachment)

    LaunchedEffect(state.task?.title) {
        val title = state.task?.title ?: return@LaunchedEffect
        if (titleFieldValue.text != title) {
            titleFieldValue = titleFieldValue.copy(text = title)
        }
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onDismiss()
    }

    // The view model also saves on its own shortly after the last edit. These two are the final
    // saves: going to the background (the process can be killed there without the screen ever
    // leaving composition) and closing the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        descriptionEditorController.flush()
        viewModel.saveIfChanged()
    }

    // Closing the screen (back, the close icon, or state.isDeleted) leaves composition and fires
    // this exactly once.
    DisposableEffect(Unit) {
        onDispose {
            descriptionEditorController.flush()
            viewModel.saveIfChanged()
        }
    }

    // Full-screen instead of a ModalBottomSheet: the M3 sheet's anchored-drag has a nested-scroll
    // anchor-recovery bug (issuetracker.google.com/issues/486562294, fixed only in alpha Compose)
    // that made a scrollable child shake/spring on drag. A plain screen has no drag-to-dismiss, so
    // the whole class of bugs is gone. Dismiss via the close icon or system back.
    BackHandler(onBack = dismissEditor)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clearDescriptionEditorFocusOnHostTap(descriptionEditorController),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit task") },
                    navigationIcon = {
                        IconButton(onClick = dismissEditor) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { innerPadding ->
            val task = state.task

            if (state.isLoading || task == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            val imageTokenIds = remember(task.description) {
                ImageTokens.findImageRefs(task.description)
                    .filterIsInstance<ImageTokens.ImageRef.Image>()
                    .map { it.attachmentId }
                    .toSet()
            }
            val visibleAttachments = state.attachments.filter {
                it.id !in imageTokenIds && it.id !in state.deletingAttachmentIds
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // Title
            item(key = "title") {
                var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                Column {
                    Box {
                        OutlinedTextField(
                            value = titleFieldValue,
                            onValueChange = { newValue ->
                                titleFieldValue = newValue
                                viewModel.updateTitle(newValue.text)
                            },
                            placeholder = { Text("Task title") },
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { fieldSize = it },
                            textStyle = MaterialTheme.typography.titleMedium,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                            visualTransformation = NlpVisualTransformation(
                                tokens = state.parseResult?.tokens ?: emptyList(),
                                isDarkTheme = isDarkTheme,
                            ),
                        )

                        NlpAutocompleteDropdown(
                            inputValue = titleFieldValue.text,
                            cursorPosition = titleFieldValue.selection.start,
                            prefixes = getPrefixes(state.parserConfig.syntaxMode),
                            projects = state.allProjects,
                            labels = state.allLabels,
                            enabled = state.parserConfig.enabled,
                            onSelect = { newText, newCursor ->
                                titleFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursor),
                                )
                                viewModel.updateTitle(newText)
                            },
                            anchorSize = fieldSize,
                        )
                    }

                    val parseResult = state.parseResult
                    if (parseResult != null && parseResult.tokens.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ParseChipRow(
                            parseResult = parseResult,
                            isDarkTheme = isDarkTheme,
                            onDismiss = viewModel::suppressType,
                        )
                    }
                }
            }

            // Description
            item(key = "description") {
                DescriptionField(
                    value = task.description,
                    onValueChange = viewModel::updateDescription,
                    taskId = task.id,
                    isUploadingImage = state.isUploadingImage,
                    onAddImageClick = imagePickerLauncher,
                    onRemoveImageAttachment = viewModel::deleteAttachment,
                    onImagePasted = viewModel::addImageAttachment,
                    editorController = descriptionEditorController,
                )
                if (state.descriptionConflict != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Description changed on another device",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Your draft is still here. Choose which version should be saved.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = viewModel::useRemoteDescription) {
                                    Text("Use server")
                                }
                                Button(onClick = viewModel::keepLocalDescription) {
                                    Text("Keep mine")
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Compact edit actions
            item(key = "task_actions") {
                val hasDueDate = task.dueDate.isNotBlank() && !DateUtils.isNullDate(task.dueDate)
                val dueDateLabel = if (hasDueDate) DateUtils.formatRelativeDate(task.dueDate) else null
                val projectName = state.allProjects.find { it.id == task.projectId }?.title ?: "No project"
                val priorityLabel = when (task.priority) {
                    1 -> "Low"
                    2 -> "Medium"
                    3 -> "High"
                    4 -> "Urgent"
                    else -> null
                }
                val recurrenceLabel = DateUtils.formatRecurrence(task.repeatAfter, task.repeatMode)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TaskDetailActionButton(
                        icon = Icons.Default.Sell,
                        contentDescription = if (task.labels.isEmpty()) {
                            "Add label"
                        } else {
                            "Edit labels, ${task.labels.size} selected"
                        },
                        isActive = task.labels.isNotEmpty(),
                        onClick = { showLabelPicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.CalendarToday,
                        contentDescription = dueDateLabel?.let { "Due date: $it" } ?: "Add due date",
                        isActive = hasDueDate,
                        onClick = { showDatePicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.Notifications,
                        contentDescription = if (task.reminders.isEmpty()) {
                            "Add reminder"
                        } else {
                            "Reminders: ${ReminderFormat.summary(task.reminders)}"
                        },
                        isActive = task.reminders.isNotEmpty(),
                        onClick = { showReminderPicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.Folder,
                        contentDescription = "Project: $projectName",
                        isActive = task.projectId > 0,
                        onClick = { showProjectPicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.Flag,
                        contentDescription = priorityLabel?.let { "Priority: $it" } ?: "Set priority",
                        isActive = priorityLabel != null,
                        onClick = { showPriorityPicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.Repeat,
                        contentDescription = if (recurrenceLabel.isBlank()) {
                            "Set recurrence"
                        } else {
                            "Recurrence: $recurrenceLabel"
                        },
                        isActive = recurrenceLabel.isNotBlank(),
                        onClick = { showRecurrencePicker = true },
                    )
                    TaskDetailActionButton(
                        icon = Icons.Default.AttachFile,
                        contentDescription = if (state.attachments.isEmpty()) {
                            "Add attachment"
                        } else {
                            "Add attachment, ${state.attachments.size} attached"
                        },
                        isActive = state.attachments.isNotEmpty(),
                        onClick = filePickerLauncher,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Assigned labels remain visible; the icon row opens the picker.
            if (task.labels.isNotEmpty()) {
                item(key = "labels") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        task.labels.forEach { label ->
                            val labelColor = parseHexColor(label.hexColor)
                                ?: MaterialTheme.colorScheme.secondaryContainer

                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = labelColor.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = label.title,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = labelColor,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Divider
            item(key = "divider_subtasks") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Subtasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Subtasks
            items(state.subtasks, key = { "subtask_${it.id}" }) { subtask ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = subtask.done,
                        onCheckedChange = { viewModel.requestToggleSubtaskDone(subtask) },
                    )
                    Text(
                        text = subtask.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (subtask.done) TextDecoration.LineThrough else null,
                        color = if (subtask.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Add subtask
            item(key = "add_subtask") {
                if (showSubtaskInput) {
                    OutlinedTextField(
                        value = subtaskInput,
                        onValueChange = { subtaskInput = it },
                        placeholder = { Text("Subtask title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (subtaskInput.isNotBlank()) {
                                    viewModel.createSubtask(subtaskInput.trim())
                                    subtaskInput = ""
                                }
                            },
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                showSubtaskInput = false
                                subtaskInput = ""
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        },
                    )
                } else {
                    TextButton(onClick = { showSubtaskInput = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add subtask")
                    }
                }
            }

            // Relations
            if (state.relations.isNotEmpty()) {
                item(key = "divider_relations") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Relations",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                state.relations.forEach { (kind, tasks) ->
                    item(key = "relation_header_$kind") {
                        Text(
                            text = com.rendyhd.vicu.util.RelationKind.label(kind),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(tasks, key = { "relation_${kind}_${it.id}" }) { related ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = related.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (kind != com.rendyhd.vicu.util.RelationKind.PARENTTASK) {
                                IconButton(onClick = { viewModel.removeRelation(kind, related.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove relation",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "add_relation") {
                TextButton(onClick = {
                    viewModel.setRelationSearchQuery("")
                    showRelationPicker = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add relation")
                }
            }

            if (visibleAttachments.isNotEmpty()) {
                item(key = "divider_attachments") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Attachments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Image-token-referenced attachments are hidden here — they render
                // as thumbnails inside DescriptionField instead.
                items(visibleAttachments, key = { "att_${it.id}" }) { attachment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = attachment.id !in state.downloadingAttachmentIds,
                                onClickLabel = "Open ${attachment.fileName}",
                            ) { viewModel.openAttachment(attachment) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (attachment.fileSize > 0) {
                                val sizeStr = when {
                                    attachment.fileSize > 1_048_576 -> "${attachment.fileSize / 1_048_576} MB"
                                    attachment.fileSize > 1024 -> "${attachment.fileSize / 1024} KB"
                                    else -> "${attachment.fileSize} B"
                                }
                                Text(
                                    text = sizeStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (attachment.id in state.downloadingAttachmentIds) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { viewModel.shareAttachment(attachment) }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share ${attachment.fileName}",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(onClick = { attachmentPendingDelete = attachment }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Created date (read-only; data already round-trips)
            if (!DateUtils.isNullDate(task.created)) {
                item(key = "created") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Created ${DateUtils.formatFullDate(task.created)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Delete task button
            item(key = "delete") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = viewModel::requestDeleteTask,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete task")
                }
            }

            // Error display
            if (state.error != null) {
                item(key = "error") {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        }
    }

    attachmentPendingDelete?.let { attachment ->
        AlertDialog(
            onDismissRequest = { attachmentPendingDelete = null },
            title = { Text("Delete attachment?") },
            text = { Text("\"${attachment.fileName}\" will be removed from this task. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        attachmentPendingDelete = null
                        viewModel.deleteAttachment(attachment.id)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { attachmentPendingDelete = null }) { Text("Cancel") } },
        )
    }

    // Delete confirmation dialog
    if (state.showDeleteConfirmation) {
        val descendantCount = state.task?.descendantsDepthFirst()?.size
            ?.takeIf { it > 0 }
            ?: state.subtasks.size
        val hasDescendants = descendantCount > 0
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text(if (hasDescendants) "Delete task and subtasks?" else "Delete task?") },
            text = {
                Text(
                    if (hasDescendants) {
                        "This task has $descendantCount ${if (descendantCount == 1) "subtask" else "subtasks"}. " +
                            "Delete them too, or keep them as standalone tasks."
                    } else {
                        "This action cannot be undone."
                    },
                )
            },
            confirmButton = {
                Row {
                    if (hasDescendants) {
                        TextButton(onClick = { viewModel.deleteTask(deleteSubtasks = false) }) {
                            Text("Keep subtasks")
                        }
                    }
                    TextButton(onClick = { viewModel.deleteTask(deleteSubtasks = true) }) {
                        Text(if (hasDescendants) "Delete all" else "Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text("Cancel")
                }
            },
        )
    }

    state.pendingSubtaskCompletion?.let { subtask ->
        val count = subtask.descendantsDepthFirst().count { !it.done }
        AlertDialog(
            onDismissRequest = viewModel::dismissSubtaskCompletion,
            title = { Text("Complete task and subtasks?") },
            text = {
                Text("This will also complete $count unfinished ${if (count == 1) "subtask" else "subtasks"}.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSubtaskCompletion) { Text("Complete all") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSubtaskCompletion) { Text("Cancel") }
            },
        )
    }

    // Picker dialogs
    if (showDatePicker) {
        VicuDatePickerDialog(
            currentDate = state.task?.dueDate,
            onDateSelected = viewModel::setDueDate,
            onClearDate = viewModel::clearDueDate,
            onDismiss = { showDatePicker = false },
        )
    }

    if (showProjectPicker) {
        ProjectPickerDialog(
            projects = state.allProjects,
            selectedProjectId = state.task?.projectId,
            onProjectSelected = viewModel::setProject,
            onDismiss = { showProjectPicker = false },
            inboxProjectId = state.inboxProjectId,
        )
    }

    if (showLabelPicker) {
        val currentLabels = state.task?.labels ?: emptyList()
        LabelPickerDialog(
            allLabels = state.allLabels,
            selectedLabelIds = currentLabels.map { it.id }.toSet(),
            onToggleLabel = { labelId ->
                if (currentLabels.any { it.id == labelId }) {
                    viewModel.removeLabel(labelId)
                } else {
                    viewModel.addLabel(labelId)
                }
            },
            onCreateLabel = viewModel::createAndAddLabel,
            onDismiss = { showLabelPicker = false },
        )
    }

    if (showReminderPicker) {
        ReminderPickerDialog(
            reminders = state.task?.reminders ?: emptyList(),
            onAddReminder = viewModel::addReminder,
            onRemoveReminder = viewModel::removeReminder,
            onDismiss = { showReminderPicker = false },
            onEditReminder = viewModel::editReminder,
            dueDate = state.task?.dueDate ?: "",
        )
    }

    if (showPriorityPicker) {
        PriorityPickerDialog(
            current = state.task?.priority ?: 0,
            onPick = viewModel::setPriority,
            onDismiss = { showPriorityPicker = false },
        )
    }

    if (showRecurrencePicker) {
        RecurrencePickerDialog(
            repeatAfter = state.task?.repeatAfter ?: 0L,
            repeatMode = state.task?.repeatMode ?: 0,
            onPick = viewModel::setRecurrence,
            onDismiss = { showRecurrencePicker = false },
        )
    }

    if (showRelationPicker) {
        RelationTaskPickerDialog(
            searchResults = relationSearchResults.filter { it.id != state.task?.id },
            onQueryChange = { viewModel.setRelationSearchQuery(it) },
            onConfirm = { otherId, kind ->
                viewModel.addRelation(otherId, kind)
                showRelationPicker = false
            },
            onDismiss = { showRelationPicker = false },
        )
    }
}

@Composable
private fun TaskDetailActionButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(21.dp),
            tint = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
