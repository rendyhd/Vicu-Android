package com.rendyhd.vicu.ui.screens.comments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.ui.components.collab.AvatarStack
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.components.collab.PersonAvatar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.task.ImageViewerDialog
import com.rendyhd.vicu.ui.imagePasteReceiver
import com.rendyhd.vicu.ui.rememberClipboardImage
import com.rendyhd.vicu.ui.screens.taskdetail.EditorBackHandler
import com.rendyhd.vicu.ui.screens.taskdetail.EditorBackState
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.CommentSegment
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.ImageTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** How often the composer looks whether the clipboard holds an image. Asking shows no paste notice. */
private const val CLIPBOARD_CHECK_MS = 1_000L

/** The composer's text and caret, all a restored draft needs, in types iOS's `UiStateCodec` keeps. */
private val ComposerFieldSaver = listSaver<TextFieldState, Any>(
    save = { listOf(it.text.toString(), it.selection.start, it.selection.end) },
    restore = { TextFieldState(it[0] as String, TextRange(it[1] as Int, it[2] as Int)) },
)

/** What the thread lists, in reading order (oldest first). */
private sealed interface ThreadEntry {
    val key: String

    data object VisibleTo : ThreadEntry {
        override val key = "visible"
    }

    data object LoadingOlder : ThreadEntry {
        override val key = "older"
    }

    data object Loading : ThreadEntry {
        override val key = "loading"
    }

    data object Empty : ThreadEntry {
        override val key = "empty"
    }

    data class Day(val label: String, override val key: String) : ThreadEntry

    data class Item(val comment: TaskComment, val time: String) : ThreadEntry {
        override val key = "c${comment.id}"
    }
}

/** The thread as entries: the "Visible to" line first, a divider before each new day (section 9.5). */
private fun threadEntries(
    comments: List<TaskComment>,
    loading: Boolean,
    loadingOlder: Boolean,
    day: ClockDay,
    fmt: DateDisplayFormat,
): List<ThreadEntry> = buildList {
    add(ThreadEntry.VisibleTo)
    if (loadingOlder) add(ThreadEntry.LoadingOlder)
    if (comments.isEmpty()) add(if (loading) ThreadEntry.Loading else ThreadEntry.Empty)
    var lastDay: kotlinx.datetime.LocalDate? = null
    for (comment in comments) {
        val instant = DateUtils.parseIsoDate(comment.created)
        val localDay = instant?.let { DueDates.localDateOf(it, day.zone) }
        if (localDay != null && localDay != lastDay) {
            add(ThreadEntry.Day(DateDisplay.formatDue(DateContext.COMMENT_DAY, comment.created, day.date, day.zone, fmt), "d$localDay"))
            lastDay = localDay
        }
        add(ThreadEntry.Item(comment, DateDisplay.formatDue(DateContext.LOGBOOK_TIME, comment.created, day.date, day.zone, fmt)))
    }
}

