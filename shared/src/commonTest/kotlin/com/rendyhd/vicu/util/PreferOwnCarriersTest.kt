package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** [SharedRoutines.preferOwnCarriers]: the desktop's `preferOwnRoutineCarriers` rule (review S-5). */
class PreferOwnCarriersTest {

    private data class C(val taskId: Long, val createdBy: Long, val routineId: String)

    private fun prefer(carriers: List<C>, me: Long?) = SharedRoutines.preferOwnCarriers(
        carriers,
        me,
        taskId = { it.taskId },
        createdBy = { it.createdBy },
        routineId = { it.routineId },
    ).map { it.taskId }

    @Test
    fun `the account's own carrier wins over another user's`() {
        assertEquals(listOf(10L), prefer(listOf(C(5, 1, "r"), C(10, 2, "r")), me = 2))
    }

    @Test
    fun `of other users' carriers only the oldest stays`() {
        assertEquals(listOf(5L, 20L), prefer(listOf(C(9, 3, "r"), C(5, 1, "r"), C(20, 1, "other")), me = 2))
    }

    @Test
    fun `the account's own duplicates are all kept, in their order`() {
        assertEquals(listOf(10L, 4L), prefer(listOf(C(10, 2, "r"), C(4, 2, "r"), C(1, 1, "r")), me = 2))
    }

    @Test
    fun `a carrier whose creator or account is not known counts as the account's own`() {
        assertEquals(listOf(8L), prefer(listOf(C(3, 1, "r"), C(8, 0, "r")), me = 2))
        assertEquals(listOf(3L, 8L), prefer(listOf(C(3, 1, "r"), C(8, 4, "r")), me = null))
    }
}
