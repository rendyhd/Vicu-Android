package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.pro.ProFixture.day
import com.rendyhd.vicu.util.pro.ProFixture.flag
import com.rendyhd.vicu.util.pro.ProFixture.forEachZone
import com.rendyhd.vicu.util.pro.ProFixture.intOf
import com.rendyhd.vicu.util.pro.ProFixture.iso
import com.rendyhd.vicu.util.pro.ProFixture.ledgerOf
import com.rendyhd.vicu.util.pro.ProFixture.local
import com.rendyhd.vicu.util.pro.ProFixture.objects
import com.rendyhd.vicu.util.pro.ProFixture.pro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `pro` vectors of cross-app-semantics-v1.json for settings, preferences, availability, start
 * dates, the ledger, repeat rules and focus (contract section 21), in the contract's zones. The
 * desktop runs the same (`pro-suite.ts`).
 */
class ProRulesFixtureTest {

    private fun jsonOf(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    // --- Settings ------------------------------------------------------------------------------

    @Test
    fun theSwitchAndTheLayers() {
        for (case in pro.getValue("settings").jsonObject.objects("enabled")) {
            val layer = case.text("layer")?.let { key -> ProLayer.entries.first { it.key == key } }
            val config = ProSwitches.read(case.getValue("config").jsonObject)
            assertEquals(case.flag("expect"), ProSettings.isEnabled(config, layer), case.text("name"))
        }
        assertEquals(false, ProSettings.isEnabled(null))
    }

    @Test
    fun theArea() {
        val area = pro.getValue("settings").jsonObject.getValue("area").jsonObject
        val projects = ProFixture.projects(area["projects"]).associateBy { it.id }
        val categories = area.getValue("categories").jsonObject
        for (case in area.objects("cases")) {
            val inArea = ProSettings.inArea(
                case.getValue("project").jsonPrimitive.long,
                ProSettings.readArea(case["area"]),
                projects,
            ) { id -> categories[id.toString()]?.text() }
            assertEquals(case.flag("expect"), inArea, case.text("name"))
        }
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    // --- Preferences ---------------------------------------------------------------------------

    @Test
    fun preferenceKeysAndValues() {
        val prefs = pro.getValue("prefs").jsonObject
        for (case in prefs.objects("keys")) {
            val key = case.text("key")!!
            assertEquals(case.flag("pro"), ProPrefs.isProKey(key), key)
        }
        for (case in prefs.objects("projectMode")) {
            assertEquals(case.text("expect"), ProPrefs.readProjectMode(case["value"]).key, case["value"].toString())
        }
        for (case in prefs.objects("onHold")) {
            assertEquals(case.flag("expect"), ProPrefs.readOnHold(case["value"]), case["value"].toString())
        }
        for (case in prefs.objects("repeatRule")) {
            val expected = case.getValue("expect")
            assertEquals(expected, ProPrefs.readRepeatRule(case["value"])?.toJson() ?: JsonNull, case["value"].toString())
        }
    }

    @Test
    fun personAndHoldLabelsAreReadFromTheKeys() {
        val values = mapOf<String, JsonElement?>(
            "label_kind.7" to JsonPrimitive("person"),
            "label_kind.8" to JsonPrimitive("other"),
            "label_status.9" to JsonPrimitive("on_hold"),
            "label_status.10" to JsonPrimitive("active"),
        )
        assertEquals(setOf(7L), ProPrefs.personLabelIds(values))
        assertEquals(setOf(9L), ProPrefs.onHoldLabelIds(values))
        assertEquals(true, ProPrefs.isPersonLabel(values, 7))
        assertEquals(true, ProPrefs.labelOnHold(values, 9))
    }

    // --- Availability --------------------------------------------------------------------------

    @Test
    fun availability() = forEachZone { zone ->
        for (world in objects(pro["availability"])) {
            val now = local(world.text("now")!!, zone)
            val rawTasks = world.objects("tasks")
            val tasks = rawTasks.map { ProFixture.task(it, zone) }
            val context = Availability.context(
                now = now,
                zone = zone,
                tasks = tasks,
                projects = ProFixture.projects(world["projects"]),
                prefs = ProFixture.prefs(world["prefs"]),
                ledger = ledgerOf(world["ledger"] as? JsonObject),
            )
            for ((index, raw) in rawTasks.withIndex()) {
                val id = raw.getValue("id").jsonPrimitive.long
                val result = context.of(tasks[index])
                val expected = raw.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(expected, result.reasons.map { it.key }, "${world.text("name")}: reasons of $id")
                assertEquals(expected.isEmpty(), result.available, "available of $id")
                raw["blockers"]?.let { assertEquals(ProFixture.longsOf(it), result.blockers, "blockers of $id") }
                assertEquals(raw.flag("next"), result.next, "next of $id")
            }
            assertEquals(ProFixture.longsOf(world["cycles"]), context.cycles, "${world.text("name")}: cycles")
            world["views"]?.jsonObject?.let { views ->
                for (view in AvailabilityView.entries) {
                    assertEquals(
                        ProFixture.longsOf(views[view.key]),
                        Availability.filterByView(tasks, view, context).map { it.id },
                        "${world.text("name")}: view ${view.key}",
                    )
                }
            }
        }
    }

    // --- Start dates ---------------------------------------------------------------------------

    private fun startTask(start: String?, end: String?, zone: kotlinx.datetime.TimeZone, done: Boolean = false, id: Long = 1) =
        Task(id = id, title = "", done = done, startDate = iso(start, zone).orEmpty(), endDate = iso(end, zone).orEmpty())

    @Test
    fun startDatesAndBlocks() = forEachZone { zone ->
        val vectors = pro.getValue("startVsBlock").jsonObject
        for (case in vectors.objects("cases")) {
            val task = startTask(case.text("start"), case.text("end"), zone)
            val name = case.text("name")
            assertEquals(case.text("startDay"), StartDates.startDayOf(task, zone)?.toString(), "$name: start day")
            assertEquals(
                case.flag("startsLater"),
                StartDates.startsLater(task, local(case.text("now")!!, zone), zone),
                "$name: starts later",
            )
            assertEquals(
                case.flag("warn"),
                StartDates.blockReplacesLaterStart(task, local(case.text("blockStart")!!, zone), zone),
                "$name: warn",
            )
        }
        for (case in vectors.objects("dateOnlyStart")) {
            assertEquals(local(case.text("expect")!!, zone), StartDates.dateOnlyStart(day(case.text("day")!!), zone))
        }
        val upcoming = vectors.getValue("upcoming").jsonObject
        val tasks = upcoming.objects("tasks").map {
            startTask(it.text("start"), it.text("end"), zone, done = it.flag("done"), id = it.getValue("id").jsonPrimitive.long)
        }
        val entries = StartDates.startsEntries(tasks, local(upcoming.text("now")!!, zone), zone)
        val expected = upcoming.objects("expect").map { it.getValue("id").jsonPrimitive.long to it.text("day") }
        assertEquals(expected, entries.map { it.id to it.day.toString() })
    }

    // --- Ledger --------------------------------------------------------------------------------

    @Test
    fun ledgerMerge() {
        for (case in objects(pro["ledgerMerge"])) {
            val merged = LedgerRules.merge(
                ProFixture.compactLedger(case.getValue("local").jsonObject),
                ProFixture.compactLedger(case.getValue("server").jsonObject),
            )
            assertEquals(ProFixture.compactLedger(case.getValue("expect").jsonObject), merged, case.text("name"))
        }
    }

    @Test
    fun pruningTombstones() {
        val vector = pro.getValue("ledgerPrune").jsonObject
        val pruned = LedgerRules.prune(
            ProFixture.compactLedger(vector.getValue("ledger").jsonObject),
            vector.getValue("nowMs").jsonPrimitive.long,
        )
        assertEquals(vector.getValue("keptPromises").jsonArray.map { it.jsonPrimitive.content }, pruned.records("promises").keys.sorted())
        assertEquals(vector.getValue("keptEstimates").jsonArray.map { it.jsonPrimitive.content }, pruned.estimates.keys.sorted())
    }

    @Test
    fun theArchivePlan() = forEachZone { zone ->
        val vector = pro.getValue("archive").jsonObject
        val plan = LedgerRules.archivePlan(ledgerOf(vector.getValue("ledger").jsonObject), day(vector.text("today")!!), zone)
        val parts = JsonObject(
            plan.parts.mapValues { (_, collections) ->
                JsonObject(collections.mapValues { (_, byId) -> JsonArray(byId.keys.sorted().map(::JsonPrimitive)) })
            },
        )
        assertEquals(vector.getValue("parts"), parts)
        assertEquals(vector.getValue("moved").jsonArray.map { it.jsonPrimitive.content }, plan.moved.map { "${it.collection}/${it.id}" })
    }

    @Test
    fun ledgerChangesKeepWhatTheyDoNotKnow() {
        val ledger = ProFixture.compactLedger(
            pro.getValue("ledgerMerge").jsonArray.map { it.jsonObject }.first { it.text("name") == "unknown fields are kept" }
                .getValue("local").jsonObject,
        )
        val changed = LedgerRules.setRecord(ledger, "promises", "5", JsonObject(mapOf("dir" to JsonPrimitive("mine"))), "dev", 5_000)
        assertEquals("mine", changed.value("promises", 5L)?.text("dir"))
        for ((key, value) in ledger.json) {
            if (key !in LedgerRules.COLLECTIONS && key != "estimates") assertEquals(value, changed.json[key], key)
        }
        val revision = LedgerRules.revisionOf(changed.records("promises").getValue("5").jsonObject["revision"])!!
        assertEquals("dev", revision.deviceId)
        val estimated = LedgerRules.setEstimate(changed, 9, 45, 7_000_000)
        assertEquals(45, estimated.estimateOf(9))
        assertNull(LedgerRules.setEstimate(estimated, 9, null, 8_000_000).estimateOf(9))
    }

    // --- Repeat rules --------------------------------------------------------------------------

    private fun repeatTask(raw: JsonObject, zone: kotlinx.datetime.TimeZone) = Task(
        id = 1,
        title = "",
        dueDate = iso(raw.text("due"), zone) ?: ProFixture.NULL_DATE,
        startDate = iso(raw.text("start"), zone).orEmpty(),
        endDate = iso(raw.text("end"), zone).orEmpty(),
        repeatAfter = raw.getValue("repeat_after").jsonPrimitive.long,
        repeatMode = raw.intOf("repeat_mode"),
    )

    @Test
    fun repeatRules() = forEachZone { zone ->
        val vectors = pro.getValue("repeatRule").jsonObject
        val now = local(vectors.text("now")!!, zone)
        for (case in vectors.objects("completion")) {
            val name = case.text("name")
            val plan = RepeatRules.completionPlan(
                repeatTask(case.getValue("task").jsonObject, zone),
                ProPrefs.readRepeatRule(case["rule"]),
                now,
                zone,
            )
            assertEquals(case.getValue("patches"), JsonArray(plan.patches), "$name: patches")
            val ruleAfter: JsonElement = when (val change = plan.rule) {
                RuleChange.Unchanged -> JsonPrimitive("unchanged")
                RuleChange.Removed -> JsonNull
                is RuleChange.Set -> change.rule.toJson()
            }
            assertEquals(case.getValue("ruleAfter"), ruleAfter, "$name: rule after")
            assertEquals(case.flag("catchUp"), plan.catchUp, "$name: catch up")
        }
        for (case in vectors.objects("catchUp")) {
            val patch = RepeatRules.catchUpPatch(
                repeatTask(case.getValue("task").jsonObject, zone),
                day(vectors.text("today")!!),
                case.text("until"),
                zone,
            )
            val expected = (case["patch"] as? JsonObject)?.let { fields ->
                JsonObject(fields.mapValues { (_, value) -> JsonPrimitive(iso(value.jsonPrimitive.content, zone)!!) })
            }
            assertEquals(expected, patch, case.text("name"))
        }
    }

    // --- Focus ---------------------------------------------------------------------------------

    @Test
    fun focus() {
        val vectors = pro.getValue("focus").jsonObject
        val projects = ProFixture.projects(vectors["projects"]).associateBy { it.id }
        val categories = vectors.getValue("categories").jsonObject
        val category = { id: Long -> categories[id.toString()]?.text() }
        val inbox = vectors.getValue("inbox").jsonPrimitive.long
        val focus = Focus.readProFocus(vectors["focus"])
        for ((id, expected) in vectors.getValue("inFocus").jsonObject) {
            assertEquals(expected.jsonPrimitive.boolean, Focus.inFocus(id.toLong(), focus, projects, category, inbox), "project $id")
            assertEquals(true, Focus.inFocus(id.toLong(), null, projects, category, inbox), "project $id without a focus")
        }
        assertEquals(
            ProFixture.longsOf(vectors["shown"]),
            Focus.projectsShownInFocus(focus, projects, category, inbox).sorted(),
        )
        for (case in vectors.objects("read")) {
            val read = Focus.readProFocus(case["value"])
            val expected = case["expect"]
            if (expected is JsonObject) {
                assertEquals(expected.getValue("categories").jsonArray.map { it.jsonPrimitive.content }, read?.categories)
                assertEquals(ProFixture.longsOf(expected["projects"]), read?.projects)
            } else {
                assertNull(read, case["value"].toString())
            }
        }
    }

    @Test
    fun theDailyFocusBlock() = forEachZone { zone ->
        val vector = pro.getValue("focusBlock").jsonObject
        val ranks = vector.getValue("ranks").jsonObject
        val tasks = vector.objects("tasks").map { ProFixture.task(it, zone) }
        val candidates = FocusBlock.candidates(tasks, zone) { id -> ranks[id.toString()]?.jsonPrimitive?.long?.toInt() }
        assertEquals(ProFixture.longsOf(vector["candidates"]), candidates)
        for (case in vector.objects("minutes")) {
            val estimates = case.getValue("estimates").jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.toInt() }
            assertEquals(case.intOf("expect"), FocusBlock.minutes(estimates), estimates.toString())
        }
    }
}
