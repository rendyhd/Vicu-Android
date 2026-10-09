package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The General tab: one list, built from the sections in GeneralAccountSections,
 * GeneralPreferenceSections and GeneralDataSections. Dialogs are opened through [openDialog]
 * and drawn by [SettingsDialogHost].
 */
@Composable
internal fun GeneralTab(
    state: SettingsUiState,
    useDeviceColors: Boolean,
    showArchivedProjects: Boolean,
    onShowArchivedProjectsChange: (Boolean) -> Unit,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    onPickCompletionSound: () -> Unit,
    listState: LazyListState,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        accountSection(state, openDialog)
        appearanceSection(state, useDeviceColors, viewModel)
        bottomBarSection(state, viewModel, openDialog)
        widgetSection(state, viewModel)
        reviewSection(state, viewModel, openDialog)
        routinesSection(state, viewModel)
        inboxSection(state, viewModel)
        logbookSection(state, viewModel, openDialog)
        inputParsingSection(state, viewModel)
        behaviorSection(state, viewModel, onPickCompletionSound)
        projectsSection(state, viewModel, openDialog, showArchivedProjects, onShowArchivedProjectsChange)
        labelsSection(state, viewModel, openDialog)
        customListsSection(state, viewModel, openDialog)
        dataSyncSection(state, viewModel, openDialog)
        signOutSection(openDialog)
    }
}
