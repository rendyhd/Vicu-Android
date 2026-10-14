package com.rendyhd.vicu.ui.screens.logbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.task.AnimatedCheckbox
import com.rendyhd.vicu.ui.components.task.TaskRowAction
import com.rendyhd.vicu.ui.components.task.taskRowActions
import com.rendyhd.vicu.ui.components.task.taskRowCustomActions

/**
 * A completed task in the Logbook: the filled check, the title in the muted colour (no strike,
 * the check says it is done) and the time of the completion on the right. The day is the group
 * heading above. A task reopened here is drawn as an ordinary open row until the user leaves.
 * The title may take a second line so a name is never cut short; the check keeps its 48 dp target.
 */
@Composable
fun LogbookRow(
    task: Task,
    time: String,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A screen reader reopens (or completes again) from the row's actions menu.
    val actions = taskRowActions(done = task.done, canSchedule = false).map { action ->
        action to {
            if (action == TaskRowAction.COMPLETE || action == TaskRowAction.REOPEN) onToggleDone()
        }
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Open", onClick = onClick)
                .taskRowCustomActions(actions)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedCheckbox(done = task.done, onToggle = onToggleDone, contentDescription = task.title)
            Text(
                text = task.title,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 52.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}
