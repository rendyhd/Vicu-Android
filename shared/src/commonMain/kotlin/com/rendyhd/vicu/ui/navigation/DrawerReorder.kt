package com.rendyhd.vicu.ui.navigation

/** The groups of rows in the drawer that can be dragged into a new order. */
internal enum class DrawerGroup(val prefix: String) {
    PROJECT("project"),
    LIST("list"),
    LABEL("label"),
}

/** A draggable row: the group it is in and its id there. */
internal data class DrawerKey(val group: DrawerGroup, val id: String)

/**
 * The key of a draggable row in the drawer's one lazy list. Every group shares the list, so a key
 * names its group; it is a string because a lazy item key must survive being saved.
 */
internal fun drawerKey(group: DrawerGroup, id: Any): String = "${group.prefix}/$id"

/** The row a lazy item [key] stands for, or null for an item that is not a draggable row. */
internal fun parseDrawerKey(key: Any?): DrawerKey? {
    val text = key as? String ?: return null
    val group = DrawerGroup.entries.firstOrNull { text.startsWith(it.prefix + "/") } ?: return null
    val id = text.substring(group.prefix.length + 1)
    return if (id.isEmpty()) null else DrawerKey(group, id)
}

/**
 * [items] with the one whose key is [from] moved into the slot of the one whose key is [to], or
 * null when either is not there or they are the same.
 */
internal fun <T> moveItem(items: List<T>, from: String, to: String, keyOf: (T) -> String): List<T>? {
    val fromIndex = items.indexOfFirst { keyOf(it) == from }
    val toIndex = items.indexOfFirst { keyOf(it) == to }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
    return items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

/**
 * [items] in the order of [ids]: the ones [ids] names first, in that order, then the rest as they
 * were. Ids that name no item are ignored.
 */
internal fun <T, K> orderedBy(items: List<T>, ids: List<K>, idOf: (T) -> K): List<T> {
    val byId = items.associateBy(idOf)
    val named = ids.distinct().mapNotNull { byId[it] }
    val namedIds = named.mapTo(HashSet(), idOf)
    return named + items.filter { idOf(it) !in namedIds }
}

/** The deepest indentation level: beyond it a project title would have no room on the sheet. */
internal const val MAX_INDENT_LEVELS = 5

/** How many indentation levels a project [depth] levels into the tree gets. */
internal fun projectIndentLevel(depth: Int): Int = depth.coerceIn(0, MAX_INDENT_LEVELS)

/** What a screen reader says for the button that opens or closes the projects below [title]. */
internal fun projectToggleDescription(title: String, expanded: Boolean): String =
    if (expanded) "Collapse $title" else "Expand $title"
