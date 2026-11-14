package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.location.SavedPlace
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.readRepoFile
import com.rendyhd.vicu.util.CrossAppFixture
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.pro.Person
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared quick-add corpus (`test-fixtures/nlp-corpus-v1.json`, identical in the desktop repo;
 * docs/cross-app-semantics-v1.md section 5). Reference time is Tue 2026-10-06 10:00 local, in
 * every zone the contract vectors run in, and a case passes only when all of title, due date,
 * priority, labels, project, recurrence and place match. Every case is parsed with the corpus's
 * saved places (section 5.3).
 */
object NlpCorpusFixture {

    @Serializable
    data class Recurrence(val interval: Int, val unit: String)

    @Serializable
    data class Place(val id: String, val name: String)

    @Serializable
    data class PlaceRead(val id: String, val trigger: String)

    @Serializable
    data class PersonRead(val id: Long, val mine: Boolean)

    @Serializable
    data class CorpusPerson(val id: Long, val title: String, val aliases: List<String> = emptyList())

    @Serializable
    data class Case(
        val input: String,
        val mode: String = "todoist",
        val locale: String = "en-US",
        val parserEnabled: Boolean = true,
        /** The week start the case is parsed with (section 2.1). */
        val weekStart: String = "monday",
        val title: String,
        val due: String? = null,
        /** The time block `for 30m` makes (section 5.4). */
        val start: String? = null,
        val end: String? = null,
        val priority: Int? = null,
        val labels: List<String> = emptyList(),
        val project: String? = null,
        val recurrence: Recurrence? = null,
        val place: PlaceRead? = null,
        /** Parsed with Pro on and the corpus's people (section 5.5). */
        val pro: Boolean = false,
        val person: PersonRead? = null,
        val proStart: String? = null,
        val estimate: Int? = null,
        val flag: Boolean = false,
        val request: Boolean = false,
    )

    @Serializable
    data class Corpus(
        val contractVersion: Int,
        val reference: String,
        val places: List<Place>,
        val people: List<CorpusPerson> = emptyList(),
        val cases: List<Case>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val corpus: Corpus by lazy { json.decodeFromString(Corpus.serializer(), readText()) }

    private fun readText(): String = readRepoFile("test-fixtures/nlp-corpus-v1.json")
}

/** A local date and time as `YYYY-MM-DDTHH:mm:ss`, the notation of the corpus. */
internal fun wallOf(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.year}-${p(value.month.number)}-${p(value.day)}T${p(value.hour)}:${p(value.minute)}:${p(value.second)}"
}

class NlpCorpusTest {

    private val reference = LocalDateTime.parse(NlpCorpusFixture.corpus.reference)
    private val places = NlpCorpusFixture.corpus.places.map { SavedPlace(it.id, it.name) }
    private val people = NlpCorpusFixture.corpus.people.map { Person(it.id, it.title, it.aliases) }

    private fun problemsFor(case: NlpCorpusFixture.Case, zone: TimeZone): List<String> {
        val config = ParserConfig(
            enabled = case.parserEnabled,
            syntaxMode = if (case.mode == "vikunja") SyntaxMode.VIKUNJA else SyntaxMode.TODOIST,
            locale = case.locale,
            pro = case.pro,
        )
        val result = TaskParser.parse(case.input, config, reference, zone, places, people, WeekStart.fromKey(case.weekStart))
        val problems = mutableListOf<String>()

        if (result.title != case.title) problems += "title '${result.title}', expected '${case.title}'"

        // The value the parser carries, and the value that is stored once the zone is applied.
        val carried = result.dueDate?.let(::wallOf)
        if (carried != case.due) problems += "due (parser) $carried, expected ${case.due}"
        val stored = result.dueDate
            ?.let { DueDates.fromParsed(it, result.dueDateHasTime, zone) }
            ?.let { CrossAppFixture.wall(it, zone) }
        if (stored != case.due) problems += "due (stored in $zone) $stored, expected ${case.due}"

        val start = result.block?.start?.let(::wallOf)
        if (start != case.start) problems += "start $start, expected ${case.start}"
        val end = result.block?.end?.let(::wallOf)
        if (end != case.end) problems += "end $end, expected ${case.end}"

        if (result.priority != case.priority) problems += "priority ${result.priority}, expected ${case.priority}"
        if (result.labels != case.labels) problems += "labels ${result.labels}, expected ${case.labels}"
        if (result.project != case.project) problems += "project ${result.project}, expected ${case.project}"
        val recurrence = result.recurrence?.let { NlpCorpusFixture.Recurrence(it.interval, it.unit.name.lowercase()) }
        if (recurrence != case.recurrence) problems += "recurrence $recurrence, expected ${case.recurrence}"
        val place = result.place?.let { NlpCorpusFixture.PlaceRead(it.place.id, it.trigger.name.lowercase()) }
        if (place != case.place) problems += "place $place, expected ${case.place}"
        val person = result.person?.let { NlpCorpusFixture.PersonRead(it.id, it.mine) }
        if (person != case.person) problems += "person $person, expected ${case.person}"
        val proStart = result.start?.let(::wallOf)
        if (proStart != case.proStart) problems += "start date $proStart, expected ${case.proStart}"
        if (result.estimate != case.estimate) problems += "estimate ${result.estimate}, expected ${case.estimate}"
        if (result.flag != case.flag) problems += "flag ${result.flag}, expected ${case.flag}"
        if (result.request != case.request) problems += "request ${result.request}, expected ${case.request}"
        return problems
    }

    private fun runCorpus(zone: TimeZone) {
        val failures = NlpCorpusFixture.corpus.cases.mapNotNull { case ->
            val problems = problemsFor(case, zone)
            if (problems.isEmpty()) null else "'${case.input}' (${case.mode}, ${case.locale}, ${case.weekStart}): ${problems.joinToString("; ")}"
        }
        assertTrue(failures.isEmpty(), "${failures.size} corpus cases failed in $zone:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `the corpus is the contract version this parser implements`() {
        assertEquals(1, NlpCorpusFixture.corpus.contractVersion)
        assertEquals(189, NlpCorpusFixture.corpus.cases.size, "the corpus changed size")
    }

    @Test
    fun `every corpus case passes in Europe_Amsterdam`() = runCorpus(TimeZone.of("Europe/Amsterdam"))

    @Test
    fun `every corpus case passes in America_New_York`() = runCorpus(TimeZone.of("America/New_York"))

    @Test
    fun `every corpus case passes in Pacific_Auckland`() = runCorpus(TimeZone.of("Pacific/Auckland"))
}
