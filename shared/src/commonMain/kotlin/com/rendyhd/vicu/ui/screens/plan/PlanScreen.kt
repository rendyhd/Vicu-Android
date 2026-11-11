package com.rendyhd.vicu.ui.screens.plan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
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
import com.rendyhd.vicu.util.CalendarEvent
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.EventEdit
import com.rendyhd.vicu.util.EventScope
import com.rendyhd.vicu.util.ListedEvent
import com.rendyhd.vicu.util.PlanEvents
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.GestureRange
import com.rendyhd.vicu.util.PlanSource
import com.rendyhd.vicu.util.PlannerTimeline
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.IcsImport
import com.rendyhd.vicu.ui.rememberCalendarFilePicker
import com.rendyhd.vicu.util.parseHexColor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel

/** A task on its way to a time: from the drawer, or from the top row. */
private data class DrawerDrag(val task: Task, val window: Offset, val fromDrawer: Boolean)

/** A block being moved, and where the finger is. */
private data class BlockCarry(val task: Task, val window: Offset?)

/** A change of a repeating Event waiting for All times, This time only or This and following (contract 20.6). */
private data class PendingEventChange(val occurrence: CalendarEvent, val edit: EventEdit?, val range: GestureRange?)

/** How long the drawer stays aside after a drop before it comes back (the design's 250 ms). */
private const val DRAWER_RETURN_MS = 250L

/** How long a carried task rests on a day of the strip before that day opens under it. */
private const val HOVER_SWITCH_MS = 500L

/** A flick this fast, in dp per second, changes the day even when the swipe was short. */
private const val FLING_DP_PER_SECOND = 600f

