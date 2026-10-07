package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.ui.rememberImagePicker
import org.koin.compose.koinInject
import com.rendyhd.vicu.util.PlatformFiles
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.picker.RecurrencePickerDialog
import com.rendyhd.vicu.ui.components.picker.VicuDatePickerDialog
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalIs24Hour
import com.rendyhd.vicu.ui.screens.taskentry.resolveEntryDueDate
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.ui.screens.taskentry.resolveTaskEntryRecurrence
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.parser.getPrefixes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEntrySheet(
    defaultProjectId: Long?,
    defaultDueDate: String? = null,
    onDismiss: () -> Unit,
    onTaskCreated: (Long) -> Unit,
    sharedContent: SharedContent? = null,
    viewModel: TaskEntryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val descriptionEditorController = rememberDescriptionEditorController()
    val isDarkTheme = isSystemInDarkTheme()

    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showPriorityPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }

    // TextFieldValue for cursor position tracking (needed for autocomplete)
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // Sync textFieldValue text with viewModel state
    LaunchedEffect(state.title) {
        if (textFieldValue.text != state.title) {
            textFieldValue = textFieldValue.copy(text = state.title)
        }
    }

    // A rotation recreates this sheet around the same view model: the draft is already there, so
    // it is kept instead of being reset to the defaults (or the shared text) again. The flag is
    // saved with the instance state; a new view model (process death) is initialised as usual.
    var initializedBeforeRecreation by rememberSaveable { mutableStateOf(false) }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(defaultProjectId, defaultDueDate, sharedContent) {
        val recreated = firstRun && initializedBeforeRecreation && viewModel.isInitialized
        firstRun = false
        if (!recreated) {
            if (sharedContent != null) {
                viewModel.initWithSharedContent(defaultProjectId, sharedContent)
            } else {
                viewModel.initWithDefaults(defaultProjectId, defaultDueDate)
            }
        }
        initializedBeforeRecreation = true
    }

    // Defer focus until the sheet has fully expanded, so the keyboard-show animation doesn't
    // fight the sheet-enter animation (open-task stutter).
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Expanded) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(state.savedTaskId) {
        state.savedTaskId?.let { id ->
            onTaskCreated(id)
            val keepOpen = state.keepEntryOpen
            viewModel.reset()
            textFieldValue = TextFieldValue("")
            if (keepOpen) {
                // Mass-add: stay open and re-focus for the next task.
                focusRequester.requestFocus()
            } else {
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Cascade owns vertical scrolling while its editor is active. Temporarily
        // disable anchored-sheet gestures so the two nested scroll systems do not
        // fight over the same drag
        sheetGesturesEnabled = !descriptionEditorController.isEditorFocused,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clearDescriptionEditorFocusOnHostTap(descriptionEditorController)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
        ) {
            // Title field with NLP highlighting and autocomplete
            var fieldSize by remember { mutableStateOf(IntSize.Zero) }
            Box {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        viewModel.setTitle(newValue.text)
                    },
                    placeholder = { Text("New task") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onSizeChanged { fieldSize = it },
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
                    anchorSize = fieldSize,
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

            val entryImagePickerLauncher = rememberImagePicker(viewModel::stagePendingImage)

            DescriptionField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                taskId = 0L,
                isUploadingImage = false,
                onAddImageClick = entryImagePickerLauncher,
                onRemoveImageAttachment = {},
                onImagePasted = viewModel::stagePendingImage,
                pendingImages = state.pendingImages,
                onRemovePending = viewModel::removePendingImage,
                editorController = descriptionEditorController,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Date chip. Shows the date that will be saved: picked by hand, else typed in the
                // title, else the one the screen seeded (same resolution as save()).
                val day = LocalClockDay.current
                val is24Hour = LocalIs24Hour.current
                val effectiveDueDate = resolveEntryDueDate(
                    dueDate = state.dueDate,
                    dueDateIsManual = state.dueDateIsManual,
                    parserEnabled = state.parserConfig.enabled,
                    parsed = state.parseResult,
                    zone = day.zone,
                )
                val hasDate = effectiveDueDate.isNotBlank() && !DateUtils.isNullDate(effectiveDueDate)
                val dateLabel = if (hasDate) {
                    DateUtils.formatDueDate(effectiveDueDate, day.date, is24Hour, day.zone)
                } else "Date"

                AssistChip(
                    onClick = { showDatePicker = true },
                    // The clear button is as tall as the chip lets it be (32 dp); a screen reader
                    // gets the same thing from the chip's actions without having to find it.
                    modifier = if (hasDate) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Clear date") {
                                    viewModel.clearDueDate()
                                    true
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                    label = { Text(dateLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = if (hasDate) {
                        {
                            IconButton(
                                onClick = { viewModel.clearDueDate() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear date",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else null,
                )

                // Recurrence chip. The displayed value uses the same manual-over-NLP
                // resolution as save(), so the preview always matches what will persist.
                val effectiveRecurrence = resolveTaskEntryRecurrence(
                    manualRecurrence = state.manualRecurrence,
                    parserEnabled = state.parserConfig.enabled,
                    parsedRecurrence = state.parseResult?.recurrence,
                )
                AssistChip(
                    onClick = { showRecurrencePicker = true },
                    label = {
                        Text(
                            if (effectiveRecurrence.isRecurring) {
                                DateUtils.formatRecurrence(
                                    effectiveRecurrence.repeatAfter,
                                    effectiveRecurrence.repeatMode,
                                )
                            } else {
                                "Repeat"
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
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
                val reminderText = if (reminderCount > 0) {
                    "$reminderCount reminder${if (reminderCount > 1) "s" else ""}"
                } else "Reminder"
                AssistChip(
                    onClick = { showReminderPicker = true },
                    label = { Text(reminderText) },
                    leadingIcon = {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                )

                // Priority chip
                val priorityLabel = when (state.priority) {
                    1 -> "Low"
                    2 -> "Medium"
                    3 -> "High"
                    4 -> "Urgent"
                    else -> "Priority"
                }
                AssistChip(
                    onClick = { showPriorityPicker = true },
                    label = { Text(priorityLabel) },
                    leadingIcon = {
                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
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
                onClick = {
                    descriptionEditorController.flush()
                    viewModel.save()
                },
                // Gate on the effective (parsed) title so NLP-only input like "@work !1"
                // doesn't look enabled and then silently no-op in save().
                enabled = viewModel.effectiveTitle().isNotBlank() && !state.isSaving,
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
            inboxProjectId = state.inboxProjectId,
        )
    }

    if (showLabelPicker) {
        LabelPickerDialog(
            allLabels = state.allLabels,
            selectedLabelIds = state.selectedLabelIds,
            onToggleLabel = viewModel::toggleLabel,
            onCreateLabel = viewModel::createAndAddLabel,
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
            dueDate = state.dueDate,
        )
    }

    if (showPriorityPicker) {
        PriorityPickerDialog(
            current = state.priority,
            onPick = viewModel::setPriority,
            onDismiss = { showPriorityPicker = false },
        )
    }

    if (showRecurrencePicker) {
        val recurrence = resolveTaskEntryRecurrence(
            manualRecurrence = state.manualRecurrence,
            parserEnabled = state.parserConfig.enabled,
            parsedRecurrence = state.parseResult?.recurrence,
        )
        RecurrencePickerDialog(
            repeatAfter = recurrence.repeatAfter,
            repeatMode = recurrence.repeatMode,
            onPick = viewModel::setRecurrence,
            onDismiss = { showRecurrencePicker = false },
        )
    }
}

@Composable
private fun AttachmentPreviewRow(
    uri: String,
    isImage: Boolean,
    onRemove: () -> Unit,
) {
    val platformFiles = koinInject<PlatformFiles>()
    val fileName = remember(uri, platformFiles) { platformFiles.getDisplayName(uri) ?: "File" }

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
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
