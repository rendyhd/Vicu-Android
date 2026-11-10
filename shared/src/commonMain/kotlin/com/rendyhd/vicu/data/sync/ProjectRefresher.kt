package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.SavedFilterImport

/**
 * Makes the project cache match the server's list (archived projects included). Projects with a
 * queued change keep their local state until the change reaches the server. Vikunja's
 * pseudo-projects (negative ids) never become projects; the saved filters among them are kept in
 * [savedFilters] for the import (cross-app semantics 3.7).
 */
class ProjectRefresher(
    private val projectDao: ProjectDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val projectMapper: ProjectMapper,
    private val savedFilters: SavedFilterStore? = null,
) {
    suspend fun refresh() {
        val (projects, pseudo) = api.getProjectsAndSavedFilters(includeArchived = true)
        val server = projects.map { with(projectMapper) { it.toEntity() } }
        projectDao.replaceAll(server, keepIds = pendingActionDao.getProjectIdsWithPendingActions().toSet())
        savedFilters?.setKnown(
            pseudo.mapNotNull { dto -> SavedFilterImport.filterIdOf(dto.id)?.let { SavedFilterRef(it, dto.title) } },
        )
    }
}
