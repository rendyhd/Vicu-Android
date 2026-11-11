package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.permission.NotificationPermissionCoordinator
import com.rendyhd.vicu.ui.components.assistant.dashedOutline
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DraftBlock
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.LeftoverAction
import com.rendyhd.vicu.util.PlanDay
import com.rendyhd.vicu.util.PlannerNudge
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val ChipShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(10.dp)

/**
 * Plan my day on a phone (design M5, contract 14.10): the same four steps as the desktop, one at a
 * time, and every one works with taps. Arrange is the timeline with the draft on it; a dashed block
 * is tapped to move it, change its length or leave it without a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanMyDayScreen(
    onClose: () -> Unit = {},
    onTaskClick: (Long) -> Unit = {},
    viewModel: PlanMyDayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenReady(!state.isLoading)
    LaunchedEffect(viewModel) { viewModel.plannerOff.collect { off -> if (off) onClose() } }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.step == STEP_CHOOSE || state.step == STEP_ARRANGE,
    ) {
        viewModel.setStep(state.step - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan my day", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Exit Plan my day")
                    }
                },
                actions = {
                    if (state.assistantReady) AssistantSwitch(state.assistantOn, viewModel::setAssistantOn)
                },
            )
        },
        bottomBar = {
            if (state.step < STEP_READY) {
                SessionButtons(
                    step = state.step,
                    busy = state.accepting,
                    onBack = { viewModel.setStep(state.step - 1) },
                    onNext = { if (state.step == STEP_ARRANGE) viewModel.accept() else viewModel.setStep(state.step + 1) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SessionHeader(state, viewModel::setDay)
            when (state.step) {
                STEP_LEFTOVERS -> LeftoversStep(state, viewModel)
                STEP_CHOOSE -> ChooseStep(state, viewModel)
                STEP_ARRANGE -> ArrangeStep(state, viewModel, onTaskClick)
                else -> ReadyStep(state, viewModel, onClose)
            }
        }
    }
}

/** The suggestions' switch: on while Ask is set up, off sends and shows nothing. */
@Composable
private fun AssistantSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "Assistant suggestions" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun SessionHeader(state: PlanMyDayUiState, onDay: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PLAN_STEPS.indices.forEach { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (index <= state.step) colors.primary else colors.outlineVariant),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${state.step + 1} of ${PLAN_STEPS.size} · ${PLAN_STEPS[state.step]}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            listOf("Today", "Tomorrow").forEachIndexed { offset, label ->
                val selected = state.day != null && state.today != null &&
                    state.day == state.today.plus(offset, DateTimeUnit.DAY)
                val enabled = state.step <= STEP_CHOOSE
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onDay(offset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            selected -> colors.onPrimaryContainer
                            enabled -> colors.onSurfaceVariant
                            else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.primaryContainer else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionButtons(step: Int, busy: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (step > STEP_LEFTOVERS) {
            OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Back")
            }
        }
        Button(onClick = onNext, enabled = !busy, modifier = Modifier.weight(2f).heightIn(min = 48.dp)) {
            Text(listOf("Next", "Arrange", "Accept the plan")[step])
        }
    }
}

// --- Small parts -----------------------------------------------------------------------------------

/** A choice: 48 dp to the finger, a 32 dp chip to the eye, dashed while it is the assistant's. */
@Composable
private fun PlanChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    suggested: Boolean = false,
    selected: Boolean = false,
    leadingSpark: Boolean = false,
    description: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .dashedOutline(colors.primary, suggested)
                .then(
                    when {
                        selected -> Modifier.clip(ChipShape).background(colors.primaryContainer)
                        suggested -> Modifier
                        else -> Modifier.border(BorderStroke(1.dp, colors.outlineVariant), ChipShape)
                    },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingSpark) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.primary)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    selected -> colors.onPrimaryContainer
                    suggested -> colors.primary
                    else -> colors.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

