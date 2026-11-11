package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskReminder(
    val reminder: String = "",
    val relativePeriod: Long = 0,
    val relativeTo: String = "",
) {
    /**
     * Counts from a date of the task ("At due time", "15 minutes before"). The server also fills
     * [reminder] in for such a reminder, with the time it worked out when it last saw the task; that
     * copy goes stale as soon as the date moves here, so it is never read as the reminder's time.
     */
    val isRelative: Boolean get() = relativeTo.isNotBlank() || relativePeriod != 0L
}
