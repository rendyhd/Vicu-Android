package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide behavior preferences that don't fit elsewhere:
 *  - Whether to play a sound on task completion (+ optional custom sound file URI)
 *  - Whether to require confirmation before destructive deletes
 */
/** What the swipe-"schedule" and multi-select "Schedule" actions do to a task. */
enum class ScheduleAction { DUE_TODAY, PRIORITY_URGENT }

/** Controls whether subtasks stay in task details or can expand inline in task lists. */
enum class SubtaskDisplayMode { INSIDE_TASK, EXPANDABLE }

/** Controls whether child projects expose their tasks as sections or navigate as project rows. */
enum class SubprojectDisplayMode { SECTIONS, PROJECT_ROWS }

data class BehaviorPrefs(
    val completionSoundEnabled: Boolean = false,
    val completionSoundUri: String? = null,
    val confirmBeforeDelete: Boolean = true,
    val inboxExcludeDated: Boolean = false,
    val scheduleAction: ScheduleAction = ScheduleAction.DUE_TODAY,
    val keepEntryOpen: Boolean = false,
    val fabAlignStart: Boolean = false,
    val subtaskDisplayMode: SubtaskDisplayMode = SubtaskDisplayMode.INSIDE_TASK,
    val subprojectDisplayMode: SubprojectDisplayMode = SubprojectDisplayMode.SECTIONS,
    /** Progress rings next to the projects in the drawer. */
    val showProjectProgress: Boolean = true,
)

class BehaviorPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_COMPLETION_SOUND_ENABLED = booleanPreferencesKey("completion_sound_enabled")
        private val KEY_COMPLETION_SOUND_URI = stringPreferencesKey("completion_sound_uri")
        private val KEY_CONFIRM_BEFORE_DELETE = booleanPreferencesKey("confirm_before_delete")
        private val KEY_INBOX_EXCLUDE_DATED = booleanPreferencesKey("inbox_exclude_dated")
        private val KEY_SCHEDULE_ACTION = stringPreferencesKey("schedule_action")
        private val KEY_KEEP_ENTRY_OPEN = booleanPreferencesKey("keep_entry_open")
        private val KEY_FAB_ALIGN_START = booleanPreferencesKey("fab_align_start")
        private val KEY_SUBTASK_DISPLAY_MODE = stringPreferencesKey("subtask_display_mode")
        private val KEY_SUBPROJECT_DISPLAY_MODE = stringPreferencesKey("subproject_display_mode")
        private val KEY_SHOW_PROJECT_PROGRESS = booleanPreferencesKey("show_project_progress")
    }

    fun getPrefs(): Flow<BehaviorPrefs> =
        dataStore.data.map { prefs ->
            BehaviorPrefs(
                completionSoundEnabled = prefs[KEY_COMPLETION_SOUND_ENABLED] ?: false,
                completionSoundUri = prefs[KEY_COMPLETION_SOUND_URI]?.takeIf { it.isNotBlank() },
                confirmBeforeDelete = prefs[KEY_CONFIRM_BEFORE_DELETE] ?: true,
                inboxExcludeDated = prefs[KEY_INBOX_EXCLUDE_DATED] ?: false,
                scheduleAction = prefs[KEY_SCHEDULE_ACTION]
                    ?.let { runCatching { ScheduleAction.valueOf(it) }.getOrNull() }
                    ?: ScheduleAction.DUE_TODAY,
                keepEntryOpen = prefs[KEY_KEEP_ENTRY_OPEN] ?: false,
                fabAlignStart = prefs[KEY_FAB_ALIGN_START] ?: false,
                subtaskDisplayMode = prefs[KEY_SUBTASK_DISPLAY_MODE]
                    ?.let { runCatching { SubtaskDisplayMode.valueOf(it) }.getOrNull() }
                    ?: SubtaskDisplayMode.INSIDE_TASK,
                subprojectDisplayMode = prefs[KEY_SUBPROJECT_DISPLAY_MODE]
                    ?.let { runCatching { SubprojectDisplayMode.valueOf(it) }.getOrNull() }
                    ?: SubprojectDisplayMode.SECTIONS,
                showProjectProgress = prefs[KEY_SHOW_PROJECT_PROGRESS] ?: true,
            )
        }

    suspend fun setCompletionSoundEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_COMPLETION_SOUND_ENABLED] = enabled }
    }

    suspend fun setCompletionSoundUri(uri: String?) {
        dataStore.edit {
            if (uri.isNullOrBlank()) it.remove(KEY_COMPLETION_SOUND_URI)
            else it[KEY_COMPLETION_SOUND_URI] = uri
        }
    }

    suspend fun setConfirmBeforeDelete(enabled: Boolean) {
        dataStore.edit { it[KEY_CONFIRM_BEFORE_DELETE] = enabled }
    }

    suspend fun setInboxExcludeDated(enabled: Boolean) {
        dataStore.edit { it[KEY_INBOX_EXCLUDE_DATED] = enabled }
    }

    suspend fun setScheduleAction(action: ScheduleAction) {
        dataStore.edit { it[KEY_SCHEDULE_ACTION] = action.name }
    }

    suspend fun setKeepEntryOpen(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_ENTRY_OPEN] = enabled }
    }

    suspend fun setFabAlignStart(enabled: Boolean) {
        dataStore.edit { it[KEY_FAB_ALIGN_START] = enabled }
    }

    suspend fun setSubtaskDisplayMode(mode: SubtaskDisplayMode) {
        dataStore.edit { it[KEY_SUBTASK_DISPLAY_MODE] = mode.name }
    }

    suspend fun setSubprojectDisplayMode(mode: SubprojectDisplayMode) {
        dataStore.edit { it[KEY_SUBPROJECT_DISPLAY_MODE] = mode.name }
    }

    suspend fun setShowProjectProgress(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_PROJECT_PROGRESS] = enabled }
    }
}
