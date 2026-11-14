package com.rendyhd.vicu.share

import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.AttachmentRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.IosPaths
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncRequester
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** What the share extension saved: `share-inbox/<id>/item.json`, the files in `files/` next to it. */
@Serializable
data class SharedInboxItem(
    val title: String = "",
    val notes: String = "",
    /** File names in the item's `files/` folder. */
    val files: List<String> = emptyList(),
)

/** Written once the task exists, so an import cut short never creates it twice. */
@Serializable
private data class SharedInboxProgress(val taskId: Long)

/**
 * Adds what was shared to Vicu from other apps. The share extension cannot run the shared code,
 * so it saves each share in the App Group (`share-inbox/`); the app turns them into tasks in the
 * Inbox, with the files attached, when it starts, comes to the front, gets a connection back and
 * in the background refresh, and then syncs.
 *
 * A share with files waits for a connection (the files go straight to the server, as when they
 * are attached in the editor); one with only text is created offline like any other task.
 */
class IosShareInbox(
    private val taskRepository: TaskRepository,
    private val attachmentRepository: AttachmentRepository,
    private val tokenStorage: TokenStorage,
    private val networkMonitor: NetworkMonitor,
    private val appMessages: AppMessages,
    private val syncRequester: () -> SyncRequester,
    private val folder: () -> String? = { IosPaths.appGroupFolder("share-inbox") },
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Imports every finished share; returns how many tasks were added. */
    suspend fun import(): Int = mutex.withLock {
        val root = folder()?.let(::Path) ?: return@withLock 0
        if (!SystemFileSystem.exists(root)) return@withLock 0
        if (tokenStorage.getVikunjaUrl().isNullOrBlank()) return@withLock 0
        // Without an Inbox there is nowhere to put them yet: they wait until one is chosen.
        val inbox = tokenStorage.getInboxProjectId()?.takeIf { it > 0 } ?: return@withLock 0

        // The extension writes `<id>.part` and renames it when done.
        val items = SystemFileSystem.list(root)
            .filter { !it.name.endsWith(".part") && !it.name.startsWith(".") && SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
            .sortedBy { it.name }
        val added = mutableListOf<String>()
        var changed = false
        for (dir in items) {
            try {
                when (val outcome = importItem(dir, inbox)) {
                    is Outcome.Added -> {
                        added += outcome.title
                        changed = true
                    }
                    Outcome.Progressed -> changed = true
                    Outcome.Waiting -> {}
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Shared item ${dir.name} not imported: ${e.message}")
            }
        }
        when (added.size) {
            0 -> {}
            1 -> appMessages.post("Added to Inbox")
            else -> appMessages.post("Added ${added.size} shared tasks to the Inbox")
        }
        if (changed) syncRequester().request()
        added.size
    }

    /**
     * Drops every share that was not added yet, finished or still open in the share sheet: the
     * account it was meant for is gone (sign-out, another account), and the next one's Inbox is
     * not where it was sent. The folder itself stays, with its backup exclusion.
     */
    suspend fun discardAll(): Unit = mutex.withLock {
        val root = folder()?.let(::Path) ?: return@withLock
        if (SystemFileSystem.exists(root)) SystemFileSystem.list(root).forEach(::delete)
    }

    private sealed interface Outcome {
        data class Added(val title: String) : Outcome
        /** Files were attached to a task added before. */
        data object Progressed : Outcome
        data object Waiting : Outcome
    }

    private suspend fun importItem(dir: Path, inbox: Long): Outcome {
        val item = read(Path(dir, ITEM_FILE))?.let { runCatching { json.decodeFromString(SharedInboxItem.serializer(), it) }.getOrNull() }
        if (item == null) {
            // Unreadable: nothing the app could ever add.
            delete(dir)
            return Outcome.Waiting
        }
        val files = item.files.map { Path(Path(dir, FILES_DIR), it) }.filter { SystemFileSystem.exists(it) }
        val progressPath = Path(dir, PROGRESS_FILE)
        val title = item.title.trim().ifEmpty { files.firstOrNull()?.name ?: "Shared item" }

        var added = false
        val taskId = read(progressPath)?.let { json.decodeFromString(SharedInboxProgress.serializer(), it).taskId } ?: run {
            if (files.isNotEmpty() && !networkMonitor.isOnline.value) return Outcome.Waiting
            when (val result = taskRepository.create(Task(id = 0, title = title, description = item.notes.trim(), projectId = inbox))) {
                is NetworkResult.Success -> {
                    write(progressPath, json.encodeToString(SharedInboxProgress.serializer(), SharedInboxProgress(result.data.id)))
                    added = true
                    result.data.id
                }
                is NetworkResult.Error -> {
                    if (result.offline) return Outcome.Waiting
                    // Refused: trying again would only be refused again.
                    appMessages.post("Could not add “$title”: ${result.message}")
                    delete(dir)
                    return Outcome.Waiting
                }
                NetworkResult.Loading -> return Outcome.Waiting
            }
        }

        if (taskId > 0) {
            for (file in files) {
                when (val result = attachmentRepository.uploadPicked(taskId, file.toString())) {
                    is NetworkResult.Success -> SystemFileSystem.delete(file, mustExist = false)
                    is NetworkResult.Error -> {
                        // The connection went: the rest goes with the next import.
                        if (!networkMonitor.isOnline.value) return if (added) Outcome.Added(title) else Outcome.Progressed
                        appMessages.post(result.message)
                        SystemFileSystem.delete(file, mustExist = false)
                    }
                    NetworkResult.Loading -> {}
                }
            }
        } else if (files.isNotEmpty()) {
            // The connection went between the check and the create, so the task waits in the
            // queue under a temporary id and the files cannot follow it.
            appMessages.post("“$title” was added without its files: there was no connection")
        }
        delete(dir)
        return if (added) Outcome.Added(title) else Outcome.Progressed
    }

    private fun read(path: Path): String? =
        if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered().use { it.readString() } else null

    private fun write(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    private fun delete(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
        SystemFileSystem.delete(path, mustExist = false)
    }

    private companion object {
        const val TAG = "IosShareInbox"
        const val ITEM_FILE = "item.json"
        const val PROGRESS_FILE = "progress.json"
        const val FILES_DIR = "files"
    }
}
