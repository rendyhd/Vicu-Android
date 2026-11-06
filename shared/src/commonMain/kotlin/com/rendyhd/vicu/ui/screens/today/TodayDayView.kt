package com.rendyhd.vicu.ui.screens.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.repository.PlannerPreferences
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.plan.PlanDayContent
import com.rendyhd.vicu.ui.screens.plan.PlanHourHeight
import com.rendyhd.vicu.ui.screens.plan.PlanOpeningMargin
import com.rendyhd.vicu.ui.screens.plan.PlanTimeline
import com.rendyhd.vicu.ui.screens.plan.openingScroll
import com.rendyhd.vicu.ui.screens.plan.TimelineRoutine
import com.rendyhd.vicu.ui.screens.plan.planDayContent
import com.rendyhd.vicu.ui.screens.plan.planTotalsText
import com.rendyhd.vicu.ui.screens.plan.routinesOnTimeline
import com.rendyhd.vicu.ui.screens.shared.CompletionHold
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PlannerHours
import com.rendyhd.vicu.util.SystemTimeSource
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.parseHexColor
import com.rendyhd.vicu.util.postIfRefused
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class TodayDayUiState(
    val content: PlanDayContent? = null,
    val routines: List<TimelineRoutine> = emptyList(),
    val hours: PlannerHours = TimeBlocks.DEFAULT_HOURS,
    val projects: Map<Long, Project> = emptyMap(),
    val heldIds: Set<Long> = emptySet(),
)

/**
 * Today's Day view (design M1): today's blocks, due times, routines and the tasks without a time. An
 * overview: it opens and ticks, and planning happens on the Plan screen. Only built while the view is
 * on screen, so a phone with the planner off fetches nothing for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayDayViewModel(
    private val taskRepository: TaskRepository,
    projectRepository: ProjectRepository,
    routineRepository: RoutineRepository,
    routinePrefsStore: RoutinePrefsStore,
    behaviorPrefsStore: BehaviorPrefsStore,
    plannerPreferences: PlannerPreferences,
    private val dayClock: DayClock,
    private val appMessages: AppMessages,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
    private val time: TimeSource = SystemTimeSource,
) : ViewModel() {

    val completions = CompletionHold(viewModelScope, navigationTicker = navigationTicker, toast = completionToast)

    private val tasks: Flow<List<Task>> = dayClock.today.flatMapLatest { today -> taskRepository.getPlanTasks(today, today) }

    private val routines: Flow<List<TimelineRoutine>> = combine(
        dayClock.today,
        routinePrefsStore.enabled,
        behaviorPrefsStore.getPrefs().map { it.plannerShowRoutines }.distinctUntilChanged(),
    ) { today, enabled, shown -> today.takeIf { enabled && shown }?.toString() to today.toString() }
        .distinctUntilChanged()
        .flatMapLatest { (date, today) ->
            if (date != null) routineRepository.observeDay(date) else flowOf(RoutineDay(today, emptyList()))
        }
        .map(::routinesOnTimeline)

    val uiState: StateFlow<TodayDayUiState> = combine(
        tasks,
        projectRepository.getAll(),
        routines,
        plannerPreferences.hours,
        combine(dayClock.day, completions.state) { day, held -> day to held },
    ) { tasks, projects, routines, hours, (day, held) ->
        val byId = projects.associateBy { it.id }
        val content = planDayContent(
            tasks.filter { it.projectId in byId },
            day.date,
            time.now(),
            hours,
            day.zone,
        ) { it.id in held }
        TodayDayUiState(
            content = content.copy(withoutTime = completions.merge(content.withoutTime)),
            routines = routines,
            hours = hours,
            projects = byId,
            heldIds = completions.completedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TodayDayUiState())

    init {
        // Done blocks stay on the timeline, and only this request brings them to the phone.
        viewModelScope.launch { dayClock.today.collect { taskRepository.refreshPlanDay(it) } }
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch {
            val held = task.id in completions.state.value
            when {
                held -> {
                    completions.undoing(task.id)
                    val result = taskRepository.setDone(task.id, false)
                    completions.release(task.id)
                    appMessages.postIfRefused(result, "reopen the task")
                }
                task.done -> appMessages.postIfRefused(taskRepository.setDone(task.id, false), "reopen the task")
                else -> {
                    completions.hold(task)
                    val result = taskRepository.toggleDone(task)
                    if (result is NetworkResult.Error) completions.release(task.id)
                    appMessages.postIfRefused(result, "complete the task")
                }
            }
        }
    }

    fun minutesNow(): Int = dayClock.minutesNow()
}

/** The List / Day switch in Today's bar, while the planner is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayViewSwitch(dayView: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(end = 4.dp)) {
        listOf(false to "List", true to "Day").forEachIndexed { index, (day, label) ->
            SegmentedButton(
                selected = dayView == day,
                onClick = { onChange(day) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                icon = {},
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
internal fun TodayDayView(
    onTaskClick: (Long) -> Unit,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayDayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val content = state.content ?: return
    val scrollState = rememberScrollState()
    val minutePx = with(LocalDensity.current) { PlanHourHeight.toPx() } / 60f
    val openingMarginPx = with(LocalDensity.current) { PlanOpeningMargin.toPx() }
    val nowMinute by produceState(viewModel.minutesNow(), content.day) {
        while (true) {
            delay(30_000)
            value = viewModel.minutesNow()
        }
    }
    // Opens an hour before now.
    LaunchedEffect(content.day) {
        withFrameNanos { }
        scrollState.scrollTo(openingScroll((viewModel.minutesNow() - 60).coerceAtLeast(0), minutePx, openingMarginPx))
    }
    val fallbackColor = MaterialTheme.colorScheme.primary
    val colorOf: (Task) -> Color = { task -> parseHexColor(state.projects[task.projectId]?.hexColor.orEmpty()) ?: fallbackColor }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = planTotalsText(content.plannedMinutes, content.freeMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = onOpenPlan,
                label = { Text("Plan") },
                leadingIcon = {
                    Icon(
                        SmartListIdentity.PLAN.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (content.withoutTime.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                items(content.withoutTime, key = { it.id }) { task ->
                    TodayChip(
                        task = task,
                        done = task.done || task.id in state.heldIds,
                        color = colorOf(task),
                        onToggleDone = { viewModel.toggleDone(task) },
                        onClick = { onTaskClick(task.id) },
                    )
                }
            }
        }
        PlanTimeline(
            day = content.day,
            blocks = content.blocks,
            markers = content.markers,
            routines = state.routines,
            hours = state.hours,
            nowMinute = nowMinute,
            scrollState = scrollState,
            colorOf = colorOf,
            heldDone = state.heldIds,
            onToggleDone = viewModel::toggleDone,
            onBlockClick = { onTaskClick(it.id) },
            onMarkerClick = { onTaskClick(it.id) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** A task without a time: a tick and its title, which opens it. */
@Composable
private fun TodayChip(task: Task, done: Boolean, color: Color, onToggleDone: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Open") { onClick() }
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggleDone() })
                .semantics { contentDescription = if (done) "Mark ${task.title} not done" else "Complete ${task.title}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (done) LocalVicuColors.current.done.color else color,
            )
        }
        Text(
            text = task.title,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}
