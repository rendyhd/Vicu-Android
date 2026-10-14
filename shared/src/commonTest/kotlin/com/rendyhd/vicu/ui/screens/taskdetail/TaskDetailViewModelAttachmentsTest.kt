package com.rendyhd.vicu.ui.screens.taskdetail

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FakePlatformFiles
import com.rendyhd.vicu.util.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uploading, opening, sharing and deleting attachments from the task editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelAttachmentsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val pdf = Attachment(id = 9, taskId = 42, fileName = "plan.pdf", mimeType = "application/pdf", fileSize = 2_000)

    private class Rig(scope: TestScope) {
        val attachments = FakeAttachmentRepository()
        val files = FakePlatformFiles()
        val received = mutableListOf<String>()
        private val authScope = CoroutineScope(SupervisorJob())
        private val messages = AppMessages()

        init {
            scope.backgroundScope.launch { messages.messages.collect { received += it.text } }
        }

        fun close() = authScope.cancel()

        val viewModel = TaskDetailViewModel(
            taskRepository = FakeTaskRepository().apply { put(Task(id = 42, title = "Plan", projectId = 1)) },
            labelRepository = FakeLabelRepository(),
            attachmentRepository = attachments,
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            platformFiles = files,
            appMessages = messages,
            dayClock = DayClock(authScope, ticking = false),
        )
    }

    private suspend fun TestScope.opened(): Rig {
        val rig = Rig(this)
        rig.attachments.attachments.value = listOf(pdf)
        rig.viewModel.loadTask(42)
        runCurrent()
        assertEquals(listOf(pdf), rig.viewModel.uiState.value.attachments)
        return rig
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    fun `a deleted attachment is hidden while the server is asked and gone once it agrees`() = runTest {
        val rig = opened()
        rig.attachments.deleteGate = CompletableDeferred()

        rig.viewModel.deleteAttachment(9)
        runCurrent()
        assertEquals(setOf(9L), rig.viewModel.uiState.value.deletingAttachmentIds)

        rig.attachments.deleteGate!!.complete(Unit)
        runCurrent()

        assertEquals(emptySet<Long>(), rig.viewModel.uiState.value.deletingAttachmentIds)
        assertEquals(emptyList<Attachment>(), rig.viewModel.uiState.value.attachments)
        assertEquals(listOf(42L to 9L), rig.attachments.deletes)
        assertTrue(rig.received.isEmpty())
        rig.close()
    }

    @Test
    fun `a delete the server refuses brings the attachment back and says why`() = runTest {
        val rig = opened()
        rig.attachments.deleteResult = NetworkResult.Error("boom")

        rig.viewModel.deleteAttachment(9)
        runCurrent()

        assertEquals(emptySet<Long>(), rig.viewModel.uiState.value.deletingAttachmentIds, "no longer hidden")
        assertEquals(listOf(pdf), rig.viewModel.uiState.value.attachments, "still attached")
        assertEquals(listOf("Could not delete the attachment: boom"), rig.received)
        rig.close()
    }

    @Test
    fun `a second tap on delete while one is running does not send it twice`() = runTest {
        val rig = opened()
        rig.attachments.deleteGate = CompletableDeferred()

        rig.viewModel.deleteAttachment(9)
        rig.viewModel.deleteAttachment(9)
        runCurrent()
        rig.attachments.deleteGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, rig.attachments.deletes.size)
        rig.close()
    }

    // ---- open and share ----------------------------------------------------------------------

    @Test
    fun `opening an attachment downloads it to the cache and opens that file`() = runTest {
        val rig = opened()

        rig.viewModel.openAttachment(pdf)
        runCurrent()

        assertEquals(listOf(9L), rig.attachments.downloads)
        assertEquals(listOf<Pair<String, String?>>("/cache/attachments/9/plan.pdf" to "application/pdf"), rig.files.openedPaths)
        assertEquals(emptySet<Long>(), rig.viewModel.uiState.value.downloadingAttachmentIds)
        assertTrue(rig.received.isEmpty())
        rig.close()
    }

    @Test
    fun `sharing an attachment downloads it and offers the cached file`() = runTest {
        val rig = opened()

        rig.viewModel.shareAttachment(pdf)
        runCurrent()

        assertEquals(listOf<Pair<String, String?>>("/cache/attachments/9/plan.pdf" to "application/pdf"), rig.files.sharedPaths)
        assertTrue(rig.files.openedPaths.isEmpty())
        rig.close()
    }

    @Test
    fun `a failed download is reported and nothing is opened`() = runTest {
        val rig = opened()
        rig.attachments.downloadResult = { NetworkResult.Error("offline") }

        rig.viewModel.openAttachment(pdf)
        runCurrent()

        assertEquals(listOf("Could not download \"plan.pdf\": offline"), rig.received)
        assertTrue(rig.files.openedPaths.isEmpty())
        assertEquals(emptySet<Long>(), rig.viewModel.uiState.value.downloadingAttachmentIds)
        rig.close()
    }

    @Test
    fun `when no app can open the file the user is told`() = runTest {
        val rig = opened()
        rig.files.presentMessage = "No app on this device can open this kind of file."

        rig.viewModel.openAttachment(pdf)
        runCurrent()

        assertEquals(listOf("No app on this device can open this kind of file."), rig.received)
        rig.close()
    }

    @Test
    fun `tapping an attachment that is being downloaded does not start a second download`() = runTest {
        val rig = opened()

        rig.viewModel.openAttachment(pdf)
        rig.viewModel.openAttachment(pdf)
        runCurrent()

        assertEquals(1, rig.attachments.downloads.size)
        rig.close()
    }

    // ---- upload ------------------------------------------------------------------------------

    @Test
    fun `a refused upload is reported in the app-wide snackbar`() = runTest {
        val rig = opened()
        rig.attachments.uploadResult = { _, _ -> NetworkResult.Error("\"big.bin\" is 25 MB, but the server accepts files up to 20 MB") }

        rig.viewModel.uploadAttachment("content://pick/1")
        runCurrent()

        assertEquals(listOf(42L to "content://pick/1"), rig.attachments.uploads)
        assertEquals(listOf("\"big.bin\" is 25 MB, but the server accepts files up to 20 MB"), rig.received)
        assertEquals(false, rig.viewModel.uiState.value.isUploadingAttachment)
        rig.close()
    }
}
