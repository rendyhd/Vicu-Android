package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.CustomListFilter
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomListFilterBuilderTest {

    @Test
    fun `today filter has a lower bound so overdue is excluded`() {
        // Regression: "today"/"this_week" previously only set an upper bound, so overdue
        // tasks leaked into the list.
        val filter = CustomListFilter(dueDateFilter = "today")
        val str = CustomListFilterBuilder.buildFilterString(filter)
        assertTrue("expected a due_date >= lower bound, got: $str", str.contains("due_date >="))
        assertTrue("expected a due_date <= upper bound, got: $str", str.contains("due_date <="))
    }

    @Test
    fun `this_week filter has a lower bound`() {
        val str = CustomListFilterBuilder.buildFilterString(CustomListFilter(dueDateFilter = "this_week"))
        assertTrue("expected a lower bound, got: $str", str.contains("due_date >="))
    }

    @Test
    fun `all filter has no due_date constraint`() {
        val str = CustomListFilterBuilder.buildFilterString(CustomListFilter(dueDateFilter = "all"))
        assertTrue("'all' should not constrain due_date, got: $str", !str.contains("due_date"))
    }
}
