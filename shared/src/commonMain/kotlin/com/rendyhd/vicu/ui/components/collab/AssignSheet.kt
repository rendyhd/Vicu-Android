package com.rendyhd.vicu.ui.components.collab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.Permission

/** The footer of the Assign list (design-system-v1.md section 4.1), the same on both apps. */
internal fun assignFooterText(projectTitle: String): String =
    "People $projectTitle is shared with. Assigned people get a notification."

/**
 * The Assign sheet of the editor (cross-app semantics 9.2): the members of the task's project, you
 * first, each with a checkbox. A tap toggles at once and the sheet stays open. Members who can only
 * view the project are listed but cannot be picked ("Can view only").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignSheet(
    task: Task,
    projectTitle: String,
    onDismiss: () -> Unit,
) {
    val collab = LocalCollab.current
    val access = collab.access(task.projectId)
    val me = collab.state.me
    val members = access.members
    var permissions by remember(task.projectId) { mutableStateOf<Map<Long, Int?>>(emptyMap()) }
    LaunchedEffect(task.projectId) { permissions = collab.memberPermissions(task.projectId) }
    val assignedIds = task.assignees.map { it.id }.toSet()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                text = "Assign",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp).semantics { heading() },
            )
            if (members.isEmpty()) {
                Text(
                    text = "Loading people…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(members, key = { it.id }) { person ->
                    val viewOnly = permissions[person.id]?.let { it < Permission.WRITE } == true
                    val assigned = person.id in assignedIds
                    // Someone who can only view stays removable if they were assigned before.
                    val enabled = !viewOnly || assigned
                    val name = Collaboration.displayName(person.toCollabUser()) + if (person.id == me) " (you)" else ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = assigned,
                                enabled = enabled,
                                role = Role.Checkbox,
                                onValueChange = { collab.setAssigned(task, person, it) },
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 24.dp)
                            .alpha(if (enabled) 1f else 0.55f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PersonAvatar(person, 32.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (viewOnly) {
                                Text(
                                    text = "Can view only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // The row is the control; the box only shows its state.
                        Checkbox(checked = assigned, onCheckedChange = null, enabled = enabled)
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                text = assignFooterText(projectTitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}
