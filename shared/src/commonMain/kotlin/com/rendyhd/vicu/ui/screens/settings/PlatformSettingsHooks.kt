package com.rendyhd.vicu.ui.screens.settings

interface PlatformSettingsHooks {
    val supportsQuickAddTile: Boolean

    /**
     * Where the platform without a quick-add tile offers quick add instead (the iOS Control
     * Center control), shown in Settings in place of the tile; null shows nothing.
     */
    val quickAddHint: String? get() = null

    /** The task list widget has a microphone button that Settings can hide (Android). */
    val hasWidgetVoiceButton: Boolean get() = false

    fun updateWidgets()
    fun scheduleSync(enabled: Boolean)
    fun scheduleDailySummary(slot: String, enabled: Boolean, hour: Int, minute: Int)
    fun sendTestNotification(): String?
    fun requestQuickAddTile(onResult: (String) -> Unit)
    fun triggerImmediateSync()

    /**
     * A record of the work the platform ran in the background (iOS decides when its background
     * refresh runs), shown in Settings when there is one.
     */
    val hasBackgroundActivityLog: Boolean get() = false

    suspend fun readBackgroundActivityLog(): String = ""
}
