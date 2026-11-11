package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `icsImport` vectors of cross-app-semantics-v1.json (contract 20.10) in the contract's zones:
 * calendar files as lines of text, read by the app's own reader (`util/ics`) and mapped by
 * [IcsImport]. The desktop runs the same through ical.js.
 */
class IcsImportFixtureTest {
    private val fixture = Json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
    private val icsImport = fixture.getValue("icsImport").jsonObject

    private val zones = listOf("Pacific/Auckland", "America/New_York", "Europe/Amsterdam")

    private fun fileOf(lines: List<String>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Vicu//Vectors//EN") + lines + "END:VCALENDAR").joinToString("\r\n")

    @Test
    fun theConstantsMatch() {
        assertEquals(icsImport.getValue("windowDays").jsonPrimitive.int, IcsImport.IMPORT_WINDOW_DAYS)
        assertEquals(icsImport.getValue("untitled").jsonPrimitive.content, IcsImport.UNTITLED_EVENT)
    }

    @Test
    fun everyVectorInEveryZone() {
        val cases = icsImport.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        var checked = 0
        for (vector in cases) {
            val name = vector.getValue("name").jsonPrimitive.content
            val text = fileOf(vector.getValue("ics").jsonArray.map { it.jsonPrimitive.content })
            val importDay = LocalDate.parse(vector.getValue("importDay").jsonPrimitive.content)
            val busyOnly = vector["busyOnly"]?.jsonPrimitive?.booleanOrNull ?: false
            val byZone = vector["expectByZone"] as? JsonObject
            for (zoneName in zones) {
                val expect = (if (byZone != null) byZone[zoneName] else vector["expect"]) as? JsonArray ?: continue
                val events = IcsImport.importText(text, importDay, TimeZone.of(zoneName), busyOnly)
                assertEquals(expect, JsonArray(events.map(PlanEvents::encode)), "$name in $zoneName")
                checked++
            }
        }
        assertTrue(checked >= cases.size)
    }
}
