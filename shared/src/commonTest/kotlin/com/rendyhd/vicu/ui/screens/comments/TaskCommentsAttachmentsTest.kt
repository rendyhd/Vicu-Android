package com.rendyhd.vicu.ui.screens.comments

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeCollaborationRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.CommentSegment
import com.rendyhd.vicu.util.FakePlatformFiles
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PickedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A file in a comment (cross-app semantics 9.5): it uploads when picked, the comment points at it
 * when sent, and it can be taken out again before that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCommentsAttachmentsTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val shot = Attachment(id = 3, taskId = 7, fileName = "shot.png", mimeType = "image/png", fileSize = 10)
    private val report = Attachment(id = 4, taskId = 7, fileName = "report.pdf", mimeType = "application/pdf", fileSize = 20)

    private inner class Rig {
        val collaboration = FakeCollaborationRepository().apply { postCommentResult = NetworkResult.Success(Unit) }
        val tasks = FakeTaskRepository().apply {
            put(Task(id = 7, title = "Seven"))
            put(Task(id = 8, title = "Eight"))
            put(Task(id = -2, title = "Not on the server yet"))
        }
        val attachments = FakeAttachmentRepository()
        val files = FakePlatformFiles().apply {
            this.files["uri:shot"] = PickedFile("shot.png", ByteArray(1))
            this.files["uri:report"] = PickedFile("report.pdf", ByteArray(1))
        }
        val messages = AppMessages()
        val tokens = InMemoryTokenStorage()
        val viewModel = TaskCommentsViewModel(collaboration, tasks, attachments, files, messages, tokens)

        /** The picked file uploads as [shot] or [report], whichever its uri names. */
        fun uploadsSucceed() {
            attachments.uploadResult = { taskId, uri ->
                val file = if (uri == "uri:shot") shot else report
                NetworkResult.Success(file.copy(taskId = taskId)).also { attachments.attachments.value += file.copy(taskId = taskId) }
            }
        }
    }

    private suspend fun TestScope.rig(open: Long = 7L): Rig {
        val rig = Rig()
        rig.tokens.storeVikunjaUrl("https://todo.example.com/")
        rig.viewModel.open(open)
        runCurrent()
        return rig
    }

    @Test
    fun `a comment points at the files added to it`() = runTest {
        val rig = rig()
        rig.uploadsSucceed()

        rig.viewModel.attach("uri:shot")
        rig.viewModel.attach("uri:report")
        runCurrent()
        assertEquals(listOf("shot.png", "report.pdf"), rig.viewModel.uiState.value.staged.map { it.name })

        assertTrue(rig.viewModel.post("Look", emptyList()))
        runCurrent()

        assertEquals(
            listOf(
                7L to "<p>Look</p>" +
                    "<img src=\"https://todo.example.com/api/v1/tasks/7/attachments/3\" alt=\"shot.png\">" +
                    "<p><a href=\"https://todo.example.com/api/v1/tasks/7/attachments/4\">report.pdf</a></p>",
            ),
            rig.collaboration.postedComments,
        )
        assertTrue(rig.viewModel.uiState.value.staged.isEmpty(), "sending empties the draft's files")
    }

    @Test
    fun `a comment can be a file alone`() = runTest {
        val rig = rig()
        rig.uploadsSucceed()
        rig.viewModel.attach("uri:report")
        runCurrent()

        assertTrue(rig.viewModel.post("  ", emptyList()))
        runCurrent()

        assertEquals(
            "<p><a href=\"https://todo.example.com/api/v1/tasks/7/attachments/4\">report.pdf</a></p>",
            rig.collaboration.postedComments.single().second,
        )
    }

    @Test
    fun `nothing to send stays unsent`() = runTest {
        val rig = rig()
        assertFalse(rig.viewModel.post(" ", emptyList()))
        runCurrent()
        assertTrue(rig.collaboration.postedComments.isEmpty())
    }

    @Test
    fun `nothing is sent while a file uploads`() = runTest {
        val rig = rig()
        rig.uploadsSucceed()
        rig.viewModel.attach("uri:shot")
        runCurrent()

        // The coroutine that uploads this one has not run yet.
        rig.viewModel.attach("uri:report")
        assertFalse(rig.viewModel.post("Too soon", emptyList()))

        assertTrue(rig.collaboration.postedComments.isEmpty())
        runCurrent()
        assertEquals(2, rig.viewModel.uiState.value.staged.size, "the draft keeps its files")
    }

    @Test
    fun `a failed upload takes the chip away and says why`() = runTest {
        val rig = rig()
        rig.attachments.uploadResult = { _, _ -> NetworkResult.Error("too big") }

        rig.viewModel.attach("uri:shot")
        runCurrent()

        assertTrue(rig.viewModel.uiState.value.staged.isEmpty())
        assertEquals("Could not attach \"shot.png\": too big", rig.messages.messages.first().text)
    }

    @Test
    fun `removing a file takes it off the task too`() = runTest {
        val rig = rig()
        rig.uploadsSucceed()
        rig.viewModel.attach("uri:shot")
        runCurrent()
        val key = rig.viewModel.uiState.value.staged.single().key

        rig.viewModel.removeStaged(key)
        runCurrent()

        assertEquals(listOf(7L to 3L), rig.attachments.deletes)
        assertTrue(rig.viewModel.uiState.value.staged.isEmpty())
        assertTrue(rig.viewModel.post(" ", emptyList()).not(), "nothing is left to send")
    }

    @Test
    fun `a task the server does not know yet takes no files`() = runTest {
        val rig = rig(open = -2L)

        rig.viewModel.attach("uri:shot")
        runCurrent()

        assertTrue(rig.attachments.uploads.isEmpty())
        assertTrue(rig.viewModel.uiState.value.staged.isEmpty())
        assertTrue(rig.messages.messages.first().text.contains("has not reached the server"))
    }

    @Test
    fun `the files stay with their task`() = runTest {
        val rig = rig()
        rig.uploadsSucceed()
        rig.viewModel.attach("uri:shot")
        runCurrent()

        rig.viewModel.open(8L)
        runCurrent()
        assertTrue(rig.viewModel.uiState.value.staged.isEmpty())

        rig.viewModel.open(7L)
        runCurrent()
        assertEquals(listOf("shot.png"), rig.viewModel.uiState.value.staged.map { it.name })
    }

    @Test
    fun `a saved draft gets its files back`() = runTest {
        val rig = rig()
        rig.attachments.attachments.value = listOf(shot, report)
        runCurrent()

        rig.viewModel.restoreStaged(7L, listOf(4L, 99L))
        runCurrent()

        assertEquals(listOf(report), rig.viewModel.uiState.value.staged.map { it.attachment })

        rig.viewModel.restoreStaged(7L, listOf(3L))
        runCurrent()
        assertEquals(listOf(report), rig.viewModel.uiState.value.staged.map { it.attachment }, "a draft with files keeps them")
    }

    private val shotSegment = CommentSegment.Attachment(3, "from the comment", image = true, url = "/api/v1/tasks/7/attachments/3")

    @Test
    fun `an answer from the server says the list is complete`() = runTest {
        val rig = rig()
        assertTrue(rig.viewModel.uiState.value.attachmentsRead)
    }

    @Test
    fun `the list of files is read after the comments`() = runTest {
        // Read in the other order, a file in the newest comment could count as gone.
        val rig = Rig()
        val comments = CompletableDeferred<Unit>()
        rig.collaboration.refreshCommentsGate = comments

        rig.viewModel.open(7L)
        runCurrent()
        assertFalse(rig.viewModel.uiState.value.attachmentsRead)

        comments.complete(Unit)
        runCurrent()
        assertTrue(rig.viewModel.uiState.value.attachmentsRead)
    }

    @Test
    fun `a task the server does not know yet has no list to read`() = runTest {
        val rig = rig(open = -2L)
        assertFalse(rig.viewModel.uiState.value.attachmentsRead)
    }

    @Test
    fun `opening uses what the list of the task knows`() = runTest {
        val rig = rig()
        rig.attachments.attachments.value = listOf(shot)
        runCurrent()

        rig.viewModel.openAttachment(shotSegment)
        runCurrent()

        assertEquals(listOf<Pair<String, String?>>("/cache/attachments/3/shot.png" to "image/png"), rig.files.openedPaths)
    }

    @Test
    fun `a file the list does not hold still opens by the name in the comment`() = runTest {
        val rig = rig()

        rig.viewModel.openAttachment(shotSegment)
        runCurrent()

        assertEquals(listOf(3L), rig.attachments.downloads)
        assertEquals("/cache/attachments/3/from the comment", rig.files.openedPaths.single().first)
    }

    @Test
    fun `the viewer hears about a save itself`() = runTest {
        val rig = rig()
        rig.attachments.attachments.value = listOf(shot)
        rig.files.saveMessage = "Saved to Downloads"
        runCurrent()

        var heard: String? = null
        rig.viewModel.saveAttachment(shotSegment) { heard = it }
        runCurrent()

        assertEquals("Saved to Downloads", heard)
        assertEquals(listOf("/cache/attachments/3/shot.png" to "shot.png"), rig.files.savedCopies)
        assertTrue(rig.viewModel.uiState.value.downloading.isEmpty())
    }

    @Test
    fun `a failed download says so in the viewer`() = runTest {
        val rig = rig()
        rig.attachments.downloadResult = { NetworkResult.Error("offline") }

        var heard: String? = null
        rig.viewModel.shareAttachment(shotSegment) { heard = it }
        runCurrent()

        assertEquals("Could not download \"from the comment\": offline", heard)
        assertTrue(rig.files.sharedPaths.isEmpty())
    }

}
