package com.rendyhd.vicu.ui.components.task

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.SharedContent
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.picker.CustomTimeDialog
import com.rendyhd.vicu.ui.components.picker.RecurrencePickerDialog
import com.rendyhd.vicu.ui.components.picker.ReminderPickerDialog
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.keyboardTargetHeight
import com.rendyhd.vicu.ui.rememberImagePicker
import com.rendyhd.vicu.ui.screens.taskdetail.PropertyDivider
import com.rendyhd.vicu.ui.screens.taskdetail.PropertyGroup
import com.rendyhd.vicu.ui.screens.taskdetail.PropertyRow
import com.rendyhd.vicu.ui.screens.taskdetail.editorTitleStyle
import com.rendyhd.vicu.ui.screens.taskdetail.reminderPresets
import com.rendyhd.vicu.ui.screens.taskentry.FieldSource
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryViewModel
import com.rendyhd.vicu.ui.screens.taskentry.entryLabelsWords
import com.rendyhd.vicu.ui.screens.taskentry.entryPriorityName
import com.rendyhd.vicu.ui.screens.taskentry.entryProjectChipLabel
import com.rendyhd.vicu.ui.screens.taskentry.entryRemindWords
import com.rendyhd.vicu.ui.screens.taskentry.resolveEntryFields
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.PlatformFiles
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TaskTemplate
import com.rendyhd.vicu.util.TaskTemplates
import com.rendyhd.vicu.util.WhenLogic
import com.rendyhd.vicu.util.WhenValue
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.getPrefixes
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The new-task sheet (design concept 3, "one sheet, two heights"), drawn over the screens.
 *
 * Compact, it sits on the keyboard: the title (its parsed words highlighted), a Notes line, and one
 * line of chips for When, Remind and Priority that say their value in words (tags, a project and a
 * repeat join it once they hold something). A chip opens
 * its tray where the keyboard was, at the keyboard's height, so the sheet does not move; tapping
 * the title (or the chip again) brings the keyboard back. A chip shows what the parser read from
 * the title unless the user set it in its tray; then the tray wins and the word loses its
 * highlight ([resolveEntryFields]).
 *
 * The expand button (or a drag up on the handle) grows the same sheet to full height: the title,
 * the notes, a checklist and a row per property, each opening the same tray content in place.
 * Nothing is saved until Send or Add, and collapsing keeps everything.
 *
 * The sheet rides on the keyboard: the room at its foot follows the keyboard frame by frame
 * ([SheetLayout]), so it opens with the keyboard, appearing on the navigation bar and going up with
 * it, and its title has the focus from the first frame.
 */