/**
 * The Comments screen of a task (design handoff M3): the thread of a task in a shared project,
 * newest at the bottom, with the composer docked above the keyboard. It opens from a row's comment
 * count or the editor's Comments chip and draws over both; back closes it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TaskCommentsScreen(
    taskId: Long,
    onClose: () -> Unit,
    viewModel: TaskCommentsViewModel,
    backState: EditorBackState? = null,
    backEnabled: Boolean = true,
) {
    LaunchedEffect(taskId) { viewModel.open(taskId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collab = LocalCollab.current
    val day = LocalClockDay.current
    val fmt = LocalDateFormat.current
    val task = state.task?.takeIf { state.taskId == taskId }
    val access = collab.access(task?.projectId ?: 0L)
    val me = collab.state.me
    val members = access.members
    val collabMembers = remember(members) { members.map { it.toCollabUser() } }
    val files = remember(taskId, state.attachments, state.attachmentsRead, state.downloading) {
        CommentFiles(
            taskId = taskId,
            known = state.attachments.associateBy { it.id },
            read = state.attachmentsRead,
            downloading = state.downloading,
            onOpen = viewModel::openAttachment,
            onSave = viewModel::saveAttachment,
            onShare = viewModel::shareAttachment,
        )
    }

    // The draft's files by id, so a draft that survives the app being ended keeps them too.
    var savedStaged by rememberSaveable(taskId) { mutableStateOf("") }
    LaunchedEffect(taskId) {
        viewModel.restoreStaged(taskId, savedStaged.split(',').mapNotNull { it.toLongOrNull() })
    }
    val stagedIds = state.staged.mapNotNull { it.attachment?.id }.joinToString(",")
    LaunchedEffect(stagedIds, state.taskId) {
        if (state.taskId == taskId) savedStaged = stagedIds
    }

    EditorBackHandler(enabled = backEnabled, backState = backState, onBack = onClose)

    // A task that is gone takes its comments with it. A project that stops being shared does not.
    var hadTask by remember(taskId) { mutableStateOf(false) }
    LaunchedEffect(task != null) {
        if (task != null) hadTask = true else if (hadTask) onClose()
    }

    var selectedId by remember(taskId) { mutableStateOf<Long?>(null) }
    var editingId by remember(taskId) { mutableStateOf<Long?>(null) }
    var pendingDelete by remember(taskId) { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val entries = remember(state.comments, state.loading, state.loadingOlder, day, fmt) {
        threadEntries(state.comments, state.loading, state.loadingOlder, day, fmt)
    }

    // The thread opens at its end once the newest page has been read (or could not be).
    var atEndOnce by remember(taskId) { mutableStateOf(false) }
    LaunchedEffect(taskId, state.loading, entries.size) {
        if (!atEndOnce && !state.loading && state.taskId == taskId) {
            listState.scrollToItem(entries.lastIndex.coerceAtLeast(0))
            atEndOnce = true
        }
    }
    // Older pages load when the top of the thread comes into view.
    LaunchedEffect(listState) {
        snapshotFlow { atEndOnce && listState.layoutInfo.totalItemsCount > 0 && listState.firstVisibleItemIndex <= 1 }
            .distinctUntilChanged().filter { it }.collect { viewModel.loadOlder() }
    }
    // A new comment at the end shows when the thread was read to its end.
    val newestKey = state.comments.lastOrNull()?.id
    LaunchedEffect(newestKey) {
        if (!atEndOnce) return@LaunchedEffect
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last >= info.totalItemsCount - 3) listState.animateScrollToItem(entries.lastIndex.coerceAtLeast(0))
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Comments",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = task?.title.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Only on a shared project, and not with an API token: Vikunja does not let
                        // tokens follow tasks (section 9.7).
                        if (state.canFollow && access.shared) {
                            val on = state.subscribed == true
                            IconToggleButton(
                                checked = on,
                                onCheckedChange = viewModel::setSubscribed,
                                enabled = state.subscribed != null,
                                colors = IconButtonDefaults.iconToggleButtonColors(
                                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Icon(
                                    imageVector = if (on) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                                    contentDescription = if (on) "Notifications on for this task" else "Notify me about new comments",
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                        if (task != null && !access.canWrite) {
                            Text(
                                text = "You can read comments in this project",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            )
                        } else if (task != null) {
                            Composer(
                                taskId = taskId,
                                members = members,
                                me = me,
                                shared = access.shared,
                                staged = state.staged,
                                onAttach = viewModel::attach,
                                onRemoveStaged = viewModel::removeStaged,
                                onSend = { text -> viewModel.post(text, collabMembers) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(entries, key = { it.key }, contentType = { it::class.simpleName }) { entry ->
                    when (entry) {
                        ThreadEntry.VisibleTo -> VisibleToRow(members, me)
                        ThreadEntry.LoadingOlder, ThreadEntry.Loading -> Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                        ThreadEntry.Empty -> Text(
                            text = state.error?.let { "Could not read the comments: $it" } ?: "No comments yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        is ThreadEntry.Day -> DayDivider(entry.label)
                        is ThreadEntry.Item -> CommentRow(
                            comment = entry.comment,
                            time = entry.time,
                            members = members,
                            me = me,
                            canWrite = access.canWrite,
                            files = files,
                            selected = selectedId == entry.comment.id,
                            editing = editingId == entry.comment.id,
                            onSelect = { selectedId = if (selectedId == entry.comment.id) null else entry.comment.id },
                            onEdit = {
                                selectedId = null
                                editingId = entry.comment.id
                            },
                            onDelete = {
                                selectedId = null
                                pendingDelete = entry.comment.id
                            },
                            onCancelEdit = { editingId = null },
                            onSaveEdit = { text ->
                                val kept = Collaboration.decodeCommentHtml(entry.comment.comment, collabMembers, taskId).attachments
                                val html = Collaboration.encodeCommentHtml(text, collabMembers, kept)
                                if (html.isNotEmpty() && html != entry.comment.comment) viewModel.edit(entry.comment.id, html)
                                editingId = null
                            },
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete != 0L) {
        val withFiles = remember(pendingDelete, state.comments) {
            state.comments.firstOrNull { it.id == pendingDelete }
                ?.let { Collaboration.decodeCommentHtml(it.comment, emptyList(), taskId).attachments.isNotEmpty() } == true
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = 0L },
            title = { Text("Delete comment?") },
            text = {
                Text(if (withFiles) "This cannot be undone. Its files stay in the task's attachments." else "This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(pendingDelete)
                        pendingDelete = 0L
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = 0L }) { Text("Cancel") } },
        )
    }
}

/** "Visible to you, Maya and Sam" with their avatars: what makes posting deliberate. */
@Composable
private fun VisibleToRow(members: List<User>, me: Long?) {
    if (members.isEmpty()) return
    val text = remember(members, me) { Collaboration.visibleToText(members.map { it.toCollabUser() }, me) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarStack(people = members, size = 18.dp, ringColor = MaterialTheme.colorScheme.surface, max = 4)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A comment in reading order: runs of text, and the attachments between them. */
private sealed interface CommentBlock {
    data class Body(val text: AnnotatedString) : CommentBlock
    data class File(val attachment: CommentSegment.Attachment) : CommentBlock
}

/** Text runs with each mention as "@Name" in `primary`, semibold; each attachment a block of its own. */
private fun commentBlocks(segments: List<CommentSegment>, mentionColor: androidx.compose.ui.graphics.Color): List<CommentBlock> =
    buildList {
        var body = AnnotatedString.Builder()
        fun endBody() {
            if (body.length > 0) add(CommentBlock.Body(body.toAnnotatedString()))
            body = AnnotatedString.Builder()
        }
        for (segment in segments) {
            when (segment) {
                is CommentSegment.Text -> body.append(segment.text)
                is CommentSegment.Mention -> body.withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)) {
                    append("@")
                    append(segment.label)
                }
                is CommentSegment.Attachment -> {
                    endBody()
                    add(CommentBlock.File(segment))
                }
            }
        }
        endBody()
    }

