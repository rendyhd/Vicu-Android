package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlChunksTest {

    @Test
    fun `2500 ids split into five chunks of 500 in order`() {
        val ids = (1L..2_500L).toList()

        val chunks = ids.sqlIdChunks()

        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.size == 500 })
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun `chunk boundaries and empty input`() {
        assertEquals(emptyList(), emptyList<Long>().sqlIdChunks())
        assertEquals(listOf(1), listOf(1L).sqlIdChunks().map { it.size })
        assertEquals(listOf(500), (1L..500L).toList().sqlIdChunks().map { it.size })
        assertEquals(listOf(500, 1), (1L..501L).toList().sqlIdChunks().map { it.size })
    }

    @Test
    fun `the chunk size stays below the old SQLite variable limit`() {
        assertTrue(MAX_SQL_ID_PARAMS < 999, "SQLite before 3.32 allows 999 bound variables per statement")
    }

    @Test
    fun `task getByIds and deleteByIds never bind more than one chunk`() = runTest {
        val dao = FakeTaskDao((1L..2_500L).map { TaskEntity(id = it, title = "Task $it") })
        val ids = (1L..2_500L).toList()

        assertEquals(2_500, dao.getByIds(ids).size)
        dao.deleteByIds(ids.take(2_100))

        assertEquals(400, dao.snapshot().size)
        assertTrue(dao.boundIdListSizes.all { it <= MAX_SQL_ID_PARAMS }, "bound ${dao.boundIdListSizes}")
        // 5 read chunks + 5 delete chunks (2,100 ids = 4 x 500 + 100).
        assertEquals(10, dao.boundIdListSizes.size)
    }

    @Test
    fun `replacing the project list deletes the missing ones in chunks`() = runTest {
        val dao = FakeProjectDao((1L..1_500L).map { ProjectEntity(id = it, title = "P $it") })
        val kept = (1L..10L).map { ProjectEntity(id = it, title = "Kept $it") }

        dao.replaceAll(kept)

        assertEquals((1L..10L).toList(), dao.snapshot().map { it.id })
        assertEquals(listOf(500, 500, 490), dao.boundIdListSizes)
    }
}
