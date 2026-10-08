package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A chip of the new-task sheet. [tint] is the role colour of the text token the value was read
 * from (the same colour as its highlight in the title); a value set with the chip's own picker, or
 * no value, has none. [onClear] is offered only while the chip has a value; a screen reader gets
 * it as a custom action too, next to the clear button (a 48 dp target beside the chip).
 */
@Composable
internal fun TaskEntryChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    clearDescription: String? = null,
    onClear: (() -> Unit)? = null,
) {
    val canClear = onClear != null && clearDescription != null
    if (!canClear) {
        EntryChip(label, onClick, modifier, tint)
        return
    }
    // The clear button sits beside the chip, not inside it: a button of 48 dp (its touch target)
    // would not fit in a 32 dp chip.
    Row(verticalAlignment = Alignment.CenterVertically) {
        EntryChip(
            label = label,
            onClick = onClick,
            modifier = modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(clearDescription!!) {
                        onClear!!.invoke()
                        true
                    },
                )
            },
            tint = tint,
        )
        IconButton(onClick = onClear!!, modifier = Modifier.size(MIN_CLEAR_TARGET)) {
            Icon(
                Icons.Default.Close,
                contentDescription = clearDescription,
                modifier = Modifier.size(16.dp),
                tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val MIN_CLEAR_TARGET = 48.dp

@Composable
private fun EntryChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    tint: Color?,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = if (tint != null) {
            AssistChipDefaults.assistChipColors(labelColor = tint, trailingIconContentColor = tint)
        } else {
            AssistChipDefaults.assistChipColors()
        },
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = tint?.copy(alpha = 0.5f) ?: MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}
