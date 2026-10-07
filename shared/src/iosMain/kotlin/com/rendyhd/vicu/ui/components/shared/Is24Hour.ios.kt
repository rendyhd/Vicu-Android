package com.rendyhd.vicu.ui.components.shared

import androidx.compose.runtime.Composable
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

@Composable
actual fun rememberIs24HourFormat(): Boolean {
    // The "j" template expands to the locale's preferred hour format; a 12 hour one has an AM/PM marker.
    val pattern = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)
    return pattern?.contains("a") != true
}
