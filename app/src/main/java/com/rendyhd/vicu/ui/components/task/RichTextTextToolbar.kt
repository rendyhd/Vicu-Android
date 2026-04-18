package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/**
 * Formatting bar shown beneath the description editor when it's focused.
 * Vikunja stores descriptions as HTML; each button wraps the current selection
 * (or inserts an empty tag pair at the cursor) with the matching tag, matching
 * the seven actions from the desktop TipTap bubble menu:
 *
 *   Bold · Italic · Strike · Code · Bullet list · Numbered list · Link
 *
 * While editing on mobile you'll see the raw tags (`<strong>hello</strong>`);
 * on desktop and in any read-only view they render as formatted HTML.
 */
@Composable
fun RichTextFormatBar(
    state: TextFieldState,
    modifier: Modifier = Modifier,
) {
    var showLinkDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            FormatButton(Icons.Default.FormatBold, "Bold") { wrapSelection(state, "<strong>", "</strong>") }
            FormatButton(Icons.Default.FormatItalic, "Italic") { wrapSelection(state, "<em>", "</em>") }
            FormatButton(Icons.Default.StrikethroughS, "Strikethrough") { wrapSelection(state, "<s>", "</s>") }
            FormatButton(Icons.Default.Code, "Code") { wrapSelection(state, "<code>", "</code>") }
            FormatButton(Icons.Default.FormatListBulleted, "Bullet list") { insertList(state, ordered = false) }
            FormatButton(Icons.Default.FormatListNumbered, "Numbered list") { insertList(state, ordered = true) }
            FormatButton(Icons.Default.Link, "Link") { showLinkDialog = true }
        }
    }

    if (showLinkDialog) {
        val sel = state.selection
        val selectedText = state.text.toString().substring(sel.min, sel.max)
        LinkInputDialog(
            initialDisplay = selectedText,
            onConfirm = { url, display ->
                val linkText = display.ifBlank { url }
                wrapSelection(state, "<a href=\"$url\">", "</a>", replacement = linkText)
                showLinkDialog = false
            },
            onDismiss = { showLinkDialog = false },
        )
    }
}

/**
 * Wrap the current selection in [open]/[close]. If no selection, insert the tag
 * pair at the cursor and place the caret between them. If [replacement] is
 * provided, replace the selection text with it (used by Link).
 */
private fun wrapSelection(
    state: TextFieldState,
    open: String,
    close: String,
    replacement: String? = null,
) {
    state.edit {
        val sel = selection
        val start = sel.min
        val end = sel.max
        val inner = replacement ?: toString().substring(start, end)
        replace(start, end, "$open$inner$close")
        selection = if (inner.isEmpty()) {
            TextRange(start + open.length)
        } else {
            TextRange(start + open.length, start + open.length + inner.length)
        }
    }
}

/**
 * Insert a `<ul>`/`<ol>` block at the cursor. If text is selected, turn each
 * non-empty line into its own `<li>` item; otherwise insert a single empty item.
 */
private fun insertList(state: TextFieldState, ordered: Boolean) {
    val tag = if (ordered) "ol" else "ul"
    state.edit {
        val sel = selection
        val start = sel.min
        val end = sel.max
        val selected = toString().substring(start, end)
        val items = if (selected.isBlank()) {
            listOf("")
        } else {
            selected.lines().filter { it.isNotBlank() }.ifEmpty { listOf("") }
        }
        val block = buildString {
            append("<$tag>")
            items.forEach { append("<li>").append(it).append("</li>") }
            append("</$tag>")
        }
        replace(start, end, block)
        // Park caret just after the opening <li> of the first item.
        val caret = start + "<$tag><li>".length + items[0].length
        selection = TextRange(caret)
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LinkInputDialog(
    initialDisplay: String,
    onConfirm: (url: String, display: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var display by remember { mutableStateOf(initialDisplay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                )
                if (initialDisplay.isBlank()) {
                    OutlinedTextField(
                        value = display,
                        onValueChange = { display = it },
                        label = { Text("Text") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(url.trim(), display.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
