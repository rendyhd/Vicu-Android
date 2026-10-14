package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.TokenStorage
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.data.local.dao.AssigneePayload
import com.rendyhd.vicu.data.local.dao.CollabQueue
import com.rendyhd.vicu.data.local.dao.CollaborationDao
import com.rendyhd.vicu.data.local.dao.CommentPayload
import com.rendyhd.vicu.data.local.dao.PendingActionDao
import com.rendyhd.vicu.data.local.dao.ProjectDao
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.PendingActionEntity
import com.rendyhd.vicu.data.local.entity.ProjectMemberEntity
import com.rendyhd.vicu.data.local.entity.TaskCommentEntity
import com.rendyhd.vicu.data.local.entity.TaskSignalEntity
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.mapper.TaskMapper
import com.rendyhd.vicu.data.remote.api.TaskCommentDto
import com.rendyhd.vicu.data.remote.api.UserDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.ProjectShares
import com.rendyhd.vicu.domain.model.ShareTeam
import com.rendyhd.vicu.domain.model.ShareUser
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.ProjectNode
import com.rendyhd.vicu.util.ShareGraph
import com.rendyhd.vicu.util.TeamShareEntry
import com.rendyhd.vicu.util.UserShareEntry
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isNetworkFailure
import com.rendyhd.vicu.util.mayHaveReachedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock

