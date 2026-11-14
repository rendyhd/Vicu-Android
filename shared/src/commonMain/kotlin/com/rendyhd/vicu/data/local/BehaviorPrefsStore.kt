package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rendyhd.vicu.util.pro.Focus
import com.rendyhd.vicu.util.pro.ProFocus
import com.rendyhd.vicu.util.pro.ProLayer
import com.rendyhd.vicu.util.pro.ProLayers
import com.rendyhd.vicu.util.pro.ProSettings
import com.rendyhd.vicu.util.pro.ProSwitches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * A view the drawer can list, in the drawer's default order. The user chooses which ones it lists
 * and where (Settings, General, Drawer; the rules are in `DrawerLayout`); a view that is not listed
 * keeps working.
 */
enum class DrawerView { INBOX, TODAY, PLAN, UPCOMING, ANYTIME, LOGBOOK, ROUTINES, REVIEW, ASK, FLAGGED, WAITING_ON, PEOPLE }

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
    /** The views of the bottom bar the user also wants in the drawer; without this a bar view is not listed there. */
    val drawerBarViews: Set<DrawerView> = emptySet(),
    /** The drawer's order as the user arranged it (`DrawerEntry.key`s); empty is the default order. */
    val drawerOrder: List<String> = emptyList(),
    /** The Plan screen and Today's Day view (contract 16.9); off means nothing of them shows or is fetched. */
    val plannerEnabled: Boolean = false,
    /** Routines as small pills on the timeline. */
    val plannerShowRoutines: Boolean = true,
    /** Whether Today opens on its Day view, the view last chosen there. */
    val plannerTodayDayView: Boolean = false,
    /** The last list or project of the Plan screen's drawer, as `PlanSource.key`. */
    val plannerSource: String = "",
    /** "HH:mm" of the evening nudge to plan tomorrow (contract 14.10), per device; null is off. */
    val plannerNudge: String? = null,
    /** Pro's switch (contract 21.1), per device and never synced; off by default. */
    val proEnabled: Boolean = false,
    /** Pro's layers; a layer not turned off is on. */
    val proLayers: ProLayers = ProLayers.DEFAULT,
    /** Available, Remaining or All per list key (`anytime`, `project:12`, `tag:4`, `list:<id>`). */
    val proListViews: Map<String, ProListView> = emptyMap(),
    /** Focus (contract 21.14), or null for none. */
    val proFocus: ProFocus? = null,
    /** Pro data reaches the assistant only while this is on. */
    val proAssistant: Boolean = false,
    /** "Ask me tomorrow" per promise (by task id): the due day asked about and the day to ask again. */
    val proVerdictSnooze: Map<String, VerdictSnooze> = emptyMap(),
    /** The first-run notice about start dates set elsewhere was shown. */
    val proOnboardingSeen: Boolean = false,
) {
    /** The switch and layers as the Pro rules read them. This app has no standalone mode. */
    val proSwitches: ProSwitches get() = ProSwitches(proEnabled = proEnabled, proLayers = proLayers)

    /** Pro is on, and with a [layer], that layer too. */
    fun isProEnabled(layer: ProLayer? = null): Boolean = ProSettings.isEnabled(proSwitches, layer)
}

/** What a list shows while Pro's availability layer is on (contract 21.2). */
enum class ProListView(val key: String) {
    AVAILABLE("available"),
    REMAINING("remaining"),
    ALL("all"),
    ;

    companion object {
        fun of(key: String?): ProListView? = entries.firstOrNull { it.key == key }
    }
}

