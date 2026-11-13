package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.RoutineRig.Companion.definition
import com.rendyhd.vicu.data.repository.RoutineRig.Companion.payload
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several carriers with the same routine id (review S-5): a member of a shared project can make a
 * carrier with someone else's routine id and `shared: true`. The account's own carrier wins, so a
 * tick never goes into the other person's task.
 */
class DuplicateRoutineCarrierTest {

    private val me = 2L
    private val maya = 1L
    private val today = "2026-10-06"
    private val shared = 36L

    @Test
    fun `a tick goes into the account's own carrier not a copy another member made`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn(me) }
        // Maya's copy has a lower task id, so it would come first.
        rig.seedCarrier(90, payload(definition = definition().copy(shared = true, name = "Copy")), projectId = shared, createdBy = maya)
        rig.seedCarrier(100, payload(definition = definition()), createdBy = me)

        rig.repository.setOccurrenceStatus(RoutineRig.ROUTINE_ID, today, RoutineRig.SLOT_ID, OccurrenceStatus.COMPLETED)

        assertEquals(1, rig.serverPayload(100).occurrences.size, "the tick is in the account's own carrier")
        assertTrue(rig.serverPayload(90).occurrences.isEmpty(), "nothing went into Maya's task")
        val shown = rig.repository.observeActive().first()
        assertEquals(listOf(100L), shown.map { it.taskId })
    }

    @Test
    fun `when only other people have the routine the oldest carrier stays`() = runTest {
        val rig = RoutineRig(backgroundScope).also { it.signIn(me) }
        rig.seedCarrier(120, payload(definition = definition().copy(shared = true, name = "Later copy")), projectId = shared, createdBy = 7)
        rig.seedCarrier(110, payload(definition = definition().copy(shared = true)), projectId = shared, createdBy = maya)

        val shown = rig.repository.observeActive().first()

        assertEquals(listOf(110L), shown.map { it.taskId })
    }
}
