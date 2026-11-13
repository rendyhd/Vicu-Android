package com.rendyhd.vicu.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A refresh that finishes after a sign-out or an account switch writes nothing (review finding
 * C-10): the old account's rows do not come back and its reminders are not armed again.
 */
class AccountEpochTest {

    @Test
    fun `a write of the current epoch runs - one of an earlier epoch is refused`() = runTest {
        val epoch = AccountEpoch()
        val started = epoch.current()
        assertEquals("written", epoch.write(started) { "written" })

        epoch.advance { }

        var ran = false
        assertFailsWith<AccountChangedException> { epoch.write(started) { ran = true } }
        assertTrue(!ran)
        assertEquals("written", epoch.write(epoch.current()) { "written" })
    }

    @Test
    fun `a refresh whose answer arrives after the wipe stores no rows and arms no reminders`() = runTest {
        val epoch = AccountEpoch()
        val server = TaskListServer().apply {
            put(1, "Call the bank", dueDate = "2026-10-07T09:00:00Z", remindersJson = """[{"reminder":"2026-10-07T08:00:00Z"}]""")
            put(2, "B")
        }
        var wiped = false
        server.override = { req ->
            // The user signs out while the list of open tasks is on its way.
            if (!wiped && req.path == "/tasks" && req.filter == "done = false") {
                wiped = true
                epoch.advance { }
            }
            null
        }
        val h = RefresherHarness(server, accountEpoch = epoch)

        assertFailsWith<AccountChangedException> { h.refresher.refresh() }

        assertTrue(h.taskDao.snapshot().isEmpty(), "no rows of the old account")
        assertTrue(h.hooks.scheduled.isEmpty(), "no reminders armed")
        assertEquals(0, h.hooks.rescheduleAllCalls)
    }

    @Test
    fun `a refresh that starts after the wipe writes as usual`() = runTest {
        val epoch = AccountEpoch()
        epoch.advance { }
        val server = TaskListServer().apply { put(1, "A") }
        val h = RefresherHarness(server, accountEpoch = epoch)

        h.refresher.refresh()

        assertEquals(listOf(1L), h.taskDao.snapshot().map { it.id })
    }
}
