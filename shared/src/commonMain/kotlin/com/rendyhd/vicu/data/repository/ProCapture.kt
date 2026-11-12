package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person
import com.rendyhd.vicu.util.pro.ProLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What quick add takes from Pro (docs/cross-app-semantics-v1.md sections 5.5 and 21): whether the
 * Pro phrases are read, and the people `Jan:` may name. [OFF] is today's Vicu: the parser reads the
 * text as before and nothing below writes a field.
 */
data class ProCapture(val on: Boolean = false, val people: List<Person> = emptyList()) {

    /** [base] with Pro's phrases on while Pro is on; unchanged otherwise. */
    fun config(base: ParserConfig): ParserConfig = if (on && !base.pro) base.copy(pro = true) else base

    /** The people the parser may match; none while Pro is off. */
    val parserPeople: List<Person> get() = if (on) people else emptyList()

    /**
     * The lone start date `starts monday` gives, as stored (section 21.3): that local midnight, or
     * the moment named. Null while Pro is off, without the phrase, or when a block holds the start.
     */
    fun startDateOf(result: ParseResult?, zone: TimeZone): String? {
        if (!on || result == null || result.block != null) return null
        return result.start?.toInstant(zone)?.toString()
    }

    /** `!flag` read while Pro is on: the task is flagged (`is_favorite`, section 21.4). */
    fun flagOf(result: ParseResult?): Boolean = on && result?.flag == true

    /** The person label `Jan:` named (section 21.5), while Pro is on and the label is still a person. */
    fun personLabelOf(result: ParseResult?): Long? {
        if (!on) return null
        val id = result?.person?.id ?: return null
        return id.takeIf { wanted -> people.any { it.id == wanted } }
    }

    /**
     * The promise `Jan:` (or `for Jan:`) makes of the new task (section 21.6), with what the entry
     * sheet's fields add: their words and how you will see it is done. Null while Pro is off,
     * without a person, or when the label is no longer a person.
     */
    fun promiseOf(result: ParseResult?, words: String? = null, doneWhen: String? = null): JsonObject? {
        val person = personLabelOf(result) ?: return null
        val mine = result?.person?.mine == true
        val fields = LinkedHashMap<String, JsonElement>()
        fields["dir"] = JsonPrimitive(if (mine) "mine" else "theirs")
        fields["person"] = JsonPrimitive(person)
        words?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["words"] = JsonPrimitive(it) }
        doneWhen?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["done_when"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    /** `~45m` read while Pro is on: the estimate in minutes (section 21.2, kept in the ledger). */
    fun estimateOf(result: ParseResult?): Int? = if (on) result?.estimate?.takeIf { it in 1..1440 } else null

    /**
     * A new task with what the text said through Pro's phrases: the start date unless the task has
     * a block (a block replaces a start date, decision 17), and the flag. The person's label is added
     * by the caller with the other labels ([personLabelOf]).
     */
    fun applyToNew(task: Task, result: ParseResult?, zone: TimeZone): Task {
        if (!on || result == null) return task
        var next = task
        val start = startDateOf(result, zone)
        // A block (chosen with a chip or by `for 30m`) already holds the start.
        if (start != null && DateUtils.parseIsoDate(task.startDate.ifBlank { null }) == null) {
            next = next.copy(startDate = start)
        }
        if (flagOf(result)) next = next.copy(isFavorite = true)
        return next
    }

    companion object {
        val OFF = ProCapture()
    }
}

/**
 * [ProCapture] as the device's settings make it: on while Pro is on here, with the labels marked as
 * a person (and their aliases from the ledger) while the promises layer is on. Off, it reads no
 * label, preference or ledger at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProCaptureSource(
    behaviorPrefs: BehaviorPrefsStore,
    private val proPreferences: ProPreferences,
    private val labelRepository: LabelRepository,
    private val proLedger: ProLedgerRepository,
) {
    private data class Switches(val on: Boolean, val people: Boolean)

    val capture: Flow<ProCapture> = behaviorPrefs.getPrefs()
        .map { Switches(it.isProEnabled(), it.isProEnabled(ProLayer.PROMISES)) }
        .distinctUntilChanged()
        .flatMapLatest { switches ->
            when {
                !switches.on -> flowOf(ProCapture.OFF)
                !switches.people -> flowOf(ProCapture(on = true))
                else -> combine(proPreferences.snapshot, labelRepository.getAll(), proLedger.ledger) { prefs, labels, ledger ->
                    ProCapture(
                        on = true,
                        people = People.peopleWithAliases(labels.map { it.id to it.title }, prefs::isPerson, ledger),
                    )
                }
            }
        }
        .distinctUntilChanged()
}
