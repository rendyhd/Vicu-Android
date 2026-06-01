package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side ordering for labels in the drawer. Vikunja has no label position field, so the
 * order is stored locally (and does not sync across devices). See issue #6 (Item 7). Stored as a
 * comma-joined list of label ids; ids not present fall back to alphabetical order at the end.
 */
private val Context.labelOrderDataStore by preferencesDataStore(name = "label_order_prefs")

@Singleton
class LabelOrderPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_ORDER = stringPreferencesKey("label_order")
    }

    fun getOrder(): Flow<List<Long>> = context.labelOrderDataStore.data.map { p ->
        p[KEY_ORDER]
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
    }

    suspend fun setOrder(ids: List<Long>) {
        context.labelOrderDataStore.edit { it[KEY_ORDER] = ids.joinToString(",") }
    }
}
