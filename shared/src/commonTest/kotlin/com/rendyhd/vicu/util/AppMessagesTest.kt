package com.rendyhd.vicu.util

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppMessagesTest {
    @Test
    fun `messages posted before anyone listens are delivered in order`() = runTest {
        val messages = AppMessages()

        messages.post("first")
        messages.post("second")

        assertEquals(listOf("first", "second"), messages.messages.take(2).toList().map { it.text })
    }

    @Test
    fun `blank messages are ignored`() = runTest {
        val messages = AppMessages()

        messages.post("")
        messages.post("   ")
        messages.post("real")

        assertEquals("real", messages.messages.first().text)
    }

    @Test
    fun `a full buffer drops the oldest message`() = runTest {
        val messages = AppMessages()
        repeat(20) { messages.post("m$it") }

        val received = messages.messages.take(16).toList().map { it.text }

        assertEquals("m4", received.first())
        assertEquals("m19", received.last())
    }

    @Test
    fun `an action message carries its label and callback`() = runTest {
        val messages = AppMessages()
        var undone = 0

        messages.post("Completed 3 tasks", "Undo") { undone++ }
        val message = messages.messages.first()
        message.onAction?.invoke()

        assertEquals("Completed 3 tasks", message.text)
        assertEquals("Undo", message.actionLabel)
        assertEquals(1, undone)
    }

    @Test
    fun `a plain message has no action`() = runTest {
        val messages = AppMessages()

        messages.post("plain")
        val message = messages.messages.first()

        assertEquals(null, message.actionLabel)
        assertEquals(null, message.onAction)
    }

    @Test
    fun `a refused write is said in the snackbar and a successful one is not`() = runTest {
        val messages = AppMessages()

        assertEquals(false, messages.postIfRefused(NetworkResult.Success(Unit), "schedule the task"))
        assertEquals(true, messages.postIfRefused(NetworkResult.Error("boom"), "schedule the task"))

        assertEquals("Could not schedule the task: boom", messages.messages.first().text)
    }
}
