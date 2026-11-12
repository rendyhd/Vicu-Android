package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DueDates
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The daily focus block (docs/cross-app-semantics-v1.md section 21.11), the same as the desktop's
 * `src/shared/pro/focus-block.ts`: one 60 to 90 minute block a day for the work that moves the top
 * initiatives. Plan my day proposes it first.
 */
object FocusBlock {
    const val MIN_MINUTES = 60
    const val MAX_MINUTES = 90
    const val CANDIDATES = 5

    /**
     * Candidates: available tasks in ranked initiatives (the top rank first), then flagged ones
     * outside them; within that, flagged first, then due day (none last), then priority (high first),
     * then id. At most five. [initiativeRank] is the rank of a project's initiative, or null.
     */
    fun candidates(tasks: List<Task>, zone: TimeZone, initiativeRank: (Long) -> Int?): List<Long> {
        class Entry(val task: Task, val rank: Int?, val due: LocalDate?)

        val eligible = tasks
            .map { Entry(it, initiativeRank(it.projectId), DueDates.localDateOf(it.dueDate, zone)) }
            .filter { it.rank != null || it.task.isFavorite }
        val sorted = eligible.sortedWith(
            Comparator { a, b ->
                val rankA = a.rank ?: Int.MAX_VALUE
                val rankB = b.rank ?: Int.MAX_VALUE
                if (rankA != rankB) return@Comparator rankA.compareTo(rankB)
                val flagA = if (a.task.isFavorite) 0 else 1
                val flagB = if (b.task.isFavorite) 0 else 1
                if (flagA != flagB) return@Comparator flagA.compareTo(flagB)
                if (a.due != b.due) {
                    return@Comparator when {
                        a.due == null -> 1
                        b.due == null -> -1
                        a.due < b.due -> -1
                        else -> 1
                    }
                }
                val priority = b.task.priority.compareTo(a.task.priority)
                if (priority != 0) priority else a.task.id.compareTo(b.task.id)
            },
        )
        return sorted.take(CANDIDATES).map { it.task.id }
    }

    /** The block's length: the candidates' estimates added up, held between 60 and 90 minutes (60 without any). */
    fun minutes(estimates: List<Int?>): Int {
        val total = estimates.sumOf { if (it != null && it > 0) it else 0 }
        if (total <= 0) return MIN_MINUTES
        return min(MAX_MINUTES, max(MIN_MINUTES, ceil(total / 15.0).toInt() * 15))
    }
}
