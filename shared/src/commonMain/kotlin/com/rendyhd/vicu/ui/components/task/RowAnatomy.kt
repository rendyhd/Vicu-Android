package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.staticCompositionLocalOf
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.components.section.ProjectMeta
import com.rendyhd.vicu.util.DateContext

// What a task row shows on its meta line depends on the view it is in (docs/design-system-v1.md,
// section 4): a row never repeats what the view already says. The same rules as the desktop
// `lib/row-anatomy.ts`.

/** What the view around a row already says. The default says nothing and uses the plain date phrase. */
data class RowView(
    /** The rows are listed inside this project (a project or the Inbox): no project on the row. */
    val projectId: Long? = null,
    /** The rows are listed under this label (a Tag view): that label is not repeated. */
    val labelId: Long? = null,
    /** The date phrase of the trailing cluster: `row`, `row.inToday` or `row.inDayGroup`. */
    val dateContext: DateContext = DateContext.ROW,
    /**
     * The list mixes projects without a header per project (Search, a custom list): a row names its
     * own project from here. Grouped lists leave it empty; their header says it, and a group of one
     * passes `projectMeta` to the row.
     */
    val projects: Map<Long, ProjectMeta> = emptyMap(),
)

/** Today lists overdue and due-today tasks: a task due today shows its time only, never the word "Today". */
val TodayRowView = RowView(dateContext = DateContext.ROW_IN_TODAY)

/** Upcoming lists tasks under a day header: a row shows its time only, never the day. */
val UpcomingRowView = RowView(dateContext = DateContext.ROW_IN_DAY_GROUP)

/** Provided by a screen around its list; views that say nothing need no provider. */
val LocalRowView = staticCompositionLocalOf { RowView() }

/**
 * The project a row names on its meta line, or null. [projectMeta] is set by the list when the
 * row's group has no header of its own; the row still leaves it off inside that very project.
 */
fun rowProject(taskProjectId: Long, view: RowView, projectMeta: ProjectMeta?): ProjectMeta? =
    if (view.projectId == taskProjectId) null else projectMeta ?: view.projects[taskProjectId]

/** The projects a mixed list can name on its rows, by id; built from the projects the screen already observes. */
fun projectMetas(projects: List<Project>): Map<Long, ProjectMeta> =
    projects.associate { it.id to ProjectMeta(it.title, it.hexColor) }

/** The labels a row shows: all of them, except the one the Tag view is already about. */
fun rowLabels(labels: List<Label>, view: RowView): List<Label> =
    if (view.labelId == null) labels else labels.filter { it.id != view.labelId }

/** The checklist count of the meta line ("1 of 3"). */
fun checklistLabel(completed: Int, total: Int): String = "$completed of $total"
