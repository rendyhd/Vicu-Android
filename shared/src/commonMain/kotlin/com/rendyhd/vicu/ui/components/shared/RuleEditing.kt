package com.rendyhd.vicu.ui.components.shared

import com.rendyhd.vicu.util.CustomListRule

/**
 * The list editor's side of a rule (cross-app semantics 3.5): the rule is saved with label and
 * project ids, as Vikunja writes it, and edited with their names. Pure, so both directions and the
 * suggestions are tested without a screen.
 */
object RuleEditing {

    /** A name and its id, for labels and projects. */
    data class Named(val id: Long, val name: String)

    private val ID_FIELDS = setOf("labels", "project", "project_id")

    private fun namesFor(field: String, labels: List<Named>, projects: List<Named>) =
        if (field == "labels") labels else projects

    private fun rewrite(text: String, replacements: List<Triple<Int, Int, String>>): String {
        var out = text
        for ((start, end, value) in replacements.sortedByDescending { it.first }) out = out.replaceRange(start, end, value)
        return out
    }

    /** [rule] (with ids) as the editor shows it: ids of known labels and projects become names. */
    fun toDisplay(rule: String?, labels: List<Named>, projects: List<Named>): String {
        if (rule.isNullOrBlank()) return ""
        val replacements = CustomListRule.terms(rule).filter { it.field in ID_FIELDS && it.op != CustomListRule.Op.LIKE }.mapNotNull { term ->
            val known = namesFor(term.field, labels, projects)
            val items = if (term.op.isList) term.items() else listOf(term.value)
            val named = items.map { item -> item.toLongOrNull()?.let { id -> known.firstOrNull { it.id == id }?.name } ?: item }
            if (named == items) return@mapNotNull null
            Triple(term.valueStart, term.valueEnd, CustomListRule.writeValue(named.joinToString(", "), list = term.op.isList))
        }
        return rewrite(rule, replacements)
    }

    /** What the editor saves, or why it cannot. */
    sealed interface Saved {
        data class Rule(val text: String?) : Saved
        data class Problem(val message: String) : Saved
    }

    /** The editor's text as the rule to save: names become ids, then the rule is checked. */
    fun fromDisplay(text: String, labels: List<Named>, projects: List<Named>): Saved {
        if (text.isBlank()) return Saved.Rule(null)
        val parsed = CustomListRule.parse(text)
        if (parsed is CustomListRule.Parsed.Error) return Saved.Problem(parsed.error.message)
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        for (term in CustomListRule.terms(text)) {
            if (term.field !in ID_FIELDS || term.op == CustomListRule.Op.LIKE) continue
            val known = namesFor(term.field, labels, projects)
            val items = if (term.op.isList) term.items() else listOf(term.value)
            if (items.all { it.toLongOrNull() != null }) continue
            val ids = items.map { item ->
                item.toLongOrNull()
                    ?: known.firstOrNull { it.name == item }?.id
                    ?: known.firstOrNull { it.name.equals(item, ignoreCase = true) }?.id
                    ?: return Saved.Problem(
                        if (term.field == "labels") "There is no label named \"$item\"." else "There is no project named \"$item\".",
                    )
            }
            replacements += Triple(term.valueStart, term.valueEnd, ids.joinToString(", "))
        }
        val rule = rewrite(text, replacements).trim()
        CustomListRule.validate(rule)?.let { return Saved.Problem(it.message) }
        return Saved.Rule(rule)
    }

    /** A suggestion: [label] to show, and the text that replaces [start] until [end]. */
    data class Suggestion(val label: String, val insert: String, val start: Int, val end: Int)

    /** The fields offered first, in the order people use them most. */
    private val COMMON_FIELDS = listOf(
        "done", "priority", "due_date", "labels", "project", "assignees", "title", "start_date", "end_date",
        "created", "updated", "done_at", "reminders", "percent_done", "description", "created_by",
    )

    private val OPERATORS = listOf("=", "!=", ">", ">=", "<", "<=", "in", "not in", "like")

    private val DATES = listOf("now", "now/d", "now/d+1d", "now/w", "now/w+1w", "now+7d", "now/M")

    private val WORD = Regex("""[A-Za-z0-9_]*$""")

    /**
     * What fits at [cursor] in [text]: fields at the start of a term, operators after a field,
     * names, dates and yes or no after an operator, and joins after a value.
     */
    fun suggest(text: String, cursor: Int, labels: List<Named>, projects: List<Named>, limit: Int = 8): List<Suggestion> {
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at)
        // The term being typed starts after the last join or opening parenthesis.
        val termStart = maxOf(before.lastIndexOf("&&"), before.lastIndexOf("||"), before.lastIndexOf('(')).let {
            when {
                it < 0 -> 0
                before.startsWith("(", it) -> it + 1
                else -> it + 2
            }
        }
        val term = before.substring(termStart)
        val field = Regex("""^\s*([A-Za-z0-9_]+)""").find(term)?.groupValues?.get(1)
        val fieldEnd = Regex("""^\s*[A-Za-z0-9_]+""").find(term)?.range?.last?.plus(1 + termStart)
        val afterField = if (fieldEnd != null) text.substring(fieldEnd, at) else ""

        fun replacingWord(options: List<String>, spaceAfter: Boolean): List<Suggestion> {
            val word = WORD.find(before)?.value.orEmpty()
            val start = at - word.length
            return options.filter { it.startsWith(word, ignoreCase = true) && it != word }
                .map { Suggestion(it, if (spaceAfter) "$it " else it, start, at) }
        }

        // Nothing typed in this term yet, or the field name half typed.
        if (field == null || (afterField.isEmpty() && term.trimEnd() == term)) {
            return replacingWord(COMMON_FIELDS, spaceAfter = true).take(limit)
        }
        val kind = CustomListRule.FIELDS[field] ?: return emptyList()
        val opMatch = Regex("""^\s*(not\s+in|like|in|>=|<=|!=|=|>|<)""").find(afterField)
        if (opMatch == null) {
            return OPERATORS.filter { op ->
                when (kind) {
                    CustomListRule.Kind.TEXT, CustomListRule.Kind.USERS, CustomListRule.Kind.CREATOR -> true
                    else -> op != "like"
                }
            }.map { Suggestion(it, "$it ", at, at) }.take(limit)
        }
        val valueText = afterField.substring(opMatch.range.last + 1)
        val typingValue = valueText.isBlank() || !valueText.endsWith(" ")
        if (typingValue) {
            // In the value: what goes there for this field.
            val item = valueText.substringAfterLast(',').trimStart()
            val start = at - item.length
            val options = when (kind) {
                CustomListRule.Kind.BOOL -> listOf("true", "false")
                CustomListRule.Kind.DATE, CustomListRule.Kind.REMINDERS -> DATES
                CustomListRule.Kind.LABELS -> labels.map { it.name }
                CustomListRule.Kind.WHOLE -> if (field == "project" || field == "project_id") projects.map { it.name } else emptyList()
                else -> emptyList()
            }
            val values = options.filter { it.startsWith(item, ignoreCase = true) && it != item }
                .map { Suggestion(it, it, start, at) }
            if (values.isNotEmpty() || item.isEmpty()) return values.take(limit)
        }
        // After a value: a join.
        val gap = if (before.endsWith(" ")) "" else " "
        return listOf("&&", "||").map { Suggestion(it, "$gap$it ", at, at) }
    }
}