/**
 * One comment: avatar, name and time, the text and its attachments. A long press on your own comment
 * highlights it and offers Edit and Delete (also as screen-reader actions). A comment waiting to sync
 * shows that in place of its time and cannot be changed until it has been sent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(
    comment: TaskComment,
    time: String,
    members: List<User>,
    me: Long?,
    canWrite: Boolean,
    files: CommentFiles,
    selected: Boolean,
    editing: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
) {
    val collabMembers = remember(members) { members.map { it.toCollabUser() } }
    val decoded = remember(comment.comment, collabMembers, files.taskId) {
        Collaboration.decodeCommentHtml(comment.comment, collabMembers, files.taskId)
    }
    val author = comment.author
    val mine = author != null && me != null && author.id == me
    val name = when {
        mine -> "You"
        author != null -> Collaboration.displayName(author.toCollabUser())
        else -> "Someone"
    }
    val edited = !comment.pending && Collaboration.isCommentEdited(comment.created, comment.updated)
    val status = when {
        comment.pending -> "Waiting to sync"
        edited -> "$time · edited"
        else -> time
    }
    val canEdit = mine && canWrite && decoded.plain && !comment.pending
    val canDelete = mine && canWrite && !comment.pending
    val primary = MaterialTheme.colorScheme.primary
    val blocks = remember(decoded, primary) { commentBlocks(decoded.segments, primary) }
    val onLongPress = if (canEdit || canDelete) onSelect else null

    // The comment's images full screen, by their place among its images; -1 while closed.
    val images = remember(decoded) { decoded.attachments.filter { it.image } }
    var viewerIndex by rememberSaveable(comment.id) { mutableIntStateOf(-1) }
    if (viewerIndex >= 0) {
        ImageViewerDialog(
            taskId = files.taskId,
            images = images.map { ImageTokens.ImageRef.Image(it.attachmentId) },
            initialIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
            onSave = { id, report -> images.firstOrNull { it.attachmentId == id }?.let { files.onSave(it, report) } },
            onShare = { id, report -> images.firstOrNull { it.attachmentId == id }?.let { files.onShare(it, report) } },
            busyIds = files.downloading,
        )
    }

    @Composable
    fun AttachmentBlock(segment: CommentSegment.Attachment) {
        if (segment.image) {
            CommentImage(
                segment = segment,
                files = files,
                onOpen = { viewerIndex = images.indexOf(segment) },
                onLongPress = onLongPress,
            )
        } else {
            CommentFile(segment = segment, files = files, onLongPress = onLongPress)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .then(
                if ((canEdit || canDelete) && !editing) {
                    Modifier
                        .combinedClickable(
                            onClick = { if (selected) onSelect() },
                            onLongClick = onSelect,
                            onLongClickLabel = "Show comment actions",
                        )
                        .semantics {
                            customActions = buildList {
                                if (canEdit) add(CustomAccessibilityAction("Edit comment") { onEdit(); true })
                                if (canDelete) add(CustomAccessibilityAction("Delete comment") { onDelete(); true })
                            }
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (selected) 8.dp else 0.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (author != null) PersonAvatar(author, 32.dp) else Spacer(modifier = Modifier.size(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (editing) {
                var draft by rememberSaveable(comment.id, stateSaver = TextFieldValue.Saver) {
                    val text = decoded.editText.orEmpty()
                    mutableStateOf(TextFieldValue(text, TextRange(text.length)))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    label = { Text("Edit comment") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                // An edit changes the text; the files stay as they are.
                decoded.attachments.forEach { AttachmentBlock(it) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancelEdit) { Text("Cancel") }
                    Button(
                        onClick = { onSaveEdit(draft.text) },
                        enabled = draft.text.isNotBlank() || decoded.attachments.isNotEmpty(),
                    ) {
                        Text("Save")
                    }
                }
            } else {
                blocks.forEach { block ->
                    when (block) {
                        is CommentBlock.Body -> Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (comment.pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        is CommentBlock.File -> AttachmentBlock(block.attachment)
                    }
                }
            }
            if (selected && !editing) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canEdit) {
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.heightIn(min = 32.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) {
                            Text("Edit", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (canDelete) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.heightIn(min = 32.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Delete", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The composer: the paperclip, a pill text field (Enter is a new line; the send button sends) and,
 * while "@" and a few letters stand before the caret, the matching members as chips above it; the
 * files added so far are chips above that. The draft is kept per task across configuration changes
 * and process death. A comment can be files alone; nothing is sent while a file is still uploading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    taskId: Long,
    members: List<User>,
    me: Long?,
    shared: Boolean,
    staged: List<StagedAttachment>,
    onAttach: (String) -> Unit,
    onRemoveStaged: (Long) -> Unit,
    onSend: (String) -> Boolean,
) {
    // A TextFieldState because only that kind of field takes an image: a long press's Paste or the
    // keyboard's clipboard hands it to imagePasteReceiver below.
    val field = rememberSaveable(taskId, saver = ComposerFieldSaver) { TextFieldState() }
    val text = field.text.toString()
    val selection = field.selection
    val mention = remember(text, selection) {
        if (selection.collapsed) Collaboration.mentionQueryAt(text, selection.start) else null
    }
    val suggestions = remember(mention, members, me) {
        mention?.let { m -> Collaboration.mentionSuggestions(m.query, members, me) { it.toCollabUser() } }.orEmpty()
    }
    val scheme = MaterialTheme.colorScheme

    // The clipboard's image is offered until it has been pasted, however it was pasted.
    val clipboard = rememberClipboardImage()
    var offeredImage by remember { mutableStateOf<Long?>(null) }
    var pastedImage by remember(taskId) { mutableStateOf<Long?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clipboard, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                offeredImage = clipboard.stamp()
                delay(CLIPBOARD_CHECK_MS)
            }
        }
    }
    val pasteImage = {
        val stamp = clipboard.stamp()
        clipboard.read()?.let { address ->
            pastedImage = stamp
            onAttach(address)
        }
        Unit
    }
    val offerPaste = offeredImage != null && offeredImage != pastedImage

    Column(modifier = Modifier.fillMaxWidth()) {
        if (staged.isNotEmpty() || offerPaste) {
            StagedAttachments(staged, onRemoveStaged, trailing = { if (offerPaste) PasteImageChip(pasteImage) })
        }
        if (mention != null && suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { person ->
                    InputChip(
                        selected = false,
                        onClick = {
                            val edit = Collaboration.applyMention(text, selection.start, mention, person.username)
                            field.edit {
                                replace(0, length, edit.text)
                                this.selection = TextRange(edit.caret)
                            }
                        },
                        label = { Text(Collaboration.displayName(person.toCollabUser())) },
                        avatar = { PersonAvatar(person, 24.dp) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AttachButton(onPicked = onAttach)
            BasicTextField(
                state = field,
                modifier = Modifier
                    .weight(1f)
                    .imagePasteReceiver { address ->
                        pastedImage = clipboard.stamp()
                        onAttach(address)
                    }
                    .onPreviewKeyEvent { event ->
                        val pasteKeys = event.type == KeyEventType.KeyDown && event.key == Key.V &&
                            (event.isCtrlPressed || event.isMetaPressed)
                        if (pasteKeys && clipboard.stamp() != null) {
                            pasteImage()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                decorator = TextFieldDecorator { inner ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .background(scheme.surface, RoundedCornerShape(24.dp))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = if (shared) "Comment. Type @ to mention" else "Comment",
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            val canSend = (text.isNotBlank() || staged.isNotEmpty()) && staged.none { it.attachment == null }
            FilledIconButton(
                onClick = { if (onSend(text)) field.clearText() },
                enabled = canSend,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send comment")
            }
        }
    }
}
