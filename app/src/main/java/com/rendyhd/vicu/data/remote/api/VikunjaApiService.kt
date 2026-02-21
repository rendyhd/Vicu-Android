package com.rendyhd.vicu.data.remote.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

interface VikunjaApiService {

    // Tasks
    @GET("tasks")
    suspend fun getAllTasks(@QueryMap filters: Map<String, String> = emptyMap()): List<TaskDto>

    @GET("tasks/{id}")
    suspend fun getTask(@Path("id") id: Long): TaskDto

    @PUT("projects/{projectId}/tasks")
    suspend fun createTask(@Path("projectId") projectId: Long, @Body task: CreateTaskDto): TaskDto

    @POST("tasks/{id}")
    suspend fun updateTask(@Path("id") id: Long, @Body task: TaskDto): TaskDto

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Long)

    // Projects
    @GET("projects")
    suspend fun getAllProjects(): List<ProjectDto>

    @GET("projects/{id}")
    suspend fun getProject(@Path("id") id: Long): ProjectDto

    @PUT("projects")
    suspend fun createProject(@Body project: ProjectDto): ProjectDto

    @POST("projects/{id}")
    suspend fun updateProject(@Path("id") id: Long, @Body project: ProjectDto): ProjectDto

    @DELETE("projects/{id}")
    suspend fun deleteProject(@Path("id") id: Long)

    // Labels
    @GET("labels")
    suspend fun getAllLabels(): List<LabelDto>

    @GET("labels/{id}")
    suspend fun getLabel(@Path("id") id: Long): LabelDto

    @PUT("labels")
    suspend fun createLabel(@Body label: LabelDto): LabelDto

    @PUT("labels/{id}")
    suspend fun updateLabel(@Path("id") id: Long, @Body label: LabelDto): LabelDto

    @DELETE("labels/{id}")
    suspend fun deleteLabel(@Path("id") id: Long)

    // Task Labels
    @PUT("tasks/{taskId}/labels")
    suspend fun addLabelToTask(@Path("taskId") taskId: Long, @Body body: LabelTaskDto)

    @DELETE("tasks/{taskId}/labels/{labelId}")
    suspend fun removeLabelFromTask(@Path("taskId") taskId: Long, @Path("labelId") labelId: Long)

    // Attachments
    @GET("tasks/{taskId}/attachments")
    suspend fun getAttachments(@Path("taskId") taskId: Long): List<AttachmentDto>

    @Multipart
    @PUT("tasks/{taskId}/attachments")
    suspend fun uploadAttachment(
        @Path("taskId") taskId: Long,
        @Part file: MultipartBody.Part,
    ): AttachmentDto

    @Streaming
    @GET("tasks/{taskId}/attachments/{attId}")
    suspend fun downloadAttachment(
        @Path("taskId") taskId: Long,
        @Path("attId") attId: Long,
    ): ResponseBody

    @DELETE("tasks/{taskId}/attachments/{attId}")
    suspend fun deleteAttachment(@Path("taskId") taskId: Long, @Path("attId") attId: Long)

    // Relations
    @PUT("tasks/{taskId}/relations")
    suspend fun createRelation(@Path("taskId") taskId: Long, @Body body: CreateRelationDto)

    @DELETE("tasks/{taskId}/relations/{relationKind}/{otherTaskId}")
    suspend fun deleteRelation(
        @Path("taskId") taskId: Long,
        @Path("relationKind") relationKind: String,
        @Path("otherTaskId") otherTaskId: Long,
    )

    // Views
    @GET("projects/{projectId}/views")
    suspend fun getProjectViews(@Path("projectId") projectId: Long): List<ProjectViewDto>

    @GET("projects/{projectId}/views/{viewId}/tasks")
    suspend fun getViewTasks(
        @Path("projectId") projectId: Long,
        @Path("viewId") viewId: Long,
        @QueryMap filters: Map<String, String> = emptyMap(),
    ): List<TaskDto>

    // Position
    @POST("tasks/{taskId}/position")
    suspend fun updateTaskPosition(@Path("taskId") taskId: Long, @Body body: TaskPositionDto)

    // Auth
    @POST("login")
    suspend fun login(@Body body: LoginRequestDto): TokenResponseDto

    @GET("info")
    suspend fun getServerInfo(): ServerInfoDto

    @GET("user")
    suspend fun getCurrentUser(): UserDto

    @GET("auth/openid/callback")
    suspend fun getOidcProviders(): List<OidcProviderDto>

    @POST("auth/openid/{providerKey}/callback")
    suspend fun exchangeOidcToken(
        @Path("providerKey") providerKey: String,
        @Body body: OidcCallbackDto,
    ): TokenResponseDto

    @PUT("tokens")
    suspend fun createApiToken(@Body body: ApiTokenRequestDto): ApiTokenResponseDto

    @POST("user/token")
    suspend fun renewToken(): TokenResponseDto
}
