package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import org.koin.compose.koinInject
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
    pureBlack: Boolean,
    viewModel: SettingsViewModel,
    openDialog: (SettingsDialog) -> Unit,
    onPickCompletionSound: () -> Unit,
    listState: LazyListState,
) {
    // Only iOS keeps a record of its background runs.
    val backgroundActivity = koinInject<PlatformSettingsHooks>().hasBackgroundActivityLog
    // The drawer lists Ask once it is set up, so only then can it be hidden from there.
    val aiSettings by koinInject<AiSettingsStore>().settings.collectAsStateWithLifecycle(initialValue = AiSettings())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
    ) {
        accountSection(state, openDialog)
        appearanceSection(state, useDeviceColors, pureBlack, viewModel)
        bottomBarSection(state, viewModel, openDialog)
        // Plan sits with Pro's tools, as on the desktop, and works whether Pro is on or off.
        proSection()
        plannerSection(state, viewModel)
        drawerSection(state, askReady = aiSettings.askReady, viewModel)
        widgetSection(state, viewModel)
        routinesSection(state, viewModel)
        logbookSection(state, viewModel, openDialog)
        inputParsingSection(state, viewModel)
        behaviorSection(state, viewModel, onPickCompletionSound)
        dataSyncSection(state, viewModel, openDialog, backgroundActivity)
        signOutSection(openDialog)
    }
}