/** Why the assistant says so. */
@Composable
private fun WhyLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(12.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GroupHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** A task's project and due date. */
@Composable
private fun TaskMeta(task: Task, state: PlanMyDayUiState) {
    val colors = MaterialTheme.colorScheme
    val clock = LocalClockDay.current
    val format = LocalDateFormat.current
    val project = state.projects[task.projectId]
    val due = DateDisplay.formatDue(DateContext.ROW, task.dueDate, clock.date, clock.zone, format)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (project != null) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(project.hexColor) ?: colors.onSurfaceVariant),
            )
            Text(
                text = project.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (due.isNotEmpty()) {
            Text(
                text = due,
                style = MaterialTheme.typography.bodySmall,
                color = if (DueDates.isOverdue(task.dueDate, clock.date, clock.zone)) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// --- Step 1: Leftovers -----------------------------------------------------------------------------

private val LeftoverButtons = listOf(
    LeftoverAction.TODAY to "Today",
    LeftoverAction.TOMORROW to "Tomorrow",
    LeftoverAction.NO_DATE to "No date",
    LeftoverAction.DONE to "Done",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeftoversStep(state: PlanMyDayUiState, viewModel: PlanMyDayViewModel) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "note") {
            Text(
                text = "Unfinished tasks from before. Nothing moves on its own: decide each one, or skip.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (state.assistantActive && state.asking) {
            item(key = "asking") {
                Text(
                    text = "Asking for suggestions…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.assistantActive && state.suggestedLeftovers.size > 1) {
            item(key = "apply") {
                PlanChip(
                    text = "Apply the ${state.suggestedLeftovers.size} suggestions",
                    onClick = viewModel::applySuggestions,
                    suggested = true,
                    leadingSpark = true,
                )
            }
        }
        if (state.leftoverRows.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Nothing left over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        }
        items(state.leftoverRows, key = { it.task.id }) { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (row.handled != null) 0.6f else 1f)
                    .border(1.dp, colors.outlineVariant, RowShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TaskMeta(row.task, state)
                if (!row.reason.isNullOrEmpty()) WhyLine(row.reason)
                if (row.handled != null) {
                    Text(row.handled, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LeftoverButtons.forEach { (action, label) ->
                            PlanChip(
                                text = label,
                                onClick = { viewModel.doLeftover(row.task, action) },
                                suggested = row.action == action,
                                description = "$label: ${row.task.title}",
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Step 2: Choose --------------------------------------------------------------------------------

@Composable
private fun ChooseStep(state: PlanMyDayUiState, viewModel: PlanMyDayViewModel) {
    val colors = MaterialTheme.colorScheme
    val day = state.day ?: return
    val today = state.today ?: return
    val name = sessionDayName(day, today)
    val suggestedIds = state.suggestedAdds.mapTo(HashSet()) { it.first.id }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item(key = "meter") { Meter(state, viewModel) }

        item(key = "day-heading") { GroupHeading(name) }
        if (state.onDay.isEmpty()) {
            item(key = "day-empty") {
                Text(
                    text = "Nothing yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        candidates("day", state.onDay, state, viewModel, reasons = true)

        if (state.suggestedAdds.isNotEmpty()) {
            item(key = "suggested-heading") { GroupHeading("Suggested") }
            items(state.suggestedAdds, key = { "suggested-${it.first.id}" }) { (task, pick) ->
                CandidateRow(task, state, viewModel, suggested = true, reason = pick.reason)
            }
        }

        val inbox = state.inbox.filter { it.id !in suggestedIds }
        if (inbox.isNotEmpty()) {
            item(key = "inbox-heading") { GroupHeading("Inbox") }
            candidates("inbox", inbox, state, viewModel)
        }

        val comingUp = state.comingUp.filter { it.id !in suggestedIds }
        if (comingUp.isNotEmpty()) {
            item(key = "coming-heading") { GroupHeading("Coming up") }
            candidates("coming", comingUp, state, viewModel)
        }

        item(key = "project-picker") { ProjectPicker(state, viewModel::setProject) }
        if (state.projectId != null && state.fromProject.isEmpty()) {
            item(key = "project-empty") {
                Text(
                    text = "Nothing else open in this project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        candidates("project", state.fromProject, state, viewModel)
    }
}

private fun LazyListScope.candidates(
    group: String,
    tasks: List<Task>,
    state: PlanMyDayUiState,
    viewModel: PlanMyDayViewModel,
    reasons: Boolean = false,
) {
    items(tasks, key = { "$group-${it.id}" }) { task ->
        CandidateRow(
            task,
            state,
            viewModel,
            reason = if (reasons && state.assistantActive) state.picks[task.id]?.reason else null,
        )
    }
}

@Composable
private fun CandidateRow(
    task: Task,
    state: PlanMyDayUiState,
    viewModel: PlanMyDayViewModel,
    suggested: Boolean = false,
    reason: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val ticked = task.id in state.ticked
    val zone = state.zone
    val day = state.day
    val placed = day != null && TimeBlocks.startsOn(task, day, zone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 1.dp)
            .dashedOutline(colors.primary, suggested)
            .clip(RowShape)
            .background(if (ticked) colors.surfaceContainer else Color.Transparent)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { viewModel.toggleTick(task.id) })
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TaskMeta(task, state)
            if (!reason.isNullOrEmpty()) WhyLine(reason)
        }
        if (!placed) LengthChip(task, state, viewModel::cycleLength)
    }
}

@Composable
private fun LengthChip(task: Task, state: PlanMyDayUiState, onCycle: (Long) -> Unit) {
    val draft = state.draftOf(task.id).takeIf { state.step == STEP_ARRANGE }
    val minutes = draft?.let { it.end - it.start } ?: state.lengths[task.id] ?: state.defaultLength
    val length = PlannerTimeline.formatLength(minutes)
    PlanChip(
        text = length,
        onClick = { onCycle(task.id) },
        suggested = state.assistantActive && task.id in state.suggestedLengths,
        description = "Length of ${task.title}: $length. Change it.",
    )
}

@Composable
private fun Meter(state: PlanMyDayUiState, viewModel: PlanMyDayViewModel) {
    val colors = MaterialTheme.colorScheme
    val format = LocalDateFormat.current
    val meter = state.meter
    val day = state.day ?: return
    val today = state.today ?: return
    val fraction = (meter.toPlace.toFloat() / maxOf(1, meter.free)).coerceIn(0f, 1f)
    val tasksText = if (meter.tasks == 1) "1 task" else "${meter.tasks} tasks"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant, RowShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$tasksText · ${PlannerTimeline.formatLength(meter.toPlace)} to place · ${PlannerTimeline.formatLength(meter.free)} free",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
                .clearAndSetSemantics { },
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(if (meter.over > 0) colors.error else colors.primary),
            )
        }
        if (meter.over > 0) {
            Text(
                text = "About ${PlannerTimeline.formatLength(meter.over)} more than your free time.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        } else {
            val from = clockLabel(state.hours.start, format)
            val to = clockLabel(state.hours.end, format)
            Text(
                text = "Free time inside your work hours ($from–$to)${if (day == today) ", from now" else ""}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (state.assistantActive && meter.over > 0) {
            state.canWait.forEach { (task, item) ->
                val nextDay = DateDisplay.midSentence(
                    DateDisplay.formatDay(DateContext.ROW, day.plus(1, DateTimeUnit.DAY), today, format),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WhyLine(
                        text = "Move ${task.title} to $nextDay" + if (item.reason.isNotEmpty()) ": ${item.reason}" else "",
                        modifier = Modifier.weight(1f),
                    )
                    PlanChip(
                        text = "Move",
                        onClick = { viewModel.moveToNextDay(task) },
                        suggested = true,
                        description = "Move ${task.title} to $nextDay",
                    )
                }
            }
        }
        if (state.assistantActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.asking -> Text(
                        text = "Asking for suggestions…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    state.failure != null -> Text(
                        text = state.failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    else -> Spacer(Modifier.weight(1f))
                }
                if (!state.asking) TextButton(onClick = viewModel::askAgain) { Text("Ask again") }
            }
        }
    }
}

/** From a project: every project with its subprojects; the Inbox is a group of its own. */
@Composable
private fun ProjectPicker(state: PlanMyDayUiState, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "From a project",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            val current = state.projectId?.let { state.projects[it]?.title }
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 200.dp)) {
                Text(current ?: "Pick one", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (current != null) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            onPick(null)
                            open = false
                        },
                    )
                }
                state.projectRows.forEach { row ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = row.project.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp * row.depth),
                            )
                        },
                        onClick = {
                            onPick(row.project.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

// --- Step 3: Arrange -------------------------------------------------------------------------------

@Composable
private fun ArrangeStep(state: PlanMyDayUiState, viewModel: PlanMyDayViewModel, onTaskClick: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val day = state.day ?: return
    val today = state.today ?: return
    val minutePx = with(LocalDensity.current) { PlanHourHeight.toPx() } / 60f
    val marginPx = with(LocalDensity.current) { PlanOpeningMargin.toPx() }
    val scrollState = rememberScrollState()
    var sheetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val fallbackColor = colors.primary
    val colorOf: (Task) -> Color = { task ->
        parseHexColor(state.projects[task.projectId]?.hexColor.orEmpty()) ?: fallbackColor
    }
    val nowMinute by produceState(viewModel.minutesNow(), day) {
        while (true) {
            delay(30_000)
            value = viewModel.minutesNow()
        }
    }

    // Opens on the first dashed block.
    val first = state.draft.placed.minOfOrNull { it.start }
    LaunchedEffect(Unit) {
        val minute = first ?: TimeBlocks.clockMinutes(state.hours.start)
        withFrameNanos { }
        scrollState.scrollTo(openingScroll(minute, minutePx, marginPx))
    }

    val kept = state.toPlace.filter { it.id in state.keep }
    val noSlot = state.draft.noSlot.mapNotNull { id -> state.toPlace.firstOrNull { it.id == id } }

    Column(Modifier.fillMaxSize()) {
        if (state.assistantActive && state.hasAnswer) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlanChip("Assistant's order", { viewModel.setArrangeByAssistant(true) }, selected = state.byAssistant)
                PlanChip("First free slots", { viewModel.setArrangeByAssistant(false) }, selected = !state.byAssistant)
            }
        }
        val parts = buildList {
            add("${state.draft.placed.size} in the draft")
            if (kept.isNotEmpty()) add("${kept.size} without a time")
            add("nothing is saved until you accept")
        }
        Text(
            text = parts.joinToString(" · ") + ". Tap a dashed block to change it, or hold and drag it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (kept.isNotEmpty() || noSlot.isNotEmpty()) {
            Column(Modifier.heightIn(max = 152.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                noSlot.forEach { task ->
                    WaitingRow(task, "No free slot left", colors.error) {
                        LengthChip(task, state, viewModel::cycleLength)
                        PlanChip("No time", { viewModel.toggleKeep(task.id) }, description = "No time: ${task.title}")
                    }
                }
                kept.forEach { task ->
                    WaitingRow(task, "No time, on ${sessionDayName(day, today).lowercase()}", colors.onSurfaceVariant) {
                        PlanChip("Give it a time", { viewModel.toggleKeep(task.id) }, description = "Give ${task.title} a time")
                    }
                }
            }
        }
        PlanTimeline(
            day = day,
            blocks = state.blocks,
            markers = state.markers,
            routines = state.routines,
            hours = state.hours,
            nowMinute = nowMinute.takeIf { day == today },
            scrollState = scrollState,
            colorOf = colorOf,
            heldDone = emptySet(),
            onToggleDone = {},
            onBlockClick = { sheetId = it.id },
            onMarkerClick = { onTaskClick(it.id) },
            canEdit = state::canChange,
            onMove = viewModel::moveBlock,
            onResize = viewModel::resizeBlock,
            dashedIds = state.dashedIds,
            draft = true,
            reasonOf = { state.reasons[it.id] },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // Events are busy time here too (contract 20.2), drawn read only beside the draft.
            events = state.events,
            eventColor = { id -> calendarColor(state.eventColors[id] ?: PlanEvents.MY_EVENTS_COLOR) },
        )
    }

    sheetId?.let { id ->
        val task = state.blocks.firstOrNull { it.task.id == id }?.task
        val draft = state.draftOf(id)
        when {
            task == null -> sheetId = null
            draft != null -> DraftBlockSheet(
                task = task,
                block = draft,
                reason = state.reasons[id],
                onNudge = { viewModel.nudgeBlock(task, it) },
                onLength = { viewModel.setBlockLength(task, it) },
                onNoTime = {
                    viewModel.toggleKeep(id)
                    sheetId = null
                },
                onDismiss = { sheetId = null },
            )
            else -> BlockSheet(
                task = task,
                canChange = state.canChange(task),
                canPlace = true,
                defaultLength = state.defaultLength,
                onSetLength = { viewModel.setBlockLength(task, it) },
                onNudge = { viewModel.nudgeBlock(task, it) },
                onMoveToDay = null,
                onRemoveTime = {
                    viewModel.removeTime(task)
                    sheetId = null
                },
                onAddBlock = {},
                onOpenTask = {
                    sheetId = null
                    onTaskClick(task.id)
                },
                onDismiss = { sheetId = null },
            )
        }
    }
}

/** A task the draft has no block for, with what can be done about it. */
@Composable
private fun WaitingRow(task: Task, status: String, statusColor: Color, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
        actions()
    }
}

/** A tap on a dashed block: earlier, later, its length, or no time. Nothing here is saved. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DraftBlockSheet(
    task: Task,
    block: DraftBlock,
    reason: String?,
    onNudge: (Int) -> Unit,
    onLength: (Int) -> Unit,
    onNoTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clock = LocalClockDay.current
    val format = LocalDateFormat.current
    val range = run {
        val start = TimeBlocks.blockOf(task, clock.zone)
        if (start == null) {
            ""
        } else {
            DateDisplay.formatBlockRange(start.start.toLocalDateTime(clock.zone), start.end.toLocalDateTime(clock.zone), format)
        }
    }
    val length = block.end - block.start
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = "$range · ${PlannerTimeline.formatLength(length)} · draft",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!reason.isNullOrEmpty()) WhyLine(reason)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PlanChip("15m earlier", { onNudge(-TimeBlocks.STEP_MINUTES) })
                PlanChip("15m later", { onNudge(TimeBlocks.STEP_MINUTES) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BlockSheetLengths.forEach { minutes ->
                    PlanChip(PlannerTimeline.formatLength(minutes), { onLength(minutes) }, selected = length == minutes)
                }
            }
            PlanChip("No time", onNoTime, description = "No time: ${task.title}")
        }
    }
}

// --- Step 4: Ready ---------------------------------------------------------------------------------

@Composable
private fun ReadyStep(state: PlanMyDayUiState, viewModel: PlanMyDayViewModel, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val format = LocalDateFormat.current
    val permission: NotificationPermissionCoordinator = koinInject()
    val day = state.day ?: return
    val today = state.today ?: return
    val nudgeTime = clockLabel(state.nudge ?: PlannerNudge.DEFAULT_TIME, format)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "title") {
            Text("${sessionDayName(day, today)} is planned", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${state.withTime.size} with a time · ${state.withoutTime.size} without · " +
                    "${PlannerTimeline.formatLength(state.freeLeft)} still free",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        items(state.withTime, key = { "time-${it.id}" }) { task ->
            val block = TimeBlocks.blockOf(task, state.zone)
            SummaryRow(
                lead = block?.let {
                    DateDisplay.formatBlockRange(it.start.toLocalDateTime(state.zone), it.end.toLocalDateTime(state.zone), format)
                }.orEmpty(),
                leadColor = colors.primary,
                title = task.title,
                trail = block?.let { PlannerTimeline.formatLength(it.minutes) },
            )
        }
        items(state.withoutTime, key = { "none-${it.id}" }) { task ->
            val lead = PlanDay.dueTimeOn(task, day, state.zone)?.let { DateDisplay.formatTime(it, format) } ?: "Any time"
            SummaryRow(lead, colors.onSurfaceVariant, task.title, null)
        }
        item(key = "remind") {
            ToggleRow("Remind me when each block starts", state.remindersOn) { on ->
                viewModel.setBlockReminders(on)
                if (on) permission.onFeatureEnabled()
            }
        }
        item(key = "nudge") {
            ToggleRow("Ask me to plan tomorrow at $nudgeTime", state.nudge != null) { on ->
                viewModel.setNudge(on)
                if (on) permission.onFeatureEnabled()
            }
        }
        item(key = "buttons") {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = viewModel::undoAll, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo all")
                }
                Button(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SummaryRow(lead: String, leadColor: Color, title: String, trail: String?) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant, RowShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(lead, style = MaterialTheme.typography.labelMedium, color = leadColor, maxLines = 1)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trail != null) {
            Text(trail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal fun clockLabel(clock: String, format: com.rendyhd.vicu.util.DateDisplayFormat): String {
    val minutes = TimeBlocks.clockMinutes(clock)
    return DateDisplay.formatTime(LocalTime(minutes / 60, minutes % 60), format)
}
