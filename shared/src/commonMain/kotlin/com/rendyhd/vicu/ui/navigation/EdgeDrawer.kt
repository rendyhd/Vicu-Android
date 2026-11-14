package com.rendyhd.vicu.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Opening the drawer by a swipe from the left edge of a list screen.
 *
 * With gesture navigation the screen's edges belong to the system's Back gesture; an app can only
 * keep 200 dp of an edge for itself. So the drawer takes the Back gesture instead: a Back swipe that
 * starts at the left edge opens the drawer, and any other Back (the right edge, a button) is passed
 * on to what would have had it. The system says which edge a swipe came from on Android 14 and
 * later; before that every Back is passed on, as it was.
 *
 * While the drawer is open, Back closes it: what a swipe opened, a swipe closes.
 *
 * [allowed] is whether the drawer may open by gesture now: signed in, the drawer closed, nothing
 * held over the screens. [isOpen] is whether the drawer is open.
 */
@Stable
class EdgeDrawer(
    val navController: NavController,
    val allowed: State<Boolean>,
    val isOpen: State<Boolean>,
    val open: () -> Unit,
    val close: () -> Unit,
)

/** The app's [EdgeDrawer]; null outside the app. */
val LocalEdgeDrawer = staticCompositionLocalOf<EdgeDrawer?> { null }

/**
 * The screens whose left edge opens the drawer: the lists the drawer and the bottom bar lead to,
 * which show the drawer's button. Plan is not one (a sideways swipe there changes the day), nor are
 * Settings, Search, Ask, Setup and the guided sessions, where both edges stay Back.
 */
internal fun isEdgeDrawerRoute(routeKey: String?): Boolean = when {
    routeKey == null -> false
    routeKey.startsWith("ProjectRoute/") || routeKey.startsWith("TagRoute/") || routeKey.startsWith("CustomListRoute/") -> true
    else -> routeKey in EDGE_DRAWER_ROUTES
}

private val EDGE_DRAWER_ROUTES = setOf(
    "InboxRoute", "TodayRoute", "UpcomingRoute", "AnytimeRoute", "LogbookRoute", "ReviewRoute", "RoutinesRoute",
    "FlaggedRoute", "WaitingOnRoute", "PeopleRoute",
)

/**
 * Whether [entry], the screen on top, is a list screen one step from the Inbox at most: a project
 * opened from its parent has a back arrow, and Back there goes to the parent.
 */
internal fun NavController.isEdgeDrawerScreen(entry: NavBackStackEntry): Boolean =
    isEdgeDrawerRoute(currentRouteKey(entry)) && (
        entry.destination.hasRoute(InboxRoute::class) ||
            previousBackStackEntry?.destination?.hasRoute(InboxRoute::class) == true
        )

/**
 * What one Back gesture does. While the drawer is open it closes the drawer when it completes.
 * Otherwise (a list screen is on top) a gesture from the left edge opens the drawer as it starts,
 * and closes it again when the finger goes back; every other Back is passed on when it completes.
 * Pure, so it is tested without a dispatcher.
 */
internal class EdgeBackGesture(
    private val drawerOpen: () -> Boolean,
    private val open: () -> Unit,
    private val close: () -> Unit,
    private val passOn: () -> Unit,
) {
    private var opening = false
    private var closing = false

    /** A gesture is under way: the handler must stay with it, whatever the drawer does meanwhile. */
    var inGesture = false
        private set

    fun started(swipeEdge: Int) {
        inGesture = true
        closing = drawerOpen()
        opening = !closing && swipeEdge == NavigationEvent.EDGE_LEFT
        if (opening) open()
    }

    fun cancelled() {
        if (opening) close()
        inGesture = false
        opening = false
        closing = false
    }

    /** Also a Back without a gesture (a button, an Android before 14): [started] was never called. */
    fun completed() {
        val wasOpening = opening
        val wasClosing = closing || (!inGesture && drawerOpen())
        inGesture = false
        opening = false
        closing = false
        when {
            wasClosing -> close()
            !wasOpening -> passOn()
        }
    }
}

private class EdgeDrawerHandler(
    private val edge: EdgeDrawer,
    private val scope: CoroutineScope,
) : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = false) {
    val input = DirectNavigationEventInput()

    /** Whether this screen wants the gesture; applied once no gesture is under way. */
    var wanted = false
        set(value) {
            field = value
            if (!gesture.inGesture && !passing) isBackEnabled = value
        }
    private var passing = false

    private val gesture = EdgeBackGesture(
        drawerOpen = { edge.isOpen.value },
        open = edge.open,
        close = edge.close,
        passOn = {
            // After this dispatch has ended, with this handler out of the way, so that the next in
            // line (the screens' own Back, or leaving the app) gets it.
            passing = true
            isBackEnabled = false
            scope.launch {
                try {
                    input.backCompleted()
                } finally {
                    passing = false
                    isBackEnabled = wanted
                }
            }
        },
    )

    override fun onBackStarted(event: NavigationEvent) = gesture.started(event.swipeEdge)

    override fun onBackCancelled() {
        gesture.cancelled()
        isBackEnabled = wanted
    }

    override fun onBackCompleted() {
        gesture.completed()
        if (!passing) isBackEnabled = wanted
    }
}

/**
 * Put first in a destination ([entry]): its Back handler is then behind the screen's own (a
 * selection to clear, a sheet to close), which are added after it, and ahead of the back stack's.
 * It only takes Back while [entry] is on top and either the drawer is open (Back closes it, on any
 * screen) or [entry] is a list screen ([isEdgeDrawerScreen]) and the drawer may open.
 */
@Composable
internal fun EdgeDrawerBack(entry: NavBackStackEntry) {
    val edge = LocalEdgeDrawer.current ?: return
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    val scope = rememberCoroutineScope()
    val handler = remember(edge, owner) { EdgeDrawerHandler(edge, scope) }
    DisposableEffect(owner, handler) {
        val dispatcher = owner.navigationEventDispatcher
        dispatcher.addHandler(handler)
        dispatcher.addInput(handler.input)
        onDispose {
            handler.remove()
            dispatcher.removeInput(handler.input)
        }
    }
    val current by edge.navController.currentBackStackEntryAsState()
    val onTop = current?.id == entry.id
    val listScreen = remember(entry, onTop) { onTop && edge.navController.isEdgeDrawerScreen(entry) }
    val wanted = onTop && (edge.isOpen.value || (listScreen && edge.allowed.value))
    SideEffect { handler.wanted = wanted }
}

/** How far from the left edge a drag may start to open the drawer. */
private val EDGE_WIDTH = 24.dp

/**
 * Opens the drawer by a drag to the right that starts at the left edge, for where the edge does not
 * belong to a system Back gesture (three-button navigation). It watches before the content does, so
 * a row under the finger does not take the drag for its own swipe, and it takes nothing else: a tap
 * or a scroll there reaches the content as before.
 */
internal fun Modifier.edgeDrawerDrag(enabled: Boolean, onOpen: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(onOpen) {
            val edge = EDGE_WIDTH.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                    if (dx > slop && dx > abs(dy)) {
                        change.consume()
                        onOpen()
                        // The rest of this drag is the drawer's: the row under it must not swipe.
                        while (true) {
                            val rest = awaitPointerEvent(PointerEventPass.Initial)
                            rest.changes.forEach { it.consume() }
                            if (rest.changes.none { it.pressed }) break
                        }
                        break
                    }
                }
            }
        }
    }
