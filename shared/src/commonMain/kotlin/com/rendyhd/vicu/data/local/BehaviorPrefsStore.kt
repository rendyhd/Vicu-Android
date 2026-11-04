package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide behavior preferences that don't fit elsewhere:
 *  - Whether to play a sound on task completion (+ optional custom sound file URI)
 *  - Whether to require confirmation before destructive deletes
 */
/**
 * What swiping a task row does. Each direction has its own (Settings, Gestures); a direction set
 * to [NONE] does not move.
 */
enum class SwipeAction { COMPLETE, CHOOSE_WHEN, DUE_TODAY, DUE_TOMORROW, URGENT, MOVE, DELETE, NONE }

/** Controls whether subtasks stay in task details or can expand inline in task lists. */
enum class SubtaskDisplayMode { INSIDE_TASK, EXPANDABLE }

/** How much of a task a row shows (design-system section 4); the values are in `design-tokens-v1.json`. */
enum class RowDensity(val titleLines: Int, val notesLines: Int) {
    COMPACT(titleLines = 1, notesLines = 0),
    COMFORTABLE(titleLines = 3, notesLines = 1),
}

/** Controls whether child projects expose their tasks as sections or navigate as project rows. */
enum class SubprojectDisplayMode { SECTIONS, PROJECT_ROWS }

/**
 * A view the drawer lists above the projects, in the drawer's order. The user can hide each one
 * from the drawer (Settings, General, Drawer); a hidden view keeps working.
 */
enum class DrawerView { TODAY, UPCOMING, ANYTIME, LOGBOOK, ROUTINES, REVIEW, ASK }

data class BehaviorPrefs(
    val completionSoundEnabled: Boolean = false,
    val completionSoundUri: String? = null,
    val confirmBeforeDelete: Boolean = true,
    val inboxExcludeDated: Boolean = false,
    val swipeRight: SwipeAction = SwipeAction.COMPLETE,
    val swipeLeft: SwipeAction = SwipeAction.CHOOSE_WHEN,
    val keepEntryOpen: Boolean = false,
    val fabAlignStart: Boolean = false,
    val subtaskDisplayMode: SubtaskDisplayMode = SubtaskDisplayMode.INSIDE_TASK,
    val rowDensity: RowDensity = RowDensity.COMPACT,
    val subprojectDisplayMode: SubprojectDisplayMode = SubprojectDisplayMode.SECTIONS,
    /** Progress rings next to the projects in the drawer. */
    val showProjectProgress: Boolean = true,
    /** How many open tasks each project has, next to it in the drawer. */
    val showProjectTaskCount: Boolean = false,
    /** The views the user took out of the drawer. */
    val hiddenDrawerViews: Set<DrawerView> = emptySet(),
)

class BehaviorPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_COMPLETION_SOUND_ENABLED = booleanPreferencesKey("completion_sound_enabled")
        private val KEY_COMPLETION_SOUND_URI = stringPreferencesKey("completion_sound_uri")
        private val KEY_CONFIRM_BEFORE_DELETE = booleanPreferencesKey("confirm_before_delete")
        private val KEY_INBOX_EXCLUDE_DATED = booleanPreferencesKey("inbox_exclude_dated")
        private val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        private val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")

        /** The swipe-left setting of earlier versions ("Choose when" or "Urgent"), read until swipe_left is written. */
        private val KEY_SCHEDULE_ACTION = stringPreferencesKey("schedule_action")

        /**
         * A stored swipe action: the name this version knows, else [fallback] (a name from a newer
         * version, or none written).
         */
        internal fun swipeAction(name: String?, fallback: SwipeAction): SwipeAction =
            SwipeAction.entries.firstOrNull { it.name == name } ?: fallback

        /** What the old swipe-left setting ("Choose when" or "Urgent") becomes. */
        internal fun legacySwipeLeft(scheduleAction: String?): SwipeAction =
            if (scheduleAction == "PRIORITY_URGENT") SwipeAction.URGENT else SwipeAction.CHOOSE_WHEN
        private val KEY_KEEP_ENTRY_OPEN = booleanPreferencesKey("keep_entry_open")
        private val KEY_FAB_ALIGN_START = booleanPreferencesKey("fab_align_start")
        private val KEY_SUBTASK_DISPLAY_MODE = stringPreferencesKey("subtask_display_mode")
        private val KEY_ROW_DENSITY = stringPreferencesKey("row_density")
        private val KEY_SUBPROJECT_DISPLAY_MODE = stringPreferencesKey("subproject_display_mode")
        private val KEY_SHOW_PROJECT_PROGRESS = booleanPreferencesKey("show_project_progress")
        private val KEY_SHOW_PROJECT_TASK_COUNT = booleanPreferencesKey("show_project_task_count")
        private val KEY_HIDDEN_DRAWER_VIEWS = stringSetPreferencesKey("hidden_drawer_views")
    }

    fun getPrefs(): Flow<BehaviorPrefs> =
        dataStore.data.map { prefs ->
            BehaviorPrefs(
                completionSoundEnabled = prefs[KEY_COMPLETION_SOUND_ENABLED] ?: false,
                completionSoundUri = prefs[KEY_COMPLETION_SOUND_URI]?.takeIf { it.isNotBlank() },
                confirmBeforeDelete = prefs[KEY_CONFIRM_BEFORE_DELETE] ?: true,
                inboxExcludeDated = prefs[KEY_INBOX_EXCLUDE_DATED] ?: false,
                swipeRight = swipeAction(prefs[KEY_SWIPE_RIGHT], SwipeAction.COMPLETE),
                swipeLeft = swipeAction(prefs[KEY_SWIPE_LEFT], legacySwipeLeft(prefs[KEY_SCHEDULE_ACTION])),
                keepEntryOpen = prefs[KEY_KEEP_ENTRY_OPEN] ?: false,
                fabAlignStart = prefs[KEY_FAB_ALIGN_START] ?: false,
                subtaskDisplayMode = prefs[KEY_SUBTASK_DISPLAY_MODE]
                    ?.let { runCatching { SubtaskDisplayMode.valueOf(it) }.getOrNull() }
                    ?: SubtaskDisplayMode.INSIDE_TASK,
                rowDensity = RowDensity.entries.firstOrNull { it.name == prefs[KEY_ROW_DENSITY] } ?: RowDensity.COMPACT,
                subprojectDisplayMode = prefs[KEY_SUBPROJECT_DISPLAY_MODE]
                    ?.let { runCatching { SubprojectDisplayMode.valueOf(it) }.getOrNull() }
                    ?: SubprojectDisplayMode.SECTIONS,
                showProjectProgress = prefs[KEY_SHOW_PROJECT_PROGRESS] ?: true,
                showProjectTaskCount = prefs[KEY_SHOW_PROJECT_TASK_COUNT] ?: false,
                // A name this version does not know (written by a newer one) is left out.
                hiddenDrawerViews = prefs[KEY_HIDDEN_DRAWER_VIEWS].orEmpty()
                    .mapNotNullTo(HashSet()) { name -> DrawerView.entries.firstOrNull { it.name == name } },
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

    suspend fun setSwipeRight(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    /** Also drops the old swipe-left setting, which this one replaces. */
    suspend fun setSwipeLeft(action: SwipeAction) {
        dataStore.edit {
            it[KEY_SWIPE_LEFT] = action.name
            it.remove(KEY_SCHEDULE_ACTION)
        }
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

    suspend fun setRowDensity(density: RowDensity) {
        dataStore.edit { it[KEY_ROW_DENSITY] = density.name }
    }

    suspend fun setSubprojectDisplayMode(mode: SubprojectDisplayMode) {
        dataStore.edit { it[KEY_SUBPROJECT_DISPLAY_MODE] = mode.name }
    }

    suspend fun setShowProjectProgress(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_PROJECT_PROGRESS] = enabled }
    }

    suspend fun setShowProjectTaskCount(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_PROJECT_TASK_COUNT] = enabled }
    }

    /** Shows [view] in the drawer again, or takes it out. Names this version does not know are kept. */
    suspend fun setDrawerViewHidden(view: DrawerView, hidden: Boolean) {
        dataStore.edit {
            val current = it[KEY_HIDDEN_DRAWER_VIEWS].orEmpty()
            val next = if (hidden) current + view.name else current - view.name
            if (next.isEmpty()) it.remove(KEY_HIDDEN_DRAWER_VIEWS) else it[KEY_HIDDEN_DRAWER_VIEWS] = next
        }
    }
}
