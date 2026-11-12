package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.dictation.KeptTranscriptStore
import com.rendyhd.vicu.data.local.dao.LocalDataDao
import com.rendyhd.vicu.data.repository.ListPositioner
import com.rendyhd.vicu.data.repository.ProLedgerRepository
import com.rendyhd.vicu.data.repository.SyncedPreferences
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.location.LocationReminderStore
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.worker.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * The only place that clears local data, so every screen that does it keeps the same promise:
 * work that has not reached the server survives unless the user explicitly discards it or the
 * account it belongs to is gone.
 *
 *  - [clearCaches]: a "clear cache and re-sync". Tasks, projects, labels and attachments are
 *    rebuilt from the server. The offline queue, the rows the queue refers to, and routine
 *    history that is still waiting to be uploaded stay.
 *  - [discardUnsyncedAndClearCaches]: the same, after the user chose to drop the offline queue.
 *  - [wipeEverything]: sign-out, or a different account signed in. Also forgets the preferences
 *    that are keyed by the old account's ids (collapsed sections, label order, routine and
 *    widget settings), the synced settings' local copy and the location reminders, stops the
 *    account's background work and drops shares not added yet.
 *
 * Every operation waits for a running sync to finish and blocks new ones while it works, so a
 * sync cannot write the old account's data back right after the wipe.
 */
class LocalDataWiper(
    private val dao: LocalDataDao,
    private val customLists: CustomListRepository,
    private val bottomBarPrefs: BottomBarPrefsStore,
    private val platformHooks: PlatformRepositoryHooks,
    private val syncStaleness: SyncStaleness,
    private val projectSectionPrefs: ProjectSectionPrefsStore,
    private val labelOrderPrefs: LabelOrderPrefsStore,
    private val routinePrefs: RoutinePrefsStore,
    private val widgetPrefs: WidgetPrefsStore,
    /** Forgotten with the cache, so the next refresh starts from the server's full list. */
    private val syncCursor: SyncCursorStore,
    /** The ids of the account's hidden carrier tasks; another account's ids mean nothing. */
    private val carrierIds: CarrierIdStore,
    /** What was learned about the old account's lists (view ids, last positions). */
    private val listPositions: ListPositioner,
    /** The account's synced settings; their unsynced changes count like custom lists'. */
    private val syncedPreferences: SyncedPreferences? = null,
    /** The account's location reminders (device-only; the saved places are a device setting and stay). */
    private val locationReminders: LocationReminderStore? = null,
    /**
     * The assistant's conversations and a kept dictation name the old account's tasks. The
     * assistant's settings and key belong to the device and stay (docs/ai-assistant-v1.md, 7.2).
     */
    private val assistantConversations: ConversationStore? = null,
    private val keptTranscript: KeptTranscriptStore? = null,
    /** What this device knows of the account's saved filters (cross-app semantics 3.7). */
    private val savedFilters: SavedFilterStore? = null,
    /** Copies of files waiting to upload; they go with the queue that names them. */
    private val offlineAttachments: OfflineAttachmentFiles? = null,
    /** The account's Pro ledger copy; its unsynced changes count while Pro is on. */
    private val proLedger: ProLedgerRepository? = null,
) {
    /**
     * Entries of routine history that have not been uploaded to the server yet (the phone-only
     * history older versions kept; sign-out deletes them). Zero once the upload has run.
     */
    val routineHistoryCount: Flow<Int> = dao.observeRoutineArchiveCount()

    /** Queued changes (waiting, in flight or failed) that have not reached the server. */
    suspend fun unsyncedActionCount(): Int = dao.countUnsyncedActions()

    private val prefsUnsynced: Flow<Boolean> = syncedPreferences?.hasUnsyncedChanges ?: flowOf(false)
    private val proUnsynced: Flow<Boolean> = proLedger?.hasUnsyncedChanges ?: flowOf(false)

    /** Custom-list or synced-settings changes the server may not have yet; they are not in the queue. */
    val syncedDataUnsynced: Flow<Boolean> =
        combine(customLists.hasUnsyncedChanges, prefsUnsynced, proUnsynced) { lists, prefs, pro -> lists || prefs || pro }

    /**
     * Everything [wipeEverything] would lose that has not reached the server: the queued changes,
     * and unsynced custom-list and settings changes, each counted as one.
     */
    suspend fun unsyncedChangeCount(): Int =
        unsyncedActionCount() +
            (if (customLists.hasUnsyncedChanges.first()) 1 else 0) +
            (if (prefsUnsynced.first()) 1 else 0) +
            (if (proUnsynced.first()) 1 else 0)

    suspend fun clearCaches() {
        SyncEngine.exclusive {
            dao.clearCaches()
            syncCursor.clear()
            syncStaleness.reset()
        }
    }

    suspend fun discardUnsyncedAndClearCaches() {
        SyncEngine.exclusive {
            dao.discardUnsyncedAndClearCaches()
            offlineAttachments?.sweep(keep = emptySet(), minAgeMillis = 0)
            // The custom lists and settings changed here and not yet synced go too; the next sync
            // reads them back from their carriers.
            customLists.clearLocal()
            syncedPreferences?.clearLocal()
            // The Pro ledger only while Pro is on: with it off its changes were not counted as unsynced.
            if (proLedger?.isProOn() == true) proLedger.clearLocal()
            syncCursor.clear()
            syncStaleness.reset()
        }
    }

    suspend fun wipeEverything() {
        SyncEngine.exclusive {
            dao.clearEverything()
            offlineAttachments?.sweep(keep = emptySet(), minAgeMillis = 0)
            customLists.clearLocal()
            syncedPreferences?.clearLocal()
            proLedger?.clearLocal()
            bottomBarPrefs.clear()
            // Preferences keyed by the old account's project and label ids, and the routine
            // device id. A cache clear keeps them: the same account is synced again.
            projectSectionPrefs.clear()
            labelOrderPrefs.clear()
            routinePrefs.clear()
            widgetPrefs.clear()
            carrierIds.clear()
            listPositions.clear()
            syncCursor.clear()
            syncStaleness.reset()
            // Clearing them stops the watched regions too (LocationReminderCoordinator).
            locationReminders?.clear()
            assistantConversations?.clear()
            keptTranscript?.clear()
            savedFilters?.clear()
        }
        // The old account's reminders must not keep firing for a signed-out (or switched) account.
        platformHooks.cancelAllAlarms()
        // Nor its daily summaries or routine maintenance, and its widgets show nothing of it.
        platformHooks.cancelAccountBackgroundWork()
        platformHooks.clearWidgetConfigurations()
        platformHooks.discardPendingShares()
        platformHooks.updateWidgets()
    }
}
