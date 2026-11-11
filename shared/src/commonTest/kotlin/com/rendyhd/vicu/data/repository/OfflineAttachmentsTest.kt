package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.OfflineAttachmentFiles
import com.rendyhd.vicu.data.local.dao.AttachmentQueue
import com.rendyhd.vicu.data.local.dao.QueuedUpload
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.repository.AttachResult
import com.rendyhd.vicu.util.FakePlatformFiles
import com.rendyhd.vicu.util.ImageTokens
import com.rendyhd.vicu.util.PickedFile
import com.rendyhd.vicu.util.WaitingUploadText
import com.rendyhd.vicu.worker.AttachmentUploadSync
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Issue 104: files attached while the server cannot be reached wait in the queue and upload later. */
class OfflineAttachmentsTest {

    private val dir = Path(SystemTemporaryDirectory, "vicu-offline-${Random.nextLong().toULong()}").toString()
    private var now = 1_000_000L
    private val store = OfflineAttachmentFiles(directory = { dir }, nowMillis = { now }, dispatcher = Dispatchers.Unconfined)

    @AfterTest
    fun cleanUp() {
        val folder = Path(dir)
        if (SystemFileSystem.exists(folder)) {
            SystemFileSystem.list(folder).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(folder, mustExist = false)
        }
    }

