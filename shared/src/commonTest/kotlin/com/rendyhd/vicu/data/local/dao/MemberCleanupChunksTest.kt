package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.FakeCollaborationDao
import com.rendyhd.vicu.data.local.entity.ProjectMemberEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `deleteMembersExcept` never binds the kept project ids: an account with 1,000 or more projects
 * would pass SQLite's variable limit (review A-DATA-2). The projects to drop go in chunks.
 */
class MemberCleanupChunksTest {

    @Test
    fun `members of projects that are gone are deleted in chunks and the rest stay`() = runTest {
        val dao = FakeCollaborationDao()
        dao.upsertMembers((1L..1_200L).map { ProjectMemberEntity(projectId = it, userId = 9) })

        // 2,000 projects remain on the server; of the cached ones only 1 to 100 are still among them.
        dao.deleteMembersExcept((1L..100L).toList() + (5_001L..6_900L).toList())

        assertEquals((1L..100L).toList(), dao.members.value.map { it.projectId }.sorted())
        assertTrue(dao.memberDeleteChunks.all { it.size <= MAX_SQL_ID_PARAMS })
        assertEquals(1_100, dao.memberDeleteChunks.sumOf { it.size })
    }
}
