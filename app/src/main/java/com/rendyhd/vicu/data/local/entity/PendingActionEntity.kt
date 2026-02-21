package com.rendyhd.vicu.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String = "",
    val entityId: Long = 0,
    val actionType: String = "",
    val payload: String = "",
    val status: String = "pending",
    val retryCount: Int = 0,
    val maxRetries: Int = 5,
    val createdAt: String = "",
    val updatedAt: String = "",
)