    private fun read(name: String): ByteArray =
        SystemFileSystem.source(Path(store.pathOf(name))).buffered().use { it.readByteArray() }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = body, status = status, headers = authTestJsonHeaders)

    private fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}"""

    private fun attachmentJson(id: Long, name: String) =
        """{"id":$id,"task_id":5,"file":{"id":$id,"name":"$name","mime":"image/jpeg","size":4},"created":"2026-11-10T10:00:00Z"}"""

    private inner class Rig(
        online: Boolean = true,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("No request expected: ${it.method.value} ${it.url.encodedPath}") },
    ) {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val bodies = mutableListOf<String>()
        val queue = FakePendingActionDao()
        val attachments = FakeAttachmentDao()
        val tasks = FakeTaskDao(listOf(TaskEntity(id = 5, title = "Fix the porch light", description = "Breaker trips")))
        val hooks = RecordingRepositoryHooks()
        val files = FakePlatformFiles().apply {
            this.files["content://pick/photo"] = PickedFile("IMG_2031.jpg", byteArrayOf(1, 2, 3, 4))
        }
        private val client = HttpClient(
            MockEngine { request ->
                requests += request.method to request.url.encodedPath
                if (request.method == HttpMethod.Patch) bodies += request.body.toByteArray().decodeToString()
                handler(request)
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        val api = VikunjaApiService(client, authTestJson)
        val repository = AttachmentRepositoryImpl(
            attachmentDao = attachments,
            api = api,
            attachmentMapper = AttachmentMapper(),
            platformFiles = files,
            mappingDispatcher = Dispatchers.Unconfined,
            pendingActionDao = queue,
            offlineFiles = store,
            networkMonitor = FakeNetworkMonitor(online),
            platformHooks = hooks,
        )
        val sync = AttachmentUploadSync(
            api = api,
            pendingActionDao = queue,
            attachmentDao = attachments,
            attachmentMapper = AttachmentMapper(),
            taskDao = tasks,
            files = store,
        )

        suspend fun uploads() = queue.snapshot().filter { it.entityType == AttachmentQueue.ENTITY_TYPE }
    }

    // ---- adding a file -------------------------------------------------------------------------

    @Test
    fun `offline a picked file is copied onto the device and queued without asking the server`() = runTest {
        val rig = Rig(online = false)

        val result = rig.repository.attachPicked(5, "content://pick/photo", intoNotes = true)

        assertEquals(AttachResult.Queued("IMG_2031.jpg"), result)
        assertTrue(rig.requests.isEmpty())
        val action = rig.uploads().single()
        assertEquals(5L, action.entityId)
        assertEquals(AttachmentQueue.UPLOAD, action.actionType)
        val upload = AttachmentQueue.decode(action)!!
        assertEquals("IMG_2031.jpg", upload.name)
        assertEquals(4L, upload.size)
        assertTrue(upload.intoNotes)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), read(upload.file))
        assertEquals(1, rig.hooks.syncTriggers)
        assertEquals(1, rig.files.sourcesClosed)
    }

    @Test
    fun `a task created offline queues its files even when online`() = runTest {
        val rig = Rig(online = true)

        assertIs<AttachResult.Queued>(rig.repository.attachPicked(-3, "content://pick/photo"))

        assertEquals(-3L, rig.uploads().single().entityId)
        // Only the size limit was asked; nothing was sent to a task the server does not know.
        assertTrue(rig.requests.none { it.second.startsWith("/tasks") })
    }

    @Test
    fun `a server that does not answer keeps the file for later`() = runTest {
        val rig = Rig(online = true) { request ->
            when (request.url.encodedPath) {
                "/info" -> json("""{"version":"v2","max_file_size":"20MB"}""")
                else -> json("{}", HttpStatusCode.ServiceUnavailable)
            }
        }

        assertIs<AttachResult.Queued>(rig.repository.attachPicked(5, "content://pick/photo"))
        assertEquals(1, rig.uploads().size)
    }

    @Test
    fun `a refusal is said at once and nothing is kept`() = runTest {
        val rig = Rig(online = true) { request ->
            when (request.url.encodedPath) {
                "/info" -> json("""{"version":"v2","max_file_size":"20MB"}""")
                else -> json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
            }
        }

        assertIs<AttachResult.Refused>(rig.repository.attachPicked(5, "content://pick/photo"))
        assertTrue(rig.uploads().isEmpty())
        assertTrue(!SystemFileSystem.exists(Path(dir)) || SystemFileSystem.list(Path(dir)).isEmpty())
    }

    @Test
    fun `the editor lists the task's waiting and refused files`() = runTest {
        val rig = Rig(online = false)
        rig.repository.attachPicked(5, "content://pick/photo")
        val id = rig.uploads().single().id

        val waiting = rig.repository.waitingUploads(5).first().single()
        assertEquals("IMG_2031.jpg", waiting.fileName)
        assertFalse(waiting.failed)
        assertTrue(waiting.isImage)
        assertEquals("Waiting to upload · 4 B", WaitingUploadText.status(waiting))

        rig.queue.updatePayload(id, AttachmentQueue.encode(AttachmentQueue.decode(rig.queue.getById(id)!!)!!.copy(error = "48 MB, more than the server accepts", tooLarge = true)))
        rig.queue.markFailed(id, "2026-11-10T10:00:00Z")
        val refused = rig.repository.waitingUploads(5).first().single()
        assertTrue(refused.failed)
        assertFalse(refused.canRetry)
        assertEquals("Not uploaded: 48 MB, more than the server accepts", WaitingUploadText.status(refused))
        assertTrue(rig.repository.waitingUploads(6).first().isEmpty())
    }

    @Test
    fun `removing a waiting file can be undone while its copy is there`() = runTest {
        val rig = Rig(online = false)
        rig.repository.attachPicked(5, "content://pick/photo")
        val id = rig.uploads().single().id

        val removed = rig.repository.removeWaiting(id)!!
        assertTrue(rig.uploads().isEmpty())

        assertTrue(rig.repository.restoreWaiting(removed))
        assertEquals("IMG_2031.jpg", AttachmentQueue.decode(rig.uploads().single())!!.name)
    }

    // ---- the replay ----------------------------------------------------------------------------

    private suspend fun queued(rig: Rig, upload: QueuedUpload, taskId: Long = 5): PendingActionEntity {
        val id = rig.queue.insert(
            PendingActionEntity(
                entityType = AttachmentQueue.ENTITY_TYPE,
                entityId = taskId,
                actionType = AttachmentQueue.UPLOAD,
                payload = AttachmentQueue.encode(upload),
                createdAt = "2026-11-10T09:00:00Z",
            ),
        )
        return rig.queue.getById(id)!!
    }

    private suspend fun storedPhoto(): String = store.save(io.ktor.utils.io.ByteReadChannel(byteArrayOf(1, 2, 3, 4)))

    /** No attachments until something is posted, then attachment 9; the task's notes are "Breaker trips". */
    private fun uploadServer(postStatus: HttpStatusCode = HttpStatusCode.Created): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        var posted = false
        return { request ->
            when {
                request.method == HttpMethod.Post -> {
                    if (postStatus == HttpStatusCode.Created) posted = true
                    json("{}", postStatus)
                }
                request.url.encodedPath == "/tasks/5/attachments" ->
                    json(if (posted) page(attachmentJson(9, "IMG_2031.jpg")) else page())
                request.method == HttpMethod.Get && request.url.encodedPath == "/tasks/5" ->
                    json("""{"id":5,"title":"Fix the porch light","description":"Breaker trips","project_id":1}""")
                request.method == HttpMethod.Patch && request.url.encodedPath == "/tasks/5" ->
                    json("""{"id":5,"title":"Fix the porch light","description":"Breaker trips\n[[image:9]]","project_id":1}""")
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
    }

    @Test
    fun `the replay uploads the copy and deletes it and puts the image at the end of the notes`() = runTest {
        val rig = Rig(handler = uploadServer())
        val file = storedPhoto()
        val action = queued(rig, QueuedUpload(file = file, name = "IMG_2031.jpg", size = 4, intoNotes = true))

        rig.sync.process(action, emptyMap())

        assertEquals(1, rig.requests.count { it.first == HttpMethod.Post })
        assertEquals(listOf(9L), rig.attachments.snapshot().map { it.id })
        val after = AttachmentQueue.decode(rig.queue.getById(action.id)!!)!!
        assertTrue(after.uploaded)
        assertEquals(9L, after.attachmentId)
        assertFalse(store.exists(file))
        assertTrue(rig.bodies.single().contains(ImageTokens.imageToken(9)))
        assertTrue(rig.tasks.getByIdSync(5)!!.description.endsWith(ImageTokens.imageToken(9)))
    }

    @Test
    fun `a retry after the upload went through only adds the image to the notes`() = runTest {
        val rig = Rig(handler = uploadServer())
        val action = queued(rig, QueuedUpload(file = "1-0000000000000000.bin", name = "IMG_2031.jpg", size = 4, intoNotes = true, uploaded = true, attachmentId = 9))

        rig.sync.process(action, emptyMap())

        assertEquals(0, rig.requests.count { it.first == HttpMethod.Post })
        assertEquals(1, rig.requests.count { it.first == HttpMethod.Patch })
    }

    @Test
    fun `while the task has queued edits the image joins them instead of going straight to the server`() = runTest {
        val rig = Rig(handler = uploadServer())
        rig.queue.insert(PendingActionEntity(entityType = "task", entityId = 5, actionType = "update", payload = """{"title":"Fix the porch light now"}""", createdAt = "2026-11-10T09:30:00Z"))
        val action = queued(rig, QueuedUpload(file = "1-0000000000000000.bin", name = "IMG_2031.jpg", size = 4, intoNotes = true, uploaded = true, attachmentId = 9))

        rig.sync.process(action, emptyMap())

        assertEquals(0, rig.requests.count { it.first == HttpMethod.Patch })
        val edit = rig.queue.snapshot().single { it.entityType == "task" }
        assertTrue(edit.payload.contains("Fix the porch light now"))
        assertTrue(edit.payload.contains("[[image:9]]"))
        assertTrue(rig.tasks.getByIdSync(5)!!.description.endsWith(ImageTokens.imageToken(9)))
    }

    @Test
    fun `a file larger than the server accepts fails with the reason and no retry`() = runTest {
        val rig = Rig(handler = uploadServer(postStatus = HttpStatusCode.PayloadTooLarge))
        val file = storedPhoto()
        val action = queued(rig, QueuedUpload(file = file, name = "site-video.mp4", size = 48_000_000))

        assertFailsWith<VikunjaApiException> { rig.sync.process(action, emptyMap()) }

        val after = AttachmentQueue.decode(rig.queue.getById(action.id)!!)!!
        assertEquals("48 MB, more than the server accepts", after.error)
        assertTrue(after.tooLarge)
        assertFalse(after.uploaded)
        assertTrue(store.exists(file))
    }

    @Test
    fun `a copy that is gone fails with a reason instead of waiting for ever`() = runTest {
        val rig = Rig(handler = uploadServer())
        val action = queued(rig, QueuedUpload(file = "1-0000000000000000.bin", name = "IMG_2031.jpg", size = 4))

        assertFailsWith<IllegalStateException> { rig.sync.process(action, emptyMap()) }

        assertEquals("the copy kept on this device is gone", AttachmentQueue.decode(rig.queue.getById(action.id)!!)!!.error)
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun `a task that is being deleted gets nothing uploaded`() = runTest {
        val rig = Rig()
        val file = storedPhoto()
        rig.queue.insert(PendingActionEntity(entityType = "task", entityId = 5, actionType = "delete", createdAt = "2026-11-10T09:30:00Z"))
        val action = queued(rig, QueuedUpload(file = file, name = "IMG_2031.jpg", size = 4))

        rig.sync.process(action, emptyMap())

        assertTrue(rig.requests.isEmpty())
        assertFalse(store.exists(file))
    }

    @Test
    fun `the upload of a task created offline follows it to its server id`() = runTest {
        val rig = Rig(online = false)
        rig.repository.attachPicked(-3, "content://pick/photo")

        rig.sync.remapTask(-3, 42)

        assertEquals(42L, rig.uploads().single().entityId)
    }

    // ---- the stored copies ---------------------------------------------------------------------

    @Test
    fun `a sweep deletes old copies nothing names and keeps the rest`() = runTest {
        val named = storedPhoto()
        val orphan = storedPhoto()
        now += OfflineAttachmentFiles.SWEEP_MIN_AGE_MS + 1
        val young = storedPhoto()

        assertEquals(1, store.sweep(keep = setOf(named)))

        assertTrue(store.exists(named))
        assertFalse(store.exists(orphan))
        assertTrue(store.exists(young))
    }

    @Test
    fun `a stored name never becomes a path`() {
        assertFailsWith<IllegalArgumentException> { store.pathOf("../../databases/vicu_database") }
        assertFailsWith<IllegalArgumentException> { store.pathOf("IMG_2031.jpg") }
    }

    @Test
    fun `quick add's placeholders never reach the server`() {
        val notes = "Breaker trips\n[[image-pending:3f2a]]\n[[image:7]]"
        assertEquals("Breaker trips\n[[image:7]]", ImageTokens.removePendingTokens(notes))
    }
}
