package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.BottomBarSlotType
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.section.sectionStateDescription
import com.rendyhd.vicu.ui.components.shared.IconRegistry
import com.rendyhd.vicu.ui.theme.VicuChipShape
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.parseHexColor

@Composable
internal fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun TimePickerRow(
    label: String,
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
) {
    val timeText = String.format(
        "%d:%02d %s",
        if (hour % 12 == 0) 12 else hour % 12,
        minute,
        if (hour < 12) "AM" else "PM",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SectionTitle(
    title: String,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A sub-heading inside a tab (Inbox, Display, Review): accent text, no icon. */
@Composable
internal fun SubsectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** One entry of a row's overflow menu. A [destructive] one is drawn in the error colour. */
internal class RowMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** The trailing "more" button of a row and the menu it opens. [contentDescription] names the row. */
@Composable
internal fun RowOverflowMenu(
    contentDescription: String,
    items: List<RowMenuItem>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            color = if (item.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** What a screen reader says for the "more" button of the row of [title]. */
internal fun moreOptionsDescription(title: String): String = "More options for $title"

/** The small "Inbox" tag on the Inbox project's row. */
@Composable
private fun InboxChip() {
    Text(
        text = "Inbox",
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, VicuChipShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** How far a project [depth] levels into the tree is moved in (the old Settings indent). */
internal fun settingsProjectIndent(depth: Int): Dp = (16 + depth * 24).dp

/**
 * An active project in the Projects tab: its folder in the project colour, the title, an "Inbox"
 * tag on the Inbox, and the "more" menu. Tapping the row edits the project.
 */
@Composable
internal fun SettingsProjectRow(
    project: Project,
    depth: Int,
    isInbox: Boolean,
    onEdit: () -> Unit,
    menuItems: List<RowMenuItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Edit", onClick = onEdit)
            .padding(start = settingsProjectIndent(depth), end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = parseHexColor(project.hexColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        // The title takes what it needs and the tag follows it; a long title gives way to the tag.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = project.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isInbox) {
                Spacer(modifier = Modifier.width(8.dp))
                InboxChip()
            }
        }
        RowOverflowMenu(contentDescription = moreOptionsDescription(project.title), items = menuItems)
    }
}

/**
 * The row that opens and closes the archived projects: "Archived" and how many. The whole row is
 * the button and announces whether the group is open.
 */
@Composable
internal fun ArchivedGroupRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = if (expanded) "Collapse" else "Expand",
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics { stateDescription = sectionStateDescription(expanded) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Archived",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * An archived project: greyed and not tappable, with a Restore button and a menu that restores or
 * deletes it.
 */
@Composable
internal fun ArchivedProjectRow(
    project: Project,
    depth: Int,
    onRestore: () -> Unit,
    menuItems: List<RowMenuItem>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = settingsProjectIndent(depth), end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = project.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRestore) {
            Icon(
                Icons.Outlined.Unarchive,
                contentDescription = "Restore ${project.title}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        RowOverflowMenu(contentDescription = moreOptionsDescription(project.title), items = menuItems)
    }
}

/** A label: its colour dot and title. Tapping the row edits it; the menu edits or deletes it. */
@Composable
internal fun LabelRow(
    label: Label,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dotColor = parseHexColor(label.hexColor)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Edit", onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor ?: MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowOverflowMenu(
            contentDescription = moreOptionsDescription(label.title),
            items = listOf(
                RowMenuItem("Edit", onClick = onEdit),
                RowMenuItem("Delete", destructive = true, onClick = onDelete),
            ),
        )
    }
}

/** A custom list: its name and filter summary. Tapping the row edits it; the menu edits or deletes it. */
@Composable
internal fun CustomListRow(
    customList: CustomList,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Edit", onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.FilterList,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customList.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val filterSummary = buildFilterSummary(customList)
            if (filterSummary.isNotBlank()) {
                Text(
                    text = filterSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RowOverflowMenu(
            contentDescription = moreOptionsDescription(customList.name),
            items = listOf(
                RowMenuItem("Edit", onClick = onEdit),
                RowMenuItem("Delete", destructive = true, onClick = onDelete),
            ),
        )
    }
}

@Composable
internal fun BottomBarSlotRow(
    slot: BottomBarSlot,
    slotIndex: Int,
    projects: List<Project>,
    customLists: List<CustomList>,
    onClick: () -> Unit,
) {
    val icon = IconRegistry.resolveIcon(slot)
    val label = when (slot.type) {
        BottomBarSlotType.TODAY -> "Today"
        BottomBarSlotType.UPCOMING -> "Upcoming"
        BottomBarSlotType.ANYTIME -> "Anytime"
        BottomBarSlotType.PROJECT -> {
            projects.find { it.id.toString() == slot.referenceId }?.title ?: "Deleted project"
        }
        BottomBarSlotType.CUSTOM_LIST -> {
            customLists.find { it.id == slot.referenceId }?.name ?: "Deleted list"
        }
    }
    val typeLabel = when (slot.type) {
        BottomBarSlotType.TODAY -> "Smart List"
        BottomBarSlotType.UPCOMING -> "Smart List"
        BottomBarSlotType.ANYTIME -> "Smart List"
        BottomBarSlotType.PROJECT -> "Project"
        BottomBarSlotType.CUSTOM_LIST -> "Custom List"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${slotIndex + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.Edit,
            contentDescription = "Edit slot",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

internal fun buildFilterSummary(list: CustomList): String {
    val parts = mutableListOf<String>()
    val f = list.filter
    if (f.dueDateFilter != "all") {
        parts.add(f.dueDateFilter.replace("_", " "))
        // Overdue tasks are part of the today / this week / this month windows unless the list
        // turned them off; say so, because it changes what the list shows.
        if (!f.includesOverdue && CustomListFilterBuilder.windowHonorsIncludeOverdue(f.dueDateFilter)) {
            parts.add("excl. overdue")
        }
    }
    if (f.projectIds.isNotEmpty()) {
        parts.add("${f.projectIds.size} project(s)")
    }
    if (f.labelIds.isNotEmpty()) {
        parts.add("${f.labelIds.size} label(s)")
    }
    if (f.includeDone) {
        parts.add("incl. done")
    }
    return parts.joinToString(" · ")
}
