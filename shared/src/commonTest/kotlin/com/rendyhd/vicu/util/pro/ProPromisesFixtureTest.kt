package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.pro.ProFixture.day
import com.rendyhd.vicu.util.pro.ProFixture.flag
import com.rendyhd.vicu.util.pro.ProFixture.forEachZone
import com.rendyhd.vicu.util.pro.ProFixture.intOf
import com.rendyhd.vicu.util.pro.ProFixture.iso
import com.rendyhd.vicu.util.pro.ProFixture.ledgerOf
import com.rendyhd.vicu.util.pro.ProFixture.local
import com.rendyhd.vicu.util.pro.ProFixture.longsOf
import com.rendyhd.vicu.util.pro.ProFixture.objects
import com.rendyhd.vicu.util.pro.ProFixture.pro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
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
 * The `pro` vectors for promises, people, the recap, meeting rounds and the portfolio (contract
 * section 21), in the contract's zones. The desktop runs the same (`pro-suite.ts`).
 */
class ProPromisesFixtureTest {

    private fun promiseTask(raw: JsonObject, zone: TimeZone) = Task(
        id = 1,
        title = "",
        done = raw.flag("done"),
        dueDate = iso(raw.text("due"), zone) ?: ProFixture.NULL_DATE,
    )

    private fun snoozeOf(raw: JsonObject?): VerdictSnooze? =
        raw?.let { VerdictSnooze(day(it.text("due")!!), day(it.text("until")!!)) }

    // --- Promises ------------------------------------------------------------------------------

    @Test
    fun verdictsDueAndMisses() = forEachZone { zone ->
        val promises = pro.getValue("promises").jsonObject
        val today = day(promises.text("today")!!)
        for (case in promises.objects("verdictDue")) {
            val name = case.text("name")
            val task = promiseTask(case.getValue("task").jsonObject, zone)
            val promise = case.getValue("promise").jsonObject
            val snooze = snoozeOf(case["snooze"] as? JsonObject)
            assertEquals(case.text("due"), Promises.effectiveDue(task, promise, zone)?.toString(), "$name: due")
            assertEquals(case.flag("needs"), Promises.needsVerdict(task, promise, today, snooze, zone), "$name: needs")
            case["canSnooze"]?.let { assertEquals(it.jsonPrimitive.content == "true", Promises.canSnooze(task, promise, snooze, zone), "$name: can snooze") }
            case["completedElsewhere"]?.let {
                assertEquals(it.jsonPrimitive.content == "true", Promises.completedElsewhere(task, promise), "$name: completed elsewhere")
            }
            case["misses"]?.let { assertEquals(it.jsonPrimitive.content.toInt(), Promises.missesOf(promise), "$name: misses") }
        }
    }

    @Test
    fun buildingAVerdict() = forEachZone { zone ->
        val promises = pro.getValue("promises").jsonObject
        for (case in promises.objects("build")) {
            val name = case.text("name")
            val task = promiseTask(case.getValue("task").jsonObject, zone)
            val change = Promises.buildVerdict(
                task = task,
                kind = case.text("kind")!!,
                id = "v",
                at = "2026-11-12T09:00:00Z",
                newDue = case.text("newDue")?.let(::day),
                blocker = case.text("blocker"),
                zone = zone,
            )
            assertEquals(case.getValue("verdict"), change.verdict, "$name: verdict")
            val expected = (case["patch"] as? JsonObject)?.let { patch ->
                if (patch.containsKey("due_date")) JsonObject(mapOf("due_date" to JsonPrimitive(iso(patch.text("due_date"), zone)!!))) else patch
            }
            assertEquals(expected, change.taskPatch, "$name: patch")
        }
    }

    @Test
    fun confirmTitles() {
        for (case in pro.getValue("promises").jsonObject.objects("confirmTitles")) {
            val name = case.text("name")
            if (name != null) {
                assertEquals(case.text("title"), Promises.confirmTitle(name, case.text("what")!!))
            } else {
                val read = Promises.readConfirmTitle(case.text("title")!!)
                val expected = case["read"] as? JsonObject
                assertEquals(expected?.let { it.text("name") to it.text("what") }, read)
            }
        }
    }

    @Test
    fun waitingOnAndUnsent() = forEachZone { zone ->
        val vector = pro.getValue("waitingOn").jsonObject
        val tasks = vector.objects("tasks").map {
            Task(
                id = it.getValue("id").jsonPrimitive.long,
                title = "",
                done = it.flag("done"),
                dueDate = iso(it.text("due"), zone) ?: ProFixture.NULL_DATE,
                labels = (it["labels"]?.jsonArray ?: JsonArray(emptyList())).map { id -> Label(id.jsonPrimitive.long, "") },
            )
        }
        val ledger = ledgerOf(vector["ledger"] as? JsonObject)
        val onHold = longsOf(vector["onHoldLabels"])
        val people = longsOf(vector["people"])
        val groups = Promises.waitingOn(tasks, ledger, { it in onHold }, { it in people }, zone)
        val expected = vector.objects("groups").map { it.longOrNull("person") to longsOf(it["tasks"]) }
        assertEquals(expected, groups.map { it.person to it.tasks })
        assertEquals(longsOf(vector["unsent"]), Promises.unsentPromises(tasks, ledger))
    }

