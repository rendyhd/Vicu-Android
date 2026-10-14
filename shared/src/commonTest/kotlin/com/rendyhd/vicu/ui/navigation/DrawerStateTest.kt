package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.domain.model.BottomBarSlot
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The drawer's state is built by one typed function, off the main thread (A-UI-19, UI-28). */
class DrawerStateTest {

    private val today = LocalDate(2026, 10, 7)

    private fun project(
        id: Long,
        parent: Long = 0,
        position: Double = id.toDouble(),
        archived: Boolean = false,
        description: String = "",
    ) = Project(id = id, title = "Project $id", parentProjectId = parent, position = position, isArchived = archived, description = description)

    /** A description a review is long overdue for. */
    private val overdueReview = "Notes\n\n---\n**Vicu review**: 2026-01-01"
    private val freshReview = "---\n**Vicu review**: 2026-10-06"
    private val excludedReview = "---\n**Vicu review**: excluded"

    private fun sources(
        projects: List<Project> = emptyList(),
        labels: List<Label> = emptyList(),
        customLists: List<CustomList> = emptyList(),
        expanded: DrawerSectionsExpanded = DrawerSectionsExpanded(),
        inboxProjectId: Long? = null,
    ) = DrawerSources(projects, labels, customLists, expanded, inboxProjectId)

    private fun build(
        sources: DrawerSources,
        review: ReviewPrefs = ReviewPrefs(),
        labelOrder: List<Long> = emptyList(),
    ) = buildDrawerState(sources, BottomBarSlot.DEFAULT_SLOTS, review, labelOrder, today)

    // --- review badge ---

    @Test
    fun `the badge counts the projects whose review is overdue`() {
        val state = build(
            sources(
                listOf(
                    project(1, description = overdueReview),
                    project(2, description = freshReview),
                    project(3), // never reviewed counts as due
                    project(4, description = excludedReview),
                ),
            ),
        )

        assertEquals(2, state.reviewOverdueCount)
        assertTrue(state.reviewEnabled)
    }

    @Test
    fun `nothing is computed for the badge when reviews are switched off`() {
        val state = build(
            sources(listOf(project(1, description = overdueReview), project(2))),
            review = ReviewPrefs(enabled = false),
        )

        assertEquals(0, state.reviewOverdueCount)
        assertFalse(state.reviewEnabled)
    }

    @Test
    fun `archived projects and the excluded inbox are not counted`() {
        val projects = listOf(project(1, description = overdueReview), project(2, archived = true), project(3))

        val excludingInbox = build(sources(projects, inboxProjectId = 3), review = ReviewPrefs(excludeInbox = true))
        val includingInbox = build(sources(projects, inboxProjectId = 3), review = ReviewPrefs(excludeInbox = false))

        assertEquals(1, excludingInbox.reviewOverdueCount)
        assertEquals(2, includingInbox.reviewOverdueCount)
    }

    @Test
    fun `a longer cadence moves the due date`() {
        // Reviewed 20 days ago: due under the default 14 days, not under 30.
        val projects = listOf(project(1, description = "---\n**Vicu review**: 2026-09-17"))

        assertEquals(1, build(sources(projects), review = ReviewPrefs(defaultCadenceDays = 14)).reviewOverdueCount)
        assertEquals(0, build(sources(projects), review = ReviewPrefs(defaultCadenceDays = 30)).reviewOverdueCount)
    }

    // --- tree ---

    @Test
    fun `the tree has roots by position with their children, and leaves out the inbox and archived projects`() {
        val state = build(
            sources(
                projects = listOf(
                    project(1, position = 20.0),
                    project(2, position = 10.0),
                    project(3, parent = 1, position = 2.0),
                    project(4, parent = 1, position = 1.0),
                    project(5, archived = true),
                    project(6),
                ),
                inboxProjectId = 6,
            ),
        )

        assertEquals(listOf(2L, 1L), state.projectTree.map { it.project.id })
        assertEquals(listOf(4L, 3L), state.projectTree.last().children.map { it.project.id })
        assertEquals(6L, state.inboxProjectId)
    }

    @Test
    fun `the project pickers get every active project, the inbox included`() {
        val state = build(
            sources(
                projects = listOf(project(1), project(5, archived = true), project(6)),
                inboxProjectId = 6,
            ),
        )

        assertEquals(setOf(1L, 6L), state.allProjects.map { it.id }.toSet())
        assertEquals(listOf(1L), state.projectTree.map { it.project.id }, "but the tree does not list the inbox")
    }

    @Test
    fun `a project whose parent is archived becomes a root`() {
        val state = build(sources(listOf(project(1, archived = true), project(2, parent = 1))))

        assertEquals(listOf(2L), state.projectTree.map { it.project.id })
    }

    @Test
    fun `a project below the inbox is listed as a root`() {
        val state = build(sources(listOf(project(6), project(7, parent = 6)), inboxProjectId = 6))

        assertEquals(listOf(7L), state.projectTree.map { it.project.id })
    }

    @Test
    fun `the tree goes as deep as the projects do`() {
        val state = build(
            sources(listOf(project(1), project(2, parent = 1), project(3, parent = 2), project(4, parent = 3))),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), state.projectRows.map { it.project.id })
        assertEquals(listOf(0, 1, 2, 3), state.projectRows.map { it.depth })
    }

    @Test
    fun `collapsed projects hide their children from the rows but not from the tree`() {
        val state = build(
            sources(
                projects = listOf(project(1), project(2, parent = 1), project(3)),
                expanded = DrawerSectionsExpanded(collapsedProjectIds = setOf(1L)),
            ),
        )

        assertEquals(listOf(1L, 3L), state.projectRows.map { it.project.id })
        assertEquals(setOf(1L), state.collapsedProjectIds)
        assertEquals(listOf(2L), state.projectTree.first().children.map { it.project.id })
    }

    // --- everything else ---

    @Test
    fun `labels follow the stored order and the rest sort by title`() {
        val labels = listOf(Label(id = 1, title = "b"), Label(id = 2, title = "a"), Label(id = 3, title = "c"))

        assertEquals(listOf(2L, 1L, 3L), build(sources(labels = labels)).labels.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), build(sources(labels = labels), labelOrder = listOf(3, 1)).labels.map { it.id })
    }

    @Test
    fun `the section state and the custom lists pass through`() {
        val list = CustomList(id = "l", name = "Work", filter = CustomListFilter())

        val state = build(
            sources(customLists = listOf(list), expanded = DrawerSectionsExpanded(projects = false, lists = true, tags = false)),
        )

        assertEquals(listOf(list), state.customLists)
        assertFalse(state.projectsExpanded)
        assertTrue(state.listsExpanded)
        assertFalse(state.tagsExpanded)
    }
}
