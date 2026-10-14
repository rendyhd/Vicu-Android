package com.rendyhd.vicu.ui.screens.shared

import com.rendyhd.vicu.util.CrossAppFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `completion` block of test-fixtures/cross-app-semantics-v1.json (section 7 of the contract). */
class CompletionHoldMachineTest {

    private val completion: JsonObject = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .jsonObject.getValue("completion").jsonObject

    @Test
    fun `the constants and texts are the contract's`() {
        assertEquals(completion.getValue("holdMs").jsonPrimitive.long, CompletionHold.HOLD_MILLIS)
        assertEquals(completion.getValue("toastMs").jsonPrimitive.long, CompletionHold.TOAST_MILLIS)
        val toast = completion.getValue("toast").jsonObject
        assertEquals(toast.getValue("action").jsonPrimitive.content, CompletionHold.TOAST_ACTION)
        assertEquals(toast.getValue("single").jsonPrimitive.content, CompletionHold.toastText(1))
        for (n in listOf(2, 3, 12)) {
            assertEquals(toast.getValue("many").jsonPrimitive.content.replace("{n}", "$n"), CompletionHold.toastText(n))
        }
    }

    @Test
    fun `the 20 vectors pass`() {
        val vectors = completion.getValue("vectors").jsonArray
        assertEquals(20, vectors.size)
        for (vector in vectors) runVector(vector.jsonObject)
    }

    private fun runVector(vector: JsonObject) {
        val name = vector.getValue("name").jsonPrimitive.content
        val events = vector.getValue("events").jsonArray.map { it.jsonObject }
        val machine = CompletionHoldMachine()
        var next = 0
        for (step in vector.getValue("expect").jsonArray.map { it.jsonObject }) {
            val at = step.getValue("at").jsonPrimitive.long
            // Events up to and including this time, in order; each fires the timers due before it.
            while (next < events.size && events[next].getValue("at").jsonPrimitive.long <= at) {
                apply(machine, events[next++], name)
            }
            val expected = CompletionHoldSnapshot(
                held = step.ids("held"),
                collapsed = step.ids("collapsed"),
                toast = step.getValue("toast").let { if (it is JsonPrimitive) it.contentOrNull else null },
            )
            assertEquals(expected, machine.snapshot(at), "$name at $at")
        }
    }

    private fun JsonObject.ids(key: String): List<Long> = (getValue(key) as JsonArray).map { it.jsonPrimitive.long }

    private fun apply(machine: CompletionHoldMachine, event: JsonObject, vector: String) {
        val at = event.getValue("at").jsonPrimitive.long
        val type = event.getValue("type").jsonPrimitive.content
        val row = event["row"]?.jsonPrimitive?.long
        val onToast = event["target"]?.jsonPrimitive?.content == "toast"
        when (type) {
            "complete" -> machine.complete(row!!, at)
            "hoverStart", "hoverEnd" ->
                if (onToast) machine.hoverToast(type == "hoverStart", at) else machine.hover(row!!, type == "hoverStart", at)
            "focusIn", "focusOut" ->
                if (onToast) machine.focusToast(type == "focusIn", at) else machine.focusRow(row!!, type == "focusIn", at)
            "navigate" -> machine.navigate(at)
            "undo" ->
                if (event.getValue("via").jsonPrimitive.content == "toast") machine.undoToast(at) else machine.undoRow(row!!, at)
            else -> error("unknown event $type in $vector")
        }
    }

    @Test
    fun `a host with its own row timers collapses a row into the toast`() {
        val machine = CompletionHoldMachine()
        machine.collapseNow(1, 5_000)
        assertEquals("Completed", machine.snapshot(5_000).toast)
        machine.collapseNow(2, 8_000)
        assertEquals("2 completed", machine.snapshot(8_000).toast)
        // The 6 s clock restarted at 8 s.
        assertEquals("2 completed", machine.snapshot(13_999).toast)
        assertNull(machine.snapshot(14_000).toast)
        machine.collapseNow(3, 20_000)
        assertEquals("Completed", machine.snapshot(20_000).toast)
        assertEquals(listOf(3L), machine.undoToast(20_001))
    }

    @Test
    fun `the toast reports the ids an undo reopens`() {
        val machine = CompletionHoldMachine()
        machine.collapseNow(7, 0)
        machine.collapseNow(4, 10)
        assertEquals(listOf(7L, 4L), machine.toastIds())
        assertEquals(listOf(7L, 4L), machine.undoToast(20))
        assertEquals(emptyList(), machine.undoToast(30))
    }
}
