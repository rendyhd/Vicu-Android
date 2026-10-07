package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.Attachment
import com.rendyhd.vicu.util.FakePlatformFiles
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.PickedFile
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeAttachmentDao : AttachmentDao {
    private val rows = MutableStateFlow<List<AttachmentEntity>>(emptyList())

    fun snapshot(): List<AttachmentEntity> = rows.value

    override fun getByTaskId(taskId: Long): Flow<List<AttachmentEntity>> =
        rows.map { list -> list.filter { it.taskId == taskId } }

    override suspend fun upsert(attachment: AttachmentEntity) {
        rows.value = rows.value.filter { it.id != attachment.id } + attachment
    }

    override suspend fun upsertAll(attachments: List<AttachmentEntity>) {
        val ids = attachments.map { it.id }.toSet()
        rows.value = rows.value.filter { it.id !in ids } + attachments
    }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filter { it.id != id }
    }
}

/** What the mock server saw: method, path, and the body as text when there was one. */
private class Seen(val method: HttpMethod, val path: String, val body: String?)

class AttachmentRepositoryImplTest {

    private class Harness(handler: suspend MockRequestHandleScope.(HttpRequestData, Seen) -> HttpResponseData) {
        val seen = mutableListOf<Seen>()
        val dao = FakeAttachmentDao()
        val files = FakePlatformFiles()

        private val client = HttpClient(
            MockEngine { request ->
                val body = if (request.method == HttpMethod.Post) {
                    runCatching { request.body.toByteArray().decodeToString() }.getOrNull()
                } else {
                    null
                }
                val entry = Seen(request.method, request.url.encodedPath, body)
                seen += entry
                handler(request, entry)
            },
        ) {
            install(ContentNegotiation) { json(authTestJson) }
        }

        val repository = AttachmentRepositoryImpl(
            attachmentDao = dao,
            api = VikunjaApiService(client, authTestJson),
            attachmentMapper = AttachmentMapper(),
            platformFiles = files,
            mappingDispatcher = Dispatchers.Unconfined,
        )

        fun count(method: HttpMethod, path: String) = seen.count { it.method == method && it.path == path }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = body, status = status, headers = authTestJsonHeaders)

