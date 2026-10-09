package com.rendyhd.vicu.ui.navigation

import com.rendyhd.vicu.domain.model.ProjectTally
import com.rendyhd.vicu.domain.repository.ProjectProgressSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [ProjectProgressSource] a test steers: [tallies] is what the phone holds, [doneByProject] what
 * the server would say. Every question is recorded in [asked].
 */
class FakeProjectProgressSource(
    initial: Map<Long, ProjectTally> = emptyMap(),
    val doneByProject: MutableMap<Long, Long?> = mutableMapOf(),
) : ProjectProgressSource {
    val tallies = MutableStateFlow(initial)
    val asked = mutableListOf<Pair<Long, ProjectTally>>()

    /** Bumped by a test to say a sync made every earlier answer stale. */
    val invalidated = MutableStateFlow(0)

    override fun observeTallies(): Flow<Map<Long, ProjectTally>> = tallies

    override fun invalidations(): Flow<Int> = invalidated

    override suspend fun doneCount(projectId: Long, tally: ProjectTally): Long? {
        asked += projectId to tally
        return doneByProject[projectId]
    }
}
