package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.ui.components.shared.snackbarColors
import com.rendyhd.vicu.ui.navigation.ScreenReady
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.ui.theme.rememberReduceMotion
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.PlanSource
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.parseHexColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/** A task on its way from the drawer to a time. */
private data class DrawerDrag(val task: Task, val window: Offset)

/** How long the drawer stays aside after a drop before it comes back (the design's 250 ms). */
private const val DRAWER_RETURN_MS = 250L

/**
 * The planning screen (design M3): the sun button (Plan my day), the gear, a 7-day strip, a
 * full-width timeline and the drawer. Phones show one day at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onTaskClick: (Long) -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onPlannerOff: () -> Unit = {},
    onPlanMyDay: () -> Unit = {},
    viewModel: PlanViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenReady(!state.isLoading)
    LaunchedEffect(viewModel) { viewModel.plannerOff.collect { off -> if (off) onPlannerOff() } }

    val format = LocalDateFormat.current
    val clock = LocalClockDay.current
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val scrollState = rememberScrollState()

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var pickedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheetTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var gearOpen by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DrawerDrag?>(null) }
    var returnDrawerAt by remember { mutableStateOf(0) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val selectedDay = state.selectedDay
    val today = state.today
    val content = state.content
    val fallbackColor = MaterialTheme.colorScheme.primary
    val colorOf: (Task) -> Color = { task ->
        parseHexColor(state.projects[task.projectId]?.hexColor.orEmpty()) ?: fallbackColor
    }
    val minutePx = with(density) { PlanHourHeight.toPx() } / 60f
    val openingMarginPx = with(density) { PlanOpeningMargin.toPx() }
    val picked = pickedId?.let { id -> state.listed.firstOrNull { it.id == id } ?: content?.withoutTime?.firstOrNull { it.id == id } }
    // The picked task can leave the list (placed elsewhere, or another list chosen): keep hold of it.
    var pickedTask by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(picked) { if (picked != null) pickedTask = picked }
    val activePick = pickedTask?.takeIf { pickedId == it.id }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = drawerOpen || pickedId != null,
    ) {
        if (pickedId != null) pickedId = null else drawerOpen = false
    }

    // Where the scroller opens: an hour before now on today, the work hours' start on another day.
    LaunchedEffect(selectedDay, today) {
        val day = selectedDay ?: return@LaunchedEffect
        val now = today ?: return@LaunchedEffect
        val opening = if (day == now) {
            (viewModel.minutesNow() - 60).coerceAtLeast(0)
        } else {
            TimeBlocks.clockMinutes(state.hours.start)
        }
        withFrameNanos { }
        scrollState.scrollTo(openingScroll(opening, minutePx, openingMarginPx))
    }

    val nowMinute by produceState(viewModel.minutesNow(), selectedDay) {
        while (true) {
            delay(30_000)
            value = viewModel.minutesNow()
        }
    }

    // The drawer comes back a moment after a drop.
    LaunchedEffect(returnDrawerAt) {
        if (returnDrawerAt == 0) return@LaunchedEffect
        delay(DRAWER_RETURN_MS)
        drawerOpen = true
    }

    // A task dragged near an edge scrolls the grid under it.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        val edge = with(density) { PlanAutoScrollEdge.toPx() }
        while (true) {
            withFrameNanos { }
            val port = viewportCoords ?: continue
            val at = drag?.window ?: break
            scrollState.dispatchRawDelta(autoScrollDelta(port.windowToLocal(at).y, port.size.height.toFloat(), edge))
        }
    }

    fun dropMinute(window: Offset): Int? {
        val grid = gridCoords ?: return null
        val minute = PlannerTimeline.snapMinutes(grid.windowToLocal(window).y / minutePx.toDouble())
        return minute.coerceIn(0, PlannerTimeline.DAY_MINUTES - TimeBlocks.STEP_MINUTES)
    }

    val dropPreview: GestureRange? = drag?.let { current ->
        if (!state.canPlace) return@let null
        val minute = dropMinute(current.window) ?: return@let null
        val length = TimeBlocks.blockOf(current.task, clock.zone)?.minutes ?: state.defaultLength
        GestureRange(minute, minOf(PlannerTimeline.DAY_MINUTES, minute + length))
    }

    val dayPhrase = if (selectedDay != null && today != null) {
        DateDisplay.formatDay(DateContext.HEADER_DAY, selectedDay, today, format)
    } else {
        ""
    }
    val dayLabel = PlanSource.dayListLabel(dayPhrase)
    val sourceLabel = when (val source = state.source) {
        PlanSource.Day -> dayLabel
        is PlanSource.Project -> state.projects[source.id]?.title ?: "Project"
        else -> PlanSource.lists(dayLabel).first { it.first == source }.second
    }

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text("Plan") },
                onOpenDrawer = onOpenDrawer,
                extraActions = {
                    if (state.stripMoved) TextButton(onClick = viewModel::goToday) { Text("Today") }
                    IconButton(onClick = onPlanMyDay) {
                        Icon(Icons.Outlined.WbSunny, contentDescription = "Plan my day")
                    }
                    IconButton(onClick = { gearOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Plan settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PlanDayStrip(
                state = state,
                onSelect = { day -> state.strip.firstOrNull()?.let { viewModel.selectDay(day, it.day) } },
                onSwipe = viewModel::moveStrip,
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { containerCoords = it },
            ) {
                // The timeline stays in sight beside the drawer.
                val drawerWidth = minOf(maxWidth * 0.62f, 280.dp)
                if (content != null && selectedDay != null) {
                    PlanTimeline(
                        day = selectedDay,
                        blocks = content.blocks,
                        markers = content.markers,
                        routines = state.routines,
                        hours = state.hours,
                        nowMinute = nowMinute.takeIf { selectedDay == today },
                        scrollState = scrollState,
                        colorOf = colorOf,
                        heldDone = emptySet(),
                        onToggleDone = viewModel::toggleDone,
                        onBlockClick = { sheetTaskId = it.id },
                        onMarkerClick = { sheetTaskId = it.id },
                        canEdit = { state.canChange(it) && state.canPlace },
                        onMove = viewModel::move,
                        onResize = viewModel::resize,
                        onTapTime = activePick?.let { task ->
                            { minute: Int ->
                                viewModel.placeAt(task, minute)
                                pickedId = null
                            }
                        },
                        dropPreview = dropPreview,
                        dropPreviewTitle = drag?.task?.title.orEmpty(),
                        onGridPlaced = { gridCoords = it },
                        onViewportPlaced = { viewportCoords = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (!drawerOpen && activePick == null && drag == null) {
                    DrawerTab(
                        count = content?.withoutTime?.size ?: 0,
                        onOpen = { drawerOpen = true },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                if (activePick != null) {
                    PlacingBanner(
                        task = activePick,
                        onNextFree = {
                            if (viewModel.placeInNextFree(activePick)) pickedId = null
                        },
                        onCancel = { pickedId = null },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Always there while a drag runs: the finger's gesture lives on a row of the drawer,
                // so it steps aside instead of leaving the composition.
                val slide by animateFloatAsState(
                    targetValue = if (drawerOpen && drag == null) 0f else 1f,
                    animationSpec = if (reduceMotion) tween(0) else VicuMotion.defaultSpatialSpec(),
                    label = "planDrawer",
                )
                if (slide < 1f || drawerOpen || drag != null) {
                    PlanDrawer(
                        state = state,
                        sourceLabel = sourceLabel,
                        dayLabel = dayLabel,
                        onClose = { drawerOpen = false },
                        onSource = viewModel::setSource,
                        onFilter = viewModel::setFilter,
                        onPick = { task ->
                            val problem = viewModel.placingProblem(task)
                            if (problem != null) {
                                // Someone who can only view still reads it; a past day says why not.
                                if (state.canChange(task)) viewModel.postMessage(problem) else onTaskClick(task.id)
                            } else {
                                pickedTask = task
                                pickedId = task.id
                                drawerOpen = false
                            }
                        },
                        onDragStart = { task, window ->
                            if (state.canPlace) {
                                drag = DrawerDrag(task, window)
                            } else {
                                viewModel.postMessage(PlanViewModel.PAST_DAY)
                            }
                        },
                        onDrag = { window -> drag = drag?.copy(window = window) },
                        onDragEnd = {
                            drag?.let { current ->
                                val inside = viewportCoords?.let { port ->
                                    val local = port.windowToLocal(current.window)
                                    local.y in 0f..port.size.height.toFloat() && local.x in 0f..port.size.width.toFloat()
                                } ?: false
                                dropMinute(current.window)?.takeIf { inside }?.let { viewModel.placeAt(current.task, it) }
                                returnDrawerAt += 1
                            }
                            drag = null
                        },
                        onDragCancel = {
                            if (drag != null) returnDrawerAt += 1
                            drag = null
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(drawerWidth)
                            .offset { IntOffset((slide * drawerWidth.toPx()).roundToInt(), 0) },
                    )
                }

                // What the finger carries.
                drag?.let { current ->
                    val local = containerCoords?.windowToLocal(current.window)
                    if (local != null) {
                        Surface(
                            modifier = Modifier
                                .offset { IntOffset(local.x.roundToInt() - 120, local.y.roundToInt() - 90) }
                                .widthIn(max = 220.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 6.dp,
                        ) {
                            Text(
                                text = current.task.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    sheetTaskId?.let { id ->
        // By id, so the sheet always shows the task as it is now.
        val task = content?.blocks?.firstOrNull { it.task.id == id }?.task
            ?: content?.markers?.firstOrNull { it.task.id == id }?.task
        if (task == null) {
            sheetTaskId = null
        } else {
            BlockSheet(
                task = task,
                canChange = state.canChange(task),
                canPlace = state.canPlace,
                defaultLength = state.defaultLength,
                onSetLength = { viewModel.setLength(task, it) },
                onNudge = { viewModel.nudge(task, it) },
                onRemoveTime = {
                    viewModel.removeTime(task)
                    sheetTaskId = null
                },
                onAddBlock = {
                    viewModel.addBlockAtDueTime(task)
                    sheetTaskId = null
                },
                onOpenTask = {
                    sheetTaskId = null
                    onTaskClick(task.id)
                },
                onDismiss = { sheetTaskId = null },
                onToggleDone = { viewModel.toggleDone(task) }.takeIf { TimeBlocks.blockOf(task, clock.zone) != null },
            )
        }
    }

    if (gearOpen) {
        ModalBottomSheet(
            onDismissRequest = { gearOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { VicuDragHandle() },
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text(
                    text = "Plan settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PlannerOptions()
            }
        }
    }
}

/** Seven days from the strip's start; a tap selects, a swipe goes a week on or back. */
@Composable
private fun PlanDayStrip(state: PlanUiState, onSelect: (kotlinx.datetime.LocalDate) -> Unit, onSwipe: (Int) -> Unit) {
    val format = LocalDateFormat.current
    val swipeEdge = with(LocalDensity.current) { 48.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .padding(bottom = 6.dp)
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                    },
                    onDragEnd = {
                        if (abs(travelled) > swipeEdge) onSwipe(if (travelled < 0) 1 else -1)
                    },
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        state.strip.forEach { strip ->
            val selected = strip.day == state.selectedDay
            val isToday = strip.day == state.today
            val colors = MaterialTheme.colorScheme
            val label = if (isToday) "Today" else DateDisplay.formatWeekdayShort(strip.day, format)
            val full = state.today?.let { DateDisplay.formatWeekdayDate(strip.day, it, format) }.orEmpty()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) colors.primaryContainer else colors.background)
                    .selectable(selected = selected) { onSelect(strip.day) }
                    .semantics {
                        contentDescription = if (isToday) "Today, $full" else full
                        stateDescription = "${PlannerTimeline.formatLength(strip.plannedMinutes)} planned"
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label.uppercase(),
                    // Seven of these share the width, and the day's full name is in its description.
                    style = MaterialTheme.typography.labelSmall.withFontScaleAtMost(StripLabelMaxScale),
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = strip.day.day.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                // The load: how full the day's work hours are.
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(28.dp, 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(strip.load)
                            .background(colors.primary),
                    )
                }
            }
        }
    }
}

/** The way into the drawer: a tap, or a swipe on the tab itself. Never the screen edge, which is Back. */
@Composable
private fun DrawerTab(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val swipe = with(LocalDensity.current) { 20.dp.toPx() }
    Box(
        modifier = modifier
            .size(width = 28.dp, height = 72.dp)
            .excludeSystemEdgeGestures()
            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .background(colors.primary)
            .clickable(onClickLabel = "Show tasks to plan") { onOpen() }
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                    },
                    onDragEnd = { if (-travelled > swipe) onOpen() },
                )
            }
            .semantics { contentDescription = "Show tasks to plan" },
        contentAlignment = Alignment.Center,
    ) {
        if (count > 0) {
            Text(text = count.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onPrimary)
        } else {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The picked task, until a time is tapped. Next free is the path without aiming at the grid. */
@Composable
private fun PlacingBanner(task: Task, onNextFree: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme.snackbarColors()
    Surface(
        modifier = modifier.padding(12.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.content,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tap a time for ${task.title}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNextFree) { Text("Next free", color = colors.action) }
            TextButton(onClick = onCancel) { Text("Cancel", color = colors.action) }
        }
    }
}