@Composable
fun TaskEntrySheet(
    defaultProjectId: Long?,
    defaultDueDate: String? = null,
    onDismiss: () -> Unit,
    onTaskCreated: (Long) -> Unit,
    sharedContent: SharedContent? = null,
    /** Opens the task a template just made. */
    onOpenMadeTask: (Long) -> Unit = {},
    /** Opens the sheet on this template, to edit it, and closes it when that is done. */
    editTemplateId: String? = null,
    viewModel: TaskEntryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val titleFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val softKeyboard = LocalSoftwareKeyboardController.current
    val descriptionEditorController = rememberDescriptionEditorController()
    val notificationPermission = koinInject<NotificationPermissionCoordinator>()
    val isDarkTheme = isSystemInDarkTheme()

    var expanded by rememberSaveable { mutableStateOf(false) }
    // The chip whose tray replaces the keyboard (compact), or the row that is open (full height).
    var trayName by rememberSaveable { mutableStateOf<String?>(null) }
    val tray = trayName?.let { EntryTray.valueOf(it) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showRecurrencePicker by remember { mutableStateOf(false) }
    var showCustomTime by remember { mutableStateOf(false) }

    // Shared text arrives as notes, so they show at once then, and keep showing while the user
    // edits them down to nothing.
    // At full height the notes are always there; back to compact they stay only once opened.
    var notesShown by rememberSaveable { mutableStateOf(false) }
    val notesOpened = notesShown || state.description.isNotBlank() || state.pendingImages.isNotEmpty()
    LaunchedEffect(notesOpened) { if (notesOpened) notesShown = true }
    val showNotes = expanded || notesOpened

    // TextFieldValue for cursor position tracking (needed for autocomplete)
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
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
    LaunchedEffect(defaultProjectId, defaultDueDate, sharedContent, editTemplateId) {
        val recreated = firstRun && initializedBeforeRecreation && viewModel.isInitialized
        firstRun = false
        if (!recreated) {
            if (editTemplateId != null) {
                expanded = true
                viewModel.startEditingTemplate(editTemplateId)
            } else if (sharedContent != null) {
                viewModel.initWithSharedContent(defaultProjectId, sharedContent)
            } else {
                viewModel.initWithDefaults(defaultProjectId, defaultDueDate)
            }
        }
        initializedBeforeRecreation = true
    }

    // Open and close: the sheet appears over a fading scrim (composed, and its title focused, in the
    // first frame) and leaves sliding down; [onDismiss] runs once it has left.
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(visibility.isIdle, visibility.currentState, closing) {
        if (closing && visibility.isIdle && !visibility.currentState) onDismiss()
    }

    // The room for the keyboard at the sheet's foot, read while laying out ([SheetLayout]): the
    // sheet rides on the keyboard as it slides, so it opens with it, starting on the navigation bar.
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    val keyboard = keyboardTargetHeight()
    val currentKeyboard by rememberUpdatedState(keyboard)
    // When the keyboard comes back in a tray's place, the room is its full height from the start, so
    // the sheet does not move: the height it had last time until it starts to open, then the height
    // it opens to. Set in the same event that brings the keyboard back (a tray closing), so not even
    // one frame is drawn without the room; it ends when the keyboard closes, or when it has not
    // started to open after a while. A keyboard that is already up needs no room kept for it.
    var expectingKeyboard by remember { mutableStateOf(false) }
    // Once the expected keyboard has started to open, a target of zero is the keyboard closing
    // again, and the room goes down with it at once.
    var expectedKeyboardStarted by remember { mutableStateOf(false) }
    var keyboardRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(keyboardRequests) {
        delay(KEYBOARD_WAIT_MS)
        if (currentKeyboard == 0.dp) expectingKeyboard = false
    }
    fun expectKeyboard() {
        if (currentKeyboard > 0.dp) return
        expectingKeyboard = true
        expectedKeyboardStarted = false
        keyboardRequests++
    }
    LaunchedEffect(keyboard) {
        if (keyboard == 0.dp) {
            expectingKeyboard = false
        } else {
            expectedKeyboardStarted = true
            lastKeyboardHeight = keyboard
        }
    }
    val heldRoom = when {
        !expectingKeyboard -> 0.dp
        keyboard > 0.dp -> keyboard
        expectedKeyboardStarted -> 0.dp
        else -> lastKeyboardHeight
    }
    val keyboardRoom: Density.() -> Int = {
        maxOf(ime.getBottom(this), navigationBars.getBottom(this), heldRoom.roundToPx())
    }
    // Exactly the keyboard's height (which counts the navigation bar under it), so swapping one for
    // the other never moves the sheet; a tray scrolls when its content is taller.
    val trayHeight = max(lastKeyboardHeight, MIN_TRAY_HEIGHT)

    fun focusTitle() {
        expectKeyboard()
        titleFocus.requestFocus()
        softKeyboard?.show()
    }

    /** Opens [which] where the keyboard was; the same chip again goes back to the keyboard. */
    fun toggleTray(which: EntryTray) {
        if (tray == which) {
            trayName = null
            if (!expanded) focusTitle()
        } else {
            trayName = which.name
            focusManager.clearFocus()
            softKeyboard?.hide()
        }
    }

    fun expand() {
        expanded = true
        focusManager.clearFocus()
        softKeyboard?.hide()
    }

    fun collapse() {
        expanded = false
        trayName = null
        focusTitle()
    }

    // Closing a sheet that has no title drops what was picked on its chips: a draft is the text,
    // and a pick without one would otherwise stop the next title from being read.
    fun dismiss() {
        if (closing) return
        viewModel.endTemplateEdit()
        if (viewModel.uiState.value.title.isBlank()) viewModel.reset()
        focusManager.clearFocus()
        softKeyboard?.hide()
        closing = true
        visibility.targetState = false
    }

    // The edit of a template is over: back to the draft under it, or away when it is what opened
    // the sheet.
    fun endTemplateEdit() {
        if (editTemplateId != null) {
            dismiss()
            return
        }
        viewModel.endTemplateEdit()
        notesShown = false
        val title = viewModel.uiState.value.title
        textFieldValue = TextFieldValue(title, TextRange(title.length))
        collapse()
    }

    fun editTemplate(template: TaskTemplate) {
        trayName = null
        expanded = true
        focusManager.clearFocus()
        softKeyboard?.hide()
        viewModel.startEditingTemplate(template.id)
    }

    // The draft stays when the sheet closes, so it can also be cleared by hand: everything goes, as
    // after a save, and Undo stands in the button's place until the user types or closes the sheet.
    fun clearDraft() {
        viewModel.clearDraft()
        textFieldValue = TextFieldValue("")
        notesShown = false
    }

    fun undoClearDraft() {
        viewModel.undoClearDraft()
        val title = viewModel.uiState.value.title
        textFieldValue = TextFieldValue(title, TextRange(title.length))
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !closing,
    ) {
        when {
            tray != null && !expanded -> toggleTray(tray)
            tray != null -> trayName = null
            state.editingTemplate != null -> endTemplateEdit()
            expanded -> collapse()
            else -> dismiss()
        }
    }

    // Typing in the notes puts the keyboard back.
    LaunchedEffect(descriptionEditorController.isEditorFocused) {
        if (descriptionEditorController.isEditorFocused && !expanded && trayName != null) {
            expectKeyboard()
            trayName = null
        }
    }

    // The last template was removed: its chip is gone, so the tray cannot stay. The keyboard stays
    // down, because it would cover the Undo on the snackbar.
    LaunchedEffect(state.templates.isEmpty(), tray) {
        if (state.templates.isEmpty() && tray == EntryTray.TEMPLATE) trayName = null
    }

    LaunchedEffect(state.madeFromTemplateId) {
        state.madeFromTemplateId?.let { id ->
            viewModel.onMadeFromTemplateHandled()
            trayName = null
            dismiss()
            onOpenMadeTask(id)
        }
    }

    LaunchedEffect(state.savedTaskId) {
        state.savedTaskId?.let { id ->
            onTaskCreated(id)
            val keepOpen = state.keepEntryOpen
            viewModel.reset()
            textFieldValue = TextFieldValue("")
            notesShown = false
            trayName = null
            expanded = false
            if (keepOpen) {
                // Mass-add: stay open and re-focus for the next task.
                focusTitle()
            } else {
                dismiss()
            }
        }
    }

    // What the task is going to get, and what each chip says (the title's highlights, the chips and
    // the save all read the same fields).
    val day = LocalClockDay.current
    val dateFormat = LocalDateFormat.current
    val fields = resolveEntryFields(state, day.zone)
    val parseResult = state.parseResult
    val whenValue = WhenLogic.valueOfDue(fields.dueDate, day.zone)
    val hasDate = whenValue.date != null
    // Where a parsed word sits in the title, in root coordinates: the chip that was read from it
    // travels out of that spot (card 4.11b).
    var titleBounds by remember { mutableStateOf<Rect?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = if (expanded) editorTitleStyle() else MaterialTheme.typography.titleMedium
    val textInset = with(LocalDensity.current) { TITLE_FIELD_TEXT_INSET_DP.dp.toPx() }
    fun tokenRectOf(type: TokenType): Rect? {
        val token = parseResult?.tokens?.firstOrNull { it.type == type } ?: return null
        return titleTokenRect(textMeasurer, titleStyle, state.title, token, titleBounds, textInset)
    }
    fun tint(source: FieldSource?, type: TokenType): Color? =
        if (source == FieldSource.TEXT) tokenChipColor(type, isDarkTheme) else null

    // Gate on the effective (parsed) title so NLP-only input like "#work !1" doesn't look enabled
    // and then silently no-op in save().
    val canSave = viewModel.effectiveTitle().isNotBlank() && !state.isSaving
    val editingTemplate = state.editingTemplate != null
    val save = {
        descriptionEditorController.flush()
        if (editingTemplate) {
            viewModel.saveTemplateEdit()
            endTemplateEdit()
        } else {
            viewModel.save(dateFormat)
        }
    }

    // The words of each property, shared by the chips and the rows.
    val dateWords = if (hasDate) {
        val due = DateDisplay.formatDue(DateContext.CHIP, fields.dueDate, day.date, day.zone, dateFormat)
        // A time block holds the time: the due date alone would read as a day without one.
        parseResult?.block?.takeIf { fields.blockStart.isNotEmpty() }
            ?.let { "$due, ${DateDisplay.formatBlockRange(it.start, it.end, dateFormat)}" }
            ?: due
    } else {
        null
    }
    val remindWords = entryRemindWords(state.reminders, state.placeReminder, dateFormat)
    val priorityWords = entryPriorityName(fields.priority)
    val pickedLabels = state.allLabels.filter { it.id in state.selectedLabelIds }.map { it.title }
    val parsedLabels = parseResult?.labels.orEmpty()
    val labelWords = entryLabelsWords(pickedLabels, parsedLabels)
    val projectTitle = state.allProjects.find { it.id == fields.projectId }?.title ?: "Project"
    val projectWords = entryProjectChipLabel(fields, projectTitle, state.title, parseResult)
    val repeatWords = if (fields.recurrence.isRecurring) {
        DateUtils.formatRecurrence(fields.recurrence.repeatAfter, fields.recurrence.repeatMode)
    } else {
        null
    }

    /** What a tray or row holds; the compact tray and the full-height row show the same. */
    @Composable
    fun TrayContent(which: EntryTray) {
        when (which) {
            EntryTray.WHEN -> {
                val now = remember(which) { SystemTimeSource.now().toLocalDateTime(day.zone).time }
                WhenTrayContent(
                    current = whenValue,
                    today = day.date,
                    now = LocalTime(now.hour, now.minute),
                    onPick = { value -> pickWhen(viewModel, value, day.zone) },
                    onCustomTime = { showCustomTime = true },
                )
            }
            EntryTray.REMIND -> {
                // The times are read when the tray opens, so "In 1 hour" means from now.
                val presets = remember(which, fields.dueDate, dateFormat, editingTemplate) {
                    reminderPresets(fields.dueDate, SystemTimeSource.now(), day.zone, dateFormat)
                        .filter { !editingTemplate || it.reminder.relativeTo == "due_date" }
                }
                RemindTrayContent(
                    reminders = state.reminders,
                    place = state.placeReminder,
                    presets = presets,
                    places = state.places,
                    onAdd = { reminder ->
                        viewModel.addReminder(reminder)
                        // A reminder needs notifications: ask now if the permission is missing.
                        notificationPermission.onFeatureEnabled()
                    },
                    onRemove = viewModel::removeReminder,
                    onPickTime = { showReminderPicker = true },
                    onPickPlace = { place, trigger ->
                        viewModel.setPlaceReminder(place, trigger)
                        notificationPermission.onFeatureEnabled()
                    },
                    onRemovePlace = viewModel::clearPlaceReminder,
                    relativeOnly = editingTemplate,
                )
            }
            EntryTray.PRIORITY -> PriorityTrayContent(current = fields.priority, onPick = viewModel::setPriority)
            EntryTray.TAGS -> TagsTrayContent(
                labels = state.allLabels,
                selectedIds = state.selectedLabelIds,
                readFromTitle = parsedLabels,
                onToggle = viewModel::toggleLabel,
                onCreate = viewModel::createAndAddLabel,
            )
            EntryTray.PROJECT -> ProjectTrayContent(
                projects = state.allProjects,
                inboxProjectId = state.inboxProjectId,
                selectedId = fields.projectId,
                onPick = viewModel::setProjectId,
            )
            EntryTray.REPEAT -> RepeatTrayContent(
                current = fields.recurrence,
                onPick = viewModel::setRecurrence,
                onOther = { showRecurrencePicker = true },
            )
            EntryTray.TEMPLATE -> TemplateTrayContent(
                templates = state.templates,
                details = { template ->
                    val due = template.due?.let {
                        DateDisplay.formatDue(DateContext.CHIP, TaskTemplates.dueDateOf(it, day.date, day.zone), day.date, day.zone, dateFormat)
                    }
                    val subtasks = template.subtasks.size.takeIf { it > 0 }?.let { if (it == 1) "1 subtask" else "$it subtasks" }
                    listOfNotNull(due, subtasks).joinToString(", ").ifEmpty { null }
                },
                onUse = viewModel::useTemplate,
                onEdit = ::editTemplate,
                onRemove = viewModel::removeTemplate,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The scrim: a tap on it closes the sheet.
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(VicuMotion.defaultEffectsSpec()),
            exit = fadeOut(VicuMotion.fastEffectsSpec()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close",
                        onClick = ::dismiss,
                    ),
            )
        }

        // Compact, an open tray takes the keyboard's place at the keyboard's height.
        val openTray = tray.takeIf { !expanded }
        AnimatedVisibility(
            visibleState = visibility,
            modifier = Modifier.align(Alignment.BottomCenter),
            // It appears where it starts, on the navigation bar, and goes up with the keyboard. Also
            // sliding in from below, it fell behind the keyboard and came out over it only after the
            // keyboard had stopped.
            enter = fadeIn(VicuMotion.fastEffectsSpec()),
            exit = slideOutVertically(VicuMotion.defaultSpatialSpec<IntOffset>()) { it } +
                fadeOut(VicuMotion.fastEffectsSpec()),
        ) {
            SheetLayout(
                expanded = expanded,
                keyboardRoom = keyboardRoom,
                tray = openTray?.let { shown ->
                    @Composable {
                        TrayPanel(height = trayHeight) {
                            AnimatedContent(
                                targetState = shown,
                                transitionSpec = {
                                    fadeIn(VicuMotion.fastEffectsSpec()) togetherWith fadeOut(VicuMotion.fastEffectsSpec())
                                },
                                label = "entryTray",
                            ) { which -> TrayContent(which) }
                        }
                    }
                },
                modifier = Modifier
                    .widthIn(max = MAX_SHEET_WIDTH)
                    .fillMaxWidth()
                    // At full height the sheet ends 12 dp under the status bar.
                    .statusBarsPadding()
                    .padding(top = 12.dp),
            ) {
                Column(modifier = Modifier.clearDescriptionEditorFocusOnHostTap(descriptionEditorController)) {
                    if (expanded) {
                        ExpandedHeader(
                            canSave = canSave,
                            onCollapse = { if (editingTemplate) endTemplateEdit() else collapse() },
                            onAdd = save,
                            editingTemplate = editingTemplate,
                            clear = when {
                                editingTemplate -> null
                                state.hasDraft -> "Clear" to ::clearDraft
                                state.canUndoClear -> "Undo" to ::undoClearDraft
                                else -> null
                            },
                        )
                    } else {
                        GrowHandle(onGrow = ::expand, onClose = ::dismiss)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        // The title with its parsed words highlighted, across the whole sheet; compact,
                        // its buttons are in the row on the keyboard.
                        var fieldSize by remember { mutableStateOf(IntSize.Zero) }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            TextField(
                                value = textFieldValue,
                                onValueChange = { newValue ->
                                    textFieldValue = newValue
                                    viewModel.setTitle(newValue.text)
                                },
                                placeholder = { Text("New task", style = titleStyle) },
                                maxLines = if (expanded) 6 else 3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(titleFocus)
                                    .onFocusChanged {
                                        // Tapping the title brings the keyboard back where the tray was.
                                        if (it.isFocused && !expanded && trayName != null) {
                                            expectKeyboard()
                                            trayName = null
                                        }
                                    }
                                    .onSizeChanged { fieldSize = it }
                                    .onGloballyPositioned { titleBounds = it.boundsInRoot() },
                                textStyle = titleStyle,
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
                            // The keyboard opens with the sheet: the title is focused as soon as
                            // it is there (the sheet's content is composed once it starts to rise).
                            LaunchedEffect(Unit) { titleFocus.requestFocus() }
                        }

                        // Notes can be written before the task exists: the Notes button on the keyboard
                        // row opens them under the title. At full height they are always there.
                        if (showNotes) {
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
                                modifier = Modifier.padding(bottom = 8.dp),
                                plain = true,
                                hostedToolbar = true,
                            )
                        }

                        if (expanded) {
                            Checklist(
                                items = state.subtasks,
                                onAdd = viewModel::addSubtask,
                                onRemove = viewModel::removeSubtask,
                            )
                            Spacer(Modifier.height(12.dp))
                            PropertyGroup {
                                fun toggleRow(which: EntryTray) {
                                    focusManager.clearFocus()
                                    trayName = if (tray == which) null else which.name
                                }
                                val rows = listOfNotNull(
                                    Triple(EntryTray.WHEN, Icons.Outlined.CalendarMonth, "When" to dateWords),
                                    Triple(EntryTray.REMIND, Icons.Outlined.Notifications, "Remind" to remindWords),
                                    Triple(EntryTray.PRIORITY, Icons.Outlined.Flag, "Priority" to priorityWords),
                                    Triple(EntryTray.TAGS, Icons.AutoMirrored.Outlined.Label, "Labels" to labelWords),
                                    Triple(EntryTray.REPEAT, Icons.Outlined.Repeat, "Repeat" to repeatWords),
                                    Triple(EntryTray.PROJECT, Icons.Outlined.Folder, "Project" to projectWords),
                                    Triple(EntryTray.TEMPLATE, Icons.Outlined.Description, "Template" to null)
                                        .takeIf { state.templates.isNotEmpty() && !editingTemplate },
                                )
                                rows.forEachIndexed { index, (which, icon, words) ->
                                    if (index > 0) PropertyDivider()
                                    EntryRow(
                                        icon = icon,
                                        name = words.first,
                                        value = words.second,
                                        open = tray == which,
                                        onClick = { toggleRow(which) },
                                    ) { TrayContent(which) }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            // One line of chips: When, Remind and Priority. A chip says its value in
                            // words; the colour of a value read from the title is the colour of its
                            // highlight. Labels, the project and a repeat live at full height and join
                            // the line only once they hold something (typed in the title or picked
                            // there); a line that does not fit scrolls sideways.
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TaskEntryChip(
                                    label = dateWords ?: "When",
                                    icon = Icons.Outlined.CalendarMonth,
                                    onClick = { toggleTray(EntryTray.WHEN) },
                                    selected = tray == EntryTray.WHEN,
                                    tint = tint(fields.dueSource, TokenType.DATE),
                                    clearDescription = if (hasDate) "Clear date" else null,
                                    onClear = if (hasDate) viewModel::clearDueDate else null,
                                    tokenRect = { tokenRectOf(TokenType.DATE) },
                                )
                                val placeFromTitle = state.pickedPlace == null && state.placeMatch != null
                                TaskEntryChip(
                                    label = remindWords ?: "Remind",
                                    icon = Icons.Outlined.Notifications,
                                    onClick = { toggleTray(EntryTray.REMIND) },
                                    selected = tray == EntryTray.REMIND,
                                    tint = if (placeFromTitle && state.reminders.isEmpty()) tokenChipColor(TokenType.PLACE, isDarkTheme) else null,
                                    tokenRect = { tokenRectOf(TokenType.PLACE) },
                                )
                                TaskEntryChip(
                                    label = priorityWords ?: "Priority",
                                    icon = Icons.Outlined.Flag,
                                    onClick = { toggleTray(EntryTray.PRIORITY) },
                                    selected = tray == EntryTray.PRIORITY,
                                    tint = tint(fields.prioritySource, TokenType.PRIORITY),
                                    tokenRect = { tokenRectOf(TokenType.PRIORITY) },
                                )
                                if (labelWords != null) {
                                    TaskEntryChip(
                                        label = labelWords,
                                        icon = Icons.AutoMirrored.Outlined.Label,
                                        onClick = { toggleTray(EntryTray.TAGS) },
                                        selected = tray == EntryTray.TAGS,
                                        tint = if (parsedLabels.isNotEmpty()) tokenChipColor(TokenType.LABEL, isDarkTheme) else null,
                                        tokenRect = { tokenRectOf(TokenType.LABEL) },
                                    )
                                }
                                // The project the sheet was opened in goes without saying; one typed
                                // in the title or picked at full height shows.
                                if (fields.parsedProjectName != null || state.projectIsManual) {
                                    TaskEntryChip(
                                        label = projectWords,
                                        icon = Icons.Outlined.Folder,
                                        onClick = { toggleTray(EntryTray.PROJECT) },
                                        selected = tray == EntryTray.PROJECT,
                                        tint = if (fields.parsedProjectName != null) tokenChipColor(TokenType.PROJECT, isDarkTheme) else null,
                                        tokenRect = { tokenRectOf(TokenType.PROJECT) },
                                    )
                                }
                                if (repeatWords != null) {
                                    TaskEntryChip(
                                        label = repeatWords,
                                        icon = Icons.Outlined.Repeat,
                                        onClick = { toggleTray(EntryTray.REPEAT) },
                                        selected = tray == EntryTray.REPEAT,
                                        tint = tint(fields.recurrenceSource, TokenType.RECURRENCE),
                                        tokenRect = { tokenRectOf(TokenType.RECURRENCE) },
                                    )
                                }
                                // Last, and only once there is a template to choose (section 18.3).
                                if (state.templates.isNotEmpty()) {
                                    TaskEntryChip(
                                        label = "Template",
                                        icon = Icons.Outlined.Description,
                                        onClick = { toggleTray(EntryTray.TEMPLATE) },
                                        selected = tray == EntryTray.TEMPLATE,
                                        tokenRect = { null },
                                    )
                                }
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
                        Spacer(modifier = Modifier.height(if (expanded) 12.dp else 4.dp))
                    }

                    // Compact, the sheet's actions ride on the keyboard (or the tray in its place). They
                    // stay outside the scrolling part, so long notes cannot push them away. At full
                    // height the header has them.
                    if (!expanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!showNotes) {
                                TextButton(
                                    onClick = {
                                        // Straight into the notes, with the cursor in them; a
                                        // tray gives way to the keyboard as the notes take focus.
                                        notesShown = true
                                        descriptionEditorController.requestFocus()
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Notes", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            when {
                                state.hasDraft -> IconButton(onClick = ::clearDraft) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear the draft")
                                }
                                state.canUndoClear -> TextButton(
                                    onClick = ::undoClearDraft,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Undo") }
                            }
                            FilledTonalIconButton(onClick = ::expand) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Show all details")
                            }
                            FilledIconButton(onClick = save, enabled = canSave) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Save task")
                            }
                        }
                    }

                    // The notes' formatting toolbar sits on the keyboard while the notes are edited.
                    if (descriptionEditorController.isEditorFocused) {
                        descriptionEditorController.hostedToolbar?.invoke(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showReminderPicker) {
        ReminderPickerDialog(
            reminders = state.reminders,
            onAddReminder = { reminder ->
                viewModel.addReminder(reminder)
                notificationPermission.onFeatureEnabled()
            },
            onRemoveReminder = viewModel::removeReminder,
            onDismiss = { showReminderPicker = false },
            onEditReminder = viewModel::editReminder,
            dueDate = fields.dueDate,
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

    if (showCustomTime) {
        CustomTimeDialog(
            initial = whenValue.time ?: LocalTime(9, 0),
            onPick = { time ->
                showCustomTime = false
                pickWhen(viewModel, WhenLogic.withTime(whenValue, time, day.date), day.zone)
            },
            onDismiss = { showCustomTime = false },
        )
    }
}

/** A When tray pick as the due date the task gets; null clears it. */
private fun pickWhen(viewModel: TaskEntryViewModel, value: WhenValue?, zone: kotlinx.datetime.TimeZone) {
    val due = value?.let { WhenLogic.dueOf(it, zone) }
    if (due == null) viewModel.clearDueDate() else viewModel.setDueDate(due.toString())
}

/** The compact sheet's handle: a drag up grows the sheet to full height, a drag down closes it. */
@Composable
private fun GrowHandle(onGrow: () -> Unit, onClose: () -> Unit) {
    val threshold = with(LocalDensity.current) { GROW_DRAG_DP.dp.toPx() }
    VicuDragHandle(
        modifier = Modifier
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Show all details") {
                        onGrow()
                        true
                    },
                    CustomAccessibilityAction("Close") {
                        onClose()
                        true
                    },
                )
            }
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        pulled += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            pulled < -threshold -> onGrow()
                            pulled > threshold -> onClose()
                        }
                    },
                )
            },
    )
}

/**
 * The full-height sheet's header: back to compact, its name, [clear] (Clear, or Undo right after
 * it, with what it does) and Add. A drag down collapses it.
 */
@Composable
private fun ExpandedHeader(
    canSave: Boolean,
    onCollapse: () -> Unit,
    onAdd: () -> Unit,
    editingTemplate: Boolean,
    clear: Pair<String, () -> Unit>?,
) {
    val threshold = with(LocalDensity.current) { GROW_DRAG_DP.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pointerInput(Unit) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        pulled += dragAmount
                        change.consume()
                    },
                    onDragEnd = { if (pulled > threshold) onCollapse() },
                )
            }
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                if (editingTemplate) Icons.Outlined.Close else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (editingTemplate) "Close without saving" else "Show fewer details",
            )
        }
        Text(
            if (editingTemplate) "Edit template" else "New task",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
        clear?.let { (label, action) ->
            TextButton(onClick = action) { Text(label) }
            Spacer(Modifier.width(4.dp))
        }
        Button(onClick = onAdd, enabled = canSave) { Text(if (editingTemplate) "Save" else "Add") }
    }
}

