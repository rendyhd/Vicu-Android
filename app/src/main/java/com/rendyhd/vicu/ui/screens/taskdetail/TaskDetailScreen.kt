package com.rendyhd.vicu.ui.screens.taskdetail

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.FileUtils

private fun parseHexColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailSheet(
    taskId: Long,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var subtaskInput by remember { mutableStateOf("") }
    var showSubtaskInput by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            val part = FileUtils.uriToMultipartPart(context, it)
            if (part != null) {
                viewModel.uploadAttachment(part)
            }
        }
    }

    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onDismiss()
    }

    // Auto-save on dismiss
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveIfChanged()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.saveIfChanged()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        val task = state.task

        if (state.isLoading || task == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Title
            item(key = "title") {
                OutlinedTextField(
                    value = task.title,
                    onValueChange = viewModel::updateTitle,
                    placeholder = { Text("Task title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleLarge,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
            }

            // Description
            item(key = "description") {
                OutlinedTextField(
                    value = task.description,
                    onValueChange = viewModel::updateDescription,
                    placeholder = { Text("Add notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Labels section
            item(key = "labels") {
                Text("Labels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
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

                    AssistChip(
                        onClick = { showLabelPicker = true },
                        label = { Text("+") },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Due date
            item(key = "due_date") {
                val hasDueDate = task.dueDate.isNotBlank() && !DateUtils.isNullDate(task.dueDate)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    if (hasDueDate) {
                        val isOverdue = DateUtils.isOverdue(task.dueDate)
                        Text(
                            text = DateUtils.formatRelativeDate(task.dueDate),
                            color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = "Add due date",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Reminders
            item(key = "reminders") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showReminderPicker = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    if (task.reminders.isNotEmpty()) {
                        Text("${task.reminders.size} reminder${if (task.reminders.size > 1) "s" else ""}")
                    } else {
                        Text("Add reminder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Project
            item(key = "project") {
                val projectName = state.allProjects.find { it.id == task.projectId }?.title ?: "No project"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProjectPicker = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(projectName)
                }
            }

            // Recurrence (read-only)
            if (task.repeatAfter > 0) {
                item(key = "recurrence") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = DateUtils.formatRecurrence(task.repeatAfter, task.repeatMode),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        onCheckedChange = { viewModel.toggleSubtaskDone(subtask) },
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

            // Divider
            item(key = "divider_attachments") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Attachments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Attachments
            items(state.attachments, key = { "att_${it.id}" }) { attachment ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    IconButton(onClick = { viewModel.deleteAttachment(attachment.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Upload attachment button
            item(key = "upload_attachment") {
                TextButton(onClick = { filePickerLauncher.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add attachment")
                }
            }

            // Delete task button
            item(key = "delete") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = viewModel::showDeleteConfirmation,
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

    // Delete confirmation dialog
    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteConfirmation,
            title = { Text("Delete task?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::deleteTask) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteConfirmation) {
                    Text("Cancel")
                }
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
        )
    }
}
