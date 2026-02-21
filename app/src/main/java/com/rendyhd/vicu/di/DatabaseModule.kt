package com.rendyhd.vicu.di

import android.content.Context
import androidx.room.Room
import com.rendyhd.vicu.data.local.VikunjaDatabase
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.dao.LabelDao
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.ThemePrefsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VikunjaDatabase =
        Room.databaseBuilder(
            context,
            VikunjaDatabase::class.java,
            "vicu_database",
        ).build()

    @Provides
    fun provideTaskDao(db: VikunjaDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideProjectDao(db: VikunjaDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideLabelDao(db: VikunjaDatabase): LabelDao = db.labelDao()

    @Provides
    fun providePendingActionDao(db: VikunjaDatabase): PendingActionDao = db.pendingActionDao()

    @Provides
    fun provideAttachmentDao(db: VikunjaDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    @Singleton
    fun provideCustomListStore(@ApplicationContext context: Context): CustomListStore =
        CustomListStore(context)

    @Provides
    @Singleton
    fun provideNotificationPrefsStore(@ApplicationContext context: Context): NotificationPrefsStore =
        NotificationPrefsStore(context)

    @Provides
    @Singleton
    fun provideThemePrefsStore(@ApplicationContext context: Context): ThemePrefsStore =
        ThemePrefsStore(context)
}
