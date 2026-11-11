package com.rendyhd.vicu.calendar

import androidx.compose.runtime.Composable

/** No calendar reader on iOS yet (EventKit comes later): nothing to ask for. */
@Composable
actual fun rememberCalendarPermission(onResult: (Boolean) -> Unit): CalendarPermission =
    CalendarPermission(granted = false, refused = false, request = {}, openSettings = {})
