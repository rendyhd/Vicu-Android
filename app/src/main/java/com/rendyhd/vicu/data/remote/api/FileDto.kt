package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.Serializable

@Serializable
data class FileDto(
    val id: Long = 0,
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val created: String = "",
)
