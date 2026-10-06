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

        assertEquals(listOf("first", "second"), messages.messages.take(2).toList())
    }

    @Test
    fun `blank messages are ignored`() = runTest {
        val messages = AppMessages()

        messages.post("")
        messages.post("   ")
        messages.post("real")

        assertEquals("real", messages.messages.first())
    }

    @Test
    fun `a full buffer drops the oldest message`() = runTest {
        val messages = AppMessages()
        repeat(20) { messages.post("m$it") }

        val received = messages.messages.take(16).toList()

        assertEquals("m4", received.first())
        assertEquals("m19", received.last())
    }
}
