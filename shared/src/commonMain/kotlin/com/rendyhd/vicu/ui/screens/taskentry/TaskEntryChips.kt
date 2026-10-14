package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.TokenType

// The words on the chips of the new-task sheet (design review 3.6): a chip shows the value in
// words, whether it came from the typed text or from the chip's own picker. The date phrase is
// DateDisplay's chip context and is built where the zone and the clock are known (the sheet).

/** "Low", "Medium", "High" or "Urgent"; null for no priority. */
internal fun entryPriorityName(priority: Int): String? = when (priority) {
    0 -> null
    1 -> "Low"
    2 -> "Medium"
    3 -> "High"
    4 -> "Urgent"
    else -> "P$priority"
}

/**
 * The project chip: the project that will get the task. A project the text names that does not
 * exist says so once the word is finished; while the caret is still in it ("#Per" at the end of
 * the text) the name may be unfinished, so the chip shows the project the task will go to.
 */
internal fun entryProjectChipLabel(
    fields: EntryFields,
    projectTitle: String,
    title: String,
    parsed: ParseResult?,
): String {
    val name = fields.parsedProjectName
    if (!fields.projectNotFound || name == null) return projectTitle
    val token = parsed?.tokens?.firstOrNull { it.type == TokenType.PROJECT }
    val open = token != null && title.substring(token.end.coerceIn(0, title.length)).isEmpty()
    return if (open) name else "$name (no such project)"
}

/**
 * The tags chip: the labels picked by hand and the ones the text names (each once, in that order),
 * in words. Null when there are none (the chip then says "Tags"). One or two names are listed,
 * more are counted.
 */
internal fun entryLabelsWords(pickedTitles: List<String>, parsedNames: List<String>): String? {
    val seen = HashSet<String>()
    val all = (pickedTitles + parsedNames).filter { seen.add(it.lowercase()) }
    return when {
        all.isEmpty() -> null
        all.size <= 2 -> all.joinToString(", ")
        else -> "${all.size} tags"
    }
}
