package com.rendyhd.vicu.util.parser

import com.rendyhd.vicu.util.CrossAppFixture
import com.rendyhd.vicu.util.DueDates
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared quick-add corpus (`test-fixtures/nlp-corpus-v1.json`, identical in the desktop repo;
 * docs/cross-app-semantics-v1.md section 5). Reference time is Tue 2026-10-06 10:00 local, in
 * every zone the contract vectors run in, and a case passes only when all of title, due date,
 * priority, labels, project and recurrence match.
 */
object NlpCorpusFixture {

    @Serializable
    data class Recurrence(val interval: Int, val unit: String)

    @Serializable
    data class Case(
        val input: String,
        val mode: String = "todoist",
        val locale: String = "en-US",
        val parserEnabled: Boolean = true,
        val title: String,
        val due: String? = null,
        val priority: Int? = null,
        val labels: List<String> = emptyList(),
        val project: String? = null,
        val recurrence: Recurrence? = null,
    )

    @Serializable
    data class Corpus(val contractVersion: Int, val reference: String, val cases: List<Case>)

    private val json = Json { ignoreUnknownKeys = true }

    val corpus: Corpus by lazy { json.decodeFromString(Corpus.serializer(), readText()) }

    private fun readText(): String {
        // Gradle runs the tests with the module directory as the working directory; search
        // upward so running them from the repo root or an IDE works too.
        val start: String = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "test-fixtures/nlp-corpus-v1.json")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        error("test-fixtures/nlp-corpus-v1.json not found above $start")
    }
}

/** A local date and time as `YYYY-MM-DDTHH:mm:ss`, the notation of the corpus. */
internal fun wallOf(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.year}-${p(value.monthNumber)}-${p(value.dayOfMonth)}T${p(value.hour)}:${p(value.minute)}:${p(value.second)}"
}

class NlpCorpusTest {

    private val reference = LocalDateTime.parse(NlpCorpusFixture.corpus.reference)

    private fun problemsFor(case: NlpCorpusFixture.Case, zone: TimeZone): List<String> {
        val config = ParserConfig(
            enabled = case.parserEnabled,
            syntaxMode = if (case.mode == "vikunja") SyntaxMode.VIKUNJA else SyntaxMode.TODOIST,
            locale = case.locale,
        )
        val result = TaskParser.parse(case.input, config, reference, zone)
        val problems = mutableListOf<String>()

        if (result.title != case.title) problems += "title '${result.title}', expected '${case.title}'"

        // The value the parser carries, and the value that is stored once the zone is applied.
        val carried = result.dueDate?.let(::wallOf)
        if (carried != case.due) problems += "due (parser) $carried, expected ${case.due}"
        val stored = result.dueDate
            ?.let { DueDates.fromParsed(it, result.dueDateHasTime, zone) }
            ?.let { CrossAppFixture.wall(it, zone) }
        if (stored != case.due) problems += "due (stored in $zone) $stored, expected ${case.due}"

        if (result.priority != case.priority) problems += "priority ${result.priority}, expected ${case.priority}"
        if (result.labels != case.labels) problems += "labels ${result.labels}, expected ${case.labels}"
        if (result.project != case.project) problems += "project ${result.project}, expected ${case.project}"
        val recurrence = result.recurrence?.let { NlpCorpusFixture.Recurrence(it.interval, it.unit.name.lowercase()) }
        if (recurrence != case.recurrence) problems += "recurrence $recurrence, expected ${case.recurrence}"
        return problems
    }

    private fun runCorpus(zone: TimeZone) {
        val failures = NlpCorpusFixture.corpus.cases.mapNotNull { case ->
            val problems = problemsFor(case, zone)
            if (problems.isEmpty()) null else "'${case.input}' (${case.mode}, ${case.locale}): ${problems.joinToString("; ")}"
        }
        assertTrue(failures.isEmpty(), "${failures.size} corpus cases failed in $zone:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `the corpus is the contract version this parser implements`() {
        assertEquals(1, NlpCorpusFixture.corpus.contractVersion)
        assertTrue(NlpCorpusFixture.corpus.cases.size >= 91, "the corpus was cut down")
    }

    @Test
    fun `every corpus case passes in Europe_Amsterdam`() = runCorpus(TimeZone.of("Europe/Amsterdam"))

    @Test
    fun `every corpus case passes in America_New_York`() = runCorpus(TimeZone.of("America/New_York"))

    @Test
    fun `every corpus case passes in Pacific_Auckland`() = runCorpus(TimeZone.of("Pacific/Auckland"))
}
