package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Vikunja saved filter as `GET filters/{id}` returns it (cross-app semantics 3.7). Read only. */
@Serializable
data class SavedFilterDto(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val filters: SavedFilterQueryDto = SavedFilterQueryDto(),
)

/** The task query a saved filter wraps (`TaskCollection` in the API). */
@Serializable
data class SavedFilterQueryDto(
    val filter: String = "",
    @SerialName("filter_include_nulls") val filterIncludeNulls: Boolean = false,
    @SerialName("sort_by") val sortBy: List<String>? = null,
    @SerialName("order_by") val orderBy: List<String>? = null,
    val s: String = "",
)
