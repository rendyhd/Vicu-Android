package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.ProjectShares
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PositionedId
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.planDropAmong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a project action came to: a confirmation to show (none for some), or the error. */
sealed interface ProjectActionResult {
    data class Done(val message: String?) : ProjectActionResult
    data class Failed(val error: String) : ProjectActionResult
}

/** What saving a new order of a project's siblings came to. */
sealed interface SiblingMoveResult {
    /** Nothing to save: the project is not among the siblings it was given. */
    data object NotMoved : SiblingMoveResult

    /** Every position was saved, or queued to be sent when the server can be reached. */
    data object Saved : SiblingMoveResult

    /** The server refused a position; [message] is what to tell the user. */
    data class Failed(val message: String) : SiblingMoveResult
}

/** The message for a new order that could not be saved, with the server's reason when there is one. */
fun orderNotSavedMessage(detail: String): String =
    if (detail.isBlank()) "Could not save the new order" else "Could not save the new order: $detail"

/** "Archive" and "Delete" are not offered on the Inbox, and this is said when one gets through anyway. */
const val ARCHIVE_INBOX_REFUSED = "Select another Inbox project before archiving this project"

/**
 * The things a user can do to a project, in one place for every screen that offers them (Settings,
 * the drawer, the project screen): create (a subproject is a create with a parent), edit, make it the
 * Inbox, archive, restore, delete, mark it reviewed, bring it back into review, and move it among its
 * siblings. Each answers with the message to show, the same wherever the action was started.
 *
 * Writes go through [ProjectRepository], so edits, archiving and review changes are applied locally
 * at once and queued when the server cannot be reached; creating and deleting need the server.
 */
