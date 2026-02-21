package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val id: Long,
    val title: String = "",
    val description: String = "",
    val hexColor: String = "",
    val createdById: Long = 0,
    val created: String = "",
    val updated: String = "",
)
