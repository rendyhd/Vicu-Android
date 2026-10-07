package com.rendyhd.vicu.ui.components.shared

import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.toWire
import com.rendyhd.vicu.util.CustomListFilterBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Include overdue tasks" switch of the custom-list editor: on by default, shown only for the
 * windows it affects, and written as an absent key unless the user turned it off.
 */
class CustomListEditorTest {

    @Test
    fun `a list without the key, or with true, opens with the switch on`() {
        assertTrue(includeOverdueSwitchOn(null))
        assertTrue(includeOverdueSwitchOn(true))
        assertFalse(includeOverdueSwitchOn(false))
    }

    @Test
    fun `the switch writes an absent key when on and false when off`() {
        assertNull(includeOverdueToStore(switchOn = true))
        assertEquals(false, includeOverdueToStore(switchOn = false))
    }

    @Test
    fun `saving a list keeps the key absent unless the user turned overdue off`() {
        fun saved(switchOn: Boolean) = CustomList(
            id = "a",
            name = "A",
            filter = CustomListFilter(dueDateFilter = "today", includeOverdue = includeOverdueToStore(switchOn)),
        ).toWire().filter.includeOverdue

        assertNull(saved(switchOn = true))
        assertEquals(false, saved(switchOn = false))
    }

    @Test
    fun `the editor offers every sort the evaluator understands`() {
        assertEquals(CustomListFilterBuilder.SORT_KEYS, SORT_BY_OPTIONS.map { it.first })
        assertEquals(SORT_BY_OPTIONS.size, SORT_BY_OPTIONS.map { it.second }.toSet().size, "labels are distinct")
    }

    @Test
    fun `every offered sort really sorts and is not the fallback`() {
        // Every field grows with the id, so an ascending sort by any key gives 1, 2, 3 while the
        // fallback for an unknown key (updated, newest first) would give 3, 2, 1.
        val tasks = (3L downTo 1L).map { n ->
            Task(
                id = n,
                title = "task ${'a' + (n - 1).toInt()}",
                dueDate = "2026-06-0${n}T10:00:00Z",
                created = "2026-05-0${n}T10:00:00Z",
                updated = "2026-04-0${n}T10:00:00Z",
                doneAt = "2026-03-0${n}T10:00:00Z",
                priority = n.toInt(),
                position = n.toDouble(),
            )
        }
        for ((key, _) in SORT_BY_OPTIONS) {
            assertEquals(listOf(1L, 2L, 3L), CustomListFilterBuilder.sortTasks(tasks, key, "asc").map { it.id }, key)
        }
    }

    @Test
    fun `the switch is shown for today, this week and this month only`() {
        for (window in listOf("today", "this_week", "this_month")) {
            assertTrue(CustomListFilterBuilder.windowHonorsIncludeOverdue(window), window)
        }
        for (window in listOf("all", "overdue", "has_due_date", "no_due_date", "something_new")) {
            assertFalse(CustomListFilterBuilder.windowHonorsIncludeOverdue(window), window)
        }
    }
}