/** A property of the full-height sheet: its row, and under it the same content as its tray. */
@Composable
private fun EntryRow(
    icon: ImageVector,
    name: String,
    value: String?,
    open: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    PropertyRow(
        icon = icon,
        name = name,
        value = value,
        description = "$name: ${value ?: "none"}",
        onClick = onClick,
        expandable = true,
        open = open,
    )
    // Springs follow the system animator scale: at scale 0 the row opens and closes at once.
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(VicuMotion.defaultSpatialSpec()) + fadeIn(VicuMotion.fastEffectsSpec()),
        exit = shrinkVertically(VicuMotion.defaultSpatialSpec()) + fadeOut(VicuMotion.fastEffectsSpec()),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
    }
}

/**
 * The sheet: [content] (the handle or header, the title and the rest) over the room at its foot,
 * which the keyboard fills, or [tray] in the keyboard's place.
 *
 * The room ([keyboardRoom]) is read while laying out, so it follows the keyboard on every frame of
 * its slide and the content rides on the keyboard's top edge without anything recomposing. Animated
 * as part of the sheet's size, the room made the sheet climb after the keyboard. Only the sheet's
 * own changes animate: compact, the content's height (a line of chips growing); growing to full
 * height, the top edge from where the compact sheet had it to the top, where it then stays whatever
 * the keyboard does.
 */
