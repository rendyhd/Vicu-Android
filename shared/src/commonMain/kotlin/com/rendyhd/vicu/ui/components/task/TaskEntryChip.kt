package com.rendyhd.vicu.ui.components.task

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
 * it as a custom action too, because the clear button is smaller than a touch target.
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
    AssistChip(
        onClick = onClick,
        modifier = if (canClear) {
            modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(clearDescription!!) {
                        onClear!!.invoke()
                        true
                    },
                )
            }
        } else {
            modifier
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (canClear) {
            {
                IconButton(onClick = onClear!!, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = clearDescription, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            null
        },
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
