package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Label(
    val id: Long,
    val title: String,
    val description: String = "",
    val hexColor: String = "",
    val createdById: Long = 0,
    val created: String = "",
    val updated: String = "",
)
