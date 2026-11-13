package com.rendyhd.vicu.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A full reconcile lists the open tasks in pages as large as the server allows (review C-13): with
 * pages of 100, a task completed while the pages downloaded shifted the rest, and a row that fell
 * between two pages was swept as vanished for a cycle.
 */
class TaskRefresherReconcilePagesTest {

    @Test
    fun `the open tasks are listed a thousand at a time`() = runTest {
        val h = RefresherHarness()
        h.server.put(1)

        h.refresher.refresh(forceFull = true)

        val open = h.server.lists().filter { it.filter == "done = false" && it.param("per_page") != "1" }
        assertEquals(listOf("1000"), open.map { it.param("per_page") })
    }
}
