package com.rendyhd.vicu.domain.model

data class Project(
    val id: Long,
    val title: String,
    val description: String = "",
    val hexColor: String = "",
    val parentProjectId: Long = 0,
    val position: Double = 0.0,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val identifier: String = "",
    val created: String = "",
    val updated: String = "",
    val ownerId: Long = 0,
    /** What you may do in the project: 0 read, 1 write, 2 admin, -1 not known yet (cross-app semantics 9.1). */
    val maxPermission: Int = -1,
)
