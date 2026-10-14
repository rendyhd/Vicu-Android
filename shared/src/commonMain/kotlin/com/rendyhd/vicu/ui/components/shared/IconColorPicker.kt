package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.util.PRESET_COLORS
import com.rendyhd.vicu.util.PRESET_COLOR_NAMES
import com.rendyhd.vicu.util.parseHexColor

/** The hex form [IconColorPicker] compares by: lower case with a leading `#`, or empty. */
internal fun normalizedColorHex(hex: String): String {
    val trimmed = hex.trim().lowercase()
    return when {
        trimmed.isEmpty() -> ""
        trimmed.startsWith("#") -> trimmed
        else -> "#$trimmed"
    }
}

/**
 * The colour of an icon: none (the surface's own content colour) or one of the project colours.
 * [selectedHex] is empty for none. Each swatch is a 48 dp radio target with the colour's name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconColorPicker(
    selectedHex: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = normalizedColorHex(selectedHex)
    FlowRow(modifier = modifier.selectableGroup()) {
        ColorSwatch(name = "No colour", color = null, selected = selected.isEmpty(), onClick = { onSelect("") })
        PRESET_COLORS.forEach { hex ->
            ColorSwatch(
                name = PRESET_COLOR_NAMES[hex] ?: hex,
                color = parseHexColor(hex),
                selected = selected == hex,
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        // Selected: a thick ring in the content colour. None is a hollow circle.
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(if (color != null) Modifier.background(color) else Modifier)
                .then(
                    when {
                        selected -> Modifier.border(3.dp, scheme.onSurface, CircleShape)
                        color == null -> Modifier.border(1.dp, scheme.outline, CircleShape)
                        else -> Modifier
                    },
                ),
        )
    }
}
