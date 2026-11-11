package com.rendyhd.vicu.calendar

import androidx.compose.runtime.Composable

/** The permission to read the phone's calendars, as a screen sees it (never write access, contract 20.9). */
class CalendarPermission(
    val granted: Boolean,
    /** Asked and refused: the system will not ask again, only its settings can allow it. */
    val refused: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/** The calendar permission; [onResult] hears the answer to [CalendarPermission.request]. */
@Composable
expect fun rememberCalendarPermission(onResult: (Boolean) -> Unit): CalendarPermission
