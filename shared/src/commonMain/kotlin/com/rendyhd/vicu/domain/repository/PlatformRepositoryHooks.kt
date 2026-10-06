package com.rendyhd.vicu.domain.repository

import com.rendyhd.vicu.domain.model.Task

interface PlatformRepositoryHooks {
    fun triggerSync()
    fun updateWidgets()
    fun playCompletionSound()
    suspend fun scheduleAlarm(task: Task)
    suspend fun cancelAlarm(taskId: Long)
    suspend fun rescheduleAlarms()

    /** Cancels every task reminder, snooze and routine alarm (sign-out, account switch). */
    suspend fun cancelAllAlarms() {}

    /**
     * Cancels the periodic work that belongs to the signed-in account: the daily summaries and
     * the routine midnight maintenance. A signed-out device must not keep notifying for it.
     * Signing in again schedules them anew.
     */
    suspend fun cancelAccountBackgroundWork() {}

    /**
     * Forgets which project or list each home-screen widget was set to show; those ids belonged
     * to the account that is gone, and the widgets fall back to their default view.
     */
    suspend fun clearWidgetConfigurations() {}

    /** Called after a routine definition or occurrence changes. */
    suspend fun routinesChanged() {
        updateWidgets()
    }
}
