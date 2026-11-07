package com.rendyhd.vicu.ui.screens.taskdetail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.rendyhd.vicu.ui.components.shared.DragClickGuard
import com.rendyhd.vicu.ui.components.shared.dragClickGuardPresses
import com.rendyhd.vicu.ui.components.shared.listScrollbar
import com.rendyhd.vicu.location.LocationReminder
import com.rendyhd.vicu.ui.components.location.LocationDisclosureSheet
import com.rendyhd.vicu.ui.components.task.DeleteTaskDialog
import com.rendyhd.vicu.ui.components.task.PriorityMark
import com.rendyhd.vicu.util.RecurrencePreset
import com.rendyhd.vicu.util.RecurrenceValue
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenQuickChoice
import com.rendyhd.vicu.util.detectRecurrencePreset
import com.rendyhd.vicu.util.recurrenceForPreset
import com.rendyhd.vicu.ui.rememberImagePicker
import com.rendyhd.vicu.ui.rememberFilePicker
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import com.rendyhd.vicu.ui.components.shared.VicuDragHandleBar
import com.rendyhd.vicu.ui.theme.rememberReduceMotion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rendyhd.vicu.ui.components.task.LabelChip
import com.rendyhd.vicu.ui.components.task.subtaskProgressLabel
import com.rendyhd.vicu.util.subtaskProgress
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.isViewableImage
import com.rendyhd.vicu.ui.components.task.ImageViewerDialog
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.ui.components.collab.AssignSheet
import com.rendyhd.vicu.ui.components.collab.AvatarStack
import com.rendyhd.vicu.ui.components.collab.CommentIcon
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.util.Collaboration
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.LocalContentColor
import com.rendyhd.vicu.ui.components.picker.LabelPickerDialog
import com.rendyhd.vicu.ui.components.picker.PriorityPickerDialog
import com.rendyhd.vicu.ui.components.picker.ProjectPickerDialog
import com.rendyhd.vicu.ui.components.picker.RelationTaskPickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.picker.RecurrencePickerDialog
import com.rendyhd.vicu.ui.components.picker.WhenSheet
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.copyPlainText
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboard
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
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.ai.dictation.DictationController
import com.rendyhd.vicu.voice.TaskDefaults
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.location.label
import com.rendyhd.vicu.ui.components.location.LocationReminderDialog
import com.rendyhd.vicu.ui.components.location.PlacesViewModel
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    taskId: Long,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel,
    /** Opens another task (a subtask) in this screen; edits made here are saved first. */
    onOpenTask: (Long) -> Unit = {},
    /** The back gesture progress shared with [EditorOverlay]; null when the screen is shown without it. */
    backState: EditorBackState? = null,
    /** False while this editor is on its way out, so a second back goes on to the screen below. */
    backEnabled: Boolean = true,
    /** Edit in the answer to Save as template. */
    onEditTemplate: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showAssignSheet by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    val notificationPermission: NotificationPermissionCoordinator = koinInject()
    val dictation: DictationController = koinInject()
    val aiSettings by koinInject<AiSettingsStore>().settings.collectAsStateWithLifecycle(initialValue = AiSettings())
    // The location reminder lives on this device only, apart from the task (PlacesViewModel).
    val placesViewModel: PlacesViewModel = koinViewModel()
    val editorTaskId = state.task?.id ?: taskId
    val locationReminder by remember(editorTaskId) { placesViewModel.reminderFor(editorTaskId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var showLocationDialog by remember { mutableStateOf(false) }
    var showPriorityPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }
    var subtaskInput by remember { mutableStateOf("") }
    var showSubtaskInput by remember { mutableStateOf(false) }
    var subtaskFieldFocused by remember { mutableStateOf(false) }
    var showRelationPicker by remember { mutableStateOf(false) }
    // The property whose presets are open in place ("date", "priority", "reminder", "repeat").
    var openRow by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var titleFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val canMoveSubtasks = viewModel.canMoveSubtasks()
    val subtaskReorder = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = (from.key as? String)?.removePrefix("subtask_")?.toLongOrNull() ?: return@rememberReorderableLazyListState
        val toId = (to.key as? String)?.removePrefix("subtask_")?.toLongOrNull() ?: return@rememberReorderableLazyListState
        viewModel.moveSubtask(fromId, toId)
    }
    var listHeightPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val relationSearchResults by viewModel.relationSearchResults.collectAsStateWithLifecycle()
    val isDarkTheme = isSystemInDarkTheme()
    var titleFieldValue by remember(taskId) {
        mutableStateOf(TextFieldValue(state.task?.title.orEmpty()))
    }
    // A task a template just made opens with its title selected, ready to be typed over.
    val titleFocus = remember { FocusRequester() }
    val titleKeyboard = LocalSoftwareKeyboardController.current
    val loadedTitle = state.task?.title
    LaunchedEffect(taskId, loadedTitle != null) {
        if (loadedTitle != null && viewModel.consumeSelectTitle(taskId)) {
            titleFieldValue = TextFieldValue(loadedTitle, TextRange(0, loadedTitle.length))
            // The title is an item of the list, composed after this runs; and the quick add sheet
            // that made the task has just put its keyboard away.
            delay(TITLE_KEYBOARD_DELAY_MS)
            titleFocus.requestFocus()
            titleKeyboard?.show()
        }
    }
    val descriptionEditorController = rememberDescriptionEditorController()
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val openSubtask: (Long) -> Unit = { subtaskId ->
        // The description is typed into the editor, not yet into the view model: hand it over so
        // opening the subtask saves it with the rest.
        descriptionEditorController.flush()
        onOpenTask(subtaskId)
    }
    // Set when the editor is dismissed. From then on its state belongs to the save already started:
    // the exit animation only plays the visuals, and the view model may load another task before the
    // screen leaves composition, so nothing of this editor is read back into it.
    var dismissed by remember { mutableStateOf(false) }
    val dismissEditor = {
        descriptionEditorController.flush()
        if (state.descriptionConflict == null) {
            // The save starts now, not when the exit animation ends and the screen is disposed.
            viewModel.saveIfChanged()
            dismissed = true
            onDismiss()
        } else {
            viewModel.requireDescriptionConflictResolution()
        }
    }

    var attachmentPendingDelete by remember { mutableStateOf<Attachment?>(null) }
    var viewingAttachmentId by rememberSaveable { mutableStateOf<Long?>(null) }

    val filePickerLauncher = rememberFilePicker(viewModel::uploadAttachment)
    val imagePickerLauncher = rememberImagePicker(viewModel::addImageAttachment)

    LaunchedEffect(state.task?.title) {
        val title = state.task?.title ?: return@LaunchedEffect
        if (titleFieldValue.text != title) {
            titleFieldValue = titleFieldValue.copy(text = title)
        }
    }

    // Adding several subtasks in a row: each new row pushes the field down, so it is brought back
    // to just above the keyboard.
    LaunchedEffect(state.subtasks.size) {
        if (subtaskFieldFocused) {
            listState.keepItemInView(ADD_SUBTASK_KEY, addSubtaskItemIndex(state.subtasks.size))
        }
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) { dismissed = true; onDismiss() }
    }

    // The view model also saves on its own shortly after the last edit. These two are the final
    // saves: going to the background (the process can be killed there without the screen ever
    // leaving composition) and closing the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!dismissed) descriptionEditorController.flush()
        viewModel.saveIfChanged()
    }

    // Closing the screen (back, the close icon, or state.isDeleted) leaves composition and fires
    // this exactly once.
    DisposableEffect(Unit) {
        onDispose {
            // A dismissed editor was flushed and saved at the moment of dismissal; flushing it now
            // could write its text into whatever task the view model has loaded since.
            if (!dismissed) descriptionEditorController.flush()
            viewModel.saveIfChanged()
        }
    }

    // Predictive back: the editor follows the gesture (EditorOverlay scales and fades it by
    // backState.progress); a cancelled gesture springs back. Without a gesture (a button press,
    // three-button navigation) the editor just closes.
    EditorBackHandler(enabled = backEnabled, backState = backState) {
        // With a description conflict the dialog opens instead of closing: back to rest.
        val conflict = state.descriptionConflict != null
        dismissEditor()
        if (conflict) backState?.let { scope.launch { it.springBack() } }
    }

    // Pulling the sheet down (by its header, or past the top of the list) closes it once let go far
    // enough or with a flick; otherwise it springs back. With a description conflict the dialog opens
    // instead and the sheet goes back to rest.
    val pull = remember { EditorPullState() }
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val releasePull by rememberUpdatedState { velocity: Float ->
        if (editorPullCloses(pull.offset, velocity, density)) dismissEditor()
        if (dismissed) {
            pull.slideOut(scope, velocity, reduceMotion)
        } else {
            pull.springBack(scope, velocity, reduceMotion)
        }
    }
    val pullConnection = remember(pull) { EditorPullConnection(pull) { releasePull(it) } }

    // A sheet over the list (design concept 4): the list stays in view above it, under a scrim that
    // closes the editor when tapped. Not a ModalBottomSheet: its anchored drag has a nested-scroll
    // bug (issuetracker.google.com/issues/486562294, fixed only in alpha Compose) that made a
    // scrollable child shake on drag, so the pull down is the editor's own (EditorPull.kt): the
    // sheet only moves by what the list does not scroll. It also closes with the chevron, the scrim
    // or back (predictive: EditorOverlay scales and fades it by backState.progress).
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = editorPullScrimFraction(pull.offset, pull.sheetHeight) }
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = EDITOR_SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close",
                    onClick = dismissEditor,
                ),
        )
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = EDITOR_SHEET_TOP_GAP)
                .onSizeChanged { pull.sheetHeight = it.height.toFloat() }
                .graphicsLayer { translationY = pull.offset }
                .clearDescriptionEditorFocusOnHostTap(descriptionEditorController),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .nestedScroll(pullConnection),
            ) {
            EditorHeader(
                modifier = Modifier.draggable(
                    state = rememberDraggableState { pull.dragBy(it) },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> releasePull(velocity) },
                ),
                onClose = dismissEditor,
                onAddRelation = {
                    viewModel.setRelationSearchQuery("")
                    showRelationPicker = true
                },
                onDelete = viewModel::requestDeleteTask,
                onDuplicate = viewModel::duplicateTask,
                onSaveAsTemplate = {
                    // The answer offers Edit on the snackbar, which the keyboard would cover.
                    focusManager.clearFocus()
                    descriptionEditorController.flush()
                    viewModel.saveAsTemplate(onEditTemplate)
                },
                menuEnabled = state.task != null,
                // Each line of the notes is its own field, so a selection cannot cross lines.
                onCopyNotes = state.task?.takeIf { AiText.hasNotes(it.description) }?.let {
                    {
                        descriptionEditorController.flush()
                        val notes = viewModel.uiState.value.task?.description
                        val text = AiText.htmlToPlain(ImageTokens.parseValue(notes.orEmpty()).first)
                        if (text.isNotEmpty()) scope.launch { clipboard.copyPlainText("Task notes", text) }
                    }
                },
                // Break into steps (docs/ai-assistant-v1.md, section 4): the model's tasks become subtasks.
                onBreakIntoSteps = state.task?.takeIf { aiSettings.dictationReady && !it.done }?.let { task ->
                    {
                        val notes = AiText.htmlToPlain(task.description)
                        dictation.start(
                            transcript = if (notes.isEmpty()) task.title else task.title + "\n\n" + notes,
                            defaults = TaskDefaults(projectId = task.projectId),
                            parentTaskId = task.id,
                        )
                    }
                },
            )
            val task = state.task

            if (state.isLoading || task == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
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
            val viewerImages = state.attachments.filter {
                it.isViewableImage && it.id !in state.deletingAttachmentIds
            }
            val viewerIndex = viewerImages.indexOfFirst { it.id == viewingAttachmentId }
            if (viewerIndex >= 0) {
                ImageViewerDialog(
                    taskId = task.id,
                    images = viewerImages.map { ImageTokens.ImageRef.Image(it.id) },
                    initialIndex = viewerIndex,
                    onDismiss = { viewingAttachmentId = null },
                    onSave = { id, report -> viewerImages.firstOrNull { it.id == id }?.let { viewModel.saveAttachment(it, report) } },
                    onShare = { id, report -> viewerImages.firstOrNull { it.id == id }?.let { viewModel.shareAttachment(it, report) } },
                    busyIds = state.downloadingAttachmentIds,
                )
            }

            // The notes' toolbar (Hide keyboard among it) sits under the list, on top of the keyboard,
            // while the notes are being edited: under a tall note it would be behind the keyboard.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The tallest it has been: the keyboard must not change where long notes fold.
                    .onSizeChanged { listHeightPx = maxOf(listHeightPx, it.height) }
                    .listScrollbar(listState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // The headline: the title reads as the task, not as a form field.
            item(key = "title") {
                var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        // Completes or reopens the task itself (not only its subtasks). Its 48 dp box
                        // and the title's first line share a centre 24 dp from the top.
                        AnimatedCheckbox(
                            done = task.done,
                            onToggle = viewModel::requestToggleDone,
                            contentDescription = "Complete task",
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
                                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)
                                    .focusRequester(titleFocus)
                                    .onSizeChanged { fieldSize = it }
                                    .onFocusChanged { titleFocused = it.isFocused },
                                textStyle = editorTitleStyle(),
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
                                                style = editorTitleStyle(),
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
                    hostedToolbar = true,
                    notesFoldHeight = with(density) { (listHeightPx / 2).toDp() }.coerceAtLeast(MIN_NOTES_FOLD),
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

            // The likely next move as buttons: Today, Tomorrow, This weekend, Next week, without the
            // day the task is already due (quickMoves). Always an item, so the indexes after it stay put.
            item(key = "quick_moves") {
                val clockDay = LocalClockDay.current
                val moves = remember(task.dueDate, clockDay) { quickMoves(task.dueDate, clockDay.date, clockDay.zone) }
                if (!task.done && moves.isNotEmpty()) {
                    QuickMoveRow(moves = moves, onMove = viewModel::moveDue)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // The properties as rows, in the order of the desktop card bar (design-system-v1.md
            // section 4.1): date, priority, labels, subtasks, reminders, repeat, project, assign,
            // attachments, comments (assign and comments only on shared projects). A set property is
            // a row; an unset one waits in the Add row, in the same order. When, Priority, Remind and
            // Repeat open their presets in place; the others open their picker.
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
                val recurrence = RecurrenceValue(task.repeatAfter, task.repeatMode)
                val recurrenceLabel = DateUtils.formatRecurrence(task.repeatAfter, task.repeatMode)
                val locationLabel = locationReminder?.label()
                val hasReminder = task.reminders.isNotEmpty() || locationLabel != null
                val reminderText = if (!hasReminder) "" else ReminderFormat.summary(task.reminders, dateFormat, locationLabel)
                val projectName = state.allProjects.find { it.id == task.projectId }?.title ?: "No project"
                val (doneSubtasks, totalSubtasks) = remember(task.relatedTasks) { task.subtaskProgress() }
                val collab = LocalCollab.current
                val access = collab.access(task.projectId)
                val assignees = if (access.shared) collab.assignees(task) else emptyList()
                val signal = collab.signal(task)
                val places by placesViewModel.places.collectAsStateWithLifecycle()

                fun toggle(key: String) {
                    openRow = if (openRow == key) null else key
                }
                val openSubtasks: () -> Unit = {
                    showSubtaskInput = true
                    scope.launch { listState.animateScrollToItem(SUBTASKS_ITEM_INDEX) }
                }

                val entries = listOf(
                    EditorEntry("date", set = hasDueDate, addLabel = "When", addIcon = Icons.Outlined.CalendarMonth, onAdd = { toggle("date") }) {
                        PropertyRow(
                            icon = Icons.Outlined.CalendarMonth,
                            name = "When",
                            value = dueDateLabel,
                            description = "When: ${dueDateLabel ?: "none"}",
                            onClick = { toggle("date") },
                            expandable = true,
                            open = openRow == "date",
                            emphasis = overdue,
                        )
                        RowPresets(visible = openRow == "date") {
                            val dueDay = WhenLogic.valueOfDue(task.dueDate, clockDay.zone).date
                            WhenLogic.quickChoices(clockDay.date).distinctBy { it.date }.forEach { choice ->
                                PresetChip(
                                    label = choice.label,
                                    selected = dueDay == choice.date,
                                    onClick = {
                                        viewModel.setDueDate(movedDue(task.dueDate, choice.date, clockDay.zone))
                                        openRow = null
                                    },
                                )
                            }
                            PresetChip(label = "Pick a date…", onClick = { showDatePicker = true })
                            if (hasDueDate) {
                                PresetChip(label = "Clear", danger = true, onClick = {
                                    viewModel.clearDueDate()
                                    openRow = null
                                })
                            }
                        }
                    },
                    EditorEntry("priority", set = priorityLabel != null, addLabel = "Priority", addIcon = Icons.Outlined.Flag, onAdd = { toggle("priority") }) {
                        PropertyRow(
                            icon = Icons.Outlined.Flag,
                            name = "Priority",
                            value = priorityLabel,
                            description = "Priority: ${priorityLabel ?: "none"}",
                            onClick = { toggle("priority") },
                            expandable = true,
                            open = openRow == "priority",
                            trailing = {
                                PriorityMark(task.priority)
                                Text(priorityLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            },
                        )
                        RowPresets(visible = openRow == "priority") {
                            (1..4).forEach { level ->
                                PresetChip(
                                    label = priorityName(level).orEmpty(),
                                    selected = task.priority == level,
                                    onClick = {
                                        viewModel.setPriority(level)
                                        openRow = null
                                    },
                                )
                            }
                            if (priorityLabel != null) {
                                PresetChip(label = "None", onClick = {
                                    viewModel.setPriority(0)
                                    openRow = null
                                })
                            }
                        }
                    },
                    EditorEntry("label", set = task.labels.isNotEmpty(), addLabel = "Labels", addIcon = Icons.AutoMirrored.Outlined.Label, onAdd = { showLabelPicker = true }) {
                        LabelsPropertyRow(
                            icon = Icons.AutoMirrored.Outlined.Label,
                            name = "Labels",
                            description = "Labels: ${task.labels.joinToString(", ") { it.title }}",
                            onClick = { showLabelPicker = true },
                        ) {
                            task.labels.forEach { label ->
                                LabelChip(title = label.title, hexColor = label.hexColor)
                            }
                        }
                    },
                    EditorEntry("subtasks", set = totalSubtasks > 0, addLabel = "Subtasks", addIcon = Icons.Outlined.Checklist, onAdd = openSubtasks) {
                        PropertyRow(
                            icon = Icons.Outlined.Checklist,
                            name = "Subtasks",
                            value = subtaskProgressLabel(doneSubtasks, totalSubtasks),
                            description = "Subtasks: $doneSubtasks of $totalSubtasks completed",
                            onClick = openSubtasks,
                        )
                    },
                    EditorEntry("reminder", set = hasReminder, addLabel = "Remind", addIcon = Icons.Outlined.Notifications, onAdd = { toggle("reminder") }) {
                        PropertyRow(
                            icon = Icons.Outlined.Notifications,
                            name = "Remind",
                            value = reminderText.ifEmpty { null },
                            description = "Reminders: ${reminderText.ifEmpty { "none" }}",
                            onClick = { toggle("reminder") },
                            expandable = true,
                            open = openRow == "reminder",
                        )
                        RowPresets(visible = openRow == "reminder") {
                            task.reminders.forEachIndexed { index, reminder ->
                                val text = ReminderFormat.format(reminder, dateFormat)
                                PresetChip(
                                    label = text,
                                    selected = true,
                                    icon = Icons.Outlined.Notifications,
                                    onClick = { showReminderPicker = true },
                                    onRemove = { viewModel.removeReminder(index) },
                                    removeDescription = "Remove reminder $text",
                                )
                            }
                            locationReminder?.let { location ->
                                PresetChip(
                                    label = location.label(),
                                    selected = true,
                                    icon = Icons.Outlined.Place,
                                    onClick = { showLocationDialog = true },
                                    onRemove = { placesViewModel.removeReminder(editorTaskId) },
                                    removeDescription = "Remove location reminder",
                                )
                            }
                            // The times are read when the row opens, so "In 1 hour" means from now.
                            val presets = remember(openRow, task.dueDate, dateFormat) {
                                reminderPresets(task.dueDate, SystemTimeSource.now(), clockDay.zone, dateFormat)
                            }
                            presets.forEach { preset ->
                                PresetChip(label = preset.label, onClick = {
                                    viewModel.addReminder(preset.reminder)
                                    // A reminder needs notifications: ask now if the permission is missing.
                                    notificationPermission.onFeatureEnabled()
                                    openRow = null
                                })
                            }
                            if (locationReminder == null) {
                                places.take(MAX_PLACE_PRESETS).forEach { place ->
                                    PresetChip(label = "Arrive: ${place.name}", icon = Icons.Outlined.Place, onClick = {
                                        placesViewModel.saveReminder(
                                            LocationReminder(
                                                taskId = editorTaskId,
                                                placeId = place.id,
                                                placeName = place.name,
                                                // A place saved on another phone has no position here yet;
                                                // the reminder syncs by place id and is watched once it is located.
                                                latitude = place.position?.latitude ?: 0.0,
                                                longitude = place.position?.longitude ?: 0.0,
                                            ),
                                        )
                                        // Explains and asks for Always when the app does not have it yet.
                                        placesViewModel.askForAccess()
                                        notificationPermission.onFeatureEnabled()
                                        openRow = null
                                    })
                                }
                                PresetChip(label = "Place…", icon = Icons.Outlined.Place, onClick = { showLocationDialog = true })
                            }
                            PresetChip(label = "Pick a time…", onClick = { showReminderPicker = true })
                        }
                    },
                    EditorEntry("repeat", set = recurrence.isRecurring, addLabel = "Repeat", addIcon = Icons.Outlined.Repeat, onAdd = { toggle("repeat") }) {
                        PropertyRow(
                            icon = Icons.Outlined.Repeat,
                            name = "Repeat",
                            value = recurrenceLabel.ifBlank { null },
                            description = "Repeat: ${recurrenceLabel.ifBlank { "never" }}",
                            onClick = { toggle("repeat") },
                            expandable = true,
                            open = openRow == "repeat",
                        )
                        RowPresets(visible = openRow == "repeat") {
                            val current = detectRecurrencePreset(recurrence)
                            listOf(
                                RecurrencePreset.DAILY to "Daily",
                                RecurrencePreset.WEEKLY to "Weekly",
                                RecurrencePreset.MONTHLY to "Monthly",
                            ).forEach { (preset, label) ->
                                PresetChip(label = label, selected = current == preset, onClick = {
                                    viewModel.setRecurrence(recurrenceForPreset(preset, recurrence.fromCompletion))
                                    openRow = null
                                })
                            }
                            PresetChip(label = "Custom…", selected = current == RecurrencePreset.CUSTOM, onClick = { showRecurrencePicker = true })
                            if (recurrence.isRecurring) {
                                PresetChip(label = "Don't repeat", danger = true, onClick = {
                                    viewModel.setRecurrence(RecurrenceValue.NONE)
                                    openRow = null
                                })
                            }
                        }
                    },
                    // A task always has a project, so this one is always a row.
                    EditorEntry("project", set = true) {
                        PropertyRow(
                            icon = Icons.Outlined.Folder,
                            name = "Project",
                            value = projectName,
                            description = "Project: $projectName",
                            onClick = { showProjectPicker = true },
                        )
                    },
                    EditorEntry(
                        "assign",
                        set = assignees.isNotEmpty(),
                        present = access.shared,
                        addLabel = "Assign",
                        addIcon = Icons.Outlined.Person,
                        addable = access.canWrite,
                        onAdd = { showAssignSheet = true },
                    ) {
                        // With read-only access the row shows the assignees and does not open.
                        PropertyRow(
                            icon = Icons.Outlined.Person,
                            name = "Assign",
                            value = collab.nameList(assignees),
                            description = Collaboration.assignChipLabel(assignees.map { it.toCollabUser() }, collab.state.me),
                            onClick = { showAssignSheet = true },
                            enabled = access.canWrite,
                            trailing = {
                                AvatarStack(people = assignees, size = 20.dp, ringColor = MaterialTheme.colorScheme.surfaceContainer, max = 3)
                                Text(
                                    text = collab.nameList(assignees),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    },
                    EditorEntry("attachments", set = state.attachments.isNotEmpty(), addLabel = "File", addIcon = Icons.Outlined.AttachFile, onAdd = filePickerLauncher) {
                        val count = state.attachments.size
                        PropertyRow(
                            icon = Icons.Outlined.AttachFile,
                            name = "Attachments",
                            value = if (count == 1) "1 file" else "$count files",
                            description = "Add attachment, $count attached",
                            onClick = filePickerLauncher,
                        )
                    },
                    EditorEntry(
                        "comments",
                        set = signal.commentCount > 0,
                        addLabel = "Comments",
                        addIcon = Icons.Outlined.ChatBubbleOutline,
                        onAdd = { collab.openComments(task.id) },
                    ) {
                        val count = signal.commentCount
                        PropertyRow(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            name = "Comments",
                            value = count.toString(),
                            description = if (signal.isUnread) "Comments, $count, new" else "Comments, $count",
                            onClick = { collab.openComments(task.id) },
                            trailing = {
                                CommentIcon(
                                    unread = signal.isUnread,
                                    iconSize = 18.dp,
                                    tint = LocalContentColor.current,
                                    ringColor = MaterialTheme.colorScheme.surfaceContainer,
                                )
                                Text(text = count.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            },
                        )
                    },
                ).filter { it.present }

                val rows = entries.filter { it.set || it.key == openRow }
                val unset = entries.filter { !it.set && it.key != openRow && it.addable }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PropertyGroup {
                        rows.forEachIndexed { index, entry ->
                            if (index > 0) PropertyDivider()
                            entry.row()
                        }
                    }
                    if (unset.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Add",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
                            )
                            unset.forEach { entry ->
                                AddPropertyChip(
                                    label = entry.addLabel,
                                    icon = entry.addIcon,
                                    description = "Add ${entry.addLabel.lowercase()}",
                                    onClick = entry.onAdd,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Subtasks: listed when there are some, or once "+ Subtasks" asked for the input
            if (state.subtasks.isNotEmpty() || showSubtaskInput) {
                item(key = "divider_subtasks") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Subtasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Subtasks: press and hold a row to move it (section 19).
                itemsIndexed(state.subtasks, key = { _, it -> "subtask_${it.id}" }, contentType = { _, _ -> "subtask" }) { index, subtask ->
                    ReorderableItem(subtaskReorder, key = "subtask_${subtask.id}", enabled = canMoveSubtasks) { isDragging ->
                        val clickGuard = remember { DragClickGuard() }
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "subtaskDragElevation")
                        Surface(
                            modifier = Modifier.dragClickGuardPresses(clickGuard),
                            shadowElevation = elevation,
                            color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (canMoveSubtasks) {
                                            Modifier.longPressDraggableHandle(
                                                onDragStarted = {
                                                    clickGuard.dragStarted()
                                                    viewModel.onSubtaskDragStarted()
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragStopped = viewModel::onSubtaskDragStopped,
                                            ).semantics {
                                                customActions = buildList {
                                                    if (index > 0) {
                                                        add(CustomAccessibilityAction("Move up") { viewModel.moveSubtaskBy(subtask.id, -1); true })
                                                    }
                                                    if (index < state.subtasks.lastIndex) {
                                                        add(CustomAccessibilityAction("Move down") { viewModel.moveSubtaskBy(subtask.id, 1); true })
                                                    }
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable(onClickLabel = "Open subtask", onClick = clickGuard.guarded { openSubtask(subtask.id) })
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
                    }
                }

                // Add subtask
                item(key = ADD_SUBTASK_KEY) {
                    if (showSubtaskInput) {
                        OutlinedTextField(
                            value = subtaskInput,
                            onValueChange = { subtaskInput = it },
                            placeholder = { Text("Subtask title") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { subtaskFieldFocused = it.isFocused },
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
                    // An image that cannot be drawn (offline, not really an image) is a file like any other.
                    var thumbnailFailed by remember(attachment.id) { mutableStateOf(false) }
                    val showsImage = attachment.isViewableImage && !thumbnailFailed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = attachment.id !in state.downloadingAttachmentIds,
                                onClickLabel = "${if (showsImage) "View" else "Open"} ${attachment.fileName}",
                            ) {
                                if (showsImage) viewingAttachmentId = attachment.id else viewModel.openAttachment(attachment)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showsImage) {
                            AsyncImage(
                                // VikunjaImages rewrites the placeholder host to the server and adds the sign-in.
                                model = "http://localhost/tasks/${task.id}/attachments/${attachment.id}?preview_size=md",
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { thumbnailFailed = true },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        } else {
                            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(if (showsImage) 12.dp else 8.dp))
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
                            IconButton(onClick = { viewModel.saveAttachment(attachment) }) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "Save ${attachment.fileName}",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
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
            if (descriptionEditorController.isEditorFocused) {
                descriptionEditorController.hostedToolbar?.invoke(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else if (!titleFocused && !subtaskFieldFocused) {
                // Complete (or Reopen) in thumb reach; it steps aside while the keyboard types.
                FilledTonalButton(
                    onClick = viewModel::requestToggleDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (task.done) "Reopen" else "Complete")
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
        DeleteTaskDialog(
            descendantCount = descendantCount,
            onDelete = { deleteSubtasks -> viewModel.deleteTask(deleteSubtasks = deleteSubtasks) },
            onDismiss = viewModel::dismissDeleteConfirmation,
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

    if (showAssignSheet) {
        state.task?.let { current ->
            AssignSheet(
                task = current,
                projectTitle = state.allProjects.find { it.id == current.projectId }?.title.orEmpty(),
                onDismiss = { showAssignSheet = false },
            )
        }
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
            locationReminder = locationReminder,
            onLocationClick = {
                showReminderPicker = false
                showLocationDialog = true
            },
            onRemoveLocation = { placesViewModel.removeReminder(editorTaskId) },
        )
    }

    // The permission explanation for the "Arrive: Home" presets; the location dialog hosts its own.
    if (!showLocationDialog) LocationDisclosureSheet(placesViewModel)

    if (showLocationDialog) {
        LocationReminderDialog(
            taskId = editorTaskId,
            existing = locationReminder,
            viewModel = placesViewModel,
            onSaved = {
                showLocationDialog = false
                // A location reminder is a notification too.
                notificationPermission.onFeatureEnabled()
            },
            onDismiss = { showLocationDialog = false },
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

/** Where the subtasks section starts in the editor's list: after the headline, the notes, the quick moves and the properties. */
private const val TITLE_KEYBOARD_DELAY_MS = 300L
private const val SUBTASKS_ITEM_INDEX = 4

private const val ADD_SUBTASK_KEY = "add_subtask"

/** The add-subtask field's place in the list: after the section's heading and its [subtasks] rows. */
private fun addSubtaskItemIndex(subtasks: Int): Int = SUBTASKS_ITEM_INDEX + 1 + subtasks

/**
 * Scrolls by what it takes to show the whole item [key] at the bottom of the list. An item that is
 * already in view stays put; one past the end is first brought into the viewport.
 */
private suspend fun LazyListState.keepItemInView(key: Any, index: Int) {
    withFrameNanos { } // The new row is laid out by then.
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key }
    if (item == null) {
        animateScrollToItem(index, scrollOffset = -info.viewportEndOffset / 2)
        return
    }
    val overflow = item.offset + item.size - info.viewportEndOffset
    if (overflow > 0) animateScrollBy(overflow.toFloat())
}

/** Long notes fold at about half of what is visible, never lower than this. */
private val MIN_NOTES_FOLD = 200.dp

/** How many saved places the Remind row offers as "Arrive: Home" presets. */
private const val MAX_PLACE_PRESETS = 3

/** The scrim over the list behind the editor. */
private const val EDITOR_SCRIM_ALPHA = 0.32f

/** The gap between the status bar and the editor sheet, where the list stays in view. */
private val EDITOR_SHEET_TOP_GAP = 12.dp

/** The headline: the title reads as the task, semibold, a step under the page title. */
@Composable
internal fun editorTitleStyle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )

/**
 * A property of the editor: [row] draws it when [set] (or while its presets are open); otherwise it
 * waits in the Add row as [addLabel]. [present] is false where the property does not exist (assign
 * and comments outside shared projects); [addable] false keeps it out of the Add row.
 */
private class EditorEntry(
    val key: String,
    val set: Boolean,
    val present: Boolean = true,
    val addLabel: String = "",
    val addIcon: ImageVector = Icons.Outlined.Add,
    val addable: Boolean = true,
    val onAdd: () -> Unit = {},
    val row: @Composable () -> Unit,
)

/**
 * The top of the editor sheet: close on the left, the handle that says it pulls down, the task menu
 * (relation, delete) on the right.
 */
@Composable
private fun EditorHeader(
    onClose: () -> Unit,
    onAddRelation: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onSaveAsTemplate: () -> Unit,
    menuEnabled: Boolean,
    modifier: Modifier = Modifier,
    onBreakIntoSteps: (() -> Unit)? = null,
    onCopyNotes: (() -> Unit)? = null,
) {
    var showMore by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Close")
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            VicuDragHandleBar()
        }
        Box {
            IconButton(onClick = { showMore = true }, enabled = menuEnabled) {
                Icon(Icons.Default.MoreHoriz, contentDescription = "More")
            }
            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                DropdownMenuItem(
                    text = { Text("Add relation") },
                    onClick = {
                        showMore = false
                        onAddRelation()
                    },
                )
                if (onBreakIntoSteps != null) {
                    DropdownMenuItem(
                        text = { Text("Break into steps") },
                        onClick = {
                            showMore = false
                            onBreakIntoSteps()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    onClick = {
                        showMore = false
                        onDuplicate()
                    },
                )
                if (onCopyNotes != null) {
                    DropdownMenuItem(
                        text = { Text("Copy notes") },
                        onClick = {
                            showMore = false
                            onCopyNotes()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Save as template") },
                    onClick = {
                        showMore = false
                        onSaveAsTemplate()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete task", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMore = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** The quick moves: tonal buttons that scroll sideways when they do not fit. */
@Composable
private fun QuickMoveRow(moves: List<WhenQuickChoice>, onMove: (WhenQuickChoice) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        moves.forEach { move ->
            FilledTonalButton(
                onClick = { onMove(move) },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(quickMoveIcon(move.id), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(move.label)
            }
        }
    }
}

private fun quickMoveIcon(id: WhenQuickChoice.Id): ImageVector = when (id) {
    WhenQuickChoice.Id.TODAY -> Icons.Outlined.WbSunny
    WhenQuickChoice.Id.TOMORROW -> Icons.Outlined.Today
    WhenQuickChoice.Id.WEEKEND -> Icons.Outlined.Weekend
    WhenQuickChoice.Id.NEXT_WEEK -> Icons.Outlined.DateRange
}
