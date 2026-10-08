package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.activity.compose.BackHandler
import com.rendyhd.vicu.ui.rememberImagePicker
import com.rendyhd.vicu.ui.rememberFilePicker
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rendyhd.vicu.ui.components.task.LabelChip
import com.rendyhd.vicu.ui.components.task.checklistLabel
import com.rendyhd.vicu.util.subtaskProgress
import kotlinx.coroutines.launch
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.task.AnimatedCheckbox
import com.rendyhd.vicu.ui.components.task.DescriptionField
import com.rendyhd.vicu.ui.components.task.clearDescriptionEditorFocusOnHostTap
import com.rendyhd.vicu.ui.components.task.rememberDescriptionEditorController
import com.rendyhd.vicu.ui.components.task.NlpAutocompleteDropdown
import com.rendyhd.vicu.ui.components.task.NlpVisualTransformation
import com.rendyhd.vicu.ui.components.task.ParseChipRow
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import org.koin.compose.koinInject
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.descendantsDepthFirst
import com.rendyhd.vicu.util.unfinishedDescendants
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
    /** Opens another task (a subtask) in this screen; edits made here are saved first. */
    onOpenTask: (Long) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    val notificationPermission: NotificationPermissionCoordinator = koinInject()
    var showPriorityPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }
    var subtaskInput by remember { mutableStateOf("") }
    var showSubtaskInput by remember { mutableStateOf(false) }
    var showRelationPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val relationSearchResults by viewModel.relationSearchResults.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()
    var titleFieldValue by remember(taskId) {
        mutableStateOf(TextFieldValue(state.task?.title.orEmpty()))
    }
    val descriptionEditorController = rememberDescriptionEditorController()
    val focusManager = LocalFocusManager.current
    val openSubtask: (Long) -> Unit = { subtaskId ->
        // The description is typed into the editor, not yet into the view model: hand it over so
        // opening the subtask saves it with the rest.
        descriptionEditorController.flush()
        onOpenTask(subtaskId)
    }
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
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = dismissEditor) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Edits are saved as you type; Done only leaves.
                        TextButton(onClick = dismissEditor) { Text("Done") }
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
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // The headline: the title reads as the task, not as a form field.
            item(key = "title") {
                var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        // Completes or reopens the task itself (not only its subtasks); the 8 dp
                        // centres the circle on the first line of the headline.
                        AnimatedCheckbox(
                            done = task.done,
                            onToggle = viewModel::requestToggleDone,
                            contentDescription = "Complete task",
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            BasicTextField(
                                value = titleFieldValue,
                                onValueChange = { newValue ->
                                    // One line: Enter must not insert a break, and pasted text loses its.
                                    val clean = newValue.text.withoutLineBreaks()
                                    titleFieldValue = if (clean == newValue.text) {
                                        newValue
                                    } else {
                                        newValue.copy(
                                            text = clean,
                                            selection = TextRange(
                                                newValue.selection.start.coerceAtMost(clean.length),
                                                newValue.selection.end.coerceAtMost(clean.length),
                                            ),
                                        )
                                    }
                                    viewModel.updateTitle(clean)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    .onSizeChanged { fieldSize = it },
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 3,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                visualTransformation = NlpVisualTransformation(
                                    tokens = state.parseResult?.tokens ?: emptyList(),
                                    isDarkTheme = isDarkTheme,
                                ),
                                decorationBox = { inner ->
                                    Box {
                                        if (titleFieldValue.text.isEmpty()) {
                                            Text(
                                                text = "Task title",
                                                style = MaterialTheme.typography.headlineSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
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
                    }

                    // What the title says that is not applied yet (it is applied when the task is saved).
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

            // Notes, as plain text under the headline
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
                    plain = true,
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

            // One row of property chips, in the order of the desktop card bar: date, priority,
            // labels, checklist, reminders, repeat, project, attachments, More. A property that is
            // not set is a quiet "+ Name" that opens the same picker.
            item(key = "task_actions") {
                val clockDay = LocalClockDay.current
                val dateFormat = LocalDateFormat.current
                val hasDueDate = task.dueDate.isNotBlank() && !DateUtils.isNullDate(task.dueDate)
                val dueDateLabel = if (hasDueDate) {
                    DateDisplay.formatDue(DateContext.CHIP, task.dueDate, clockDay.date, clockDay.zone, dateFormat)
                } else {
                    null
                }
                val overdue = hasDueDate && DateUtils.isOverdue(task.dueDate, clockDay.date, clockDay.zone)
                val priorityLabel = priorityName(task.priority)
                val recurrenceLabel = DateUtils.formatRecurrence(task.repeatAfter, task.repeatMode)
                val reminderText = if (task.reminders.isEmpty()) "" else ReminderFormat.summary(task.reminders, dateFormat)
                val projectName = state.allProjects.find { it.id == task.projectId }?.title ?: "No project"
                val (doneSubtasks, totalSubtasks) = remember(task.relatedTasks) { task.subtaskProgress() }
                var showMore by remember { mutableStateOf(false) }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    PropertyChip(
                        set = hasDueDate,
                        emphasis = overdue,
                        text = dueDateLabel ?: "Date",
                        description = dueDateLabel?.let { "Due date: $it" } ?: "Add due date",
                        onClick = { showDatePicker = true },
                    )
                    PropertyChip(
                        set = priorityLabel != null,
                        text = priorityLabel ?: "Priority",
                        description = priorityLabel?.let { "Priority: $it" } ?: "Set priority",
                        onClick = { showPriorityPicker = true },
                    )
                    PropertyChip(
                        set = task.labels.isNotEmpty(),
                        text = "Label",
                        description = if (task.labels.isEmpty()) {
                            "Add label"
                        } else {
                            "Labels: ${task.labels.joinToString(", ") { it.title }}"
                        },
                        onClick = { showLabelPicker = true },
                    ) {
                        task.labels.forEach { label ->
                            LabelChip(title = label.title, hexColor = label.hexColor, modifier = Modifier.widthIn(max = 140.dp))
                        }
                    }
                    PropertyChip(
                        set = totalSubtasks > 0,
                        text = if (totalSubtasks > 0) checklistLabel(doneSubtasks, totalSubtasks) else "Checklist",
                        description = if (totalSubtasks > 0) {
                            "Subtasks: $doneSubtasks of $totalSubtasks completed"
                        } else {
                            "Add subtask"
                        },
                        onClick = {
                            showSubtaskInput = true
                            scope.launch { listState.animateScrollToItem(SUBTASKS_ITEM_INDEX) }
                        },
                    )
                    PropertyChip(
                        set = task.reminders.isNotEmpty(),
                        text = if (task.reminders.isEmpty()) "Reminder" else reminderText,
                        description = if (task.reminders.isEmpty()) "Add reminder" else "Reminders: $reminderText",
                        onClick = { showReminderPicker = true },
                    )
                    PropertyChip(
                        set = recurrenceLabel.isNotBlank(),
                        text = if (recurrenceLabel.isBlank()) "Repeat" else recurrenceLabel,
                        description = if (recurrenceLabel.isBlank()) "Set recurrence" else "Recurrence: $recurrenceLabel",
                        onClick = { showRecurrencePicker = true },
                    )
                    // A task always has a project, so this one is always a chip.
                    PropertyChip(
                        set = true,
                        text = projectName,
                        description = "Project: $projectName",
                        onClick = { showProjectPicker = true },
                    )
                    PropertyChip(
                        set = state.attachments.isNotEmpty(),
                        text = if (state.attachments.isEmpty()) "" else state.attachments.size.toString(),
                        description = if (state.attachments.isEmpty()) {
                            "Add attachment"
                        } else {
                            "Add attachment, ${state.attachments.size} attached"
                        },
                        onClick = filePickerLauncher,
                        icon = Icons.Default.AttachFile,
                    )
                    Box {
                        PropertyChip(
                            set = false,
                            text = "",
                            description = "More",
                            onClick = { showMore = true },
                            icon = Icons.Default.MoreHoriz,
                        )
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("Add relation") },
                                onClick = {
                                    showMore = false
                                    viewModel.setRelationSearchQuery("")
                                    showRelationPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete task", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMore = false
                                    viewModel.requestDeleteTask()
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Subtasks: listed when there are some, or once "+ Checklist" asked for the input
            if (state.subtasks.isNotEmpty() || showSubtaskInput) {
                item(key = "divider_subtasks") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Subtasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Subtasks
                items(state.subtasks, key = { "subtask_${it.id}" }, contentType = { "subtask" }) { subtask ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Open subtask") { openSubtask(subtask.id) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = subtask.done,
                            onCheckedChange = { viewModel.requestToggleSubtaskDone(subtask) },
                            modifier = Modifier.semantics { contentDescription = subtask.title },
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
                    items(tasks, key = { "relation_${kind}_${it.id}" }, contentType = { "relation" }) { related ->
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
            if (visibleAttachments.isNotEmpty()) {
                item(key = "divider_attachments") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Attachments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Image-token-referenced attachments are hidden here — they render
                // as thumbnails inside DescriptionField instead.
                items(visibleAttachments, key = { "att_${it.id}" }, contentType = { "attachment" }) { attachment ->
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

            // The footer: when it was made, and that nothing needs saving.
            item(key = "footer") {
                Spacer(modifier = Modifier.height(16.dp))
                val created = if (DateUtils.isNullDate(task.created)) {
                    ""
                } else {
                    "Created ${DateDisplay.formatDayMonthYear(task.created, LocalClockDay.current.zone, LocalDateFormat.current)}. "
                }
                Text(
                    text = "${created}Saved as you type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
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

    if (state.showCompleteConfirmation) {
        val count = state.task?.unfinishedDescendants()?.size ?: 0
        AlertDialog(
            onDismissRequest = viewModel::dismissCompletion,
            title = { Text("Complete task and subtasks?") },
            text = {
                Text("This will also complete $count unfinished ${if (count == 1) "subtask" else "subtasks"}.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCompletion) { Text("Complete all") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCompletion) { Text("Cancel") }
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
        WhenSheet(
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
            onAddReminder = { reminder ->
                viewModel.addReminder(reminder)
                // A reminder needs notifications: ask now if the permission is missing.
                notificationPermission.onFeatureEnabled()
            },
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
        val projectTitles = remember(state.allProjects) { state.allProjects.associate { it.id to it.title } }
        RelationTaskPickerDialog(
            searchResults = relationSearchResults.filter { it.id != state.task?.id },
            projectTitleOf = projectTitles::get,
            onQueryChange = { viewModel.setRelationSearchQuery(it) },
            onConfirm = { otherId, kind ->
                viewModel.addRelation(otherId, kind)
                showRelationPicker = false
            },
            onDismiss = { showRelationPicker = false },
        )
    }
}

/** Where the subtasks section starts in the editor's list: after the headline, the notes and the chips. */
private const val SUBTASKS_ITEM_INDEX = 3

/**
 * A property chip of the editor's one row (design review 3.7): the value in words when [set], a
 * quiet "+ Name" when not. [content] replaces the text (the labels draw their own chips); [icon]
 * is for the icon-only chips (attachments, More). The touch target is 48 dp whatever the chip's size.
 */
@Composable
private fun PropertyChip(
    set: Boolean,
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    icon: ImageVector? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        emphasis -> scheme.error
        set -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = color,
        border = if (set) BorderStroke(1.dp, if (emphasis) scheme.error.copy(alpha = 0.5f) else scheme.outlineVariant) else null,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            if (content != null && set) {
                content()
            } else if (text.isNotEmpty()) {
                Text(
                    text = if (set) text else "+ $text",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}
