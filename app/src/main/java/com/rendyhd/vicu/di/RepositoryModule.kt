package com.rendyhd.vicu.di

import com.rendyhd.vicu.data.repository.AttachmentRepositoryImpl
import com.rendyhd.vicu.data.repository.LabelRepositoryImpl
import com.rendyhd.vicu.data.repository.ProjectRepositoryImpl
import com.rendyhd.vicu.data.repository.TaskRepositoryImpl
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindLabelRepository(impl: LabelRepositoryImpl): LabelRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentRepository(impl: AttachmentRepositoryImpl): AttachmentRepository
}