    private fun JsonObject.longOrNull(key: String): Long? = whole(key)

    // --- People --------------------------------------------------------------------------------

    private fun peopleOf(array: JsonElement?): List<Person> = array!!.jsonArray.map {
        val person = it.jsonObject
        Person(person.getValue("id").jsonPrimitive.long, person.text("title")!!, person.texts("aliases"))
    }

    @Test
    fun matchingNamesToPeople() {
        val vector = pro.getValue("personMatch").jsonObject
        val people = peopleOf(vector["people"])
        for (case in vector.objects("cases")) {
            val match = People.matchPerson(case.text("name")!!, people)
            assertEquals(case.longOrNull("id") to case.text("match"), match.id to match.match.key, "\"${case.text("name")}\"")
        }
        for (case in vector.objects("aliases")) {
            assertEquals(case.texts("expect"), People.withAlias(case.texts("aliases"), case.text("add")!!))
        }
    }

    private fun countsOf(raw: JsonObject) = PersonCounts(
        delivered = raw.whole("delivered")?.toInt() ?: 0,
        late = raw.whole("late")?.toInt() ?: 0,
        dropped = raw.whole("dropped")?.toInt() ?: 0,
        takenOver = raw.whole("takenOver")?.toInt() ?: 0,
        renegotiated = raw.whole("renegotiated")?.toInt() ?: 0,
        missed = raw.whole("missed")?.toInt() ?: 0,
        openTheirs = raw.whole("openTheirs")?.toInt() ?: 0,
        openMine = raw.whole("openMine")?.toInt() ?: 0,
    )

    @Test
    fun personPatternAndCounts() = forEachZone { zone ->
        val vector = pro.getValue("personPattern").jsonObject
        for (case in vector.objects("texts")) {
            assertEquals(case.text("text"), People.patternText(countsOf(case.getValue("counts").jsonObject)))
        }
        val c = vector.getValue("counts").jsonObject
        val open = longsOf(c["openTasks"])
        val counts = People.personCounts(c.getValue("person").jsonPrimitive.long, ledgerOf(c["ledger"] as? JsonObject), { it in open }, day(c.text("today")!!), zone)
        assertEquals(countsOf(c.getValue("expect").jsonObject), counts)
        assertEquals(c.text("text"), People.patternText(counts))
    }

    // --- The recap -----------------------------------------------------------------------------

    @Test
    fun theRecap() = forEachZone { zone ->
        val r = pro.getValue("recapText").jsonObject
        val doneTasks = longsOf(r["doneTasks"])
        val names = r.getValue("names").jsonObject
        val tasks = r.objects("tasks").map {
            val id = it.getValue("id").jsonPrimitive.long
            Task(
                id = id,
                title = it.text("title")!!,
                done = id in doneTasks,
                dueDate = iso(it.text("due"), zone) ?: ProFixture.NULL_DATE,
                created = iso(it.text("created"), zone).orEmpty(),
            )
        }
        val ledger = ledgerOf(r["ledger"] as? JsonObject)
        for (case in r.objects("cases")) {
            val name = case.text("name")
            val scopeJson = case.getValue("scope").jsonObject
            val scope = RecapScope(day(scopeJson.text("from")!!), day(scopeJson.text("to")!!), longsOf(scopeJson["people"]), scopeJson.flag("includeMine"))
            val items = Recaps.items(scope, tasks, ledger, zone)
            val sections = LinkedHashMap<String, MutableList<Long>>()
            for (item in items) sections.getOrPut(item.section.key) { mutableListOf() }.add(item.taskId)
            val expectedSections = case.getValue("sections").jsonObject.mapValues { (_, ids) -> longsOf(ids) }
            assertEquals(expectedSections, sections.toMap(), "$name: sections")
            val recap = Recaps.build(
                title = case.text("title")!!,
                date = day(case.text("date")!!),
                items = items,
                personName = { id -> names[id.toString()]?.jsonPrimitive?.content ?: "?" },
                notDiscussed = case.texts("notDiscussed"),
                absent = case.texts("absent"),
                includeQuotes = case.flag("includeQuotes"),
                now = local(r.text("now")!!, zone),
                zone = zone,
                fmt = DateDisplayFormat(case.text("locale")!!, case.flag("hour12")),
            )
            assertEquals(case.texts("text"), recap.text.split('\n'), "$name: text")
            assertEquals(case.text("html"), recap.html, "$name: html")
            assertEquals(longsOf(case["taskIds"]), recap.taskIds, "$name: task ids")
        }
    }

    // --- Meeting rounds ------------------------------------------------------------------------

    private fun view(state: RoundState): JsonObject = JsonObject(
        mapOf(
            "current" to (state.current?.let { JsonPrimitive(it) } ?: JsonNull),
            "discussed" to JsonArray(state.discussed.map(::JsonPrimitive)),
            "skipped" to JsonArray(state.skipped.map(::JsonPrimitive)),
            "absent" to JsonArray(state.absent.map(::JsonPrimitive)),
        ),
    )