@Composable
private fun SheetLayout(
    expanded: Boolean,
    keyboardRoom: Density.() -> Int,
    tray: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val growth = animateFloatAsState(if (expanded) 1f else 0f, VicuMotion.defaultSpatialSpec(), label = "sheetGrowth")
    // The compact sheet's height when it was last compact, where growing to full height starts.
    val lastCompactHeight = remember { IntArray(1) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Layout(
            contents = listOf(
                {
                    // At full height the content fills what the room leaves.
                    Box(
                        modifier = if (expanded) Modifier else Modifier.animateContentSize(VicuMotion.defaultSpatialSpec()),
                        propagateMinConstraints = true,
                    ) { content() }
                },
                { tray?.invoke() },
            ),
        ) { (contentMeasurables, trayMeasurables), constraints ->
            val width = constraints.maxWidth
            val trayPlaceable = trayMeasurables.firstOrNull()?.measure(Constraints(width, width, 0, constraints.maxHeight))
            val room = trayPlaceable?.height ?: keyboardRoom()
            val contentHeight = (constraints.maxHeight - room).coerceAtLeast(0)
            val contentPlaceable = contentMeasurables.first().measure(
                if (expanded) Constraints.fixed(width, contentHeight) else Constraints(width, width, 0, contentHeight),
            )
            val compactHeight = if (expanded) {
                lastCompactHeight[0]
            } else {
                (contentPlaceable.height + room).also { lastCompactHeight[0] = it }
            }
            val height = (compactHeight + (constraints.maxHeight - compactHeight) * growth.value).roundToInt()
                .coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                contentPlaceable.place(0, 0)
                trayPlaceable?.place(0, height - room)
            }
        }
    }
}

