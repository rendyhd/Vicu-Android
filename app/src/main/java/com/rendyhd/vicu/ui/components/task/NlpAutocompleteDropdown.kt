package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.parser.SyntaxPrefixes

private const val MAX_SUGGESTIONS = 8

data class AutocompleteItem(
    val display: String,
    val replacement: String,
    val prefix: String,
)

@Composable
fun NlpAutocompleteDropdown(
    inputValue: String,
    cursorPosition: Int,
    prefixes: SyntaxPrefixes,
    projects: List<Project>,
    labels: List<Label>,
    enabled: Boolean,
    onSelect: (newText: String, newCursor: Int) -> Unit,
) {
    if (!enabled) return

    val suggestions by remember(inputValue, cursorPosition, prefixes, projects, labels) {
        derivedStateOf {
            computeSuggestions(inputValue, cursorPosition, prefixes, projects, labels)
        }
    }

    if (suggestions.isEmpty()) return

    Popup(
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shadowElevation = 4.dp,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
            ) {
                items(suggestions) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val (newText, newCursor) = buildReplacement(
                                    inputValue, cursorPosition, item,
                                )
                                onSelect(newText, newCursor)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = item.display,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun computeSuggestions(
    input: String,
    cursor: Int,
    prefixes: SyntaxPrefixes,
    projects: List<Project>,
    labels: List<Label>,
): List<AutocompleteItem> {
    if (input.isEmpty() || cursor <= 0) return emptyList()

    // Find the trigger prefix by scanning backwards from cursor
    val textBefore = input.substring(0, cursor.coerceAtMost(input.length))

    // Find last prefix occurrence that's at start or after whitespace
    val projectPrefix = prefixes.project
    val labelPrefix = prefixes.label

    data class TriggerMatch(val prefix: String, val prefixPos: Int, val query: String)

    fun findTrigger(prefix: String): TriggerMatch? {
        val lastIdx = textBefore.lastIndexOf(prefix)
        if (lastIdx < 0) return null
        // Must be at start or after whitespace
        if (lastIdx > 0 && !textBefore[lastIdx - 1].isWhitespace()) return null
        val query = textBefore.substring(lastIdx + prefix.length)
        // Query should not contain spaces (unless quoted, which we skip for autocomplete)
        if (query.contains(' ')) return null
        return TriggerMatch(prefix, lastIdx, query)
    }

    // Check project trigger
    val projectTrigger = findTrigger(projectPrefix)
    if (projectTrigger != null) {
        return projects
            .filter { it.title.contains(projectTrigger.query, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
            .map { project ->
                val replacement = if (project.title.contains(' ')) {
                    """${projectPrefix}"${project.title}" """
                } else {
                    "${projectPrefix}${project.title} "
                }
                AutocompleteItem(
                    display = project.title,
                    replacement = replacement,
                    prefix = projectPrefix,
                )
            }
    }

    // Check label trigger
    val labelTrigger = findTrigger(labelPrefix)
    if (labelTrigger != null) {
        return labels
            .filter { it.title.contains(labelTrigger.query, ignoreCase = true) }
            .take(MAX_SUGGESTIONS)
            .map { label ->
                val replacement = if (label.title.contains(' ')) {
                    """${labelPrefix}"${label.title}" """
                } else {
                    "${labelPrefix}${label.title} "
                }
                AutocompleteItem(
                    display = label.title,
                    replacement = replacement,
                    prefix = labelPrefix,
                )
            }
    }

    return emptyList()
}

private fun buildReplacement(
    input: String,
    cursor: Int,
    item: AutocompleteItem,
): Pair<String, Int> {
    val textBefore = input.substring(0, cursor.coerceAtMost(input.length))
    val lastIdx = textBefore.lastIndexOf(item.prefix)
    if (lastIdx < 0) return input to cursor

    val before = input.substring(0, lastIdx)
    val after = input.substring(cursor.coerceAtMost(input.length))
    val newText = before + item.replacement + after
    val newCursor = before.length + item.replacement.length
    return newText to newCursor
}
