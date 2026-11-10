package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.SavedFilterImport
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Turns the account's Vikunja saved filters into custom lists (cross-app semantics 3.7): all of
 * them on this device's first connect, and the ones the person picks after that.
 */
class SavedFilterImporter(
    private val api: VikunjaApiService,
    private val store: SavedFilterStore,
    private val customLists: CustomListRepository,
    private val messages: AppMessages? = null,
    private val newId: () -> String = ::randomUuid,
) {
    /** One filter of the review sheet, with what importing it would do. */
    data class Preview(
        val filter: SavedFilterRef,
        val rule: String,
        val result: SavedFilterImport.Result,
        val importedBefore: Boolean,
    )

    /** The filters on the server that were never imported and not put off with Not now. */
    val offer: Flow<List<SavedFilterRef>> =
        combine(store.known, customLists.importedSavedFilters, store.dismissed) { known, imported, dismissed ->
            known.filter { it.id !in imported && it.id !in dismissed }
        }

    /** Every filter on the server, as the last project refresh found them. */
    val known: Flow<List<SavedFilterRef>> = store.known

    /**
     * The step after a sync (SyncEngine): drop duplicate imports, and on this device's first
     * connect import every readable filter not imported yet. [listsSynced] is the custom-list
     * sync's result: without a read of the carrier, the import record is not known and nothing
     * is imported. Returns whether lists were added (the caller syncs them right away).
     */
    suspend fun afterSync(listsSynced: CustomListSyncStatus): Boolean {
        if (listsSynced != CustomListSyncStatus.Idle) return false
        customLists.dropDuplicateImports()
        if (store.isFirstConnectDone()) return false
        val imported = customLists.importedSavedFilters.first()
        val wanted = store.known.first().filter { it.id !in imported }
        val previews = previews(wanted) ?: return false
        val added = import(previews.filter { it.result.list != null })
        store.markFirstConnectDone()
        return added > 0
    }

    /** The review sheet's rows for [filters], read from the server; null when that failed. */
    suspend fun previews(filters: List<SavedFilterRef>): List<Preview>? {
        val imported = customLists.importedSavedFilters.first()
        return try {
            filters.map { ref ->
                val dto = api.getSavedFilter(ref.id)
                Preview(
                    filter = ref,
                    rule = dto.filters.filter.ifBlank { dto.filters.s },
                    result = SavedFilterImport.convert(dto, newId()),
                    importedBefore = ref.id in imported,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Reading saved filters failed: ${e.message}")
            null
        }
    }

    /** Imports [previews] that can be imported; says so on the app-wide snackbar. The count added. */
    suspend fun import(previews: List<Preview>): Int {
        val lists = previews.mapNotNull { it.result.list }
        if (lists.isEmpty()) return 0
        customLists.importLists(lists, lists.mapNotNull { it.savedFilterId })
        val text = if (lists.size == 1) "Imported 1 saved filter as a list" else "Imported ${lists.size} saved filters as lists"
        messages?.postOpeningList(text, lists.first().id)
        return lists.size
    }

    /** Hides the offer for [filters] on this device until another filter appears. */
    suspend fun dismiss(filters: List<SavedFilterRef>) = store.dismiss(filters.map { it.id })

    /** Whether the first-open notice of the imported list [listId] was closed on this device. */
    val noticeClosed: Flow<Set<String>> = store.noticeClosed

    suspend fun closeNotice(listId: String) = store.closeNotice(listId)

    private companion object {
        const val TAG = "SavedFilterImporter"
    }
}
