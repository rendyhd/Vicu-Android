package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.ProjectTally
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The numbers behind the drawer's project progress rings (decision 11). */
interface ProjectProgressSource {
    /** The open tasks and the cached done tasks of every project that has a task here; follows the local database. */
    fun observeTallies(): Flow<Map<Long, ProjectTally>>

    /**
     * The done tasks of [projectId] on the server, without the hidden carrier tasks this app knows
     * about (routines, synced custom lists). One cheap request, cached; [tally] is what the phone
     * holds now, and a change in it makes the cached number stale. Null when it cannot be read
     * (offline, a server without a usable total): the ring is then left out.
     */
    suspend fun doneCount(projectId: Long, tally: ProjectTally): Long?

    /**
     * Changes each time everything [doneCount] answered earlier went stale at once (a sync that
     * sent changes to the server). A caller that remembers what it asked asks again when it moves.
     */
    fun invalidations(): Flow<Int> = flowOf(0)
}
