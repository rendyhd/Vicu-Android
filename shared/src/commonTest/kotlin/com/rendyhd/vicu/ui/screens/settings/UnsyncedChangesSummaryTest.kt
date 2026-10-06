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
}
