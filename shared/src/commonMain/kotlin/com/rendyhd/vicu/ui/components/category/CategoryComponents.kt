package com.rendyhd.vicu.ui.components.category

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.components.section.sectionStateDescription
import com.rendyhd.vicu.util.ProjectCategory
import com.rendyhd.vicu.util.ProjectCategoryState
import com.rendyhd.vicu.util.countOf

/** The words of the category actions, the same on the desktop. */
object CategoryLabels {
    const val MOVE_TO = "Move to category"
    const val ADD_ABOVE = "Add category above"
    const val NONE = "No category"
    const val NEW = "New category"
    const val RENAME = "Rename"
    const val RENAME_TITLE = "Rename category"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val DELETE = "Delete category"
}

/**
 * The user's project categories wherever a project is picked: provided at the app root, so a
 * picker groups its projects under the same headers as the drawer without every screen passing them.
 */
val LocalProjectCategories = compositionLocalOf { ProjectCategoryState.EMPTY }

/** A category's name above its projects in a picker: a heading, not something to pick. */
@Composable
fun PickerCategoryHeading(category: ProjectCategory, modifier: Modifier = Modifier) {
    Text(
        text = category.name,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** What a screen reader says for a category's header. */
internal fun categoryHeaderDescription(category: ProjectCategory, count: Int): String =
    "${category.name}, category, ${countOf(count, "project")}"

/**
 * A category's header in a project list (docs/design-system-v1.md, section 5): the name as the
 * user typed it, small and quiet under a hairline, so it orders the list without competing with the
 * Projects header above it. Collapsed, the number of its projects follows the name.
 *
 * With [onToggle] the row opens and closes the category (the drawer) and a long press opens its
 * menu; without it (Settings) the row has a "more" button for the menu instead. The menu, and the
 * screen reader's actions, are Rename, Move up and Move down where there is room, and Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryHeader(
    category: ProjectCategory,
    count: Int,
    collapsed: Boolean,
    startPadding: Dp,
    onToggle: (() -> Unit)?,
    onRename: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actions = buildList {
        add(CategoryLabels.RENAME to onRename)
        onMoveUp?.let { add(CategoryLabels.MOVE_UP to it) }
        onMoveDown?.let { add(CategoryLabels.MOVE_DOWN to it) }
        add(CategoryLabels.DELETE to onDelete)
    }
    // In the drawer the chevron lines up with the section header's; in Settings the "more" button with the rows'.
    val endPadding = if (onToggle != null) startPadding else 4.dp
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(
            modifier = Modifier.padding(start = startPadding, end = if (onToggle != null) startPadding else 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onToggle != null) {
                            Modifier.combinedClickable(
                                role = Role.Button,
                                onClickLabel = if (collapsed) "Expand" else "Collapse",
                                onLongClickLabel = "More options",
                                onLongClick = { menuOpen = true },
                                onClick = onToggle,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = categoryHeaderDescription(category, count)
                        if (onToggle != null) stateDescription = sectionStateDescription(!collapsed)
                        customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
                    }
                    .heightIn(min = 48.dp)
                    .padding(start = startPadding, end = endPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (collapsed) "${category.name} · $count" else category.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onToggle != null) {
                    Icon(
                        imageVector = if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "More options for ${category.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                actions.forEach { (label, run) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (label == CategoryLabels.DELETE) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            run()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Asks for a category's name: a new one ([initialName] empty) or a rename. A blank name is not
 * taken; it says "Enter a name" instead.
 */
@Composable
fun CategoryNameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var showError by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    showError = false
                },
                label = { Text("Name") },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text("Enter a name") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) showError = true else onConfirm(name.trim())
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "Move to category" for [projectTitle]: no category or one of [categories] (display order), the
 * one it is in ([currentId]) marked, and "New category" to make one and move it there.
 */
@Composable
fun MoveToCategoryDialog(
    projectTitle: String,
    categories: List<ProjectCategory>,
    currentId: String?,
    onChoose: (ProjectCategory?) -> Unit,
    onNewCategory: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"$projectTitle\" to") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CategoryChoice(CategoryLabels.NONE, selected = currentId == null) { onChoose(null) }
                categories.forEach { category ->
                    CategoryChoice(category.name, selected = category.id == currentId) { onChoose(category) }
                }
                TextButton(onClick = onNewCategory, modifier = Modifier.padding(top = 4.dp)) {
                    Text(CategoryLabels.NEW)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CategoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
