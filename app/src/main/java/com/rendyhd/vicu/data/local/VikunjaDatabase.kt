package com.rendyhd.vicu.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import com.rendyhd.vicu.data.local.entity.LabelEntity
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity

@Database(
    entities = [
        TaskEntity::class,
        ProjectEntity::class,
        LabelEntity::class,
        PendingActionEntity::class,
        AttachmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VikunjaDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun projectDao(): ProjectDao
    abstract fun labelDao(): LabelDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun attachmentDao(): AttachmentDao
}
