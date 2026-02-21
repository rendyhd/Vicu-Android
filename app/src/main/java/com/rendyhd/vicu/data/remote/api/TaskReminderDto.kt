package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskReminderDto(
    val reminder: String = "",
    @SerialName("relative_period") val relativePeriod: Long = 0,
    @SerialName("relative_to") val relativeTo: String = "",
)
