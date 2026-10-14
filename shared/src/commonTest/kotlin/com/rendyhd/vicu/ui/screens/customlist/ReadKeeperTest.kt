package com.rendyhd.vicu.ui.screens.customlist

import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.util.CustomListContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReadKeeperTest {

    private fun context(vararg signals: Pair<Long, TaskSignal>) = CustomListContext(me = 1, signals = signals.toMap())

    @Test
    fun `a task read while the unread list is open still counts as unread there`() {
        val keeper = ReadKeeper()
        keeper.contextFor(context(5L to TaskSignal(2, isUnread = true)), "unread")

        val afterReading = keeper.contextFor(context(5L to TaskSignal(2, isUnread = false)), "unread")

        assertEquals(TaskSignal(2, isUnread = true), afterReading.signals[5L])
    }

    @Test
    fun `a task that was never unread here is left alone`() {
        val keeper = ReadKeeper()
        val read = context(6L to TaskSignal(1, isUnread = false))
        assertSame(read, keeper.contextFor(read, "unread"))
    }

    @Test
    fun `other lists see the signals as they are`() {
        val keeper = ReadKeeper()
        keeper.contextFor(context(5L to TaskSignal(2, isUnread = true)), "unread")
        val afterReading = context(5L to TaskSignal(2, isUnread = false))
        assertSame(afterReading, keeper.contextFor(afterReading, "any"))
        assertSame(afterReading, keeper.contextFor(afterReading, null))
    }
}
