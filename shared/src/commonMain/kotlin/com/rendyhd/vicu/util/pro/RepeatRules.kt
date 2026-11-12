package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What completing a repeat does to the stored rule. */
sealed interface RuleChange {
    /** The rule stays as it is. */
    data object Unchanged : RuleChange

    /** The rule is removed (the repeat ended). */
    data object Removed : RuleChange

    data class Set(val rule: RepeatRule) : RuleChange
}

class CompletionPlan(
    /** Merge patches to send, in order; the last is always `{ "done": true }`. */
    val patches: List<JsonObject>,
    val rule: RuleChange,
    /** After the server answers, run [RepeatRules.catchUpPatch] on the task it returns. */
    val catchUp: Boolean,
)

/**
 * Repeat rules (docs/cross-app-semantics-v1.md section 21.13), the same as the desktop's
 * `src/shared/pro/repeat-rule.ts`: an end (a day or a number of times) and catching up, beside
 * Vikunja's own repeat. Completing stays `{ "done": true }`; at the end the repeat is turned off
 * first, so the server completes the task instead of moving it.
 */
object RepeatRules {
    private val DONE = JsonObject(mapOf("done" to JsonPrimitive(true)))
    private val REPEAT_OFF = JsonObject(mapOf("repeat_after" to JsonPrimitive(0), "repeat_mode" to JsonPrimitive(0)))

    /** Whether Vikunja repeats the task: an interval, or monthly (mode 1). */
    fun repeats(task: Task): Boolean = task.repeatMode == 1 || task.repeatAfter > 0

    /** One month later in local time, the way JavaScript's `Date` does it (day 31 rolls into the next month). */
    private fun addMonthLikeJs(due: Instant, zone: TimeZone): Instant {
        val local = due.toLocalDateTime(zone)
        val total = local.year * 12 + (local.month.ordinal) + 1
        val first = LocalDate(total / 12, total % 12 + 1, 1)
        val date = first.plus(local.day - 1, DateTimeUnit.DAY)
        return LocalDateTime(date, LocalTime(local.hour, local.minute, local.second)).toInstant(zone)
    }

    private fun nextDue(due: Instant?, repeatAfter: Long, repeatMode: Int, now: Instant, zone: TimeZone): Instant? {
        if (repeatMode == 2) return if (repeatAfter > 0) now + repeatAfter.seconds else null
        if (due == null) return null
        if (repeatMode == 1) return addMonthLikeJs(due, zone)
        if (repeatAfter > 0) return due + repeatAfter.seconds
        return null
    }

    /**
     * Vikunja's next due date after a completion at [now]: mode 0 adds the interval, 1 a month, 2
     * counts from now.
     */
    fun nextDue(task: Task, now: Instant, zone: TimeZone): Instant? =
        nextDue(instantOf(task.dueDate), task.repeatAfter, task.repeatMode, now, zone)

    /**
     * What completing a repeating task with a rule sends. The end is reached when this occurrence is
     * the last one (`count` 1) or the next due day is after `until`: the repeat goes off first, then
     * the completion, and the rule is removed. Otherwise the completion, the count lowered by one, and
     * catching up when the rule says so (not for mode 2, which already counts from the completion).
     */
    fun completionPlan(task: Task, rule: RepeatRule?, now: Instant, zone: TimeZone): CompletionPlan {
        if (rule == null || !repeats(task)) return CompletionPlan(listOf(DONE), RuleChange.Unchanged, false)
        val next = nextDue(task, now, zone)
        val until = rule.until
        val ends = rule.count == 1 ||
            (until != null && next != null && next.toLocalDateTime(zone).date.toString() > until)
        if (ends) return CompletionPlan(listOf(REPEAT_OFF, DONE), RuleChange.Removed, false)
        val count = rule.count
        val change = if (count != null) RuleChange.Set(rule.copy(count = count - 1)) else RuleChange.Unchanged
        return CompletionPlan(listOf(DONE), change, rule.catchUp && task.repeatMode != 2)
    }

    /**
     * Catching up, after the server moved the task: while its due day is before [today], move it by
     * the interval (mode 0) or a month (mode 1) again; the start and end move by the same amount.
     * Null when nothing moves. Stops at the rule's [until]: the patch is then null and the task stays
     * as it is. Values are UTC instants.
     */
    fun catchUpPatch(task: Task, today: LocalDate, until: String?, zone: TimeZone): JsonObject? {
        val due = instantOf(task.dueDate)
        if (due == null || !repeats(task) || task.repeatMode == 2) return null
        var moved: Instant = due
        var guard = 0
        while (moved.toLocalDateTime(zone).date < today && guard++ < 1000) {
            moved = nextDue(moved, task.repeatAfter, task.repeatMode, moved, zone) ?: return null
        }
        if (moved == due) return null
        if (until != null && moved.toLocalDateTime(zone).date.toString() > until) return null
        val delta = moved - due
        val patch = LinkedHashMap<String, JsonElement>()
        patch["due_date"] = JsonPrimitive(moved.toString())
        instantOf(task.startDate)?.let { patch["start_date"] = JsonPrimitive((it + delta).toString()) }
        instantOf(task.endDate)?.let { patch["end_date"] = JsonPrimitive((it + delta).toString()) }
        return JsonObject(patch)
    }
}
