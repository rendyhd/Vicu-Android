package com.rendyhd.vicu.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The order a person dragged a task's subtasks into (docs/cross-app-semantics-v1.md section 19), a
 * synced preference of the account: `subtask_order.<taskId>` holds subtask ids.
 */
object SubtaskOrder {
    /** The ids [value] holds; anything else acts as no order. */
    fun read(value: JsonElement?): List<Long> =
        (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull }.orEmpty()

    /** Ids listed first, in the listed order; the others follow as they came. */
    fun <T> sorted(items: List<T>, order: List<Long>, id: (T) -> Long): List<T> {
        if (order.isEmpty()) return items
        val place = order.withIndex().associate { (index, value) -> value to index }
        return items.sortedBy { place[id(it)] ?: Int.MAX_VALUE }
    }
}
