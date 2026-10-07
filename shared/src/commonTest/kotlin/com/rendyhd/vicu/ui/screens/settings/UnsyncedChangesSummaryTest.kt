package com.rendyhd.vicu.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class UnsyncedChangesSummaryTest {

    @Test
    fun `names pending and failed counts separately`() {
        assertEquals("5 unsynced changes (3 waiting, 2 failed)", unsyncedChangesSummary(pending = 3, failed = 2))
    }

    @Test
    fun `omits the part that is zero and uses the singular`() {
        assertEquals("1 unsynced change (1 waiting)", unsyncedChangesSummary(pending = 1, failed = 0))
        assertEquals("2 unsynced changes (2 failed)", unsyncedChangesSummary(pending = 0, failed = 2))
    }

    @Test
    fun `the sign-out warning agrees with the number`() {
        assertEquals(
            "1 unsynced change (1 waiting) has not reached the server and will be lost.",
            unsyncedChangesLostWarning(pending = 1, failed = 0),
        )
        assertEquals(
            "3 unsynced changes (1 waiting, 2 failed) have not reached the server and will be lost.",
            unsyncedChangesLostWarning(pending = 1, failed = 2),
        )
    }

    @Test
    fun `the clear-cache note agrees with the number`() {
        assertEquals(
            "1 unsynced change (1 failed). It is kept and sent on the next sync unless you discard it.",
            unsyncedChangesKeptNote(pending = 0, failed = 1),
        )
        assertEquals(
            "2 unsynced changes (2 waiting). They are kept and sent on the next sync unless you discard them.",
            unsyncedChangesKeptNote(pending = 2, failed = 0),
        )
    }
}
