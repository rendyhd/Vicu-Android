package com.rendyhd.vicu.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Minimal drag handle for [androidx.compose.material3.ModalBottomSheet].
 *
 * Unlike `BottomSheetDefaults.DragHandle`, this carries no long-press tooltip,
 * so dragging the sheet never surfaces a "Drag handle" tooltip popup.
 */
@Composable
fun VicuDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A target of at least 48 dp, named for a screen reader (the sheet makes it an action).
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Sheet handle" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}
