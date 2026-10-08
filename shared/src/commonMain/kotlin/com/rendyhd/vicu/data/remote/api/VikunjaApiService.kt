package com.rendyhd.vicu.data.remote.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import com.rendyhd.vicu.util.Constants
import com.rendyhd.vicu.util.contentDispositionFileParameter
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class KtorResponse<T>(
    val code: Int,
    val isSuccessful: Boolean,
    val body: T?,
    val headers: Map<String, List<String>>,
    val problem: VikunjaProblemDto? = null,
) {
    fun code(): Int = code
    fun body(): T? = body
}

class VikunjaApiService(
    private val client: HttpClient,
    private val json: Json,
) {
    companion object {
        private const val DEFAULT_PAGE_SIZE = 100
        private const val SUBTASK_EXPANSION = "subtasks"
        private val MERGE_PATCH = ContentType.parse("application/merge-patch+json")

        /**
         * Requests that change data on the server leave one at a time, whatever task they are
         * for, in the whole process (single writes, bulk actions and the queue replay all come
         * through here). Vikunja's default database is SQLite, which answers parallel writes with
         * "database is locked" (a 500); a bulk change used to lose part of its requests to the
         * offline queue that way. Held only while one request is on the wire (never across a
         * repository lock or another write), so it cannot deadlock. The order within one task is
         * kept by `TaskWriteGate`. Attachment uploads are not serialised: one can take minutes.
         */
        private val writeLock = Mutex()

        /** Run [block] after every earlier write of the process has finished. */
        internal suspend fun <T> serialWrite(block: suspend () -> T): T = writeLock.withLock { block() }
    }

    /**
     * One page of tasks. With [expandSubtasks] (the default) Vikunja returns the tasks that are not
     * subtasks and then adds the subtasks of those, so a filter that a subtask matches but its
     * parent does not never reaches the subtask. Pass false for any request that must return
     * exactly the tasks the filter matches (incremental refresh, completed tasks): every task
     * still carries its `related_tasks`, which are not part of the expansion.
     */
    suspend fun getTasksPage(
        filters: Map<String, String> = emptyMap(),
        expandSubtasks: Boolean = true,
    ): PaginatedResponse<TaskDto> =
        client.get("tasks") {
            if (expandSubtasks) parameter("expand", SUBTASK_EXPANSION)
            filters.forEach { (key, value) -> parameter(key, value) }
        }.bodyOrThrow()

    /**
     * One page of the tasks of a single project (`GET projects/{id}/tasks`), without the subtask
     * expansion, so [PaginatedResponse.total] counts every task the filter matches. The progress
     * rings ask for a page of one and read only the total.
     */
    suspend fun getProjectTasksPage(
        projectId: Long,
        filters: Map<String, String> = emptyMap(),
    ): PaginatedResponse<TaskDto> =
        client.get("projects/$projectId/tasks") {
            filters.forEach { (key, value) -> parameter(key, value) }
        }.bodyOrThrow()

    suspend fun getAllTasks(
        filters: Map<String, String> = emptyMap(),
        expandSubtasks: Boolean = true,
    ): List<TaskDto> =
        fetchAllPages(filters) { params -> getTasksPage(params, expandSubtasks) }

    suspend fun getTask(id: Long): TaskDto =
        client.get("tasks/$id") {
            parameter("expand", SUBTASK_EXPANSION)
        }.bodyOrThrow()

    suspend fun createTask(projectId: Long, task: CreateTaskDto): TaskDto = serialWrite {
        client.post("projects/$projectId/tasks") {
            contentType(ContentType.Application.Json)
            setBody(task)
        }.bodyOrThrow(HttpStatusCode.Created)
    }

    suspend fun updateTask(id: Long, patch: JsonObject): TaskDto {
        val response = sendMergePatch("tasks/$id", patch)
        return if (response.isUnchanged()) getTask(id) else response.bodyOrThrow()
    }

    suspend fun deleteTask(id: Long) = serialWrite {
        client.delete("tasks/$id").requireNoContent()
    }

    suspend fun getAllProjects(includeArchived: Boolean = false): List<ProjectDto> =
        fetchAllPages(
            buildMap {
                if (includeArchived) put("is_archived", "true")
            },
        ) { params ->
            client.get("projects") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    suspend fun getProject(id: Long): ProjectDto =
        client.get("projects/$id").bodyOrThrow()

    suspend fun createProject(project: CreateProjectDto): ProjectDto = serialWrite {
        client.post("projects") {
            contentType(ContentType.Application.Json)
            setBody(project)
        }.bodyOrThrow(HttpStatusCode.Created)
    }

    suspend fun updateProject(id: Long, patch: JsonObject): ProjectDto {
        val response = sendMergePatch("projects/$id", patch)
        return if (response.isUnchanged()) getProject(id) else response.bodyOrThrow()
    }

    suspend fun deleteProject(id: Long) = serialWrite {
        client.delete("projects/$id").requireNoContent()
    }

    suspend fun getAllLabels(): List<LabelDto> =
        fetchAllPages { params ->
            client.get("labels") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    suspend fun getLabel(id: Long): LabelDto =
        client.get("labels/$id").bodyOrThrow()

    suspend fun createLabel(label: CreateLabelDto): LabelDto = serialWrite {
        client.post("labels") {
            contentType(ContentType.Application.Json)
            setBody(label)
        }.bodyOrThrow(HttpStatusCode.Created)
    }

    suspend fun updateLabel(id: Long, patch: JsonObject): LabelDto {
        val response = sendMergePatch("labels/$id", patch)
        return if (response.isUnchanged()) getLabel(id) else response.bodyOrThrow()
    }

    suspend fun deleteLabel(id: Long) = serialWrite {
        client.delete("labels/$id").requireNoContent()
    }

    suspend fun getTaskLabels(taskId: Long): List<LabelDto> =
        fetchAllPages { params ->
            client.get("tasks/$taskId/labels") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    suspend fun addLabelToTask(taskId: Long, body: LabelTaskDto) = serialWrite {
        client.post("tasks/$taskId/labels") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireStatus(HttpStatusCode.Created)
    }

    suspend fun removeLabelFromTask(taskId: Long, labelId: Long) = serialWrite {
        client.delete("tasks/$taskId/labels/$labelId").requireNoContent()
    }

    suspend fun getAttachments(taskId: Long): List<AttachmentDto> =
        fetchAllPages { params ->
            client.get("tasks/$taskId/attachments") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    /**
     * Uploads a file by streaming it: [content] is asked for the bytes when the request is sent
     * (and again if it has to be sent again), so the file is never held in memory. [size] lets
     * the request state its length up front.
     */
    suspend fun uploadAttachment(taskId: Long, fileName: String, size: Long?, content: () -> ByteReadChannel) {
        client.post("tasks/$taskId/attachments") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "files",
                            ChannelProvider(size, content),
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/octet-stream")
                                append(HttpHeaders.ContentDisposition, contentDispositionFileParameter(fileName))
                            },
                        )
                    },
                ),
            )
        }.requireStatus(HttpStatusCode.Created)
    }

    /** Streams an attachment to [consume] as it arrives; the body is never collected in memory. */
    suspend fun <T> downloadAttachment(
        taskId: Long,
        attachmentId: Long,
        consume: suspend (ByteReadChannel) -> T,
    ): T = client.prepareGet("tasks/$taskId/attachments/$attachmentId").execute { response ->
        response.ensureSuccess()
        consume(response.bodyAsChannel())
    }

    suspend fun deleteAttachment(taskId: Long, attachmentId: Long) = serialWrite {
        client.delete("tasks/$taskId/attachments/$attachmentId").requireNoContent()
    }

    suspend fun createRelation(taskId: Long, body: CreateRelationDto) = serialWrite {
        client.post("tasks/$taskId/relations") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireStatus(HttpStatusCode.Created)
    }

    suspend fun deleteRelation(taskId: Long, relationKind: String, otherTaskId: Long) = serialWrite {
        client.delete("tasks/$taskId/relations/$relationKind/$otherTaskId").requireNoContent()
    }

    suspend fun getProjectViews(projectId: Long): List<ProjectViewDto> =
        fetchAllPages { params ->
            client.get("projects/$projectId/views") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    suspend fun getViewTasksPage(
        projectId: Long,
        viewId: Long,
        filters: Map<String, String> = emptyMap(),
        expandSubtasks: Boolean = true,
    ): PaginatedResponse<TaskDto> =
        client.get("projects/$projectId/views/$viewId/tasks") {
            if (expandSubtasks) parameter("expand", SUBTASK_EXPANSION)
            filters.forEach { (key, value) -> parameter(key, value) }
        }.bodyOrThrow()

    suspend fun getAllViewTasks(
        projectId: Long,
        viewId: Long,
        filters: Map<String, String> = emptyMap(),
        expandSubtasks: Boolean = true,
    ): List<TaskDto> =
        fetchAllPages(filters) { params -> getViewTasksPage(projectId, viewId, params, expandSubtasks) }

    suspend fun updateTaskPosition(taskId: Long, body: TaskPositionDto) = serialWrite {
        client.put("tasks/$taskId/position") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireStatus(HttpStatusCode.OK)
    }

    suspend fun login(body: LoginRequestDto): KtorResponse<TokenResponseDto> =
        client.post("login") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.toKtorResponse()

    suspend fun getServerInfo(): ServerInfoDto =
        client.get("info").bodyOrThrow()

    suspend fun getCurrentUser(): UserDto =
        client.get("user").bodyOrThrow()

    /**
     * Asks the server at [baseUrl] who [token] belongs to, without using or changing the app's
     * session. The request goes through a bare copy of the client (same engine and JSON setup,
     * none of the base-URL redirection, Authorization injection or 401 refresh handling), so a
     * wrong token cannot trigger a refresh, flip the auth state or leave anything stored behind.
     */
    suspend fun getCurrentUserWithToken(baseUrl: String, token: String): UserDto {
        val bare = client.config { }
        try {
            return bare.get("${baseUrl.trim().trimEnd('/')}${Constants.API_BASE_PATH}/user") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.bodyOrThrow()
        } finally {
            bare.close()
        }
    }

    suspend fun exchangeOidcToken(
        providerKey: String,
        body: OidcCallbackDto,
    ): KtorResponse<TokenResponseDto> =
        client.post("auth/openid/$providerKey/callback") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.toKtorResponse()

    suspend fun createApiToken(body: ApiTokenRequestDto): ApiTokenResponseDto =
        client.post("tokens") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyOrThrow(HttpStatusCode.Created)

    suspend fun listApiTokens(query: String? = null): List<ApiTokenDto> =
        fetchAllPages(
            buildMap {
                if (!query.isNullOrBlank()) put("q", query)
            },
        ) { params ->
            client.get("tokens") {
                params.forEach { (key, value) -> parameter(key, value) }
            }.bodyOrThrow()
        }

    suspend fun deleteApiToken(id: Long) {
        client.delete("tokens/$id").requireNoContent()
    }

    suspend fun getApiTokenRoutes(): Map<String, Map<String, RouteDetailDto>> =
        client.get("routes").bodyOrThrow()

    suspend fun refreshToken(cookie: String): KtorResponse<TokenResponseDto> =
        client.post("user/token/refresh") {
            header(HttpHeaders.Cookie, cookie)
        }.toKtorResponse()

    suspend fun serverLogout() {
        client.post("logout").requireStatus(HttpStatusCode.OK)
    }

    private suspend fun <T> fetchAllPages(
        filters: Map<String, String> = emptyMap(),
        fetchPage: suspend (Map<String, String>) -> PaginatedResponse<T>,
    ): List<T> {
        val base = filters
            .filterKeys { it != "page" }
            .toMutableMap()
            .apply { putIfAbsent("per_page", DEFAULT_PAGE_SIZE.toString()) }
        val all = mutableListOf<T>()
        var page = 1
        var totalPages = 1
        do {
            val response = fetchPage(base + ("page" to page.toString()))
            all += response.items
            totalPages = maxOf(totalPages, response.totalPages.coerceAtLeast(1))
            page++
        } while (page <= totalPages)
        return all
    }

    private suspend fun sendMergePatch(path: String, patch: JsonObject): HttpResponse = serialWrite {
        client.patch(path) {
            contentType(MERGE_PATCH)
            setBody(patch)
        }
    }

    /**
     * Vikunja answers a merge patch that changes nothing (the server already has every value in
     * it) with 304 Not Modified and no body. The change is on the server, so it is done: the
     * update functions read the current state back instead of failing. This happens when a queued
     * change is folded into one the server already has (completed offline, reopened before the
     * sync), or when a change is sent again after an attempt whose answer was lost.
     */
    private fun HttpResponse.isUnchanged(): Boolean = status == HttpStatusCode.NotModified

    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(
        expectedStatus: HttpStatusCode? = null,
    ): T {
        ensureSuccess(expectedStatus)
        return body()
    }

    private suspend fun HttpResponse.requireNoContent() {
        ensureSuccess(HttpStatusCode.NoContent)
    }

    private suspend fun HttpResponse.requireStatus(expectedStatus: HttpStatusCode) {
        ensureSuccess(expectedStatus)
        bodyAsText()
    }

    private suspend fun HttpResponse.ensureSuccess(expectedStatus: HttpStatusCode? = null) {
        val statusMatches = expectedStatus?.let { status == it } ?: (status.value in 200..299)
        if (!statusMatches) throw toApiException()
    }

    private suspend fun HttpResponse.toApiException(): VikunjaApiException {
        val raw = runCatching { bodyAsText() }.getOrDefault("")
        val problem = runCatching {
            json.decodeFromString<VikunjaProblemDto>(raw)
        }.getOrNull()
        return VikunjaApiException(status.value, problem)
    }

    private suspend inline fun <reified T> HttpResponse.toKtorResponse(): KtorResponse<T> {
        val success = status.value in 200..299
        val bodyObject = if (success) {
            runCatching { body<T>() }.getOrNull()
        } else {
            null
        }
        val problem = if (success) {
            null
        } else {
            val raw = runCatching { bodyAsText() }.getOrDefault("")
            runCatching { json.decodeFromString<VikunjaProblemDto>(raw) }.getOrNull()
        }
        return KtorResponse(
            code = status.value,
            isSuccessful = success,
            body = bodyObject,
            headers = headers.entries().associate { it.key to it.value },
            problem = problem,
        )
    }
}
