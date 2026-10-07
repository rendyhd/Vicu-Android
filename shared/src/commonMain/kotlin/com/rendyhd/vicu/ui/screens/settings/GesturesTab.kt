package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.local.ScheduleAction

/** What swiping a task to the left does under the chosen "Swipe to schedule" setting. */
internal fun swipeLeftDescription(action: ScheduleAction): String = when (action) {
    ScheduleAction.DUE_TODAY -> "Set the due date to today"
    ScheduleAction.PRIORITY_URGENT -> "Mark the task urgent (priority 4)"
}

/**
 * A completed task stays in the list for a few seconds (struck through), whichever way it was
 * completed. No snackbar is shown for it, and none for the swipe actions.
 */
internal const val UNDO_TIP =
    "Tip: A task you complete stays in the list for a few seconds. " +
        "Tap its checkbox again to undo."

// ========== Gestures Tab ==========

@Composable
internal fun GesturesTab(
    scheduleAction: ScheduleAction,
    listState: LazyListState,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        item(key = "gestures_header") {
            SectionHeader(icon = Icons.Outlined.Gesture, title = "Gesture Guide")
        }

        item(key = "gestures_intro") {
            Text(
                text = "Learn how to interact with tasks using gestures.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "gesture_swipe_right") {
            GestureRow(
                icon = Icons.Outlined.SwipeRight,
                gesture = "Swipe Right",
                description = "Complete a task",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item(key = "gesture_swipe_left") {
            GestureRow(
                icon = Icons.Outlined.SwipeLeft,
                gesture = "Swipe Left",
                description = swipeLeftDescription(scheduleAction),
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        item(key = "gesture_swipe_setting") {
            Text(
                text = "Change what Swipe Left does under General > Preferences > Swipe to schedule. " +
                    "A swipe needs a drag of half the row's width.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 4.dp),
            )
        }

        item(key = "gesture_tap") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Tap",
                description = "Open task details",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item(key = "gesture_long_press") {
            GestureRow(
                icon = Icons.Outlined.TouchApp,
                gesture = "Long Press",
                description = "Select multiple tasks",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        item(key = "gesture_drag") {
            GestureRow(
                icon = Icons.Outlined.DragHandle,
                gesture = "Long Press and Drag",
                description = "Reorder tasks in the Inbox and in projects ordered by hand",
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        item(key = "gesture_checkbox") {
            GestureRow(
                icon = Icons.Outlined.CheckCircle,
                gesture = "Tap Checkbox",
                description = "Toggle task completion",
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item(key = "gestures_divider") {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item(key = "gestures_tip") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = UNDO_TIP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "gestures_bottom_spacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GestureRow(
    icon: ImageVector,
    gesture: String,
    description: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = gesture,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
