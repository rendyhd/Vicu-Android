package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DailySummary
import com.rendyhd.vicu.util.TimeBlocks

/** What a daily summary notification contains. */
data class DailySummaryContent(
    val counts: DailySummary.Counts,
    /** Up to a few titles of tasks due today (not overdue), earliest first. */
    val dueTodayTitles: List<String>,
) {
    /**
     * The lines of the expanded notification: today's titles, how many more are due today, then
     * the overdue and tomorrow counts.
     */
    fun lines(): List<String> = buildList {
        addAll(dueTodayTitles)
        val remaining = counts.dueToday - dueTodayTitles.size
        if (remaining > 0) add("+$remaining more today")
        if (counts.overdue > 0) add("${counts.overdue} overdue tasks need attention")
        if (counts.dueTomorrow > 0) add("${counts.dueTomorrow} due tomorrow")
    }
}

/** Reads the counts of the daily summary from the local database. */
class DailySummaryReader(private val taskDao: TaskDao) {

    suspend fun read(
        day: ClockDay,
        includeOverdue: Boolean,
        includeDueToday: Boolean,
        includeTomorrow: Boolean,
    ): DailySummaryContent {
        val b = DailySummary.boundaries(day.date, day.zone)
        // A block starting today is on the Today list whatever its due date (contract section 2).
        val blocksToday = if (includeDueToday) {
            taskDao.getStartingTodayWithoutDueToday(b.startOfToday, b.startOfTomorrow)
                .filter { TimeBlocks.isBlock(it.startDate, it.endDate, day.zone) }
        } else {
            emptyList()
        }
        val counts = DailySummary.Counts(
            overdue = if (includeOverdue) taskDao.countOverdue(b.startOfToday) else 0,
            dueToday = if (includeDueToday) taskDao.countDueToday(b.startOfToday, b.startOfTomorrow) + blocksToday.size else 0,
            dueTomorrow = if (includeTomorrow) taskDao.countDueTomorrow(b.startOfTomorrow, b.startOfDayAfterTomorrow) else 0,
        )
        val titles = if (counts.dueToday > 0) {
            val due = taskDao.getDueTodaySync(b.startOfToday, b.startOfTomorrow, TITLE_LIMIT)
            (due.map { it.title } + blocksToday.map { it.title }).take(TITLE_LIMIT)
        } else {
            emptyList()
        }
        return DailySummaryContent(counts, titles)
    }

    companion object {
        const val TITLE_LIMIT = 3
    }
}
