package com.rendyhd.vicu.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-only collapsed state for project sections, scoped to the project view containing them.
 * Vikunja does not store this presentation preference.
 */
class ProjectSectionPrefsStore(
    private val dataStore: DataStore<Preferences>,
) {
    fun collapsedSectionIds(rootProjectId: Long): Flow<Set<Long>> =
        dataStore.data.map { prefs ->
            decodeIds(prefs[key(rootProjectId)])
        }

    suspend fun setExpanded(
        rootProjectId: Long,
        sectionProjectId: Long,
        isExpanded: Boolean,
    ) {
        dataStore.edit { prefs ->
            val prefsKey = key(rootProjectId)
            val collapsedIds = decodeIds(prefs[prefsKey]).toMutableSet()
            if (isExpanded) {
                collapsedIds.remove(sectionProjectId)
            } else {
                collapsedIds.add(sectionProjectId)
            }

            if (collapsedIds.isEmpty()) {
                prefs.remove(prefsKey)
            } else {
                prefs[prefsKey] = collapsedIds.sorted().joinToString(",")
            }
        }
    }

    /** Forgets every collapsed section (the project ids belong to the account that is gone). */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun key(rootProjectId: Long) =
        stringPreferencesKey("collapsed_sections_$rootProjectId")

    private fun decodeIds(raw: String?): Set<Long> =
        raw
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            .orEmpty()
}