/** Where the keyboard was: a tray of the compact sheet, scrolling when its content is taller. */
@Composable
private fun TrayPanel(height: Dp, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainer)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) { content() }
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
    }
}

/**
 * The checklist of the full-height sheet: what becomes the task's subtasks when it is added. The
 * field keeps the keyboard up after each item, so a list is typed in one go.
 */
@Composable
private fun Checklist(items: List<String>, onAdd: (String) -> Unit, onRemove: (Int) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Column {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = scheme.outline,
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp).size(20.dp),
                )
                Text(item, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove $item", modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (items.isEmpty()) Icons.Outlined.Checklist else Icons.Outlined.Add,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp).size(20.dp),
            )
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (items.isEmpty()) "Checklist" else "Add an item") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
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

/**
 * The keyboard's height the last time it opened, for this process (a phone keyboard's usual height
 * before that). The sheet keeps this much room for a keyboard coming back in a tray's place until
 * it starts to open, and its trays are this tall.
 */
private var lastKeyboardHeight by mutableStateOf(320.dp)

/** How long the sheet keeps room for a keyboard that has not started to open. */
private const val KEYBOARD_WAIT_MS = 600L

/** A tray is at least this tall, for a keyboard much smaller than a phone's usual one. */
private val MIN_TRAY_HEIGHT = 200.dp

/** On a tablet the sheet stays a sheet rather than a full-width strip. */
private val MAX_SHEET_WIDTH = 640.dp

/** How far the handle must be dragged to grow the sheet or close it. */
private const val GROW_DRAG_DP = 40

/** The scrim over the screens, as a Material bottom sheet draws it. */
private const val SCRIM_ALPHA = 0.32f
