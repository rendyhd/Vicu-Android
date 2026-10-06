package com.rendyhd.vicu.ui.components.shared

import androidx.compose.runtime.compositionLocalOf
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The current local date. Provided once at the app root from DayClock, so a composable that
 * reads it is recomposed when midnight passes (or the date or time zone is changed) instead of
 * showing the date it saw when it first composed. The default is the system date, for previews.
 */
val LocalToday = compositionLocalOf<LocalDate> { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
