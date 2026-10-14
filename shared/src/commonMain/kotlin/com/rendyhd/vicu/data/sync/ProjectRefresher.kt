package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService

/**
 * Makes the project cache match the server's list (archived projects included). Projects with a
 * queued change keep their local state until the change reaches the server.
 */
class ProjectRefresher(
    private val projectDao: ProjectDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val projectMapper: ProjectMapper,
) {
    suspend fun refresh() {
        val server = api.getAllProjects(includeArchived = true).map { with(projectMapper) { it.toEntity() } }
        projectDao.replaceAll(server, keepIds = pendingActionDao.getProjectIdsWithPendingActions().toSet())
    }
}
