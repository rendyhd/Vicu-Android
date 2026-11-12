package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.TimeBlocks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

enum class ProWeeklyStepId(val key: String, val title: String) {
    VERDICTS("verdicts", "Give verdicts"),
    UNSENT("unsent", "Send what was agreed"),
    ESCALATIONS("escalations", "Escalations and decisions"),
    PORTFOLIO("portfolio", "Check the portfolio"),
    VALUE("value", "Note the value delivered"),
}

data class ProWeeklyStep(val id: ProWeeklyStepId, val title: String, val count: Int)

data class WeeklyMirror(
    /** Promises of others you finished yourself ("taken over") in the last 7 days. */
    val rescues: Int,
    /** Workdays (Monday to Friday) of the last 7 days with a focus block done. */
    val focusDays: Int,
    val workdays: Int,
    /** Your open tasks (not someone else's promise). */
    val youHold: Int,
    /** Open promises others made to you. */
    val othersHold: Int,
    /** Open "Confirm with" tasks. */
    val unconfirmed: Int,
)

/**
 * The weekly execution review (docs/cross-app-semantics-v1.md section 21.11), the same as the
 * desktop's `src/shared/pro/weekly.ts`: Pro's steps before the usual weekly review (section 14.7)
 * and the mirror line.
 */
object Weekly {
    private fun truthy(value: JsonElement?): Boolean = when {
        value == null || value is JsonNull -> false
        value is JsonPrimitive && value.isString -> value.content.isNotEmpty()
        value is JsonPrimitive -> value.content != "false" && value.content != "0"
        else -> true
    }

    /**
     * The steps, in this order, for the layers that are on: promises (verdicts due, promises not sent
     * yet), portfolio (escalations drafted or accepted plus open owed decisions; initiatives not
     * ready, over the cap or stalled; initiatives closed in the last 7 days without a value entry). A
     * step with nothing to do is still listed with 0, so the review has the same shape each week.
     * [stalledInitiatives] is the active initiatives with no available task.
     */
    fun proWeeklySteps(
        tasks: List<Task>,
        ledger: ProLedger?,
        layers: ProLayers,
        today: LocalDate,
        stalledInitiatives: Int,
        zone: TimeZone,
    ): List<ProWeeklyStep> {
        val steps = mutableListOf<ProWeeklyStep>()
        fun step(id: ProWeeklyStepId, count: Int) = steps.add(ProWeeklyStep(id, id.title, count))
        if (layers.promises) {
            step(ProWeeklyStepId.VERDICTS, tasks.count { Promises.needsVerdict(it, ledger?.value("promises", it.id), today, null, zone) })
            step(ProWeeklyStepId.UNSENT, Promises.unsentPromises(tasks, ledger).size)
        }
        if (layers.portfolio) {
            val escalations = ledger?.values("escalations").orEmpty().values.count {
                it.text("status") == "drafted" || it.text("status") == "accepted"
            }
            val owed = ledger?.values("owed").orEmpty().values.count { !truthy(it["resolved"]) }
            step(ProWeeklyStepId.ESCALATIONS, escalations + owed)
            val ranked = Portfolio.rankedInitiatives(ledger)
            val cap = Portfolio.capState(ledger)
            val notReady = ranked.count { !Portfolio.readiness(it.value).ready }
            step(ProWeeklyStepId.PORTFOLIO, notReady + (if (cap.active > cap.cap) 1 else 0) + stalledInitiatives)
            val weekStart = today.plus(-6, DateTimeUnit.DAY).toString()
            val last = today.toString()
            var closedWithoutValue = 0
            for ((id, value) in ledger?.values("initiatives").orEmpty()) {
                val closedOn = value.text("closed_on")
                if (value.text("status") != "closed" || closedOn.isNullOrEmpty() || closedOn < weekStart || closedOn > last) continue
                val hasValue = ledger?.values("log").orEmpty().values.any {
                    it.text("kind") == "value" && it.whole("initiative") == id.toLongOrNull()
                }
                if (!hasValue) closedWithoutValue++
            }
            step(ProWeeklyStepId.VALUE, closedWithoutValue)
        }
        return steps
    }

    // --- The mirror ------------------------------------------------------------------------

    /**
     * A day had a focus block when a task in a ranked initiative had a block of at least 60 minutes
     * on that day and is done.
     */
    fun weeklyMirror(tasks: List<Task>, ledger: ProLedger?, today: LocalDate, zone: TimeZone): WeeklyMirror {
        val from = today.plus(-6, DateTimeUnit.DAY)
        val ranked = Portfolio.rankedInitiatives(ledger).map { it.projectId }.toSet()
        var rescues = 0
        for (promise in ledger?.values("promises").orEmpty().values) {
            val final = LedgerRules.finalVerdictOf(promise)
            if (final?.text("kind") != "taken_over") continue
            val day = localDayOf(final.text("at"), zone) ?: continue
            if (day >= from && day <= today) rescues++
        }
        val focusDays = mutableSetOf<LocalDate>()
        var workdays = 0
        var day = from
        while (day <= today) {
            if (day.dayOfWeek.isoDayNumber <= 5) workdays++
            day = day.plus(1, DateTimeUnit.DAY)
        }
        var youHold = 0
        var othersHold = 0
        var unconfirmed = 0
        for (task in tasks) {
            val block = TimeBlocks.blockOf(task, zone)
            if (task.done && block != null && block.minutes >= 60 && task.projectId in ranked &&
                block.day >= from && block.day <= today && block.day.dayOfWeek.isoDayNumber <= 5
            ) {
                focusDays.add(block.day)
            }
            if (task.done) continue
            val promise = ledger?.value("promises", task.id)
            val theirs = promise?.text("dir") == "theirs" && LedgerRules.finalVerdictOf(promise) == null
            if (theirs) othersHold++ else youHold++
            if (task.title.isNotEmpty() && Promises.readConfirmTitle(task.title) != null) unconfirmed++
        }
        return WeeklyMirror(rescues, focusDays.size, workdays, youHold, othersHold, unconfirmed)
    }

    private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /** The mirror in plain words. */
    fun mirrorText(mirror: WeeklyMirror): String {
        val rescues = if (mirror.rescues == 0) "took over no promises" else "took over ${plural(mirror.rescues, "promise", "promises")}"
        val parts = mutableListOf(
            "Last 7 days: you $rescues and had a focus block on ${mirror.focusDays} of ${plural(mirror.workdays, "workday", "workdays")}.",
            "You hold ${plural(mirror.youHold, "open task", "open tasks")}; others hold ${plural(mirror.othersHold, "promise", "promises")} to you.",
        )
        if (mirror.unconfirmed != 0) parts.add("${mirror.unconfirmed} still to confirm.")
        return parts.joinToString(" ")
    }
}
