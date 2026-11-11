package com.rendyhd.vicu.data.local

import com.rendyhd.vicu.util.ioDispatcher
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

/**
 * Copies of files attached while the server could not be reached, kept until the queued upload
 * has gone through (the same as the desktop's offline attachments folder). A picked file cannot
 * be read later (Android grants the app a picked file only for a while, iOS hands out a temporary
 * copy), so the bytes are copied in at once.
 *
 * Files are named by the time they were written and a random part, never by the user's file
 * name, so a name can never become a path. The display name lives in the queued action.
 */
class OfflineAttachmentFiles(
    /** The folder, outside backups; created when missing. */
    private val directory: () -> String,
    private val nowMillis: () -> Long,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) {
    private fun dir(): Path = Path(directory()).also { SystemFileSystem.createDirectories(it) }

    /** The path of the stored file [name]; throws for a name this class did not make. */
    fun pathOf(name: String): String {
        require(STORED_NAME.matches(name)) { "Invalid offline attachment name: $name" }
        return Path(dir(), name).toString()
    }

    /** True when the stored file [name] is still there. */
    suspend fun exists(name: String): Boolean = withContext(dispatcher) {
        STORED_NAME.matches(name) && SystemFileSystem.metadataOrNull(Path(pathOf(name)))?.isRegularFile == true
    }

    /**
     * Writes [content] to a new file and returns its name. A failed write leaves nothing behind.
     */
    suspend fun save(content: ByteReadChannel): String = withContext(dispatcher) {
        val name = "${nowMillis()}-${randomHex()}.bin"
        val target = Path(pathOf(name))
        val part = Path("$target$PART_SUFFIX")
        try {
            SystemFileSystem.sink(part).buffered().use { sink ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = content.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) {
                        if (content.isClosedForRead) break
                        continue
                    }
                    sink.write(buffer, 0, read)
                }
            }
            SystemFileSystem.atomicMove(part, target)
        } catch (e: Throwable) {
            SystemFileSystem.delete(part, mustExist = false)
            throw e
        }
        name
    }

    /** Streams the stored file [name]; nothing is read until the channel is. */
    fun readChannel(name: String): ByteReadChannel {
        val path = Path(pathOf(name))
        return CoroutineScope(dispatcher).writer {
            SystemFileSystem.source(path).buffered().use { source ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = source.readAtMostTo(buffer, 0, buffer.size)
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                }
            }
        }.channel
    }

    /** Deletes the stored file [name]; one that is already gone is not an error. */
    suspend fun delete(name: String) = withContext(dispatcher) {
        if (STORED_NAME.matches(name)) SystemFileSystem.delete(Path(pathOf(name)), mustExist = false)
    }

    /**
     * Deletes stored files no queued or failed upload names ([keep]) and leftovers of an interrupted
     * write. A file younger than [minAgeMillis] stays: it may have been written a moment before its
     * upload joins the queue. Returns how many files went.
     */
    suspend fun sweep(keep: Set<String>, minAgeMillis: Long = SWEEP_MIN_AGE_MS): Int = withContext(dispatcher) {
        val folder = Path(directory())
        if (!SystemFileSystem.exists(folder)) return@withContext 0
        val cutoff = nowMillis() - minAgeMillis
        var removed = 0
        for (path in SystemFileSystem.list(folder)) {
            val name = path.name
            val stored = name.removeSuffix(PART_SUFFIX)
            if (!STORED_NAME.matches(stored)) continue
            val writtenAt = stored.substringBefore('-').toLongOrNull() ?: continue
            val stale = writtenAt < cutoff && (name.endsWith(PART_SUFFIX) || stored !in keep)
            if (!stale) continue
            if (runCatching { SystemFileSystem.delete(path, mustExist = false) }.isSuccess) removed++
        }
        removed
    }

    private fun randomHex(): String =
        Random.nextBytes(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        private val STORED_NAME = Regex("""^\d{1,19}-[a-f0-9]{16}\.bin$""")
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024

        /** Files younger than this survive a sweep even when nothing names them yet. */
        const val SWEEP_MIN_AGE_MS = 60 * 60 * 1000L
    }
}
