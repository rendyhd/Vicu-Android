package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.domain.model.TaskOrder
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Local-only task order: the global choice and each project's own (see [TaskOrderSettings]).
 * Vikunja has no field for it, so it does not sync across devices. Overrides are stored as
 * `projectId=key` pairs joined by commas; an unknown key (from a newer version) is skipped.
 *
 * [onChange] runs after every change, so the widgets that show a project can be redrawn in the
 * new order.
 */
class TaskOrderPrefsStore(
    private val dataStore: DataStore<Preferences>,
    private val onChange: () -> Unit = {},
) {
    private companion object {
        val KEY_GLOBAL = stringPreferencesKey("global_order")
        val KEY_OVERRIDES = stringPreferencesKey("project_overrides")
    }

    val settings: Flow<TaskOrderSettings> = dataStore.data.map { prefs ->
        TaskOrderSettings(
            global = TaskOrder.fromKey(prefs[KEY_GLOBAL]) ?: TaskOrder.DEFAULT,
            projectOverrides = decodeOverrides(prefs[KEY_OVERRIDES]),
        )
    }.distinctUntilChanged()

    suspend fun setGlobalOrder(order: TaskOrder) {
        dataStore.edit { it[KEY_GLOBAL] = order.key }
        onChange()
    }

    /** Gives [projectId] its own [order], or with null makes it follow its parent or the global one again. */
    suspend fun setProjectOrder(projectId: Long, order: TaskOrder?) {
        dataStore.edit { prefs ->
            val overrides = decodeOverrides(prefs[KEY_OVERRIDES]).toMutableMap()
            if (order == null) overrides.remove(projectId) else overrides[projectId] = order
            if (overrides.isEmpty()) {
                prefs.remove(KEY_OVERRIDES)
            } else {
                prefs[KEY_OVERRIDES] = encodeOverrides(overrides)
            }
        }
        onChange()
    }

    /** Every project follows the global order again. */
    suspend fun clearProjectOverrides() {
        dataStore.edit { it.remove(KEY_OVERRIDES) }
        onChange()
    }

    /**
     * Forgets the projects' own orders (their ids belong to the account that is gone). The global
     * order is the user's, not the account's, and stays.
     */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY_OVERRIDES) }
    }

    private fun encodeOverrides(overrides: Map<Long, TaskOrder>): String =
        overrides.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.key}" }

    private fun decodeOverrides(raw: String?): Map<Long, TaskOrder> =
        raw?.split(',')
            ?.mapNotNull { pair ->
                val id = pair.substringBefore('=').trim().toLongOrNull() ?: return@mapNotNull null
                val order = TaskOrder.fromKey(pair.substringAfter('=', "").trim()) ?: return@mapNotNull null
                id to order
            }
            ?.toMap()
            .orEmpty()
}