/**
 * The planning screen (design M3): the sun button (Plan my day), the gear, a 7-day strip, a
 * full-width timeline and the drawer. Phones show one day at a time; a sideways swipe on the timeline
 * goes to the next or previous day, and a task carried over a day of the strip opens that day.
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
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    ScreenReady(!state.isLoading)
    LaunchedEffect(viewModel) { viewModel.plannerOff.collect { off -> if (off) onPlannerOff() } }

    val format = LocalDateFormat.current
    SideEffect { viewModel.format = format }
    val clock = LocalClockDay.current
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val neighbourScroll = rememberScrollState()

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var pickedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheetTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var gearOpen by rememberSaveable { mutableStateOf(false) }
    // Something new on an empty time (a press and hold, or New event in the gear), and whether it starts as an Event.
    var newAtMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var newAsEvent by rememberSaveable { mutableStateOf(false) }
    var calendarsOpen by rememberSaveable { mutableStateOf(false) }
    // A calendar file being previewed (contract 20.10); its text is not saved with the screen.
    var importText by remember { mutableStateOf<String?>(null) }
    val pickCalendarFile = rememberCalendarFilePicker { text ->
        when {
            text == null -> viewModel.postMessage("Could not read that file")
            !IcsImport.isCalendar(text) -> viewModel.postMessage("That file is not a calendar file")
            else -> importText = text
        }
    }
    var eventSheet by remember { mutableStateOf<CalendarEvent?>(null) }
    var pendingEventChange by remember { mutableStateOf<PendingEventChange?>(null) }
    var drag by remember { mutableStateOf<DrawerDrag?>(null) }
    var blockCarry by remember { mutableStateOf<BlockCarry?>(null) }
    // What the drawer and the top row listed when a drag began: the row under the finger stays put
    // while the day changes under it.
    var frozenListed by remember { mutableStateOf<List<Task>?>(null) }
    var frozenTopRow by remember { mutableStateOf<List<Task>?>(null) }
    var returnDrawerAt by remember { mutableStateOf(0) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var topRowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val stripDays = remember { mutableStateMapOf<LocalDate, LayoutCoordinates>() }
    // A day reached by a swipe or under a carried task keeps the hour on screen.
    var keepScrollFor by remember { mutableStateOf<LocalDate?>(null) }
    val swipe = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var swipeTravel by remember { mutableStateOf(0f) }

    val selectedDay = state.selectedDay
    val today = state.today
    val content = state.content
    val latestState by rememberUpdatedState(state)
    val fallbackColor = MaterialTheme.colorScheme.primary
    val colorOf: (Task) -> Color = { task ->
        parseHexColor(state.projects[task.projectId]?.hexColor.orEmpty()) ?: fallbackColor
    }
    val eventColor: (String) -> Color = { id -> calendarColor(state.calendar.colorOf(id)) }
    val isOwnEvent: (CalendarEvent) -> Boolean = { it.planEventId != null }

    /** A change from the sheet or the timeline: a repeating Event asks which times it is for. */
    fun changeEvent(occurrence: CalendarEvent, edit: EventEdit?, range: GestureRange?) {
        if (viewModel.repeats(occurrence)) {
            pendingEventChange = PendingEventChange(occurrence, edit, range)
        } else if (edit != null) {
            viewModel.changeEvent(occurrence, edit, EventScope.ALL)
        } else if (range != null) {
            viewModel.moveEvent(occurrence, range, EventScope.ALL)
        }
    }
    val minutePx = with(density) { PlanHourHeight.toPx() } / 60f
    val openingMarginPx = with(density) { PlanOpeningMargin.toPx() }
    val picked = pickedId?.let { id ->
        state.listed.firstOrNull { it.id == id } ?: content?.withoutTime?.firstOrNull { it.id == id }
    }
    // The picked task can leave the list (placed elsewhere, another list or day chosen): keep hold of it.
    var pickedTask by remember { mutableStateOf<Task?>(null) }
    LaunchedEffect(picked) { if (picked != null) pickedTask = picked }
    val activePick = pickedTask?.takeIf { pickedId == it.id }
    val carrying = drag != null || blockCarry != null

    // Only the days on the strip now: an entry left from another week may point at a cell that shows a different day.
    fun stripDayAt(window: Offset): LocalDate? = state.strip.firstOrNull { strip ->
        stripDays[strip.day]?.let { it.isAttached && it.boundsInWindow().contains(window) } == true
    }?.day

    fun overTopRow(window: Offset): Boolean =
        topRowCoords?.takeIf { it.isAttached }?.boundsInWindow()?.contains(window) == true

    val carryFinger: Offset? = drag?.window ?: blockCarry?.window
    val hoveredDay = carryFinger?.let(::stripDayAt)?.takeIf { today != null && PlannerTimeline.canPlaceOn(it, today) }
    val topRowHovered = carryFinger?.let(::overTopRow) == true
    val topRowTarget = when {
        blockCarry != null -> TopRowTarget.REMOVE_TIME
        drag?.fromDrawer == true -> TopRowTarget.PLAN_FOR_DAY
        else -> null
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = drawerOpen || pickedId != null,
    ) {
        if (pickedId != null) pickedId = null else drawerOpen = false
    }

    // Where the scroller opens: an hour before now on today, the work hours' start on another day. A
    // day reached by a swipe or under a carried task keeps the hour the finger is on.
    LaunchedEffect(selectedDay, today) {
        val day = selectedDay ?: return@LaunchedEffect
        val now = today ?: return@LaunchedEffect
        if (keepScrollFor == day || carrying) {
            keepScrollFor = null
            return@LaunchedEffect
        }
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

    // A task resting on a day of the strip opens that day under the finger.
    LaunchedEffect(hoveredDay) {
        val day = hoveredDay ?: return@LaunchedEffect
        if (day == latestState.selectedDay) return@LaunchedEffect
        delay(HOVER_SWITCH_MS)
        keepScrollFor = day
        viewModel.showDay(day)
    }

    // A task dragged near an edge scrolls the grid under it, but not while it is over the strip or the top row.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        val edge = with(density) { PlanAutoScrollEdge.toPx() }
        while (true) {
            withFrameNanos { }
            val port = viewportCoords ?: continue
            val at = drag?.window ?: break
            if (stripDayAt(at) != null || overTopRow(at)) continue
            scrollState.dispatchRawDelta(autoScrollDelta(port.windowToLocal(at).y, port.size.height.toFloat(), edge))
        }
    }

    fun dropMinute(window: Offset): Int? {
        val grid = gridCoords ?: return null
        val minute = PlannerTimeline.snapMinutes(grid.windowToLocal(window).y / minutePx.toDouble())
        return minute.coerceIn(0, PlannerTimeline.DAY_MINUTES - TimeBlocks.STEP_MINUTES)
    }

    val dropPreview: GestureRange? = drag?.let { current ->
        if (!state.canPlace || stripDayAt(current.window) != null || overTopRow(current.window)) return@let null
        val minute = dropMinute(current.window) ?: return@let null
        val length = TimeBlocks.blockOf(current.task, clock.zone)?.minutes ?: state.defaultLength
        GestureRange(minute, minOf(PlannerTimeline.DAY_MINUTES, minute + length))
    }

    fun startDrag(task: Task, window: Offset, fromDrawer: Boolean) {
        if (!state.canPlace) {
            viewModel.postMessage(PlanViewModel.PAST_DAY)
            return
        }
        frozenListed = state.listed
        frozenTopRow = content?.topRow
        drag = DrawerDrag(task, window, fromDrawer)
    }

    fun endDrag() {
        drag?.let { current ->
            val at = current.window
            val day = stripDayAt(at)
            when {
                day != null -> viewModel.planForDay(current.task, day)
                overTopRow(at) -> if (current.fromDrawer) selectedDay?.let { viewModel.planForDay(current.task, it) }
                else -> {
                    val inside = viewportCoords?.let { port ->
                        val local = port.windowToLocal(at)
                        local.y in 0f..port.size.height.toFloat() && local.x in 0f..port.size.width.toFloat()
                    } ?: false
                    dropMinute(at)?.takeIf { inside }?.let { viewModel.placeAt(current.task, it) }
                }
            }
            if (current.fromDrawer) returnDrawerAt += 1
        }
        drag = null
        frozenListed = null
        frozenTopRow = null
    }

    fun cancelDrag() {
        if (drag?.fromDrawer == true) returnDrawerAt += 1
        drag = null
        frozenListed = null
        frozenTopRow = null
    }

    fun pick(task: Task) {
        val problem = viewModel.placingProblem(task)
        if (problem != null) {
            // Someone who can only view still reads it; a past day says why not.
            if (state.canChange(task)) viewModel.postMessage(problem) else onTaskClick(task.id)
        } else {
            pickedTask = task
            pickedId = task.id
            drawerOpen = false
        }
    }

    val dayPhrase = if (selectedDay != null && today != null) {
        DateDisplay.formatDay(DateContext.HEADER_DAY, selectedDay, today, format)
    } else {
        ""
    }
    val sourceLabel = when (val source = state.source) {
        PlanSource.Day -> dayPhrase
        is PlanSource.Project -> state.projects[source.id]?.title ?: "Project"
        else -> PlanSource.lists(dayPhrase).first { it.first == source }.second
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
                hoveredDay = hoveredDay,
                onSelect = { day ->
                    // A second finger on a day while the first one carries a task: the hour stays.
                    if (carrying) keepScrollFor = day
                    state.strip.firstOrNull()?.let { viewModel.selectDay(day, it.day) }
                },
                onSwipe = viewModel::moveStrip,
                onDayPlaced = { day, coords -> stripDays[day] = coords },
            )
            // Pull to refresh: tasks and the calendars at once (contract 20.9).
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth()
                        .clipToBounds()
                        .onGloballyPositioned { containerCoords = it },
                ) {
                    // The timeline stays in sight beside the drawer.
                    val drawerWidth = minOf(maxWidth * 0.62f, 280.dp)
                    val widthPx = constraints.maxWidth.toFloat()
                    val flingPx = with(density) { FLING_DP_PER_SECOND.dp.toPx() }

                    fun settleSwipe(velocity: Float) {
                        val travelled = swipeTravel
                        swipeTravel = 0f
                        val step = when {
                            travelled < -widthPx / 3 || (travelled < 0 && velocity < -flingPx) -> 1
                            travelled > widthPx / 3 || (travelled > 0 && velocity > flingPx) -> -1
                            else -> 0
                        }
                        scope.launch {
                            val from = latestState.selectedDay
                            if (step == 0 || from == null) {
                                swipe.animateTo(0f, VicuMotion.defaultSpatialSpec())
                                return@launch
                            }
                            val target = from.plus(step, DateTimeUnit.DAY)
                            if (reduceMotion) fade.animateTo(0f, tween(90)) else swipe.animateTo(-step * widthPx, VicuMotion.defaultSpatialSpec())
                            keepScrollFor = target
                            viewModel.stepDay(step)
                            withTimeoutOrNull(1_000) { snapshotFlow { latestState.selectedDay }.first { it == target } }
                            withFrameNanos { }
                            swipe.snapTo(0f)
                            if (reduceMotion) fade.animateTo(1f, tween(150))
                        }
                    }

                    val swipeModifier = Modifier.daySwipe(
                        enabled = !drawerOpen && !carrying && content != null,
                        onStart = {
                            swipeTravel = 0f
                            scope.launch { neighbourScroll.scrollTo(scrollState.value) }
                        },
                        onDelta = { delta ->
                            swipeTravel += delta
                            // To the running total: a late update can only repeat a position, never lose a step.
                            val to = swipeTravel
                            if (!reduceMotion) scope.launch { swipe.snapTo(to) }
                        },
                        onEnd = ::settleSwipe,
                    )

                    // The day a swipe shows before it lands: it only draws.
                    val neighbour = when {
                        swipe.value < 0f -> state.next
                        swipe.value > 0f -> state.previous
                        else -> null
                    }
                    if (neighbour != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = swipe.value + if (swipe.value < 0f) widthPx else -widthPx },
                        ) {
                            PlanTopRow(
                                allDayEvents = neighbour.allDayEvents,
                                eventColor = eventColor,
                                tasks = neighbour.topRow,
                                heldIds = emptySet(),
                                target = null,
                                targetHovered = false,
                                colorOf = colorOf,
                                canDrag = { false },
                                onPick = {},
                                onToggleDone = {},
                                onDragStart = { _, _ -> },
                                onDrag = {},
                                onDragEnd = {},
                                onDragCancel = {},
                                interactive = false,
                            )
                            PlanTimeline(
                                day = neighbour.day,
                                blocks = neighbour.blocks,
                                markers = neighbour.markers,
                                routines = emptyList(),
                                hours = state.hours,
                                nowMinute = viewModel.minutesNow().takeIf { neighbour.day == today },
                                scrollState = neighbourScroll,
                                colorOf = colorOf,
                                heldDone = emptySet(),
                                onToggleDone = {},
                                onBlockClick = {},
                                onMarkerClick = {},
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                events = neighbour.events,
                                eventColor = eventColor,
                            )
                        }
                    }

                    if (content != null && selectedDay != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = swipe.value
                                    alpha = fade.value
                                }
                                .then(swipeModifier),
                        ) {
                            PlanTopRow(
                                allDayEvents = content.allDayEvents,
                                eventColor = eventColor,
                                onEventClick = { eventSheet = it },
                                tasks = frozenTopRow ?: content.topRow,
                                heldIds = state.heldIds,
                                target = topRowTarget,
                                targetHovered = topRowHovered,
                                colorOf = colorOf,
                                canDrag = { state.canChange(it) && state.canPlace },
                                onPick = ::pick,
                                onToggleDone = viewModel::toggleTopRow,
                                onDragStart = { task, window -> startDrag(task, window, fromDrawer = false) },
                                onDrag = { window -> drag = drag?.copy(window = window) },
                                onDragEnd = ::endDrag,
                                onDragCancel = ::cancelDrag,
                                modifier = Modifier.onGloballyPositioned { topRowCoords = it },
                            )
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
                                onMove = { task, minute ->
                                    val day = latestState.selectedDay
                                    if (day == null || TimeBlocks.blockOf(task, clock.zone)?.day == day) {
                                        viewModel.move(task, minute)
                                    } else {
                                        viewModel.moveTo(task, day, minute)
                                    }
                                },
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
                                onBlockDrag = { task, window ->
                                    blockCarry = if (window == null) null else BlockCarry(task, window)
                                },
                                onBlockDropOutside = { task ->
                                    val at = blockCarry?.window
                                    val day = at?.let(::stripDayAt)
                                    when {
                                        at == null -> false
                                        day != null -> {
                                            if (TimeBlocks.blockOf(task, clock.zone)?.day != day) viewModel.moveToDay(task, day)
                                            true
                                        }
                                        overTopRow(at) -> {
                                            latestState.selectedDay?.let { viewModel.planForDay(task, it) }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                                scrollsUnder = { at -> stripDayAt(at) == null && !overTopRow(at) },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                events = content.events,
                                eventColor = eventColor,
                                onEventClick = { eventSheet = it },
                                canEditEvent = isOwnEvent,
                                onEventChange = { occurrence, range -> changeEvent(occurrence, null, range) },
                                onLongPressTime = if (activePick == null && drag == null) {
                                    { minute: Int ->
                                        newAsEvent = false
                                        newAtMinute = minute
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    if (!drawerOpen && activePick == null && !carrying) {
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
                    val fromDrawer = drag?.fromDrawer == true
                    val slide by animateFloatAsState(
                        targetValue = if (drawerOpen && drag == null) 0f else 1f,
                        animationSpec = if (reduceMotion) tween(0) else VicuMotion.defaultSpatialSpec(),
                        label = "planDrawer",
                    )
                    if (slide < 1f || drawerOpen || fromDrawer) {
                        PlanDrawer(
                            state = state,
                            listed = frozenListed ?: state.listed,
                            sourceLabel = sourceLabel,
                            dayLabel = dayPhrase,
                            onClose = { drawerOpen = false },
                            onSource = viewModel::setSource,
                            onFilter = viewModel::setFilter,
                            onPick = ::pick,
                            onDragStart = { task, window -> startDrag(task, window, fromDrawer = true) },
                            onDrag = { window -> drag = drag?.copy(window = window) },
                            onDragEnd = ::endDrag,
                            onDragCancel = ::cancelDrag,
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
                onMoveToDay = { day ->
                    keepScrollFor = day
                    viewModel.moveToDay(task, day)
                    sheetTaskId = null
                },
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
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Plan settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PlannerOptions()
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                PlanEventsSection(
                    onNewEvent = {
                        gearOpen = false
                        newAsEvent = true
                        newAtMinute = newEventMinute(selectedDay == today, viewModel.minutesNow(), state.hours)
                    },
                    onImport = {
                        gearOpen = false
                        pickCalendarFile()
                    },
                    onOpenEvent = { listed ->
                        gearOpen = false
                        val day = today ?: selectedDay
                        if (day != null) eventSheet = occurrenceToOpen(listed, day, clock.zone)
                    },
                    onOpenCalendars = if (viewModel.calendarsSupported) {
                        {
                            gearOpen = false
                            calendarsOpen = true
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    if (calendarsOpen) {
        CalendarsSheet(onDismiss = { calendarsOpen = false })
    }

    val importing = importText
    val importDay = today
    if (importing != null && importDay != null) {
        ImportCalendarSheet(
            text = importing,
            importDay = importDay,
            zone = clock.zone,
            onImport = { events ->
                importText = null
                viewModel.importEvents(events)
            },
            onDismiss = { importText = null },
        )
    }

    val newMinute = newAtMinute
    if (newMinute != null && selectedDay != null) {
        NewOnPlanSheet(
            day = selectedDay,
            startMinute = newMinute,
            defaultLength = state.defaultLength,
            canAddTask = state.canPlace,
            startAsEvent = newAsEvent,
            onTask = { title ->
                viewModel.createTask(title, selectedDay, newMinute)
                newAtMinute = null
            },
            onEvent = { title, repeat ->
                viewModel.createEvent(title, selectedDay, newMinute, state.defaultLength, repeat)
                newAtMinute = null
            },
            onDismiss = { newAtMinute = null },
        )
    }

    eventSheet?.let { shown ->
        // As it is now: the occurrence on screen, or the one the gear's list opened.
        val occurrence = state.calendar.events.firstOrNull { it.id == shown.id } ?: shown
        EventSheet(
            occurrence = occurrence,
            own = state.calendar.ownEvent(occurrence.planEventId),
            calendarName = state.calendar.nameOf(occurrence.calendarId),
            defaultLength = state.defaultLength,
            onSave = { edit ->
                eventSheet = null
                changeEvent(occurrence, edit, null)
            },
            onSkip = {
                eventSheet = null
                viewModel.skipEvent(occurrence)
            },
            onDelete = {
                eventSheet = null
                viewModel.deleteEvent(occurrence)
            },
            onDismiss = { eventSheet = null },
        )
    }

    pendingEventChange?.let { pending ->
        EventScopeDialog(
            title = pending.occurrence.title,
            allowThisOnly = pending.edit?.let(PlanEvents::allowsThisOnly) ?: true,
            onPick = { scope ->
                pendingEventChange = null
                when {
                    pending.edit != null -> viewModel.changeEvent(pending.occurrence, pending.edit, scope)
                    pending.range != null -> viewModel.moveEvent(pending.occurrence, pending.range, scope)
                }
            },
            onDismiss = { pendingEventChange = null },
        )
    }
}

/** Where New event in the gear starts: the next quarter hour on today, the work hours' start on another day. */
private fun newEventMinute(isToday: Boolean, nowMinute: Int, hours: com.rendyhd.vicu.util.PlannerHours): Int {
    val minute = if (isToday) ((nowMinute + 14) / 15) * 15 else TimeBlocks.clockMinutes(hours.start)
    return minute.coerceIn(0, PlannerTimeline.DAY_MINUTES - TimeBlocks.STEP_MINUTES)
}

/** The occurrence an Event in the gear's list opens: [from] when it happens then, else its next one, else its first day. */
private fun occurrenceToOpen(listed: ListedEvent, from: LocalDate, zone: kotlinx.datetime.TimeZone): CalendarEvent {
    val event = listed.event
    val day = when {
        PlanEvents.occursOn(event, from) -> from
        else -> PlanEvents.nextOccurrence(event, from) ?: event.start
    }
    return PlanEvents.occurrences(listOf(listed), listOf(day), zone).firstOrNull()
        ?: PlanEvents.occurrences(listOf(ListedEvent(listed.id, event.copy(until = null, skips = emptyList()))), listOf(event.start), zone).first()
}

/**
 * Seven days from the strip's start; a tap selects, a swipe goes a week on or back. While a task is
 * carried, [hoveredDay] is the day under the finger, which opens after a moment.
 */
@Composable
private fun PlanDayStrip(
    state: PlanUiState,
    hoveredDay: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (Int) -> Unit,
    onDayPlaced: (LocalDate, LayoutCoordinates) -> Unit,
) {
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
            // Each day its own cell, so a cell's position is never reported for a day it no longer shows.
            key(strip.day) {
                val selected = strip.day == state.selectedDay
                val hovered = strip.day == hoveredDay
                val isToday = strip.day == state.today
                val colors = MaterialTheme.colorScheme
                val label = if (isToday) "Today" else DateDisplay.formatWeekdayShort(strip.day, format)
                val full = state.today?.let { DateDisplay.formatWeekdayDate(strip.day, it, format) }.orEmpty()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .onGloballyPositioned { onDayPlaced(strip.day, it) }
                        .clip(MaterialTheme.shapes.small)
                        .background(if (selected) colors.primaryContainer else colors.background)
                        .then(if (hovered) Modifier.border(2.dp, colors.primary, MaterialTheme.shapes.small) else Modifier)
                        .selectable(selected = selected) { onSelect(strip.day) }
                        .semantics {
                            contentDescription = if (isToday) "Today, $full" else full
                            stateDescription = listOfNotNull(
                                "${PlannerTimeline.formatLength(strip.plannedMinutes)} planned",
                                strip.eventMinutes.takeIf { it > 0 }?.let { "${PlannerTimeline.formatLength(it)} in events" },
                            ).joinToString(", ")
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
