package com.rendyhd.vicu.domain.model

/**
 * What the phone holds of one project's tasks: the open ones (the open side of its progress ring)
 * and the done ones it happens to have. The done number is not a count of the project's history
 * (the phone only keeps some of it); it only changes when a task of the project is completed,
 * reopened or removed, which is what tells the progress ring to ask the server again.
 */
data class ProjectTally(val open: Int, val doneOnPhone: Int) {
    companion object {
        /** A project the phone holds no task of. */
        val EMPTY = ProjectTally(open = 0, doneOnPhone = 0)
    }
}
