package com.rendyhd.vicu.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomList(
    val id: String,
    val name: String,
    val icon: String = "",
    val filter: CustomListFilter,
)

@Serializable
data class CustomListFilter(
    val projectIds: List<Long> = emptyList(),
    val sortBy: String = "due_date",
    val orderBy: String = "asc",
    val dueDateFilter: String = "all",
    val priorityFilter: List<Int> = emptyList(),
    val labelIds: List<Long> = emptyList(),
    val includeDone: Boolean = false,
    val includeTodayAllProjects: Boolean = false,
)
