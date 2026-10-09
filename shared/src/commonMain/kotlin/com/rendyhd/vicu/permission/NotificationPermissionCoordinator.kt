package com.rendyhd.vicu.permission

import com.rendyhd.vicu.data.local.NotificationPermissionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The platform side of the notification permission; implemented in the app module. */
interface NotificationPermissionPlatform {
    /** False where there is no runtime permission (before Android 13). */
    val needsRuntimePermission: Boolean

    fun isGranted(): Boolean

    /** Shows the system prompt. False when there is no screen to show it on right now. */
    fun launchSystemRequest(): Boolean

    fun openSystemSettings()
}

/** The sheet the user is shown before the system prompt (or before the settings page). */
data class NotificationPermissionPrompt(val step: NotificationPermissionStep)

/**
 * Decides when to ask for the notification permission and holds the sheet that explains it.
 * The app never asks on launch: only after setup, or when the user turns on a reminder or a
 * daily summary. [NotificationPermissionPolicy] holds the rules.
 */
class NotificationPermissionCoordinator(
    private val platform: NotificationPermissionPlatform,
    private val store: NotificationPermissionStore,
    private val scope: CoroutineScope,
) {
    private val _prompt = MutableStateFlow<NotificationPermissionPrompt?>(null)

    /** The sheet to show, or null. */
    val prompt: StateFlow<NotificationPermissionPrompt?> = _prompt.asStateFlow()

    /** Setup just finished. */
    fun onSetupCompleted() {
        scope.launch { evaluate(NotificationPermissionTrigger.AFTER_SETUP) }
    }

    /** The user turned on a reminder or a daily summary. */
    fun onFeatureEnabled() {
        scope.launch { evaluate(NotificationPermissionTrigger.FEATURE_ENABLED) }
    }

    /** The user asked for notifications in Settings: the system prompt if it can still show, else the settings page. */
    fun requestFromSettings() {
        scope.launch {
            when (NotificationPermissionPolicy.nextFromSettings(currentState())) {
                NotificationPermissionStep.SHOW_RATIONALE -> launchSystemRequest()
                else -> platform.openSystemSettings()
            }
        }
    }

    /** The user accepted the sheet. */
    fun confirm() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        when (prompt.step) {
            NotificationPermissionStep.SHOW_RATIONALE -> scope.launch { launchSystemRequest() }
            NotificationPermissionStep.OPEN_SYSTEM_SETTINGS -> platform.openSystemSettings()
            NotificationPermissionStep.NONE -> {}
        }
    }

    /** The user closed the sheet without going on. */
    fun dismiss() {
        _prompt.value = null
    }

    private suspend fun launchSystemRequest() {
        // Counted only when the prompt really went up, so a missing screen costs no request.
        if (platform.launchSystemRequest()) store.recordRequest()
    }

    private suspend fun evaluate(trigger: NotificationPermissionTrigger) {
        if (_prompt.value != null) return
        val step = NotificationPermissionPolicy.next(currentState(), trigger)
        if (step == NotificationPermissionStep.NONE) return
        if (trigger == NotificationPermissionTrigger.AFTER_SETUP) store.markAfterSetupHandled()
        _prompt.value = NotificationPermissionPrompt(step)
    }

    private suspend fun currentState() = NotificationPermissionState(
        needsRuntimePermission = platform.needsRuntimePermission,
        granted = platform.isGranted(),
        requestsMade = store.requestsMade(),
        afterSetupHandled = store.afterSetupHandled(),
    )
}
