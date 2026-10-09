package com.rendyhd.vicu.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.rendyhd.vicu.util.DateDisplayFormat

/**
 * The device's 12/24 hour setting, provided once at the app root. Task rows and the task editor
 * read it to show an explicit due time the way the user's device shows every other time.
 */
val LocalIs24Hour = compositionLocalOf { false }

/**
 * The locale and clock every date text is phrased with (docs/cross-app-semantics-v1.md section 8):
 * the device locale and [LocalIs24Hour]. Provided once at the app root.
 */
val LocalDateFormat = compositionLocalOf { DateDisplayFormat.system(is24Hour = false) }

/** Reads the device's 12/24 hour setting and follows it when the app comes back to the foreground. */
@Composable
expect fun rememberIs24HourFormat(): Boolean
