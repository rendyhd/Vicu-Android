package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The General tab: one list, built from the sections in GeneralAccountSections,
 * GeneralPreferenceSections and GeneralDataSections. Everything about projects, labels and lists
 * is in the Projects tab. Dialogs are opened through [openDialog] and drawn by [SettingsDialogHost].
 */
@Composable
internal fun GeneralTab(
    state: SettingsUiState,
    useDeviceColors: Boolean,
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
        routinesSection(state, viewModel)
        logbookSection(state, viewModel, openDialog)
        inputParsingSection(state, viewModel)
        behaviorSection(state, viewModel, onPickCompletionSound)
        dataSyncSection(state, viewModel, openDialog)
        signOutSection(openDialog)
    }
}
