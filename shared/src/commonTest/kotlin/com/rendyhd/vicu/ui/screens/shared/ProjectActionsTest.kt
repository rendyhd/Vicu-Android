package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.data.repository.RecordingRepositoryHooks
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.POSITION_STEP
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project actions every screen shares (Settings, the drawer, the project screen): what each one
 * writes and what it says.
 */
class ProjectActionsTest {

    private class Rig(projects: List<Project> = emptyList()) {
        val repo = FakeProjectRepository(projects)
        val auth = offlineAuthManager()
        val hooks = RecordingRepositoryHooks()
        val actions = testProjectActions(repo, auth = auth, hooks = hooks)
    }

    // --- create and edit ---

    @Test
    fun `a subproject is created below the project it was added to`() = runTest {
        val rig = Rig(listOf(Project(id = 7, title = "Work")))

        val result = rig.actions.create("Reports", "#ff0000", parentProjectId = 7)

        assertEquals(ProjectActionResult.Done("Project created"), result)
        val created = rig.repo.creates.single()
        assertEquals("Reports", created.title)
        assertEquals("#ff0000", created.hexColor)
        assertEquals(7L, created.parentProjectId)
    }

    @Test
    fun `an edit changes the title, colour and parent and nothing else`() = runTest {
        val project = Project(id = 7, title = "Work", description = "notes", position = 300.0)
        val rig = Rig(listOf(project))

        val result = rig.actions.edit(project, "Job", "#00ff00", parentProjectId = 3)

        assertEquals(ProjectActionResult.Done("Project updated"), result)
        assertEquals(project.copy(title = "Job", hexColor = "#00ff00", parentProjectId = 3), rig.repo.updates.single())
    }

    @Test
    fun `a refused change is reported with the server's message`() = runTest {
        val project = Project(id = 7, title = "Work")
        val rig = Rig(listOf(project))
        rig.repo.updateResult = { NetworkResult.Error("Server said no") }

        assertEquals(ProjectActionResult.Failed("Server said no"), rig.actions.edit(project, "Job", "", 0))
    }

    // --- the Inbox ---

    @Test
    fun `set as inbox makes the project the inbox, the same as the inbox picker`() = runTest {
        val rig = Rig(listOf(Project(id = 5, title = "Inbox"), Project(id = 6, title = "Work")))
        rig.auth.onInboxProjectSelected(5)

        val result = rig.actions.setInbox(6)

        assertEquals(ProjectActionResult.Done("Inbox project updated"), result)
        assertEquals(6L, rig.auth.getInboxProjectId())
        assertEquals(6L, rig.actions.inboxProjectId.first())
    }

    // --- archive, restore, delete ---

    @Test
    fun `the inbox cannot be archived`() = runTest {
        val inbox = Project(id = 5, title = "Inbox")
        val rig = Rig(listOf(inbox))
        rig.auth.onInboxProjectSelected(5)

        assertEquals(ProjectActionResult.Failed(ARCHIVE_INBOX_REFUSED), rig.actions.archive(inbox))
        assertTrue(rig.repo.updates.isEmpty())
    }

    @Test
    fun `archiving and restoring flip the flag and refresh the widgets`() = runTest {
        val project = Project(id = 6, title = "Work")
        val rig = Rig(listOf(project))

        assertEquals(ProjectActionResult.Done("Project archived"), rig.actions.archive(project))
        assertEquals(true, rig.repo.updates.last().isArchived)
        assertEquals(ProjectActionResult.Done("Project restored"), rig.actions.restore(project.copy(isArchived = true)))
        assertEquals(false, rig.repo.updates.last().isArchived)
        assertEquals(2, rig.hooks.widgetUpdates)
    }

    @Test
    fun `a refused archive leaves the widgets alone`() = runTest {
        val project = Project(id = 6, title = "Work")
        val rig = Rig(listOf(project))
        rig.repo.updateResult = { NetworkResult.Error("Forbidden") }

        assertEquals(ProjectActionResult.Failed("Forbidden"), rig.actions.archive(project))
        assertEquals(0, rig.hooks.widgetUpdates)
    }