class CollaborationRepositoryImpl(
    private val collaborationDao: CollaborationDao,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val pendingActionDao: PendingActionDao,
    private val api: VikunjaApiService,
    private val taskMapper: TaskMapper,
    private val projectMapper: ProjectMapper,
    private val tokenStorage: TokenStorage,
    private val tempIds: TempIdGenerator,
    private val platformHooks: PlatformRepositoryHooks,
    /** Sends a change to an existing task or queues it; shared with the task and label repositories. */
    private val writeGate: TaskWriteGate = TaskWriteGate(pendingActionDao),
    private val mappingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CollaborationRepository {

    companion object {
        private const val TAG = "Collaboration"

        /** Members are read again after this long (cross-app semantics 9.1). */
        const val MEMBERS_STALE_MS = 10 * 60 * 1000L

        /** At most this many member lookups at once: there is one per project. */
        private const val MEMBER_LOOKUP_CONCURRENCY = 4
    }

    private val me = MutableStateFlow<Long?>(null)
    private val refreshLock = Mutex()
    private var membersReadAt = 0L
    /** Whose members were read: another account (after a sign-in) reads them again at once. */
    private var membersReadFor: Long? = null

    private suspend fun loadMe(): Long? {
        val id = tokenStorage.getUserId()
        me.value = id
        return id
    }

    override val state: Flow<CollabState> =
        combine(projectDao.getAll(), collaborationDao.observeMembers(), me) { projects, members, myId ->
            val byProject = members.groupBy { it.projectId }
            CollabState(
                me = myId,
                projects = projects.associate { entity ->
                    val project = with(projectMapper) { entity.toDomain() }
                    val people = byProject[entity.id]?.sortedBy { it.position }?.map { User(it.userId, it.username, it.name) }
                    entity.id to CollabState.accessFor(project, people, myId)
                },
            )
        }.onStart { loadMe() }.flowOn(mappingDispatcher)

    override val signals: Flow<Map<Long, TaskSignal>> =
        collaborationDao.observeSignals()
            .map { rows -> rows.associate { it.taskId to TaskSignal(it.commentCount, it.isUnread) } }
            .flowOn(mappingDispatcher)

    private fun failure(e: Exception, doing: String): NetworkResult.Error = NetworkResult.Error(
        message = if (isNetworkFailure(e)) "Can't reach the server" else e.message ?: "Could not $doing",
        code = (e as? VikunjaApiException)?.httpStatus,
        offline = isNetworkFailure(e),
    )

    private inline fun <T> attempt(doing: String, block: () -> T): NetworkResult<T> = try {
        NetworkResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "Could not $doing: ${e.message}")
        failure(e, doing)
    }

    // --- Members and signals ------------------------------------------------------------------

    override suspend fun refreshMembers(force: Boolean): NetworkResult<Unit> = refreshLock.withLock {
        val myId = loadMe() ?: return NetworkResult.Success(Unit)
        // Fresh, for this account, and not wiped since (a sign-out clears the members).
        val fresh = myId == membersReadFor && nowMillis() - membersReadAt < MEMBERS_STALE_MS &&
            collaborationDao.observeMembers().first().isNotEmpty()
        if (!force && fresh) return NetworkResult.Success(Unit)
        attempt("read who can see your projects") {
            val projects = projectDao.getAll().first()
            val gate = Semaphore(MEMBER_LOOKUP_CONCURRENCY)
            coroutineScope {
                projects.map { project ->
                    async {
                        gate.withPermit {
                            val users = api.searchProjectUsers(project.id)
                            collaborationDao.replaceMembers(
                                project.id,
                                users.mapIndexed { index, user ->
                                    ProjectMemberEntity(project.id, user.id, user.username, user.name, index)
                                },
                            )
                        }
                    }
                }.awaitAll()
            }
            collaborationDao.deleteMembersExcept(projects.map { it.id }.ifEmpty { listOf(0L) })
            membersReadAt = nowMillis()
            membersReadFor = myId
        }
    }

    private suspend fun sharedProjectIds(): List<Long> = state.first().projects.filterValues { it.shared }.keys.toList()

    override suspend fun refreshSignals(): NetworkResult<Unit> = attempt("read the comment counts") {
        val shared = sharedProjectIds()
        if (shared.isEmpty()) {
            collaborationDao.replaceSignals(emptyList())
            return@attempt
        }
        val filter = "done = false && project in ${shared.joinToString(", ")}"
        val tasks = api.getAllTasks(mapOf("filter" to filter), expandSubtasks = false, collabSignals = true)
        val serverIds = tasks.map { it.id }.toSet()
        collaborationDao.replaceSignals(
            tasks.map { TaskSignalEntity(it.id, it.commentCount ?: 0, it.isUnread ?: false) } +
                pendingCommentSignals(serverIds) + doneTaskSignals(serverIds),
        )
        // The answer carries the assignees too: take them now instead of waiting for the next task
        // refresh to read the tasks again.
        val protectedIds = pendingActionDao.getTaskIdsWithPendingActions().toSet()
        val cached = taskDao.getByIds(tasks.map { it.id }).associateBy { it.id }
        val changed = tasks.mapNotNull { dto ->
            val row = cached[dto.id] ?: return@mapNotNull null
            if (dto.id in protectedIds) return@mapNotNull null
            val assignees = (dto.assignees ?: emptyList()).map { User(it.id, it.username, it.name) }
            val updated = with(taskMapper) { row.withAssignees(assignees) }
            if (updated.assigneesJson != row.assigneesJson) updated else null
        }
        if (changed.isNotEmpty()) taskDao.upsertAll(changed)
    }

    override suspend fun refreshSignalsFor(filter: String): NetworkResult<Unit> = attempt("read the comment counts") {
        val shared = sharedProjectIds()
        if (shared.isEmpty()) return@attempt
        val inShared = "project in ${shared.joinToString(", ")}"
        val combined = if (filter.isBlank()) inShared else "($filter) && $inShared"
        val tasks = api.getAllTasks(mapOf("filter" to combined), expandSubtasks = false, collabSignals = true)
        collaborationDao.upsertSignals(tasks.map { TaskSignalEntity(it.id, it.commentCount ?: 0, it.isUnread ?: false) })
    }

    /**
     * The signals of done tasks that [refreshSignalsFor] read stay when [refreshSignals] replaces
     * the open ones; that list's next fetch brings them up to date.
     */
    private suspend fun doneTaskSignals(serverIds: Set<Long>): List<TaskSignalEntity> {
        val others = collaborationDao.observeSignals().first().filter { it.taskId > 0 && it.taskId !in serverIds }
        if (others.isEmpty()) return emptyList()
        val done = taskDao.getByIds(others.map { it.taskId }).filter { it.done }.mapTo(HashSet()) { it.id }
        return others.filter { it.taskId in done }
    }

    /** Tasks created offline in a shared project keep the count of their queued comments. */
    private suspend fun pendingCommentSignals(serverIds: Set<Long>): List<TaskSignalEntity> =
        collaborationDao.observeSignals().first()
            .filter { it.taskId < 0 && it.taskId !in serverIds }

    override suspend fun refresh(force: Boolean): NetworkResult<Unit> {
        val members = refreshMembers(force)
        val signals = refreshSignals()
        return if (members is NetworkResult.Error) members else signals
    }

    // --- Assignees ----------------------------------------------------------------------------

    private suspend fun queue(entityType: String, taskId: Long, actionType: String, payload: String) {
        val now = DateUtils.nowIso()
        pendingActionDao.queueCollabAction(
            PendingActionEntity(
                entityType = entityType,
                entityId = taskId,
                actionType = actionType,
                payload = payload,
                createdAt = now,
                updatedAt = now,
            ),
        )
        platformHooks.triggerSync()
    }

    private suspend fun currentTaskId(id: Long): Long =
        if (id >= 0L || taskDao.getByIdSync(id) != null) id else tempIds.realIdFor(id) ?: id

    private suspend fun setAssignedLocally(taskId: Long, person: User, assigned: Boolean) {
        val row = taskDao.getByIdSync(taskId) ?: return
        val current = with(taskMapper) { row.toDomain() }.assignees.filter { it.id != person.id }
        taskDao.upsert(with(taskMapper) { row.withAssignees(if (assigned) current + person else current) })
    }

    override suspend fun setAssigned(task: Task, person: User, assigned: Boolean): NetworkResult<Unit> {
        val taskId = currentTaskId(task.id)
        val action = if (assigned) CollabQueue.ADD else CollabQueue.REMOVE
        val queue: suspend () -> NetworkResult<Unit> = {
            setAssignedLocally(taskId, person, assigned)
            queue(CollabQueue.ASSIGNEE, taskId, action, CollabQueue.encode(AssigneePayload(person.id, person.username, person.name)))
            NetworkResult.Success(Unit)
        }
        // A task created offline has no id the server knows yet: the change waits for the create.
        if (taskId < 0L) return queue()
        setAssignedLocally(taskId, person, assigned)
        return writeGate.sendOrQueue(
            taskId = taskId,
            send = {
                if (assigned) api.addAssignee(taskId, person.id) else api.removeAssignee(taskId, person.id)
                NetworkResult.Success(Unit)
            },
            queue = queue,
            refused = { e ->
                setAssignedLocally(taskId, person, !assigned)
                failure(e, if (assigned) "assign ${person.name.ifBlank { person.username }}" else "unassign ${person.name.ifBlank { person.username }}")
            },
        )
    }

    override suspend fun memberPermissions(projectId: Long): Map<Long, Int?> {
        val nodes = projectDao.getAll().first().map { ProjectNode(it.id, it.parentProjectId, it.ownerId) }
        val chain = Collaboration.projectChain(projectId, nodes)
        val graph = coroutineScope {
            val reads = chain.map { node ->
                async {
                    val users = runCatching { api.getProjectUserShares(node.id) }.getOrNull()
                    val teams = runCatching { api.getProjectTeamShares(node.id) }.getOrNull()
                    Triple(node.id, users, teams)
                }
            }.awaitAll()
            ShareGraph(
                projects = nodes,
                shares = reads.mapNotNull { (id, users, _) -> users?.let { list -> id to list.map { UserShareEntry(it.id, it.permission) } } }.toMap(),
                teams = reads.mapNotNull { (id, _, teams) ->
                    teams?.let { list -> id to list.map { team -> TeamShareEntry(team.permission, team.members.orEmpty().map { it.id }) } }
                }.toMap(),
            )
        }
        return collaborationDao.getMembers(projectId).associate { it.userId to Collaboration.memberPermission(projectId, it.userId, graph) }
    }

    // --- Comments -----------------------------------------------------------------------------

    private fun TaskCommentEntity.toDomain() = TaskComment(
        id = id,
        taskId = taskId,
        author = if (authorId != 0L || authorUsername.isNotEmpty()) User(authorId, authorUsername, authorName) else null,
        comment = comment,
        created = created,
        updated = updated,
        pending = pending,
    )

    private fun TaskCommentDto.toEntity(taskId: Long) = TaskCommentEntity(
        id = id,
        taskId = taskId,
        authorId = author?.id ?: 0,
        authorUsername = author?.username ?: "",
        authorName = author?.name ?: "",
        comment = comment,
        created = DateUtils.normalizeToUtc(created),
        updated = DateUtils.normalizeToUtc(updated),
    )

    override fun comments(taskId: Long): Flow<List<TaskComment>> =
        collaborationDao.observeComments(taskId).map { rows -> rows.map { it.toDomain() } }.flowOn(mappingDispatcher)

    /** Comments with a waiting edit, and those whose delete waits: a refresh must not bring them back. */
    private suspend fun queuedCommentIds(taskId: Long): Pair<Set<Long>, Set<Long>> {
        val actions = pendingActionDao.getActiveForTask(CollabQueue.COMMENT, taskId)
        val deleted = actions.filter { it.actionType == CollabQueue.DELETE }.mapNotNull { CollabQueue.comment(it.payload)?.commentId }.toSet()
        val local = collaborationDao.observeComments(taskId).first().filter { it.pending }.map { it.id }.toSet()
        return local to deleted
    }

    override suspend fun refreshComments(taskId: Long): NetworkResult<Boolean> {
        val id = currentTaskId(taskId)
        if (id < 0L) return NetworkResult.Success(false)
        return attempt("read the comments") {
            val page = api.getTaskCommentsPage(id, 1)
            val (keep, deleted) = queuedCommentIds(id)
            collaborationDao.replaceComments(
                taskId = id,
                comments = page.items.filter { it.id !in deleted }.map { it.toEntity(id) },
                keepIds = keep,
                wholeThread = page.totalPages <= 1,
            )
            val waitingCreates = keep.count { it < 0 }
            val current = collaborationDao.getSignal(id)
            collaborationDao.upsertSignals(
                listOf(TaskSignalEntity(id, page.total.toInt() + waitingCreates - deleted.size, current?.isUnread ?: false)),
            )
            page.page < page.totalPages
        }
    }

    override suspend fun loadOlderComments(taskId: Long, page: Int): NetworkResult<Boolean> {
        val id = currentTaskId(taskId)
        if (id < 0L) return NetworkResult.Success(false)
        return attempt("read older comments") {
            val answer = api.getTaskCommentsPage(id, page)
            val (keep, deleted) = queuedCommentIds(id)
            collaborationDao.upsertComments(answer.items.filter { it.id !in deleted && it.id !in keep }.map { it.toEntity(id) })
            answer.page < answer.totalPages
        }
    }

    private suspend fun meAsAuthor(taskId: Long): User? {
        val myId = me.value ?: loadMe() ?: return null
        val projectId = taskDao.getByIdSync(taskId)?.projectId
        val members = projectId?.let { collaborationDao.getMembers(it) }.orEmpty()
        return members.firstOrNull { it.userId == myId }?.let { User(it.userId, it.username, it.name) } ?: User(myId, "", "")
    }

    override suspend fun postComment(taskId: Long, html: String): NetworkResult<Unit> {
        val id = currentTaskId(taskId)
        val author = meAsAuthor(id)
        val tempId = tempIds.next()
        val now = DateUtils.nowIso()
        collaborationDao.upsertComments(
            listOf(
                TaskCommentEntity(
                    id = tempId,
                    taskId = id,
                    authorId = author?.id ?: 0,
                    authorUsername = author?.username ?: "",
                    authorName = author?.name ?: "",
                    comment = html,
                    created = now,
                    updated = now,
                    pending = true,
                ),
            ),
        )
        collaborationDao.changeCommentCount(id, 1)
        var attemptError: Exception? = null
        val queue: suspend () -> NetworkResult<Unit> = {
            // A create that may have reached the server is looked for before it is sent again.
            val maybeSent = attemptError?.let(::mayHaveReachedServer) ?: false
            queue(CollabQueue.COMMENT, id, CollabQueue.CREATE, CollabQueue.encode(CommentPayload(tempId, html, maybeSent)))
            NetworkResult.Success(Unit)
        }
        if (id < 0L) return queue()
        return writeGate.sendOrQueue(
            taskId = id,
            send = {
                val created = try {
                    api.createComment(id, html)
                } catch (e: Exception) {
                    attemptError = e
                    throw e
                }
                collaborationDao.adoptComment(tempId, created.toEntity(id))
                NetworkResult.Success(Unit)
            },
            queue = queue,
            refused = { e ->
                collaborationDao.deleteComment(tempId)
                collaborationDao.changeCommentCount(id, -1)
                failure(e, "post the comment")
            },
        )
    }

    override suspend fun editComment(taskId: Long, commentId: Long, html: String): NetworkResult<Unit> {
        val id = currentTaskId(taskId)
        val before = collaborationDao.getComment(commentId)
        before?.let { collaborationDao.upsertComments(listOf(it.copy(comment = html, updated = DateUtils.nowIso(), pending = true))) }
        val queue: suspend () -> NetworkResult<Unit> = {
            queue(CollabQueue.COMMENT, id, CollabQueue.UPDATE, CollabQueue.encode(CommentPayload(commentId, html)))
            NetworkResult.Success(Unit)
        }
        // A comment still waiting to be created takes the edit into its create.
        if (id < 0L || commentId < 0L) return queue()
        return writeGate.sendOrQueue(
            taskId = id,
            send = {
                val saved = api.updateComment(id, commentId, html)
                val entity = saved.toEntity(id)
                collaborationDao.upsertComments(
                    listOf(if (saved.author == null && before != null) entity.copy(authorId = before.authorId, authorUsername = before.authorUsername, authorName = before.authorName) else entity),
                )
                NetworkResult.Success(Unit)
            },
            queue = queue,
            refused = { e ->
                before?.let { collaborationDao.upsertComments(listOf(it)) }
                failure(e, "save the comment")
            },
        )
    }

    override suspend fun deleteComment(taskId: Long, commentId: Long): NetworkResult<Unit> {
        val id = currentTaskId(taskId)
        val before = collaborationDao.getComment(commentId)
        collaborationDao.deleteComment(commentId)
        collaborationDao.changeCommentCount(id, -1)
        val queue: suspend () -> NetworkResult<Unit> = {
            queue(CollabQueue.COMMENT, id, CollabQueue.DELETE, CollabQueue.encode(CommentPayload(commentId)))
            NetworkResult.Success(Unit)
        }
        if (id < 0L || commentId < 0L) return queue()
        return writeGate.sendOrQueue(
            taskId = id,
            send = {
                try {
                    api.deleteComment(id, commentId)
                } catch (e: VikunjaApiException) {
                    // Already gone: that is what was asked for.
                    if (e.httpStatus != 404) throw e
                }
                NetworkResult.Success(Unit)
            },
            queue = queue,
            refused = { e ->
                before?.let { collaborationDao.upsertComments(listOf(it)) }
                collaborationDao.changeCommentCount(id, 1)
                failure(e, "delete the comment")
            },
        )
    }

    override suspend fun markRead(taskId: Long) {
        val id = currentTaskId(taskId)
        collaborationDao.markRead(id)
        if (id <= 0L) return
        attempt("mark the comments read") { api.markTaskRead(id) }
    }

    override suspend fun isSubscribed(taskId: Long): NetworkResult<Boolean> {
        val id = currentTaskId(taskId)
        if (id <= 0L) return NetworkResult.Success(false)
        return attempt("read notifications for the task") { api.isSubscribed(id) }
    }

    override suspend fun setSubscribed(taskId: Long, subscribed: Boolean): NetworkResult<Unit> {
        val id = currentTaskId(taskId)
        if (id <= 0L) return NetworkResult.Error("The task is not on the server yet")
        return attempt("change notifications for the task") { api.setSubscribed(id, subscribed) }
    }

    // --- Sharing ------------------------------------------------------------------------------

    private fun UserDto.toUser() = User(id, username, name)

    override suspend fun shares(projectId: Long): NetworkResult<ProjectShares> = attempt("read who the project is shared with") {
        coroutineScope {
            val users = async { api.getProjectUserShares(projectId) }
            val teams = async { api.getProjectTeamShares(projectId) }
            val owner = async { runCatching { api.getProject(projectId).owner?.toUser() }.getOrNull() }
            ProjectShares(
                owner = owner.await(),
                users = users.await().map { ShareUser(User(it.id, it.username, it.name), it.permission) },
                teams = teams.await().map { team -> ShareTeam(team.id, team.name, team.permission, team.members.orEmpty().map { it.toUser() }) },
            )
        }
    }

    override suspend fun findUser(username: String): NetworkResult<User?> {
        val query = username.trim().removePrefix("@")
        if (query.isEmpty()) return NetworkResult.Success(null)
        return attempt("look the person up") {
            api.searchUsers(query).firstOrNull { it.username.equals(query, ignoreCase = true) }?.toUser()
        }
    }

    private suspend fun afterShareChange(): NetworkResult<Unit> {
        refreshMembers(force = true)
        refreshSignals()
        return NetworkResult.Success(Unit)
    }

    override suspend fun share(projectId: Long, username: String, permission: Int): NetworkResult<Unit> {
        val result = attempt("share the project") { api.shareProject(projectId, username, permission) }
        return if (result is NetworkResult.Error) result else afterShareChange()
    }

    override suspend fun updateShare(projectId: Long, username: String, permission: Int): NetworkResult<Unit> {
        val result = attempt("change the permission") { api.updateProjectShare(projectId, username, permission) }
        return if (result is NetworkResult.Error) result else afterShareChange()
    }

    override suspend fun removeShare(projectId: Long, username: String): NetworkResult<Unit> {
        val result = attempt("remove the person") { api.removeProjectShare(projectId, username) }
        return if (result is NetworkResult.Error) result else afterShareChange()
    }
}
