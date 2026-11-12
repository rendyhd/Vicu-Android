package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.CustomListWire
import com.rendyhd.vicu.domain.model.CustomListWireFilter
import com.rendyhd.vicu.domain.model.toDomain
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.pro.ProFixture.day
import com.rendyhd.vicu.util.pro.ProFixture.flag
import com.rendyhd.vicu.util.pro.ProFixture.forEachZone
import com.rendyhd.vicu.util.pro.ProFixture.ledgerOf
import com.rendyhd.vicu.util.pro.ProFixture.local
import com.rendyhd.vicu.util.pro.ProFixture.longsOf
import com.rendyhd.vicu.util.pro.ProFixture.objects
import com.rendyhd.vicu.util.pro.ProFixture.pro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `pro` vectors for import, export and the Copilot prompt, the weekly review, and the
 * `customListPro` vectors (contract sections 21.12, 21.11 and 3.8), in the contract's zones. The
 * desktop runs the same (`pro-suite.ts`).
 */
class ProImportWeeklyFixtureTest {

    private fun nullable(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    // --- Import --------------------------------------------------------------------------------

    @Test
    fun importDates() {
        for (case in objects(pro["importDates"])) {
            val read = ProImport.readDate(case.text("text")!!, day(case.text("ref")!!))
            assertEquals(case.text("expect"), read?.toString(), "\"${case.text("text")}\" from ${case.text("ref")}")
        }
    }

    @Test
    fun readingPastedText() {
        for (case in objects(pro["importRead"])) {
            val name = case.text("name")
            val read = ProImport.readImport(case.texts("input").joinToString("\n"), day(case.text("today")!!))
            assertEquals(case.flag("ok"), read.ok, "$name: ok")
            val source = JsonObject(
                mapOf(
                    "kind" to JsonPrimitive(read.source.kind),
                    "title" to JsonPrimitive(read.source.title),
                    "date" to nullable(read.source.date?.toString()),
                ),
            )
            assertEquals(case.getValue("source"), source, "$name: source")
            assertEquals(case.getValue("items"), JsonArray(read.items.map { it.toJson() }), "$name: items")
            assertEquals(case.texts("problems"), read.problems, "$name: problems")
        }
    }

    private fun rowJson(row: ImportRow): JsonObject = JsonObject(
        mapOf(
            "index" to JsonPrimitive(row.index),
            "action" to JsonPrimitive(row.action.key),
            "name" to nullable(row.name),
            "person" to JsonObject(
                mapOf(
                    "id" to (row.person.id?.let { JsonPrimitive(it) } ?: JsonNull),
                    "match" to JsonPrimitive(row.person.match.key),
                ),
            ),
            "initiative" to (row.initiative?.let { JsonPrimitive(it) } ?: JsonNull),
            "initiativeName" to nullable(row.initiativeName),
            "target" to (row.target?.let { JsonPrimitive(it) } ?: JsonNull),
            "check" to JsonPrimitive(row.check),
            "title" to nullable(row.title),
            "due" to nullable(row.due?.toString()),
            "ready" to JsonPrimitive(row.ready),
        ),
    )

    @Test
    fun matchingAnImport() {
        val m = pro.getValue("importMatch").jsonObject
        val world = m.getValue("world").jsonObject
        val people = world.getValue("people").jsonArray.map {
            val person = it.jsonObject
            Person(person.getValue("id").jsonPrimitive.long, person.text("title")!!, person.texts("aliases"))
        }
        val initiatives = world.objects("initiatives").map { ImportInitiative(it.getValue("id").jsonPrimitive.long, it.text("title")!!) }
        val openPromises = world.objects("openPromises").map {
            ImportOpenPromise(
                it.getValue("taskId").jsonPrimitive.long,
                it.getValue("person").jsonPrimitive.long,
                it.text("title")!!,
                it.text("dir")!!,
            )
        }
        val items = m.objects("items").map(ImportItem::fromJson)
        val read = ReadImport(true, ImportSource("meeting", "", day("2026-11-12")), items, emptyList())
        val match = ProImport.matchImport(read, ImportWorld(people, initiatives, openPromises))
        assertEquals(m.getValue("rows"), JsonArray(match.rows.map(::rowJson)))
        assertEquals(longsOf(m["notMentioned"]), match.notMentioned)
    }

    private fun exportPromise(raw: JsonObject) = ExportPromise(
        taskId = raw.getValue("taskId").jsonPrimitive.long,
        owner = raw.text("owner")!!,
        what = raw.text("what")!!,
        due = raw.text("due")?.let(::day),
        initiative = raw.text("initiative"),
    )

    @Test
    fun exportingOpenPromises() {
        val x = pro.getValue("exportOpen").jsonObject
        val text = ProImport.exportOpen(x.text("title")!!, day(x.text("date")!!), x.objects("promises").map(::exportPromise))
        assertEquals(x.getValue("expect"), Json.parseToJsonElement(text))
    }

    @Test
    fun theCopilotPrompt() {
        val template = CopilotPromptTemplate.fromJson(ProFixture.copilotTemplate)
        for (case in objects(pro["copilotPrompt"])) {
            val input = case.getValue("input").jsonObject
            val prompt = ProImport.copilotPrompt(
                template,
                CopilotPromptInput(
                    kind = input.text("kind")!!,
                    date = day(input.text("date")!!),
                    me = input.text("me")!!,
                    people = input.texts("people"),
                    initiatives = input.texts("initiatives"),
                    open = input.objects("open").map(::exportPromise),
                ),
            )
            assertEquals(case.texts("text"), prompt.split('\n'), case.text("name"))
        }
    }

    // --- The weekly review ---------------------------------------------------------------------

    @Test
    fun weeklyStepsAndTheMirror() = forEachZone { zone ->
        val w = pro.getValue("weeklyPro").jsonObject
        val ledger = ledgerOf(w["ledger"] as? JsonObject)
        val tasks = w.objects("tasks").map { ProFixture.task(it, zone) }
        val today = day(w.text("today")!!)
        val stalled = w.getValue("stalledInitiatives").jsonPrimitive.content.toInt()

        fun steps(layers: ProLayers): JsonElement = JsonArray(
            Weekly.proWeeklySteps(tasks, ledger, layers, today, stalled, zone).map {
                JsonObject(mapOf("id" to JsonPrimitive(it.id.key), "count" to JsonPrimitive(it.count)))
            },
        )
        assertEquals(w.getValue("steps"), steps(ProLayers.DEFAULT))
        assertEquals(w.getValue("stepsWithoutPromises"), steps(ProLayers.DEFAULT.copy(promises = false)))

        val mirror = Weekly.weeklyMirror(tasks, ledger, today, zone)
        val expected = w.getValue("mirror").jsonObject
        fun field(key: String) = expected.getValue(key).jsonPrimitive.content.toInt()
        assertEquals(
            WeeklyMirror(field("rescues"), field("focusDays"), field("workdays"), field("youHold"), field("othersHold"), field("unconfirmed")),
            mirror,
        )
        assertEquals(w.text("mirrorText"), Weekly.mirrorText(mirror))
    }

    // --- Custom lists with Pro conditions ------------------------------------------------------

    @Test
    fun customListsWithProConditions() = forEachZone { zone ->
        val c = ProFixture.customListPro
        val now = local(c.text("now")!!, zone)
        val today = ProFixture.today(now, zone)
        val tasks = c.objects("tasks").map { ProFixture.task(it, zone) }
        val ledger = ledgerOf(c["ledger"] as? JsonObject)
        val prefs = ProFixture.prefs(c["prefs"])
        val availability = Availability.context(now, zone, tasks, ProFixture.projects(c["projects"]), prefs, ledger)
        for (entry in c.objects("cases")) {
            val filterJson = entry.getValue("filter").jsonObject
            val filter = CustomListFilter(
                dueDateFilter = "all",
                includeDone = filterJson["include_done"]?.jsonPrimitive?.content == "true",
                pro = ProListFilter.read(filterJson),
            )
            val context = if (entry.flag("noContext")) {
                CustomListContext.NONE
            } else {
                CustomListContext(pro = ProListContext(availability, ledger) { id -> ProPrefs.projectOnHold(prefs, id) })
            }
            val matched = CustomListFilterBuilder.applyClientSideFilters(tasks, filter, today, zone, context, now).map { it.id }
            assertEquals(longsOf(entry["expect"]), matched, filterJson.toString() + if (entry.flag("noContext")) " without the data" else "")
        }
    }

    @Test
    fun readingTheListKeys() {
        val c = ProFixture.customListPro
        for (entry in c.objects("hasCondition")) {
            assertEquals(entry.flag("expect"), ProListFilter.read(entry.getValue("filter").jsonObject).hasCondition, entry["filter"].toString())
        }
        for (entry in c.objects("rowFields")) {
            val read = ListConditions.readRowFields(entry["value"])
            val expected = entry["expect"]
            assertEquals(if (expected is JsonArray) expected.map { it.jsonPrimitive.content } else null, read?.map { it.key })
        }
        for (entry in c.objects("groupBy")) {
            assertEquals(entry.text("expect"), ListConditions.readGroupBy(entry["value"]).key)
        }
    }

    @Test
    fun proKeysSurviveTheWireFormAndAreEvaluatedFromIt() {
        val wireJson = """
            {"id":"l1","name":"Mine","icon":"","filter":{"due_date_filter":"all","availability":"available","flagged":true,"estimate_max":30,
              "project_status":"active","group_by":"person","row_fields":["due","estimate"],"promise":"theirs","person":[40],"future_key":{"a":1}}}
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val wire = json.decodeFromString(com.rendyhd.vicu.domain.model.CustomListWireSerializer, wireJson)
        val list = wire.toDomain()
        assertEquals("available", list.filter.pro.availability)
        assertEquals(true, list.filter.pro.flagged)
        assertEquals(30, list.filter.pro.estimateLimit)
        assertEquals(listOf(40L), list.filter.pro.person)
        assertEquals(true, list.filter.pro.hasCondition)
        // Written back unchanged, with the key this version does not know.
        val back = json.parseToJsonElement(json.encodeToString(com.rendyhd.vicu.domain.model.CustomListWireSerializer, list.toWire(wire))).jsonObject.getValue("filter").jsonObject
        val original = json.parseToJsonElement(wireJson).jsonObject.getValue("filter").jsonObject
        for ((key, value) in original) {
            if (key in ProListFilter.KEYS || key == "future_key") assertEquals(value, back[key], key)
        }
        // A changed Pro value replaces the stored one.
        val edited = list.copy(filter = list.filter.copy(pro = list.filter.pro.copy(flagged = false)))
        val written = json.parseToJsonElement(json.encodeToString(com.rendyhd.vicu.domain.model.CustomListWireSerializer, edited.toWire(wire))).jsonObject.getValue("filter").jsonObject
        assertEquals(JsonPrimitive(false), written["flagged"])
        assertEquals(original["future_key"], written["future_key"])
        // Without any Pro key nothing is written.
        val plain = CustomListWireFilter()
        assertEquals(ProListFilter.NONE, ProListFilter.read(plain.unknown))
    }
}