    @Test
    fun `delete removes the project`() = runTest {
        val rig = Rig(listOf(Project(id = 6, title = "Work")))

        assertEquals(ProjectActionResult.Done("Project deleted"), rig.actions.delete(6))
        assertEquals(listOf(6L), rig.repo.deletes)
    }

    // --- review ---

    @Test
    fun `include in review rewrites an excluded footer as never reviewed and keeps the notes`() = runTest {
        val project = Project(id = 6, title = "Work", description = "Notes\n\n---\n**Vicu review**: excluded")
        val rig = Rig(listOf(project))

        val result = rig.actions.includeInReview(project)

        assertEquals(ProjectActionResult.Done("Included \"Work\" in review"), result)
        val written = rig.repo.updates.single().description
        assertEquals("Notes\n\n---\n**Vicu review**: never", written)
        assertEquals(ReviewState.NEVER, ReviewMetadata.parse(written).state)
    }

    @Test
    fun `include in review writes the footer the exclude path reverses`() = runTest {
        // Excluding and including again leaves a project that is tracked and never reviewed.
        val excluded = ReviewMetadata.excludedDescription("Plan", excluded = true)
        val project = Project(id = 6, title = "Work", description = excluded)
        val rig = Rig(listOf(project))

        rig.actions.includeInReview(project)

        assertEquals(
            ReviewMetadata.excludedDescription(excluded, excluded = false),
            rig.repo.updates.single().description,
        )
        assertEquals("Plan\n\n---\n**Vicu review**: never", rig.repo.updates.single().description)
    }

    @Test
    fun `mark reviewed records today and keeps a cadence of its own, and undo puts the project back`() = runTest {
        val project = Project(id = 6, title = "Work", description = "x\n\n---\n**Vicu review**: never · every 30 days")
        val rig = Rig(listOf(project))

        val result = rig.actions.markReviewed(project)

        assertEquals(ProjectActionResult.Done("Marked \"Work\" reviewed"), result)
        assertEquals("x\n\n---\n**Vicu review**: 2026-10-10 · every 30 days", rig.repo.updates.single().description)

        assertEquals(ProjectActionResult.Done(null), rig.actions.undoReview(project))
        assertEquals(project, rig.repo.updates.last())
    }

    // --- moving among siblings ---

    private fun project(id: Long, position: Double) = Project(id = id, title = "P$id", position = position)

    @Test
    fun `a move between two siblings saves one position`() = runTest {
        val rig = Rig()

        val result = rig.actions.moveAmongSiblings(3, listOf(project(1, 100.0), project(3, 300.0), project(2, 200.0)))

        assertEquals(SiblingMoveResult.Saved, result)
        assertEquals(listOf(3L to 150.0), rig.repo.updates.map { it.id to it.position })
    }

    @Test
    fun `siblings that share a position are renumbered, the moved one last`() = runTest {
        val rig = Rig()

        rig.actions.moveAmongSiblings(3, listOf(project(1, 0.0), project(3, 0.0), project(2, 0.0)))

        assertEquals(
            listOf(1L to POSITION_STEP, 2L to 3 * POSITION_STEP, 3L to 2 * POSITION_STEP),
            rig.repo.updates.map { it.id to it.position },
        )
    }

    @Test
    fun `a refusal stops the move and says the order was not saved`() = runTest {
        val rig = Rig()
        rig.repo.updateResult = { NetworkResult.Error("Server said no") }

        val result = rig.actions.moveAmongSiblings(3, listOf(project(1, 0.0), project(3, 0.0), project(2, 0.0)))

        assertEquals(SiblingMoveResult.Failed("Could not save the new order: Server said no"), result)
        assertEquals(1, rig.repo.updates.size, "nothing is sent after a refusal")
    }

    @Test
    fun `a project that is not among the siblings is not moved`() = runTest {
        val rig = Rig()

        assertEquals(SiblingMoveResult.NotMoved, rig.actions.moveAmongSiblings(9, listOf(project(1, 100.0))))
        assertTrue(rig.repo.updates.isEmpty())
    }

    @Test
    fun `the order message names the reason when there is one`() {
        assertEquals("Could not save the new order", orderNotSavedMessage(""))
        assertEquals("Could not save the new order: offline", orderNotSavedMessage("offline"))
    }
}
