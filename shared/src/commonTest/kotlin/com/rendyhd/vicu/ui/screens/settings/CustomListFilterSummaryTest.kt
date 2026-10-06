package com.rendyhd.vicu.ui.screens.settings

import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one-line summary under a custom list in Settings says how the list is filtered. */
class CustomListFilterSummaryTest {

    private fun summary(filter: CustomListFilter) = buildFilterSummary(CustomList(id = "a", name = "A", filter = filter))

    @Test
    fun `a list without conditions has no summary`() {
        assertEquals("", summary(CustomListFilter()))
    }

    @Test
    fun `a window that includes overdue tasks says only the window`() {
        assertEquals("today", summary(CustomListFilter(dueDateFilter = "today")))
        assertEquals("today", summary(CustomListFilter(dueDateFilter = "today", includeOverdue = true)))
    }

    @Test
    fun `excluded overdue tasks are mentioned for the windows the switch applies to`() {
        assertEquals("today · excl. overdue", summary(CustomListFilter(dueDateFilter = "today", includeOverdue = false)))
        assertEquals(
            "this week · excl. overdue",
            summary(CustomListFilter(dueDateFilter = "this_week", includeOverdue = false)),
        )
        assertEquals(
            "this month · excl. overdue",
            summary(CustomListFilter(dueDateFilter = "this_month", includeOverdue = false)),
        )
    }

    @Test
    fun `the switch is not mentioned where it has no effect`() {
        for (window in listOf("all", "overdue", "has_due_date", "no_due_date")) {
            val expected = if (window == "all") "" else window.replace("_", " ")
            assertEquals(expected, summary(CustomListFilter(dueDateFilter = window, includeOverdue = false)), window)
        }
    }

    @Test
    fun `the overdue note sits with the window among the other conditions`() {
        val filter = CustomListFilter(
            dueDateFilter = "this_week",
            includeOverdue = false,
            projectIds = listOf(1L, 2L),
            labelIds = listOf(3L),
            includeDone = true,
        )
        assertEquals("this week · excl. overdue · 2 project(s) · 1 label(s) · incl. done", summary(filter))
    }
}
