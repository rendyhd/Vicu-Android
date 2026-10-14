package com.rendyhd.vicu.ui.components.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppFabTest {

    private val onClick: () -> Unit = {}
    private val newTask = FabSpec("New task", expanded = true, onClick = onClick)
    private val newRoutine = FabSpec("New routine", expanded = true, onClick = onClick)

    @Test
    fun `the host hands back each screen's button until the screen takes it out`() {
        val host = AppFabHost()
        host.put("today", newTask)
        host.put("routines", newRoutine)

        assertEquals(newTask, host.specOf("today"))
        assertEquals(newRoutine, host.specOf("routines"))

        host.remove("today")
        assertNull(host.specOf("today"))
        assertNull(host.specOf(null))
    }

    @Test
    fun `an equal button is not written again`() {
        val host = AppFabHost()
        host.put("today", newTask)
        host.put("today", newTask.copy())

        assertSame(newTask, host.specOf("today"))
    }

    @Test
    fun `the current screen's button wins`() {
        assertEquals(newRoutine, fabToShow(current = newRoutine, last = newTask, settling = true))
        assertEquals(newRoutine, fabToShow(current = newRoutine, last = newTask, settling = false))
    }

    @Test
    fun `a new screen keeps the previous button for its first frames, so the button does not drop out`() {
        assertEquals(newTask, fabToShow(current = null, last = newTask, settling = true))
    }

    @Test
    fun `a screen without a button hides it once it has settled`() {
        assertNull(fabToShow(current = null, last = newTask, settling = false))
        assertNull(fabToShow(current = null, last = null, settling = true))
    }
}
