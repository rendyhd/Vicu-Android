package com.rendyhd.vicu.ui.components.task

import androidx.compose.runtime.compositionLocalOf
import com.rendyhd.vicu.data.local.RowDensity
import com.rendyhd.vicu.data.local.SubtaskDisplayMode

/** App-wide subtask presentation preference, supplied once by [com.rendyhd.vicu.ui.VicuApp]. */
val LocalSubtaskDisplayMode = compositionLocalOf { SubtaskDisplayMode.INSIDE_TASK }

/** How much of a task a row shows, supplied once by [com.rendyhd.vicu.ui.VicuApp]. */
val LocalRowDensity = compositionLocalOf { RowDensity.COMPACT }
