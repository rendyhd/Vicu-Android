package com.rendyhd.vicu.ui.components.task

import com.rendyhd.vicu.ui.rememberImagePicker
import org.koin.compose.koinInject
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.util.PlatformFiles
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.screens.taskentry.FieldSource
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.ui.screens.taskentry.entryLabelsWords
import com.rendyhd.vicu.ui.screens.taskentry.entryPriorityName
import com.rendyhd.vicu.ui.screens.taskentry.entryProjectChipLabel
import com.rendyhd.vicu.ui.screens.taskentry.resolveEntryFields
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.getPrefixes

/**
 * The new-task sheet (design review 3.6): a compact sheet on the keyboard with the title (its
 * parsed words highlighted), one row of chips (When, Project, Tags, Priority) that say the value in
 * words, and "+ Notes", which grows the sheet with the description, Repeat and Reminder. A chip shows
 * what the parser read from the title unless the user set that chip with its picker, in which case
 * the chip wins and its token loses the highlight ([resolveEntryFields]); the date shows once.
 */
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
    val notificationPermission = koinInject<NotificationPermissionCoordinator>()
    var showPriorityPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }

    // "+ Notes" grows the sheet. A draft that already has notes or files keeps it grown.
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val showNotes = notesOpen || state.description.isNotBlank() ||
        state.pendingAttachmentUris.isNotEmpty() || state.pendingImages.isNotEmpty()

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
            notesOpen = false
            if (keepOpen) {
                // Mass-add: stay open and re-focus for the next task.
                focusRequester.requestFocus()
            } else {
                onDismiss()
            }
        }
    }

    // What the task is going to get, and what each chip says (the title's highlights, the chips and
    // the save all read the same fields).
    val day = LocalClockDay.current
    val dateFormat = LocalDateFormat.current
    val fields = resolveEntryFields(state, day.zone)
    val parseResult = state.parseResult
    // Gate on the effective (parsed) title so NLP-only input like "#work !1" doesn't look enabled
    // and then silently no-op in save().
    val canSave = viewModel.effectiveTitle().isNotBlank() && !state.isSaving
    val save = {
        descriptionEditorController.flush()
        viewModel.save()
    }

    // Closing a sheet that has no title drops what was picked on its chips: a draft is the text,
    // and a pick without one would otherwise stop the next title from being read.
    val dismiss = {
        if (state.title.isBlank()) viewModel.reset()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
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
                .padding(bottom = 12.dp)
                .imePadding(),
        ) {
            // The title with its parsed words highlighted, and the send button next to it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                Box(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                            viewModel.setTitle(newValue.text)
                        },
                        placeholder = { Text("New task", style = MaterialTheme.typography.titleMedium) },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onSizeChanged { fieldSize = it },
                        textStyle = MaterialTheme.typography.titleMedium,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { if (canSave) save() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        visualTransformation = NlpVisualTransformation(
                            tokens = parseResult?.tokens ?: emptyList(),
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
                FilledIconButton(onClick = save, enabled = canSave) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Save task")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // The chips: one row at rest. A chip says its value in words; the colour of a value read
            // from the title is the colour of its highlight.
            fun tint(source: FieldSource?, type: TokenType): Color? =
                if (source == FieldSource.TEXT) tokenChipColor(type, isDarkTheme) else null

            // One row: the chips scroll sideways when they say values, "+ Notes" stays at its end.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // When: the one place the date is shown.
                    val hasDate = fields.dueDate.isNotBlank()
                    TaskEntryChip(
                        label = if (hasDate) {
                            DateDisplay.formatDue(DateContext.CHIP, fields.dueDate, day.date, day.zone, dateFormat)
                        } else {
                            "When"
                        },
                        onClick = { showDatePicker = true },
                        tint = tint(fields.dueSource, TokenType.DATE),
                        clearDescription = if (hasDate) "Clear date" else null,
                        onClear = if (hasDate) viewModel::clearDueDate else null,
                    )

                    val projectTitle = state.allProjects.find { it.id == fields.projectId }?.title ?: "Project"
                    TaskEntryChip(
                        label = entryProjectChipLabel(fields, projectTitle, state.title, parseResult),
                        onClick = { showProjectPicker = true },
                        tint = if (fields.parsedProjectName != null) tokenChipColor(TokenType.PROJECT, isDarkTheme) else null,
                    )

                    val pickedLabels = state.allLabels.filter { it.id in state.selectedLabelIds }.map { it.title }
                    val parsedLabels = parseResult?.labels.orEmpty()
                    val labelWords = entryLabelsWords(pickedLabels, parsedLabels)
                    TaskEntryChip(
                        label = labelWords ?: "Tags",
                        onClick = { showLabelPicker = true },
                        tint = if (parsedLabels.isNotEmpty()) tokenChipColor(TokenType.LABEL, isDarkTheme) else null,
                        clearDescription = if (labelWords != null) "Clear tags" else null,
                        onClear = if (labelWords != null) viewModel::clearLabels else null,
                    )

                    val priorityName = entryPriorityName(fields.priority)
                    TaskEntryChip(
                        label = priorityName ?: "Priority",
                        onClick = { showPriorityPicker = true },
                        tint = tint(fields.prioritySource, TokenType.PRIORITY),
                        clearDescription = if (priorityName != null) "Clear priority" else null,
                        onClear = if (priorityName != null) ({ viewModel.setPriority(0) }) else null,
                    )

                    if (!showNotes) {
                        // A repeat or a reminder that is already set stays in view while the sheet is small.
                        if (fields.recurrenceSource != null) {
                            RecurrenceChip(fields.recurrence, fields.recurrenceSource, isDarkTheme, viewModel) {
                                showRecurrencePicker = true
                            }
                        }
                        if (state.reminders.isNotEmpty()) {
                            ReminderChip(state.reminders.size) { showReminderPicker = true }
                        }
                }
                }
                if (!showNotes) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TaskEntryChip(label = "+ Notes", onClick = { notesOpen = true })
                }
            }

            if (showNotes) {
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

                // What only a task with notes has room for.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RecurrenceChip(fields.recurrence, fields.recurrenceSource, isDarkTheme, viewModel) {
                        showRecurrencePicker = true
                    }
                    ReminderChip(state.reminders.size) { showReminderPicker = true }
                }
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
        WhenSheet(
            currentDate = fields.dueDate,
            onDateSelected = viewModel::setDueDate,
            onClearDate = viewModel::clearDueDate,
            onDismiss = { showDatePicker = false },
        )
    }

    if (showProjectPicker) {
        ProjectPickerDialog(
            projects = state.allProjects,
            selectedProjectId = fields.projectId,
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
            onAddReminder = { reminder ->
                viewModel.addReminder(reminder)
                // A reminder needs notifications: ask now if the permission is missing.
                notificationPermission.onFeatureEnabled()
            },
            onRemoveReminder = viewModel::removeReminder,
            onDismiss = { showReminderPicker = false },
            onEditReminder = viewModel::editReminder,
            dueDate = fields.dueDate,
        )
    }

    if (showPriorityPicker) {
        PriorityPickerDialog(
            current = fields.priority,
            onPick = viewModel::setPriority,
            onDismiss = { showPriorityPicker = false },
        )
    }

    if (showRecurrencePicker) {
        RecurrencePickerDialog(
            repeatAfter = fields.recurrence.repeatAfter,
            repeatMode = fields.recurrence.repeatMode,
            onPick = viewModel::setRecurrence,
            onDismiss = { showRecurrencePicker = false },
        )
    }
}

/** Repeat: its value in words once set (from the text or the picker), "Repeat" before. */
@Composable
private fun RecurrenceChip(
    recurrence: RecurrenceValue,
    source: FieldSource?,
    isDarkTheme: Boolean,
    viewModel: TaskEntryViewModel,
    onClick: () -> Unit,
) {
    val set = source != null
    TaskEntryChip(
        label = if (set) DateUtils.formatRecurrence(recurrence.repeatAfter, recurrence.repeatMode) else "Repeat",
        onClick = onClick,
        tint = if (source == FieldSource.TEXT) tokenChipColor(TokenType.RECURRENCE, isDarkTheme) else null,
        clearDescription = if (set) "Clear repeat" else null,
        onClear = if (set) ({ viewModel.setRecurrence(RecurrenceValue.NONE) }) else null,
    )
}

@Composable
private fun ReminderChip(count: Int, onClick: () -> Unit) {
    TaskEntryChip(
        label = if (count > 0) "$count reminder${if (count > 1) "s" else ""}" else "Reminder",
        onClick = onClick,
    )
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