/** A promise's verdict put off once: [due] is the due day asked about, [until] the day to ask again. */
data class VerdictSnooze(val due: String, val until: String)

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
        private val KEY_DRAWER_BAR_VIEWS = stringSetPreferencesKey("drawer_bar_views")
        private val KEY_DRAWER_ORDER = stringPreferencesKey("drawer_order")
        private val KEY_PLANNER_ENABLED = booleanPreferencesKey("planner_enabled")
        private val KEY_PLANNER_SHOW_ROUTINES = booleanPreferencesKey("planner_show_routines")
        private val KEY_PLANNER_TODAY_DAY_VIEW = booleanPreferencesKey("planner_today_day_view")
        private val KEY_PLANNER_SOURCE = stringPreferencesKey("planner_source")
        private val KEY_PLANNER_NUDGE = stringPreferencesKey("planner_nudge")
        private val KEY_PRO_ENABLED = booleanPreferencesKey("pro_enabled")
        private val KEY_PRO_LAYERS = stringPreferencesKey("pro_layers")
        private val KEY_PRO_LIST_VIEWS = stringPreferencesKey("pro_list_views")
        private val KEY_PRO_FOCUS = stringPreferencesKey("pro_focus")
        private val KEY_PRO_ASSISTANT = booleanPreferencesKey("pro_assistant")
        private val KEY_PRO_VERDICT_SNOOZE = stringPreferencesKey("pro_verdict_snooze")
        private val KEY_PRO_ONBOARDING_SEEN = booleanPreferencesKey("pro_onboarding_seen")

        /** A stored JSON value, or null when there is none or it does not read. */
        private fun parseJson(raw: String?): JsonElement? =
            raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

        private fun objectOf(raw: String?): JsonObject = parseJson(raw) as? JsonObject ?: JsonObject(emptyMap())

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        internal fun readListViews(raw: String?): Map<String, ProListView> =
            objectOf(raw).mapNotNull { (key, value) -> ProListView.of(value.text())?.let { key to it } }.toMap()

        internal fun readVerdictSnooze(raw: String?): Map<String, VerdictSnooze> =
            objectOf(raw).mapNotNull { (key, value) ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                val due = entry["due"].text() ?: return@mapNotNull null
                val until = entry["until"].text() ?: return@mapNotNull null
                key to VerdictSnooze(due, until)
            }.toMap()
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
                drawerBarViews = prefs[KEY_DRAWER_BAR_VIEWS].orEmpty()
                    .mapNotNullTo(HashSet()) { name -> DrawerView.entries.firstOrNull { it.name == name } },
                drawerOrder = prefs[KEY_DRAWER_ORDER].orEmpty().split(',').filter { it.isNotEmpty() },
                plannerEnabled = prefs[KEY_PLANNER_ENABLED] ?: false,
                plannerShowRoutines = prefs[KEY_PLANNER_SHOW_ROUTINES] ?: true,
                plannerTodayDayView = prefs[KEY_PLANNER_TODAY_DAY_VIEW] ?: false,
                plannerSource = prefs[KEY_PLANNER_SOURCE].orEmpty(),
                plannerNudge = prefs[KEY_PLANNER_NUDGE],
                proEnabled = prefs[KEY_PRO_ENABLED] ?: false,
                proLayers = ProSettings.readLayers(parseJson(prefs[KEY_PRO_LAYERS])),
                proListViews = readListViews(prefs[KEY_PRO_LIST_VIEWS]),
                proFocus = Focus.readProFocus(parseJson(prefs[KEY_PRO_FOCUS])),
                proAssistant = prefs[KEY_PRO_ASSISTANT] ?: false,
                proVerdictSnooze = readVerdictSnooze(prefs[KEY_PRO_VERDICT_SNOOZE]),
                proOnboardingSeen = prefs[KEY_PRO_ONBOARDING_SEEN] ?: false,
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

    suspend fun setPlannerEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PLANNER_ENABLED] = enabled }
    }

    suspend fun setPlannerShowRoutines(shown: Boolean) {
        dataStore.edit { it[KEY_PLANNER_SHOW_ROUTINES] = shown }
    }

    suspend fun setPlannerTodayDayView(dayView: Boolean) {
        dataStore.edit { it[KEY_PLANNER_TODAY_DAY_VIEW] = dayView }
    }

    suspend fun setPlannerSource(key: String) {
        dataStore.edit { it[KEY_PLANNER_SOURCE] = key }
    }

    suspend fun setPlannerNudge(time: String?) {
        dataStore.edit { if (time == null) it.remove(KEY_PLANNER_NUDGE) else it[KEY_PLANNER_NUDGE] = time }
    }

    /** Turns Pro on or off here. Only this switch changes: turning Pro off writes nothing else. */
    suspend fun setProEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PRO_ENABLED] = enabled }
    }

    /** Turns one layer on or off. Layers a newer version stored are kept. */
    suspend fun setProLayer(layer: ProLayer, on: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PRO_LAYERS] = JsonObject(objectOf(prefs[KEY_PRO_LAYERS]) + (layer.key to JsonPrimitive(on))).toString()
        }
    }

    /** What list [listKey] shows; null is the default (Available). Other lists are kept. */
    suspend fun setProListView(listKey: String, view: ProListView?) {
        dataStore.edit { prefs ->
            val stored = objectOf(prefs[KEY_PRO_LIST_VIEWS])
            val next = if (view == null) stored - listKey else stored + (listKey to JsonPrimitive(view.key))
            if (next.isEmpty()) prefs.remove(KEY_PRO_LIST_VIEWS) else prefs[KEY_PRO_LIST_VIEWS] = JsonObject(next).toString()
        }
    }

    /** The focus; null or an empty one is none. */
    suspend fun setProFocus(focus: ProFocus?) {
        dataStore.edit { prefs ->
            if (focus == null || (focus.categories.isEmpty() && focus.projects.isEmpty())) {
                prefs.remove(KEY_PRO_FOCUS)
            } else {
                prefs[KEY_PRO_FOCUS] = JsonObject(
                    mapOf(
                        "categories" to JsonArray(focus.categories.map(::JsonPrimitive)),
                        "projects" to JsonArray(focus.projects.map(::JsonPrimitive)),
                    ),
                ).toString()
            }
        }
    }

    suspend fun setProAssistant(enabled: Boolean) {
        dataStore.edit { it[KEY_PRO_ASSISTANT] = enabled }
    }

    /** "Ask me tomorrow" for the promise of task [taskId]; null forgets it. */
    suspend fun setVerdictSnooze(taskId: String, snooze: VerdictSnooze?) {
        dataStore.edit { prefs ->
            val stored = objectOf(prefs[KEY_PRO_VERDICT_SNOOZE])
            val next = if (snooze == null) {
                stored - taskId
            } else {
                stored + (taskId to JsonObject(mapOf("due" to JsonPrimitive(snooze.due), "until" to JsonPrimitive(snooze.until))))
            }
            if (next.isEmpty()) prefs.remove(KEY_PRO_VERDICT_SNOOZE) else prefs[KEY_PRO_VERDICT_SNOOZE] = JsonObject(next).toString()
        }
    }

    suspend fun setProOnboardingSeen(seen: Boolean) {
        dataStore.edit { it[KEY_PRO_ONBOARDING_SEEN] = seen }
    }

    /** Shows [view] in the drawer again, or takes it out. Names this version does not know are kept. */
    suspend fun setDrawerViewHidden(view: DrawerView, hidden: Boolean) {
        dataStore.edit {
            val current = it[KEY_HIDDEN_DRAWER_VIEWS].orEmpty()
            val next = if (hidden) current + view.name else current - view.name
            if (next.isEmpty()) it.remove(KEY_HIDDEN_DRAWER_VIEWS) else it[KEY_HIDDEN_DRAWER_VIEWS] = next
        }
    }

    /** Lists [view] in the drawer while the bottom bar holds it too, or not. Names this version does not know are kept. */
    suspend fun setDrawerBarViewShown(view: DrawerView, shown: Boolean) {
        dataStore.edit {
            val current = it[KEY_DRAWER_BAR_VIEWS].orEmpty()
            val next = if (shown) current + view.name else current - view.name
            if (next.isEmpty()) it.remove(KEY_DRAWER_BAR_VIEWS) else it[KEY_DRAWER_BAR_VIEWS] = next
        }
    }

    /**
     * The drawer's order: every entry this version knows, as `DrawerEntry.key`s. An empty list is
     * the default order again. Keys only a newer version knows are kept, after the ones given.
     */
    suspend fun setDrawerOrder(keys: List<String>) {
        dataStore.edit {
            if (keys.isEmpty()) {
                it.remove(KEY_DRAWER_ORDER)
            } else {
                val fromNewer = it[KEY_DRAWER_ORDER].orEmpty().split(',').filter { key -> key.isNotEmpty() && key !in keys }
                it[KEY_DRAWER_ORDER] = (keys + fromNewer).joinToString(",")
            }
        }
    }
}
