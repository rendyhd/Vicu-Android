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

    /** Called after a routine definition or occurrence changes. */
    suspend fun routinesChanged() {
        updateWidgets()
    }
}
