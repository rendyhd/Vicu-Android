package com.rendyhd.vicu.ui.components.task

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.FileUtils
import com.rendyhd.vicu.util.parser.getPrefixes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEntrySheet(
    defaultProjectId: Long?,
    defaultDueDate: String? = null,
    onDismiss: () -> Unit,
    onTaskCreated: (Long) -> Unit,
    sharedContent: SharedContent? = null,
    viewModel: TaskEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val isDarkTheme = isSystemInDarkTheme()

    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }

    // TextFieldValue for cursor position tracking (needed for autocomplete)
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Sync textFieldValue text with viewModel state
    LaunchedEffect(state.title) {
        if (textFieldValue.text != state.title) {
            textFieldValue = textFieldValue.copy(text = state.title)
        }
    }

    LaunchedEffect(defaultProjectId, defaultDueDate, sharedContent) {
        if (sharedContent != null) {
            viewModel.initWithSharedContent(defaultProjectId, sharedContent)
        } else {
            viewModel.initWithDefaults(defaultProjectId, defaultDueDate)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.savedTaskId) {
        state.savedTaskId?.let { id ->
            onTaskCreated(id)
            viewModel.reset()
            textFieldValue = TextFieldValue("")
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
        ) {
            // Title field with NLP highlighting and autocomplete
            Box {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        viewModel.setTitle(newValue.text)
                    },
                    placeholder = { Text("New task") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    visualTransformation = NlpVisualTransformation(
                        tokens = state.parseResult?.tokens ?: emptyList(),
                        isDarkTheme = isDarkTheme,
                    ),
                )

                // Autocomplete dropdown
                NlpAutocompleteDropdown(
                    inputValue = textFieldValue.text,
                    cursorPosition = textFieldValue.selection.start,
                    prefixes = getPrefixes(state.parserConfig.syntaxMode),
                    projects = state.allProjects,
                    labels = state.allLabels,
                    enabled = state.parserConfig.enabled,
                    onSelect = { newText, newCursor ->
                        textFieldValue = TextFieldValue(
                            text = newText,
                            selection = TextRange(newCursor),
                        )
                        viewModel.setTitle(newText)
                    },
                )
            }

            // NLP preview chips
            val parseResult = state.parseResult
            if (parseResult != null && parseResult.tokens.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ParseChipRow(
                    parseResult = parseResult,
                    isDarkTheme = isDarkTheme,
                    onDismiss = viewModel::suppressType,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                placeholder = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Date chip
                val hasDate = state.dueDate.isNotBlank() && !DateUtils.isNullDate(state.dueDate)
                val dateLabel = if (hasDate) {
                    DateUtils.formatRelativeDate(state.dueDate)
                } else "Date"

                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(dateLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = if (hasDate) {
                        {
                            IconButton(
                                onClick = { viewModel.clearDueDate() },
                                modifier = Modifier.size(16.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear date",
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    } else null,
                )

                // Project chip
                val projectName = state.allProjects.find { it.id == state.projectId }?.title ?: "Project"
                AssistChip(
                    onClick = { showProjectPicker = true },
                    label = { Text(projectName) },
                    leadingIcon = {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

                // Labels chip
                val labelCount = state.selectedLabelIds.size
                val labelText = if (labelCount > 0) "$labelCount label${if (labelCount > 1) "s" else ""}" else "Labels"
                AssistChip(
                    onClick = { showLabelPicker = true },
                    label = { Text(labelText) },
                    leadingIcon = {
                        Icon(Icons.Default.Sell, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

                // Reminder chip
                val reminderCount = state.reminders.size
                val reminderText = if (reminderCount > 0) "$reminderCount" else "Remind"
                AssistChip(
                    onClick = { showReminderPicker = true },
                    label = { Text(reminderText) },
                    leadingIcon = {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

            }

            // Pending attachment previews
            if (state.pendingAttachmentUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Attachments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                state.pendingAttachmentUris.forEachIndexed { index, uri ->
                    AttachmentPreviewRow(
                        uri = uri,
                        isImage = state.pendingAttachmentMimeType?.startsWith("image/") == true,
                        onRemove = { viewModel.removePendingAttachment(index) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = viewModel::save,
                enabled = state.title.isNotBlank() && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSaving) "Saving..." else "Save")
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    // Picker dialogs
    if (showDatePicker) {
        VicuDatePickerDialog(
            currentDate = state.dueDate,
            onDateSelected = viewModel::setDueDate,
            onClearDate = viewModel::clearDueDate,
            onDismiss = { showDatePicker = false },
        )
    }

    if (showProjectPicker) {
        ProjectPickerDialog(
            projects = state.allProjects,
            selectedProjectId = state.projectId,
            onProjectSelected = viewModel::setProjectId,
            onDismiss = { showProjectPicker = false },
        )
    }

    if (showLabelPicker) {
        LabelPickerDialog(
            allLabels = state.allLabels,
            selectedLabelIds = state.selectedLabelIds,
            onToggleLabel = viewModel::toggleLabel,
            onCreateLabel = { name, color ->
                // Create label via repo, then add to selected set
                // For now, labels are created on save
            },
            onDismiss = { showLabelPicker = false },
        )
    }

    if (showReminderPicker) {
        ReminderPickerDialog(
            reminders = state.reminders,
            onAddReminder = viewModel::addReminder,
            onRemoveReminder = viewModel::removeReminder,
            onDismiss = { showReminderPicker = false },
            onEditReminder = viewModel::editReminder,
        )
    }
}

@Composable
private fun AttachmentPreviewRow(
    uri: Uri,
    isImage: Boolean,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val fileName = remember(uri) { FileUtils.getDisplayName(context, uri) ?: "File" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isImage) {
            AsyncImage(
                model = uri,
                contentDescription = fileName,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
