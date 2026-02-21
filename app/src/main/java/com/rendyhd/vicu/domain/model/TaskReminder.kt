package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskReminder(
    val reminder: String = "",
    val relativePeriod: Long = 0,
    val relativeTo: String = "",
)
