package com.rendyhd.vicu.ui.screens.today

import com.rendyhd.vicu.ui.components.shared.listScrollbar
import com.rendyhd.vicu.ui.components.shared.VicuSnackbarHost
import com.rendyhd.vicu.voice.TaskDefaults
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.isFinished
import com.rendyhd.vicu.ui.components.fab.FabDropTarget
import com.rendyhd.vicu.ui.components.fab.fabDropTarget
import com.rendyhd.vicu.ui.navigation.ScreenReady
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.shared.FabClearance
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.section.SectionLevel
import com.rendyhd.vicu.ui.components.section.SectionTone
import com.rendyhd.vicu.ui.components.section.openCount
import com.rendyhd.vicu.ui.components.section.showsGroupHeader
import com.rendyhd.vicu.ui.components.selection.SelectionAction
import com.rendyhd.vicu.ui.components.selection.SelectionPickers
import com.rendyhd.vicu.ui.components.selection.SelectionTopBar
import com.rendyhd.vicu.ui.components.selection.assignAction
import com.rendyhd.vicu.ui.components.selection.SelectionViewModel
import com.rendyhd.vicu.ui.components.shared.LocalFabAlignStart
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.LocalToday
import com.rendyhd.vicu.ui.components.shared.ScreenFab
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.shared.rememberVicuTopBarScroll
import com.rendyhd.vicu.ui.components.task.SwipeableTaskItem
import com.rendyhd.vicu.ui.components.task.TodayRowView
import com.rendyhd.vicu.ui.screens.routines.RoutineOccurrenceRow
import com.rendyhd.vicu.ui.screens.shared.TaskProjectGroup
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.TodayRoutines
import com.rendyhd.vicu.util.parseHexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShowTaskEntry: (Long?, String?) -> Unit = { _, _ -> },
    onOpenRoutines: () -> Unit = {},
    onOpenUpcoming: () -> Unit = {},
    onOpenPlan: () -> Unit = {},
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The planner's List / Day switch (contract 16.9): off means the list and nothing else.
    val behaviorStore = koinInject<BehaviorPrefsStore>()
    val behavior by behaviorStore.getPrefs().collectAsStateWithLifecycle(initialValue = BehaviorPrefs())
    val dayView = behavior.plannerEnabled && behavior.plannerTodayDayView
    val switchScope = rememberCoroutineScope()
    // A screen navigated to fades in once this is true (ScreenReveal).
    ScreenReady(!state.isLoading)

    // Rows kept on screen after completing them are let go when the screen is left.
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val topBarScroll = rememberVicuTopBarScroll()

    // Routines on Today (cross-app semantics 6.10): one row per routine, the ones due now up to the
    // limit, the rest behind one line that opens in place until Today is left. What is due now
    // follows the clock, checked twice a minute.
    val routineDay = state.routineDay
    val nowMinutes by produceState(viewModel.minutesNow(), routineDay.date) {
        while (true) {
            delay(30_000)
            value = viewModel.minutesNow()
        }
    }
    val routinePlan = remember(routineDay, nowMinutes, state.routineLimit) {
        TodayRoutines.plan(
            items = routineDay.occurrences,
            nowMinutes = nowMinutes,
            limit = state.routineLimit,
            routineId = { it.routine.definition.id },
            name = { it.routine.definition.name },
            minutes = { it.slot.reminderMinutes },
            finished = { it.status.isFinished },
            carried = { it.scheduledDate < routineDay.date },
        )
    }
    var routinesOpened by remember { mutableStateOf(false) }
    val shownRoutines = when {
        state.routinesFolded -> emptyList()
        routinesOpened -> routinePlan.rows
        else -> routinePlan.visible
    }
    val routineLine = routinePlan.lineText?.takeIf { !state.routinesFolded }

    // The routines are read apart from the tasks and can come in after them. LazyColumn keeps the
    // first visible row where it is, so a list still at its top would then start at the first
    // task with the routines above it, out of sight; it goes back to the top instead.
    val routineItemCount = if (routinePlan.rows.isEmpty()) 0 else 1 + shownRoutines.size + (if (routineLine != null) 1 else 0)
    LaunchedEffect(routineItemCount > 0) {
        if (routineItemCount == 0) return@LaunchedEffect
        withFrameNanos { }
        if (listState.firstVisibleItemIndex == routineItemCount && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(0)
        }
    }

    // All clear warms in only when Today had tasks during this visit and the last one has just gone.
    val hasContent = state.projectGroups.isNotEmpty() || state.overdueGroups.isNotEmpty() || state.routineDay.open.isNotEmpty()
    var hadTasks by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasContent) { if (hasContent) hadTasks = true }

    val selectionVm: SelectionViewModel = koinViewModel()
    val selectedIds by selectionVm.selectedIds.collectAsStateWithLifecycle()
    val selectionActive = selectedIds.isNotEmpty()
    var selectionAction by remember { mutableStateOf<SelectionAction?>(null) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = selectionActive,
    ) { selectionVm.clear() }

    Scaffold(

        modifier = topBarScroll.modifier,
        topBar = {
            if (selectionActive) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { selectionVm.clear() },
                    onToday = { selectionVm.bulkToday() },
                    onComplete = { selectionVm.bulkComplete(viewModel.completions) },
                    onSchedule = { selectionAction = SelectionAction.SCHEDULE },
                    onSetPriority = { selectionAction = SelectionAction.SET_PRIORITY },
                    onMove = { selectionAction = SelectionAction.MOVE_PROJECT },
                    onApplyLabel = { selectionAction = SelectionAction.APPLY_LABEL },
                    onRemove = { selectionAction = SelectionAction.REMOVE },
                    onAssign = selectionVm.assignAction { selectionAction = SelectionAction.ASSIGN },
                    onDuplicate = selectionVm::bulkDuplicate,
                    copyTitles = selectionVm::selectedTitles,
                )
            } else {
                VicuTopAppBar(
                    title = {
                        Column {
                            Text("Today")
                            // The date is a small line under the title in the bar.
                            Text(
                                text = DateDisplay.formatDay(DateContext.HEADER_FULL, LocalToday.current, LocalToday.current, LocalDateFormat.current),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onOpenDrawer = onOpenDrawer,
                    scroll = topBarScroll,
                    onNavigateToSearch = onNavigateToSearch,
                    extraActions = if (behavior.plannerEnabled) {
                        {
                            TodayViewSwitch(
                                dayView = dayView,
                                onChange = { day -> switchScope.launch { behaviorStore.setPlannerTodayDayView(day) } },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                val day = LocalClockDay.current
                val today = DueDates.today(day.date, day.zone).toString()
                ScreenFab(
                    onClick = { onShowTaskEntry(null, today) },
                    expanded = !listState.canScrollBackward,
                    taskDefaults = TaskDefaults(dueDate = today, listName = "Today"),
                )
            }
        },
        floatingActionButtonPosition = if (LocalFabAlignStart.current) FabPosition.Start else FabPosition.End,
        snackbarHost = { VicuSnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh(showSpinner = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (dayView) {
                TodayDayView(onTaskClick = onTaskClick, onOpenPlan = onOpenPlan)
                return@PullToRefreshBox
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().listScrollbar(listState),
                contentPadding = PaddingValues(bottom = FabClearance),
            ) {
                // Finished routines (completed or skipped) are left out; the count still covers the day.
                val openRoutines = state.routineDay.open
                if (routinePlan.rows.isNotEmpty()) {
                    item(key = "routine_header", contentType = "header") {
                        val folded = state.routinesFolded
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = !folded,
                                    role = Role.Button,
                                    onValueChange = { open -> viewModel.setRoutinesFolded(!open) },
                                )
                                .semantics { stateDescription = if (folded) "Collapsed" else "Expanded" }
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Routines · ${routineDay.completedCount}/${routineDay.scheduledCount}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Icon(
                                if (folded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp).size(20.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onOpenRoutines) { Text("Manage") }
                        }
                    }
                    items(shownRoutines, key = { "routine_${it.routineId}" }, contentType = { "occurrence" }) { row ->
                        RoutineOccurrenceRow(
                            occurrence = row.item,
                            onToggle = { viewModel.toggleRoutine(row.item) },
                            onSkip = { viewModel.skipRoutine(row.item) },
                            progress = row.progress,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp).animateItem(),
                        )
                    }
                    if (routineLine != null) {
                        item(key = "routine_line", contentType = "routine_line") {
                            TextButton(
                                onClick = { routinesOpened = !routinesOpened },
                                modifier = Modifier.padding(start = 12.dp).animateItem(),
                            ) {
                                Text(if (routinesOpened) "Show less" else routineLine)
                            }
                        }
                    }
                }

                if (state.projectGroups.isEmpty() && state.overdueGroups.isEmpty() &&
                    openRoutines.isEmpty() && !state.isLoading
                ) {
                    item(key = "all_clear", contentType = "all_clear") {
                        // Emptied after having had tasks in this visit: the last one was done.
                        TodayAllClear(
                            justCleared = hadTasks,
                            nextUpcoming = state.nextUpcoming,
                            onOpenUpcoming = onOpenUpcoming,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    // Overdue (local date before today) sits above Today; each section has its
                    // own project groups. The Today title only shows when it follows Overdue.
                    val showSectionTitles = state.overdueGroups.isNotEmpty()
                    if (showSectionTitles) {
                        item(key = "overdue_title", contentType = "title") {
                            SectionHeader(
                                title = "Overdue",
                                count = state.overdueGroups.sumOf { openCount(it.tasks, state.completedTaskIds) },
                                tone = SectionTone.OVERDUE,
                            )
                        }
                        taskGroupItems(
                            groups = state.overdueGroups,
                            keyPrefix = "overdue",
                            // An Overdue group takes the + for its project only: a new task is not overdue.
                            dueToday = false,
                            level = SectionLevel.TWO,
                            state = state,
                            viewModel = viewModel,
                            selectionVm = selectionVm,
                            selectedIds = selectedIds,
                            onTaskClick = onTaskClick,
                        )
                    }
                    if (showSectionTitles && state.projectGroups.isNotEmpty()) {
                        item(key = "today_title", contentType = "title") {
                            SectionHeader(
                                title = "Today",
                                count = state.projectGroups.sumOf { openCount(it.tasks, state.completedTaskIds) },
                                modifier = Modifier.fabDropTarget(LocalClockDay.current.let { FabDropTarget.day(it.date, it.zone) }),
                            )
                        }
                    }
                    taskGroupItems(
                        groups = state.projectGroups,
                        keyPrefix = "today",
                        dueToday = true,
                        // Without an Overdue section above, the projects are the top level of the list.
                        level = if (showSectionTitles) SectionLevel.TWO else SectionLevel.ONE,
                        state = state,
                        viewModel = viewModel,
                        selectionVm = selectionVm,
                        selectedIds = selectedIds,
                        onTaskClick = onTaskClick,
                    )
                }
            }
        }
    }

    SelectionPickers(
        selectionVm = selectionVm,
        action = selectionAction,
        selectedCount = selectedIds.size,
        onDismiss = { selectionAction = null },
    )

    // Error snackbar with retry
    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.refresh(true)
            }
            viewModel.clearError()
        }
    }
}

/** One group per project with its task rows; keys carry [keyPrefix] so sections never collide. */
private fun LazyListScope.taskGroupItems(
    groups: List<TaskProjectGroup>,
    keyPrefix: String,
    /** A + dropped on a group header makes a task due today in its project (else only in its project). */
    dueToday: Boolean,
    level: SectionLevel,
    state: TodayUiState,
    viewModel: TodayViewModel,
    selectionVm: SelectionViewModel,
    selectedIds: Set<Long>,
    onTaskClick: (Long) -> Unit,
) {
    val selectionActive = selectedIds.isNotEmpty()
    groups.forEach { group ->
        // A group of one task has no header: its project goes on the row's meta line.
        val hasHeader = showsGroupHeader(group.tasks.size)
        val projectMeta = if (hasHeader) null else ProjectMeta(group.title, group.hexColor)
        if (hasHeader) {
            item(key = "${keyPrefix}_header_${group.projectId}", contentType = "header") {
                val day = LocalClockDay.current
                val projectId = group.projectId.takeIf { it > 0 }
                SectionHeader(
                    title = group.title,
                    level = level,
                    dotColor = parseHexColor(group.hexColor),
                    count = openCount(group.tasks, state.completedTaskIds),
                    modifier = Modifier.fabDropTarget(
                        if (dueToday) FabDropTarget.day(day.date, day.zone, projectId) else projectId?.let { FabDropTarget(projectId = it) },
                    ),
                )
            }
        }
        items(group.tasks, key = { it.id }, contentType = { "task" }) { task ->
            val displayTask =
                if (task.id in state.completedTaskIds) task.copy(done = true) else task
            SwipeableTaskItem(
                task = displayTask,
                onToggleDone = {
                    if (task.id in state.completedTaskIds) {
                        viewModel.undoComplete(task)
                    } else {
                        viewModel.toggleDone(task)
                    }
                },
                onClick = {
                    if (selectionActive) {
                        selectionVm.toggle(task.id)
                    } else {
                        onTaskClick(task.id)
                    }
                },
                onSubtaskToggleDone = viewModel::toggleDone,
                onSubtaskClick = { child -> onTaskClick(child.id) },
                selectionActive = selectionActive,
                selected = task.id in selectedIds,
                onLongClick = { selectionVm.toggle(task.id) },
                modifier = Modifier.animateItem(),
                projectMeta = projectMeta,
                rowView = TodayRowView,
            )
        }
    }
}
