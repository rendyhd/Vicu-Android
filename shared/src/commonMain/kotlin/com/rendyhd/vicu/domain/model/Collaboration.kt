package com.rendyhd.vicu.domain.model

import com.rendyhd.vicu.util.CollabUser
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.Permission

/** A person as the collaboration rules see one. */
fun User.toCollabUser(): CollabUser = CollabUser(id, username, name)

/** What collaboration means for one project (docs/cross-app-semantics-v1.md section 9.1). */
data class ProjectAccess(
    /** Someone besides you can see the project: assignees and comments show on its tasks. */
    val shared: Boolean,
    /** Everyone who can see it, you first. Empty while not known. */
    val members: List<User>,
    /** What you may do there: [Permission.READ], [Permission.WRITE] or [Permission.ADMIN]. */
    val permission: Int,
) {
    val canWrite: Boolean get() = permission >= Permission.WRITE
    val isAdmin: Boolean get() = permission >= Permission.ADMIN

    companion object {
        /** A project whose members are not known looks private: nothing new shows until it is known to be shared. */
        val PRIVATE = ProjectAccess(shared = false, members = emptyList(), permission = Permission.WRITE)
    }
}

/** Collaboration across the app: who you are and the access of every project. */
data class CollabState(
    val me: Long? = null,
    val projects: Map<Long, ProjectAccess> = emptyMap(),
) {
    fun access(projectId: Long): ProjectAccess = projects[projectId] ?: ProjectAccess.PRIVATE

    /** You, as the member lists know you (for "You" and your avatar). */
    fun meAsUser(): User? = me?.let { id -> projects.values.asSequence().flatMap { it.members }.firstOrNull { it.id == id } }

    val anyShared: Boolean get() = projects.values.any { it.shared }

    companion object {
        /** The access of [project] from its members and permission (exported for tests). */
        fun accessFor(project: Project, members: List<User>?, me: Long?): ProjectAccess {
            val permission = Collaboration.effectivePermission(project.maxPermission.takeIf { it >= 0 }, project.isArchived)
            if (members.isNullOrEmpty()) {
                // Not known yet: a project someone else owns is shared with you all the same.
                val shared = me != null && project.ownerId != 0L && project.ownerId != me
                return ProjectAccess(shared = shared, members = emptyList(), permission = permission)
            }
            return ProjectAccess(
                shared = Collaboration.isSharedProject(members.map { it.id }, me),
                members = Collaboration.orderPeople(members, me) { it.id },
                permission = permission,
            )
        }
    }
}

/** The comment signals of a task in a shared project (cross-app semantics 9.4). */
data class TaskSignal(val commentCount: Int = 0, val isUnread: Boolean = false)

/** A comment of a task thread. [comment] is HTML; [pending] while it waits in the offline queue. */
data class TaskComment(
    val id: Long,
    val taskId: Long,
    val author: User?,
    val comment: String,
    val created: String,
    val updated: String,
    val pending: Boolean = false,
)

/** A direct share of a project. */
data class ShareUser(val user: User, val permission: Int)

/** A team share of a project (listed only; teams are managed in Vikunja). */
data class ShareTeam(val id: Long, val name: String, val permission: Int, val members: List<User>)

data class ProjectShares(val owner: User?, val users: List<ShareUser>, val teams: List<ShareTeam>)
