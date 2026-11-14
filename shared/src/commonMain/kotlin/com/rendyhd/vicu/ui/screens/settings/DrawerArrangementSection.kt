package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.DrawerView
import com.rendyhd.vicu.ui.components.pro.ProIcons
import com.rendyhd.vicu.ui.components.shared.SmartListIdentity
import com.rendyhd.vicu.ui.components.task.moveCustomActions
import com.rendyhd.vicu.ui.navigation.DrawerBlock
import com.rendyhd.vicu.ui.navigation.DrawerEntry
import com.rendyhd.vicu.ui.navigation.DrawerLayout
import com.rendyhd.vicu.ui.navigation.DrawerViewOffer
import com.rendyhd.vicu.ui.navigation.drawerViewOffers
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.util.pro.ProLayer
import sh.calvin.reorderable.ReorderableColumn

/**
 * How the drawer is arranged ([DrawerLayout]): one list of the views it can list now and its three
 * blocks (projects, lists, labels), dragged into the user's order. A view has a switch that lists it
 * in the drawer; one the bottom bar holds says so and is off until it is turned on. A view that is
 * not listed keeps working.
 */
internal fun LazyListScope.drawerSection(
    state: SettingsUiState,
    askReady: Boolean,
    viewModel: SettingsViewModel,
) {
    item(key = "drawer_header") {
        Text(
            text = "Drawer",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    item(key = "drawer_desc") {
        Text(
            text = "Choose what the drawer lists and drag it into your order. A view of the bottom bar is in the drawer too once you turn it on here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    val prefs = state.behaviorPrefs
    item(key = "drawer_entries") {
        val offers = drawerViewOffers(
            slots = state.bottomBarSlots,
            routinesEnabled = state.routineVisibility.enabled,
            reviewEnabled = state.reviewPrefs.enabled,
            askReady = askReady,
            plannerEnabled = prefs.plannerEnabled,
            proAvailability = prefs.isProEnabled(ProLayer.AVAILABILITY),
            proPromises = prefs.isProEnabled(ProLayer.PROMISES),
        )
        DrawerArrangement(
            entries = DrawerLayout.arrangeable(prefs.drawerOrder, offers),
            offers = offers,
            hidden = prefs.hiddenDrawerViews,
            barViews = prefs.drawerBarViews,
            onArrange = { arranged -> viewModel.setDrawerOrder(DrawerLayout.keysAfterArranging(prefs.drawerOrder, arranged)) },
            onShownChange = { offer, shown -> viewModel.setDrawerViewShown(offer.view, offer.inBar, shown) },
        )
    }

    if (prefs.drawerOrder.isNotEmpty()) {
        item(key = "drawer_reset") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextButton(onClick = viewModel::resetDrawerOrder) { Text("Reset order") }
            }
        }
    }

    item(key = "drawer_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun DrawerArrangement(
    entries: List<DrawerEntry>,
    offers: List<DrawerViewOffer>,
    hidden: Set<DrawerView>,
    barViews: Set<DrawerView>,
    onArrange: (List<DrawerEntry>) -> Unit,
    onShownChange: (DrawerViewOffer, Boolean) -> Unit,
) {
    // The order on show: the dropped one at once, until the stored order says the same.
    var live by remember(entries) { mutableStateOf(entries) }
    val haptic = LocalHapticFeedback.current
    val arrange = { next: List<DrawerEntry> ->
        if (next != live) {
            live = next
            onArrange(next)
        }
    }
    ReorderableColumn(
        list = live,
        onSettle = { from, to -> arrange(DrawerLayout.moved(live, from, to)) },
        // A reorder step (design-system-v1, haptics).
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
    ) { index, entry, isDragging ->
        key(entry.key) {
            val offer = (entry as? DrawerEntry.View)?.let { view -> offers.firstOrNull { it.view == view.view } }
            DrawerEntryRow(
                entry = entry,
                // The projects, lists and labels are always in the drawer: no switch.
                shown = offer?.let { DrawerLayout.isShown(it, hidden, barViews) },
                inBar = offer?.inBar == true,
                dragging = isDragging,
                handle = Modifier.draggableHandle(),
                onShownChange = { shown -> offer?.let { onShownChange(it, shown) } },
                // The drag has no equivalent for a screen reader: the same move, one place at a time.
                onMoveUp = if (index > 0) ({ arrange(DrawerLayout.moved(live, index, index - 1)) }) else null,
                onMoveDown = if (index < live.lastIndex) ({ arrange(DrawerLayout.moved(live, index, index + 1)) }) else null,
            )
        }
    }
}

/** An entry with its icon as the drawer draws it, a handle to drag it by, and for a view its switch. */
@Composable
private fun DrawerEntryRow(
    entry: DrawerEntry,
    shown: Boolean?,
    inBar: Boolean,
    dragging: Boolean,
    handle: Modifier,
    onShownChange: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val label = drawerEntryLabel(entry)
    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        shadowElevation = if (dragging) 4.dp else 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = handle
                    .size(48.dp)
                    .semantics { contentDescription = "Move $label" }
                    .moveCustomActions(onMoveUp, onMoveDown),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val content = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .padding(end = 16.dp)
            Row(
                // The row is the switch, so a screen reader finds one control.
                modifier = if (shown == null) content else Modifier
                    .weight(1f)
                    .toggleable(value = shown, role = Role.Switch, onValueChange = onShownChange)
                    .heightIn(min = 56.dp)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative: the label names the entry.
                Icon(
                    imageVector = drawerEntryIcon(entry),
                    contentDescription = null,
                    tint = drawerEntryIdentity(entry)?.color(LocalVicuColors.current.identity)
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (inBar) {
                        Text(
                            "Also in the bottom bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (shown != null) Switch(checked = shown, onCheckedChange = null)
            }
        }
    }
}

internal fun drawerEntryLabel(entry: DrawerEntry): String = when (entry) {
    is DrawerEntry.Block -> when (entry.block) {
        DrawerBlock.PROJECTS -> "Projects"
        DrawerBlock.LISTS -> "Lists"
        DrawerBlock.LABELS -> "Labels"
    }
    is DrawerEntry.View -> when (entry.view) {
        DrawerView.INBOX -> "Inbox"
        DrawerView.TODAY -> "Today"
        DrawerView.PLAN -> "Plan"
        DrawerView.UPCOMING -> "Upcoming"
        DrawerView.ANYTIME -> "Anytime"
        DrawerView.LOGBOOK -> "Logbook"
        DrawerView.ROUTINES -> "Routines"
        DrawerView.REVIEW -> "Review"
        DrawerView.ASK -> "Ask"
        DrawerView.FLAGGED -> "Flagged"
        DrawerView.WAITING_ON -> "Waiting on"
        DrawerView.PEOPLE -> "People"
    }
}

private fun drawerEntryIdentity(entry: DrawerEntry): SmartListIdentity? = when ((entry as? DrawerEntry.View)?.view) {
    DrawerView.INBOX -> SmartListIdentity.INBOX
    DrawerView.TODAY -> SmartListIdentity.TODAY
    DrawerView.PLAN -> SmartListIdentity.PLAN
    DrawerView.UPCOMING -> SmartListIdentity.UPCOMING
    DrawerView.ANYTIME -> SmartListIdentity.ANYTIME
    DrawerView.LOGBOOK -> SmartListIdentity.LOGBOOK
    DrawerView.ROUTINES -> SmartListIdentity.ROUTINES
    DrawerView.REVIEW -> SmartListIdentity.REVIEW
    DrawerView.ASK, DrawerView.FLAGGED, DrawerView.WAITING_ON, DrawerView.PEOPLE, null -> null
}

private fun drawerEntryIcon(entry: DrawerEntry): ImageVector = when (entry) {
    is DrawerEntry.Block -> when (entry.block) {
        DrawerBlock.PROJECTS -> Icons.Outlined.Folder
        DrawerBlock.LISTS -> Icons.AutoMirrored.Filled.FormatListBulleted
        DrawerBlock.LABELS -> Icons.AutoMirrored.Outlined.Label
    }
    is DrawerEntry.View -> drawerEntryIdentity(entry)?.icon ?: when (entry.view) {
        DrawerView.FLAGGED -> ProIcons.FLAGGED
        DrawerView.WAITING_ON -> ProIcons.WAITING_ON
        DrawerView.PEOPLE -> ProIcons.PEOPLE
        else -> Icons.Outlined.AutoAwesome
    }
}