    private fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}"""

    private fun attachmentJson(id: Long, name: String = "a.txt", size: Long = 3) =
        """{"id":$id,"task_id":5,"file":{"id":$id,"name":"$name","mime":"text/plain","size":$size},"created":"2026-10-06T10:00:00Z"}"""

    private fun info(maxFileSize: String) = """{"version":"v1","max_file_size":"$maxFileSize"}"""

    /** A server that has no attachments, then one with id 9 once something was posted. */
    private fun uploadServer(maxFileSize: String = "20MB"): Harness {
        var posted = false
        return Harness { request, _ ->
            when {
                request.url.encodedPath == "/info" -> json(info(maxFileSize))
                request.method == HttpMethod.Post -> {
                    posted = true
                    json("{}", HttpStatusCode.Created)
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/tasks/5/attachments" ->
                    json(if (posted) page(attachmentJson(9, "x.txt", 11)) else page())
                else -> error("Unexpected ${request.method.value} ${request.url.encodedPath}")
            }
        }
    }

    // ---- upload ------------------------------------------------------------------------------

    @Test
    fun `an upload streams the picked file and stores the new attachment`() = runTest {
        val h = uploadServer()
        h.files.files["content://pick/1"] = PickedFile("x.txt", "hello world".encodeToByteArray())

        val result = h.repository.uploadPicked(5, "content://pick/1")

        assertTrue(result is NetworkResult.Success, "$result")
        assertEquals(9L, (result as NetworkResult.Success).data.id)
        val body = h.seen.single { it.method == HttpMethod.Post }.body.orEmpty()
        assertTrue(body.contains("hello world"), "the file's bytes are in the request")
        assertEquals(1, h.files.channelsOpened, "the file is read once, as a stream")
        assertEquals(listOf(9L), h.dao.snapshot().map { it.id })
        assertEquals(h.files.sourcesOpened, h.files.sourcesClosed, "the source is closed afterwards")
    }

    @Test
    fun `the file name is escaped in the Content-Disposition header`() = runTest {
        val h = uploadServer()
        h.files.files["content://pick/1"] = PickedFile("my \"best\"\r\nfile.txt", "abc".encodeToByteArray())

        h.repository.uploadPicked(5, "content://pick/1")

        val body = h.seen.single { it.method == HttpMethod.Post }.body.orEmpty()
        assertTrue(body.contains("filename=\"my \\\"best\\\"file.txt\""), body)
        assertTrue(!body.contains("best\"\r\nfile"), "the line break did not survive")
    }

    @Test
    fun `a file over the server's limit is refused before anything is sent`() = runTest {
        val h = uploadServer(maxFileSize = "10 B")
        h.files.files["content://pick/1"] = PickedFile("big.bin", ByteArray(25))

        val result = h.repository.uploadPicked(5, "content://pick/1")

        assertTrue(result is NetworkResult.Error)
        assertEquals(
            "\"big.bin\" is 25 B, but the server accepts files up to 10 B",
            (result as NetworkResult.Error).message,
        )
        assertEquals(0, h.count(HttpMethod.Post, "/tasks/5/attachments"))
        assertEquals(0, h.files.channelsOpened)
    }

    @Test
    fun `a file exactly at the limit is accepted`() = runTest {
        val h = uploadServer(maxFileSize = "11 B")
        h.files.files["content://pick/1"] = PickedFile("x.txt", "hello world".encodeToByteArray())

        assertTrue(h.repository.uploadPicked(5, "content://pick/1") is NetworkResult.Success)
    }

    @Test
    fun `the limit is 20 MB when the server does not say`() = runTest {
        val h = uploadServer(maxFileSize = "")

        assertEquals(20_000_000L, h.repository.maxUploadBytes())
    }

    @Test
    fun `the limit is 20 MB when the server cannot be asked, and is asked again next time`() = runTest {
        var infoCalls = 0
        val h = Harness { request, _ ->
            if (request.url.encodedPath == "/info") {
                infoCalls++
                if (infoCalls == 1) respond("", HttpStatusCode.ServiceUnavailable) else json(info("5MB"))
            } else {
                error("Unexpected ${request.url.encodedPath}")
            }
        }

        assertEquals(20_000_000L, h.repository.maxUploadBytes())
        assertEquals(5_000_000L, h.repository.maxUploadBytes())
    }

    @Test
    fun `the limit is read from the server once`() = runTest {
        val h = uploadServer(maxFileSize = "5MB")

        h.repository.maxUploadBytes()
        h.repository.maxUploadBytes()

        assertEquals(1, h.count(HttpMethod.Get, "/info"))
    }

    @Test
    fun `a file that cannot be read is reported`() = runTest {
        val h = uploadServer()

        val result = h.repository.uploadPicked(5, "content://gone")

        assertEquals("Could not read the file", (result as NetworkResult.Error).message)
    }

    @Test
    fun `a refused upload is an error and still closes the source`() = runTest {
        val h = Harness { request, _ ->
            when {
                request.url.encodedPath == "/info" -> json(info("20MB"))
                request.method == HttpMethod.Post -> json("""{"title":"Too big","status":413,"code":0}""", HttpStatusCode.PayloadTooLarge)
                else -> json(page())
            }
        }
        h.files.files["content://pick/1"] = PickedFile("x.txt", "abc".encodeToByteArray())

        val result = h.repository.uploadPicked(5, "content://pick/1")

        assertTrue(result is NetworkResult.Error)
        assertEquals(1, h.files.sourcesClosed)
        assertTrue(h.dao.snapshot().isEmpty())
    }

    // ---- download ----------------------------------------------------------------------------

    private val attachment = Attachment(id = 9, taskId = 5, fileName = "a.pdf", mimeType = "application/pdf")

    @Test
    fun `a download streams into the cache and returns the cached path`() = runTest {
        val h = Harness { request, _ ->
            if (request.url.encodedPath == "/tasks/5/attachments/9") {
                respond("PDFDATA", HttpStatusCode.OK)
            } else {
                error("Unexpected ${request.url.encodedPath}")
            }
        }

        val result = h.repository.downloadToCache(attachment)

        assertEquals("/cache/attachments/9/a.pdf", (result as NetworkResult.Success).data)
        assertEquals("PDFDATA", h.files.saved.getValue(9L).decodeToString())
    }

    @Test
    fun `a failed download is an error and nothing is cached`() = runTest {
        val h = Harness { _, _ -> json("""{"title":"Not found","status":404,"code":0}""", HttpStatusCode.NotFound) }

        val result = h.repository.downloadToCache(attachment)

        assertTrue(result is NetworkResult.Error)
        assertTrue(h.files.saved.isEmpty())
    }

    @Test
    fun `a download that cannot be written is an error`() = runTest {
        val h = Harness { _, _ -> respond("PDFDATA", HttpStatusCode.OK) }
        h.files.saveFailure = Exception("disk full")

        val result = h.repository.downloadToCache(attachment)

        assertEquals("disk full", (result as NetworkResult.Error).message)
    }

    // ---- delete ------------------------------------------------------------------------------

    private fun cachedRow() = AttachmentEntity(id = 9, taskId = 5, fileName = "a.pdf")

    @Test
    fun `a delete the server refuses leaves the cached row alone`() = runTest {
        val h = Harness { _, _ -> respond("", HttpStatusCode.InternalServerError) }
        h.dao.upsert(cachedRow())

        val result = h.repository.delete(5, 9)

        assertTrue(result is NetworkResult.Error)
        assertEquals(listOf(9L), h.dao.snapshot().map { it.id }, "nothing to roll back")
        assertEquals(listOf(9L), h.repository.getByTaskId(5).first().map { it.id })
    }

    @Test
    fun `a delete the server accepts removes the cached row`() = runTest {
        val h = Harness { _, _ -> respond("", HttpStatusCode.NoContent) }
        h.dao.upsert(cachedRow())

        val result = h.repository.delete(5, 9)

        assertTrue(result is NetworkResult.Success)
        assertTrue(h.dao.snapshot().isEmpty())
    }

    @Test
    fun `deleting an attachment that is already gone from the server removes the row too`() = runTest {
        val h = Harness { _, _ -> json("""{"title":"Not found","status":404,"code":0}""", HttpStatusCode.NotFound) }
        h.dao.upsert(cachedRow())

        val result = h.repository.delete(5, 9)

        assertTrue(result is NetworkResult.Success)
        assertTrue(h.dao.snapshot().isEmpty())
    }
}
