package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight

/**
 * The scroll of a list screen's large title: the title folds into the bar as the list scrolls.
 * Give [modifier] to the screen's Scaffold (it carries the nested scroll) and the object itself to
 * [VicuTopAppBar]. Keeps the experimental Material 3 scroll behaviour out of the screens.
 */
class VicuTopBarScroll @OptIn(ExperimentalMaterial3Api::class) internal constructor(
    internal val behavior: TopAppBarScrollBehavior,
) {
    @OptIn(ExperimentalMaterial3Api::class)
    val modifier: Modifier = Modifier.nestedScroll(behavior.nestedScrollConnection)

    /** 0 while the title is fully expanded, 1 once it has folded into the bar. */
    @OptIn(ExperimentalMaterial3Api::class)
    val collapsedFraction: Float get() = behavior.state.collapsedFraction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberVicuTopBarScroll(listState: LazyListState? = null): VicuTopBarScroll {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    if (listState != null) {
        // A list that fits on the screen cannot be scrolled back, so a title folded earlier (the list was
        // longer, rows were completed) would stay folded with nothing to open it again: open it.
        val fits = !listState.canScrollBackward && !listState.canScrollForward
        LaunchedEffect(fits) {
            if (fits) {
                behavior.state.heightOffset = 0f
                behavior.state.contentOffset = 0f
            }
        }
    }
    return VicuTopBarScroll(behavior)
}

/**
 * The top bar of a screen. With [scroll] it is a large bar (bold 28 sp title that folds into the bar
 * on scroll, its container turning to the surface container colour once scrolled); without it, the
 * plain small bar (settings, review, routines).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VicuTopAppBar(
    title: @Composable () -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: (() -> Unit)? = null,
    extraActions: @Composable (RowScope.() -> Unit)? = null,
    scroll: VicuTopBarScroll? = null,
) {
    val navigationIcon: @Composable () -> Unit = {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu")
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        extraActions?.invoke(this)
        if (onNavigateToSearch != null) {
            IconButton(onClick = onNavigateToSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
        }
    }
    if (scroll == null) {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions)
        return
    }
    val boldTitle: @Composable () -> Unit = {
        ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)) { title() }
    }
    LargeTopAppBar(
        title = boldTitle,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scroll.behavior,
    )
}
