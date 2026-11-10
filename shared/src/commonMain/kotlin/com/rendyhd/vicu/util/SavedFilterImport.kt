package com.rendyhd.vicu.util

import com.rendyhd.vicu.data.remote.api.SavedFilterDto
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.ListSortKey

/**
 * How a Vikunja saved filter becomes a custom list (docs/cross-app-semantics-v1.md, section 3.7).
 * Desktop has the same in `src/shared/saved-filter-import.ts`; both run the `savedFilterImport`
 * vectors.
 */
object SavedFilterImport {

    /** The pseudo-project id Vikunja gives saved filter [filterId], and back. */
    fun projectIdOf(filterId: Long): Long = -(filterId + 1)

    /** The saved filter a pseudo-project stands for, or null for a real project or Favorites (-1). */
    fun filterIdOf(projectId: Long): Long? = if (projectId <= -2) -projectId - 1 else null

    enum class Status { EXACT, CHANGED, UNREADABLE }

    /** Why an import is not exact, in the contract's order. */
    enum class Reason(val wire: String) {
        KANBAN_COLUMN("kanban_column"),
        OTHER_FIELD("other_field"),
        MANUAL_ORDER("manual_order"),
        SORT_KEY("sort_key"),
    }

    /** The list a filter becomes ([list] is null when it is unreadable), and how it went. */
    data class Result(val list: CustomList?, val status: Status, val reasons: List<Reason>)

    /** The icon an imported list gets. */
    const val ICON = "filter_list"

    /** [filter] as a custom list with the id [listId]. */
    fun convert(filter: SavedFilterDto, listId: String): Result {
        val reasons = mutableSetOf<Reason>()
        val query = filter.filters
        val text = query.filter.trim().ifEmpty {
            query.s.takeIf { it.isNotBlank() }?.let { search ->
                val value = CustomListRule.writeValue(search, list = false)
                "title like $value || description like $value"
            } ?: ""
        }

        var rule = text
        when (val parsed = CustomListRule.parse(text)) {
            is CustomListRule.Parsed.Error -> return Result(null, Status.UNREADABLE, emptyList())
            is CustomListRule.Parsed.Ok -> {
                CustomListRule.withoutTerms(parsed.root, CustomListRule.SERVER_ONLY_FIELDS)?.let { (root, removed) ->
                    if ("bucket_id" in removed) reasons += Reason.KANBAN_COLUMN
                    if ("position" in removed || "uid" in removed) reasons += Reason.OTHER_FIELD
                    rule = CustomListRule.write(root)
                }
            }
        }
        if (CustomListRule.validate(rule) != null) return Result(null, Status.UNREADABLE, emptyList())

        val keys = mutableListOf<ListSortKey>()
        query.sortBy.orEmpty().forEachIndexed { index, key ->
            val order = query.orderBy?.getOrNull(index)?.takeIf { it == "desc" } ?: "asc"
            when {
                key == "position" -> reasons += Reason.MANUAL_ORDER
                key !in CustomListFilterBuilder.SORT_KEYS -> reasons += Reason.SORT_KEY
                else -> keys += ListSortKey(key, order)
            }
        }
        val first = keys.firstOrNull() ?: ListSortKey("due_date", "asc")

        val list = CustomList(
            id = listId,
            name = filter.title.trim().ifEmpty { "Saved filter ${filter.id}" },
            icon = ICON,
            filter = CustomListFilter(
                includeDone = true,
                sortBy = first.sortBy,
                orderBy = first.orderBy,
                thenBy = keys.drop(1),
                rule = rule.takeIf { it.isNotBlank() },
                ruleIncludeNulls = query.filterIncludeNulls,
            ),
            savedFilterId = filter.id,
        )
        val ordered = Reason.entries.filter { it in reasons }
        return Result(list, if (ordered.isEmpty()) Status.EXACT else Status.CHANGED, ordered)
    }
}
