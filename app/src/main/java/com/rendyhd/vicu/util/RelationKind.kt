package com.rendyhd.vicu.util

/** Vikunja relation_kind wire values + display helpers. */
object RelationKind {
    const val SUBTASK = "subtask"
    const val PARENTTASK = "parenttask"
    const val RELATED = "related"
    const val DUPLICATEOF = "duplicateof"
    const val DUPLICATES = "duplicates"
    const val BLOCKING = "blocking"
    const val BLOCKED = "blocked"
    const val PRECEDES = "precedes" // wire value has ONE 'e' despite Go varname
    const val FOLLOWS = "follows"
    const val COPIEDFROM = "copiedfrom"
    const val COPIEDTO = "copiedto"

    /** Kinds offered when adding a relation. subtask has its own dedicated section. */
    val SELECTABLE = listOf(RELATED, BLOCKING, BLOCKED, DUPLICATEOF, DUPLICATES, PRECEDES, FOLLOWS)

    /** Kinds rendered in the generic Relations section (parenttask shown read-only). */
    val DISPLAYABLE = listOf(
        PARENTTASK, RELATED, BLOCKING, BLOCKED, DUPLICATEOF, DUPLICATES,
        PRECEDES, FOLLOWS, COPIEDFROM, COPIEDTO,
    )

    fun label(kind: String): String = when (kind) {
        SUBTASK -> "Subtask"
        PARENTTASK -> "Parent task"
        RELATED -> "Related"
        DUPLICATEOF -> "Duplicate of"
        DUPLICATES -> "Duplicates"
        BLOCKING -> "Blocking"
        BLOCKED -> "Blocked by"
        PRECEDES -> "Precedes"
        FOLLOWS -> "Follows"
        COPIEDFROM -> "Copied from"
        COPIEDTO -> "Copied to"
        else -> kind.replaceFirstChar { it.uppercase() }
    }
}