    @Test
    fun meetingRounds() {
        for (case in objects(pro["meetingRound"])) {
            val name = case.text("name")
            var state = Meeting.startRound(longsOf(case["people"]), case.flag("withMe"))
            assertEquals(case.getValue("start"), view(state), "$name: start")
            for (step in case.objects("steps")) {
                state = when (step.text("op")) {
                    "next" -> Meeting.nextPerson(state)
                    "skip" -> Meeting.skipPerson(state)
                    "pick" -> Meeting.pickPerson(state, step.getValue("person").jsonPrimitive.long)
                    "absent" -> Meeting.markAbsent(state, step.getValue("person").jsonPrimitive.long)
                    else -> state
                }
                assertEquals(step.getValue("expect"), view(state), "$name: after ${step.text("op")}")
            }
            assertEquals(case.getValue("round"), Meeting.endRound(state, "m1", "2026-11-12"), "$name: round")
        }
    }

    // --- Portfolio -----------------------------------------------------------------------------

    @Test
    fun rankingMovingAndTheCap() {
        val pf = pro.getValue("portfolio").jsonObject
        val ledger = ledgerOf(pf["ledger"] as? JsonObject)
        assertEquals(longsOf(pf["ranked"]), Portfolio.rankedInitiatives(ledger).map { it.projectId })
        for (move in pf.objects("moves")) {
            val ranks = Portfolio.moveRank(ledger, move.getValue("project").jsonPrimitive.long, move.intOf("to"))
            val expected = move.getValue("ranks").jsonObject.entries.associate { (id, rank) -> id.toLong() to rank.jsonPrimitive.content.toInt() }
            assertEquals(expected, ranks)
        }
        for (case in pf.objects("cap")) {
            val settings = case["settings"] as? JsonObject
            val withSettings = ledgerOf(JsonObject(pf.getValue("ledger").jsonObject + (settings?.let { mapOf("settings" to it) } ?: emptyMap())))
            val expected = case.getValue("expect").jsonObject
            val state = Portfolio.capState(withSettings)
            assertEquals(CapState(expected.intOf("active"), expected.intOf("cap"), expected.flag("agreed"), expected.flag("full")), state)
        }
    }

    @Test
    fun parkingAndUnparking() {
        val pf = pro.getValue("portfolio").jsonObject
        val park = pf.getValue("park").jsonObject
        assertEquals(
            park.getValue("expect"),
            Portfolio.parkedValue(park.getValue("value").jsonObject, day(park.text("on")!!), park.text("reason")!!),
        )
        val unpark = pf.getValue("unpark").jsonObject
        assertEquals(unpark.getValue("expect"), Portfolio.unparkedValue(ledgerOf(pf["ledger"] as? JsonObject), unpark.getValue("value").jsonObject))
    }

    @Test
    fun readiness() {
        for (case in objects(pro["readiness"])) {
            val result = Portfolio.readiness(case["value"] as? JsonObject)
            assertEquals(case.intOf("done"), result.done, case.text("name"))
            assertEquals(case.texts("missing"), result.missing.map { it.key }, case.text("name"))
            assertEquals(case.texts("missing").isEmpty(), result.ready, case.text("name"))
        }
    }

    @Test
    fun escalations() = forEachZone { zone ->
        val e = pro.getValue("escalationDraft").jsonObject
        val ledger = ledgerOf(e["ledger"] as? JsonObject)
        val tasks = e.objects("tasks").associate {
            val id = it.getValue("id").jsonPrimitive.long
            id to Task(id = id, title = it.text("title")!!, dueDate = iso(it.text("due"), zone) ?: ProFixture.NULL_DATE)
        }
        val names = e.getValue("names").jsonObject
        val projects = e.getValue("projects").jsonObject
        val today = day(e.text("today")!!)
        val fmt = DateDisplayFormat(e.text("locale")!!, hour12 = false)
        for (case in e.objects("due")) {
            val task = case.getValue("task").jsonPrimitive.long
            assertEquals(case.flag("expect"), Portfolio.escalationDue(ledger, task), "task $task")
        }
        for (case in e.objects("drafts")) {
            val draft = Portfolio.draftEscalation(
                ledger = ledger,
                taskId = case.getValue("task").jsonPrimitive.long,
                tasks = tasks,
                personName = { names.getValue(it.toString()).jsonPrimitive.content },
                projectName = { projects.getValue(it.toString()).jsonPrimitive.content },
                dateText = { DateDisplay.formatWeekdayDate(it, today, fmt) },
                today = today,
                zone = zone,
            )
            assertEquals<JsonElement?>(case.getValue("expect"), draft, "draft of ${case["task"]}")
        }
        val withEscalation = ledgerOf(
            JsonObject(e.getValue("ledger").jsonObject + ("escalations" to JsonObject(mapOf("x" to e.objects("drafts")[0].getValue("expect"))))),
        )
        val after = e.getValue("afterDraft").jsonObject
        assertEquals(after.flag("expect"), Portfolio.escalationDue(withEscalation, after.getValue("task").jsonPrimitive.long))
    }
}
