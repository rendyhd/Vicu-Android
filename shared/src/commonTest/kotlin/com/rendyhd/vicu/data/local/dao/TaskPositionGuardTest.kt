package com.rendyhd.vicu.data.local.dao

import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeTaskDao
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A position is only sent by the server when a task is fetched through a list view, so every
 * other answer says 0. Such an answer must not erase the order that was stored.
 */
class TaskPositionGuardTest {

    private fun entity(id: Long, position: Double, title: String = "T$id") =
        TaskEntity(id = id, title = title, position = position)

    @Test
    fun `a task that arrives without a position keeps the stored one`() {
        val kept = keepStoredPositions(listOf(entity(1, 0.0), entity(2, 0.0)), mapOf(1L to 500.0))

        assertEquals(listOf(500.0, 0.0), kept.map { it.position })
    }

    @Test
    fun `a task that arrives with a position wins`() {
        val kept = keepStoredPositions(listOf(entity(1, 700.0)), mapOf(1L to 500.0))

        assertEquals(700.0, kept.single().position)
    }

    @Test
    fun `nothing stored means nothing changes`() {
        val tasks = listOf(entity(1, 0.0))
        assertEquals(tasks, keepStoredPositions(tasks, emptyMap()))
    }

    @Test
    fun `upserting a refreshed copy keeps the order the inbox was given`() = runTest {
        val dao = FakeTaskDao(listOf(entity(1, 500.0, title = "Old")))

        dao.upsert(entity(1, 0.0, title = "Edited elsewhere"))

        val row = dao.entity(1)!!
        assertEquals("Edited elsewhere", row.title, "the rest of the row is replaced")
        assertEquals(500.0, row.position)
    }

    @Test
    fun `upserting many keeps positions per task`() = runTest {
        val dao = FakeTaskDao(listOf(entity(1, 100.0), entity(2, 200.0), entity(3, 0.0)))

        dao.upsertAll(listOf(entity(1, 0.0), entity(2, 250.0), entity(3, 0.0), entity(4, 0.0)))

        assertEquals(
            listOf(100.0, 250.0, 0.0, 0.0),
            listOf(1L, 2L, 3L, 4L).map { dao.entity(it)!!.position },
        )
    }

    @Test
    fun `a position set on purpose replaces the stored one, even with zero`() = runTest {
        val dao = FakeTaskDao(listOf(entity(1, 500.0), entity(2, 600.0)))

        dao.updatePositions(mapOf(1L to 0.0, 2L to 650.0))

        assertEquals(listOf(0.0, 650.0), listOf(1L, 2L).map { dao.entity(it)!!.position })
    }

    @Test
    fun `a long list of unplaced tasks is looked up in chunks`() = runTest {
        val rows = (1L..(MAX_SQL_ID_PARAMS * 2L + 5)).map { entity(it, it.toDouble()) }
        val dao = FakeTaskDao(rows)

        dao.upsertAll(rows.map { it.copy(position = 0.0, title = "again") })

        assertEquals(rows.map { it.position }, rows.map { dao.entity(it.id)!!.position })
        assertEquals(true, dao.boundIdListSizes.all { it <= MAX_SQL_ID_PARAMS })
    }
}
