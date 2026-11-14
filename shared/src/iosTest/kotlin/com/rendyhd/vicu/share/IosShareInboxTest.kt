package com.rendyhd.vicu.share

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.NetworkMonitor
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosShareInboxTest {
    private val folder = NSTemporaryDirectory() + "share-inbox-test-" + NSUUID().UUIDString
    private val storage = InMemoryTokenStorage()
    private val tasks = FakeTaskRepository()
    private val attachments = FakeAttachmentRepository()
    private val online = MutableStateFlow(true)
    private val network = object : NetworkMonitor {
        override val isOnline = online
    }
    private val messages = AppMessages()
    private var syncs = 0

    private fun TestScope.inbox(): IosShareInbox {
        val requester = SyncRequester(scope = backgroundScope, sync = { syncs++; true }, isSignedIn = { true })
        return IosShareInbox(
            taskRepository = tasks,
            attachmentRepository = attachments,
            tokenStorage = storage,
            networkMonitor = network,
            appMessages = messages,
            syncRequester = { requester },
            folder = { folder },
        )
    }

    private fun write(path: String, text: String) {
        SystemFileSystem.createDirectories(Path(path).parent!!)
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
    }

    private fun exists(path: String) = SystemFileSystem.exists(Path(path))

    private fun share(id: String, json: String, vararg files: String) {
        write("$folder/$id/item.json", json)
        files.forEach { write("$folder/$id/files/$it", "content of $it") }
    }

    private suspend fun signIn() {
        storage.storeVikunjaUrl("https://vikunja.test")
        storage.storeApiToken("tk_1", 0L)
        storage.storeInboxProjectId(3)
    }

    private fun createSucceeds(id: Long = 41) {
        tasks.createHandler = { NetworkResult.Success(it.copy(id = id)) }
    }

    @AfterTest
    fun cleanUp() {
        fun delete(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
            SystemFileSystem.delete(path, mustExist = false)
        }
        delete(Path(folder))
    }

    @Test
    fun aShareBecomesATaskInTheInboxAndIsSynced() = runTest {
        signIn()
        createSucceeds()
        share("a", """{"title":"Read this article","notes":"https://example.com/post"}""")

        assertEquals(1, inbox().import())
        runCurrent()

        val created = tasks.created.single()
        assertEquals("Read this article", created.title)
        assertEquals("https://example.com/post", created.description)
        assertEquals(3L, created.projectId)
        assertFalse(exists("$folder/a"))
        assertEquals(1, syncs)
        assertEquals("Added to Inbox", messages.messages.first().text)
    }

    @Test
    fun filesAreAttachedToTheNewTask() = runTest {
        signIn()
        createSucceeds(41)
        attachments.uploadResult = { taskId, uri -> NetworkResult.Success(Attachment(id = 1, taskId = taskId, fileName = uri.substringAfterLast('/'))) }
        share("b", """{"title":"","notes":"","files":["photo.jpg","notes.pdf"]}""", "photo.jpg", "notes.pdf")

        assertEquals(1, inbox().import())

        // A share without a title is named after its first file.
        assertEquals("photo.jpg", tasks.created.single().title)
        assertEquals(listOf(41L to "$folder/b/files/photo.jpg", 41L to "$folder/b/files/notes.pdf"), attachments.uploads)
        assertFalse(exists("$folder/b"))
    }

    @Test
    fun aShareWithFilesWaitsForAConnection() = runTest {
        signIn()
        createSucceeds()
        online.value = false
        share("c", """{"title":"Scan","files":["scan.pdf"]}""", "scan.pdf")

        assertEquals(0, inbox().import())
        assertTrue(tasks.created.isEmpty())
        assertTrue(exists("$folder/c/files/scan.pdf"))

        online.value = true
        assertEquals(1, inbox().import())
        assertEquals(1, attachments.uploads.size)
    }

    @Test
    fun anImportCutShortDoesNotAddTheTaskTwice() = runTest {
        signIn()
        createSucceeds()
        attachments.uploadResult = { taskId, _ -> NetworkResult.Success(Attachment(id = 1, taskId = taskId, fileName = "a")) }
        share("d", """{"title":"Receipt","files":["receipt.png"]}""", "receipt.png")
        write("$folder/d/progress.json", """{"taskId":77}""")

        // Only the files that were left are sent, to the task added before.
        assertEquals(0, inbox().import())
        assertTrue(tasks.created.isEmpty())
        assertEquals(listOf(77L to "$folder/d/files/receipt.png"), attachments.uploads)
        assertFalse(exists("$folder/d"))
    }

    @Test
    fun unfinishedSharesAndSignedOutAppsAreLeftAlone() = runTest {
        createSucceeds()
        share("e", """{"title":"Later"}""")
        // Signed out: nothing to add it to yet.
        assertEquals(0, inbox().import())
        assertTrue(exists("$folder/e"))

        signIn()
        write("$folder/f.part/item.json", """{"title":"Still being written"}""")
        assertEquals(1, inbox().import())
        assertEquals(listOf("Later"), tasks.created.map { it.title })
        assertTrue(exists("$folder/f.part/item.json"))
    }

    @Test
    fun sharesForAnAccountThatIsGoneAreDropped() = runTest {
        share("h", """{"title":"For the old account","files":["photo.jpg"]}""", "photo.jpg")
        write("$folder/i.part/item.json", """{"title":"Still in the share sheet"}""")
        val inbox = inbox()

        inbox.discardAll()

        // The folder stays, with its backup exclusion; what was in it is gone.
        assertTrue(exists(folder))
        assertTrue(SystemFileSystem.list(Path(folder)).isEmpty())
        signIn()
        createSucceeds()
        assertEquals(0, inbox.import())
        assertTrue(tasks.created.isEmpty())
    }

    @Test
    fun aRefusedShareIsReportedAndDropped() = runTest {
        signIn()
        tasks.createHandler = { NetworkResult.Error("Forbidden", code = 403) }
        share("g", """{"title":"Nope"}""")

        assertEquals(0, inbox().import())
        assertFalse(exists("$folder/g"))
        assertEquals("Could not add “Nope”: Forbidden", messages.messages.first().text)
    }
}
