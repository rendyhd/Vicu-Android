package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.data.local.dao.LocalDataDao
import com.rendyhd.vicu.data.sync.SyncStaleness
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.worker.SyncEngine
import kotlinx.coroutines.flow.Flow

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
 *    widget settings) and stops the account's background work.
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
) {
    /**
     * Entries of routine history that have not been uploaded to the server yet (the phone-only
     * history older versions kept; sign-out deletes them). Zero once the upload has run.
     */
    val routineHistoryCount: Flow<Int> = dao.observeRoutineArchiveCount()

    /** Queued changes (waiting, in flight or failed) that have not reached the server. */
    suspend fun unsyncedActionCount(): Int = dao.countUnsyncedActions()

    suspend fun clearCaches() {
        SyncEngine.exclusive {
            dao.clearCaches()
            syncStaleness.reset()
        }
    }

    suspend fun discardUnsyncedAndClearCaches() {
        SyncEngine.exclusive {
            dao.discardUnsyncedAndClearCaches()
            syncStaleness.reset()
        }
    }

    suspend fun wipeEverything() {
        SyncEngine.exclusive {
            dao.clearEverything()
            customLists.clearLocal()
            bottomBarPrefs.clear()
            // Preferences keyed by the old account's project and label ids, and the routine
            // device id. A cache clear keeps them: the same account is synced again.
            projectSectionPrefs.clear()
            labelOrderPrefs.clear()
            routinePrefs.clear()
            widgetPrefs.clear()
            syncStaleness.reset()
        }
        // The old account's reminders must not keep firing for a signed-out (or switched) account.
        platformHooks.cancelAllAlarms()
        // Nor its daily summaries or routine maintenance, and its widgets show nothing of it.
        platformHooks.cancelAccountBackgroundWork()
        platformHooks.clearWidgetConfigurations()
        platformHooks.updateWidgets()
    }
}
