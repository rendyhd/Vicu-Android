package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API v2 validates `reminder` as an RFC 3339 date-time, so relative reminders must omit it
 * rather than send `""` (issue #30). Absent fields are left out of the JSON entirely.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskReminderDto(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reminder: String? = null,
    @SerialName("relative_period") val relativePeriod: Long = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("relative_to") val relativeTo: String? = null,
)
