package com.rendyhd.vicu.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Full access to the iPhone's calendars (EventKit, iOS 17), asked when Calendars opens. Read only:
 * Vicu never asks for write access. Once refused, only the app's page in Settings can allow it.
 */
@Composable
actual fun rememberCalendarPermission(onResult: (Boolean) -> Unit): CalendarPermission {
    var granted by remember { mutableStateOf(IosEventStore.hasFullAccess()) }
    var refused by remember { mutableStateOf(IosEventStore.isRefused()) }
    val latestResult by rememberUpdatedState(onResult)
    return CalendarPermission(
        granted = granted,
        refused = refused,
        request = {
            IosEventStore.requestFullAccess { ok ->
                granted = ok
                refused = !ok
                latestResult(ok)
            }
        },
        openSettings = {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
                UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
            }
        },
    )
}