class ProjectActions(
    private val projectRepository: ProjectRepository,
    private val authManager: AuthManager,
    private val repositoryHooks: PlatformRepositoryHooks,
    private val dayClock: DayClock,
    /** Sharing (cross-app semantics 9.7); null where the app is built without it (tests). */
    private val collaboration: CollaborationRepository? = null,
) {
    /** The Inbox project's id, null until one is chosen. */
    val inboxProjectId: Flow<Long?> get() = authManager.inboxProjectId

    /** Sibling moves are saved one at a time, so a quick second drop cannot overtake the first. */
    private val moveMutex = Mutex()

    /** Creates a project; with a [parentProjectId] other than 0 it is a subproject of that one. */
    suspend fun create(title: String, hexColor: String, parentProjectId: Long): ProjectActionResult {
        val project = Project(id = 0, title = title, hexColor = hexColor, parentProjectId = parentProjectId)
        return projectRepository.create(project).toResult("Project created")
    }

    suspend fun edit(project: Project, title: String, hexColor: String, parentProjectId: Long): ProjectActionResult {
        val updated = project.copy(title = title, hexColor = hexColor, parentProjectId = parentProjectId)
        return projectRepository.update(updated).toResult("Project updated")
    }

    /** Makes [projectId] the Inbox: the same as choosing it in the Inbox project picker. */
    suspend fun setInbox(projectId: Long): ProjectActionResult {
        authManager.onInboxProjectSelected(projectId)
        return ProjectActionResult.Done("Inbox project updated")
    }

    /** Archives [project]. The Inbox cannot be archived until another project is the Inbox. */
    suspend fun archive(project: Project): ProjectActionResult {
        if (project.id == authManager.getInboxProjectId()) return ProjectActionResult.Failed(ARCHIVE_INBOX_REFUSED)
        return setArchived(project, archived = true)
    }

    suspend fun restore(project: Project): ProjectActionResult = setArchived(project, archived = false)

    private suspend fun setArchived(project: Project, archived: Boolean): ProjectActionResult {
        val result = projectRepository.update(project.copy(isArchived = archived))
        // A widget set to this project shows it gone, or back.
        if (result !is NetworkResult.Error) repositoryHooks.updateWidgets()
        return result.toResult(if (archived) "Project archived" else "Project restored")
    }

    /** Deletes the project and its tasks. */
    suspend fun delete(projectId: Long): ProjectActionResult =
        projectRepository.delete(projectId).toResult("Project deleted")

    /**
     * Records a review of [project] today, keeping a cadence of its own. The confirmation is the
     * text of the snackbar that offers to undo it ([undoReview]).
     */
    suspend fun markReviewed(project: Project): ProjectActionResult {
        val today = dayClock.day.value.date
        val updated = project.copy(description = ReviewMetadata.reviewedDescription(project.description, today))
        return projectRepository.update(updated).toResult(markedReviewedMessage(project))
    }

    /** Puts [previous] (the project as it was before [markReviewed]) back. Nothing to say when it worked. */
    suspend fun undoReview(previous: Project): ProjectActionResult =
        projectRepository.update(previous).toResult(null)

    /**
     * Brings an excluded project back into review: its footer is rewritten as never reviewed, its own
     * cadence kept, the same rewrite the review screen's exclude path makes in reverse (and the
     * desktop's `excludeFromReviewRequest(project, false)`).
     */
    suspend fun includeInReview(project: Project): ProjectActionResult {
        val description = ReviewMetadata.excludedDescription(project.description, excluded = false)
        return projectRepository.update(project.copy(description = description))
            .toResult("Included \"${project.title}\" in review")
    }

    /**
     * Saves the order of a project's siblings after [movedId] was dragged (or moved a place by a
     * screen reader): [siblingsInNewOrder] is every project of that level, in the order now shown.
     * The positions are planned like the desktop app's: one position between the new neighbours, or
     * a renumbering of the level when they leave no room. The first refusal stops the rest.
     */
    suspend fun moveAmongSiblings(movedId: Long, siblingsInNewOrder: List<Project>): SiblingMoveResult {
        val plan = planDropAmong(siblingsInNewOrder.map { PositionedId(it.id, it.position) }, movedId)
            ?: return SiblingMoveResult.NotMoved
        val byId = siblingsInNewOrder.associateBy { it.id }
        return moveMutex.withLock {
            for (update in plan.updates) {
                val project = byId[update.id] ?: continue
                val result = projectRepository.update(project.copy(position = update.position))
                if (result is NetworkResult.Error) return@withLock SiblingMoveResult.Failed(orderNotSavedMessage(result.message))
            }
            SiblingMoveResult.Saved
        }
    }

    // --- Sharing (admins only, online only) ------------------------------------------------------

    /** Who [projectId] is shared with: its owner, the people and the teams. */
    suspend fun shares(projectId: Long): NetworkResult<ProjectShares> =
        collaboration?.shares(projectId) ?: NetworkResult.Error(SHARING_UNAVAILABLE)

    /** The person with exactly this username, or null when there is none. */
    suspend fun findUser(username: String): NetworkResult<User?> =
        collaboration?.findUser(username) ?: NetworkResult.Error(SHARING_UNAVAILABLE)

    /** Shares [project] with [person] at [permission] (0 can view, 1 can edit, 2 admin). */
    suspend fun share(project: Project, person: User, permission: Int): ProjectActionResult =
        (collaboration?.share(project.id, person.username, permission) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult("Shared \"${project.title}\" with ${displayName(person)}")

    suspend fun updateShare(project: Project, person: User, permission: Int): ProjectActionResult =
        (collaboration?.updateShare(project.id, person.username, permission) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult(null)

    /** Takes [person]'s access away; the message is the one the Undo sits beside. */
    suspend fun unshare(project: Project, person: User): ProjectActionResult =
        (collaboration?.removeShare(project.id, person.username) ?: NetworkResult.Error(SHARING_UNAVAILABLE))
            .toResult(noLongerHasAccessMessage(person))

    companion object {
        fun markedReviewedMessage(project: Project): String = "Marked \"${project.title}\" reviewed"

        fun noLongerHasAccessMessage(person: User): String = "${displayName(person)} no longer has access."

        private fun displayName(person: User): String = person.name.trim().ifEmpty { person.username }

        private const val SHARING_UNAVAILABLE = "Sharing is not available"
    }
}

private fun NetworkResult<*>.toResult(success: String?): ProjectActionResult = when (this) {
    is NetworkResult.Error -> ProjectActionResult.Failed(message)
    else -> ProjectActionResult.Done(success)
}
