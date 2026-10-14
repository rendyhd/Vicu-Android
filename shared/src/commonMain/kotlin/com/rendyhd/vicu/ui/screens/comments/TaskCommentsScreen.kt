package com.rendyhd.vicu.ui.screens.comments

import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.domain.model.TaskComment
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.domain.model.toCollabUser
import com.rendyhd.vicu.ui.components.collab.AvatarStack
import com.rendyhd.vicu.ui.components.collab.LocalCollab
import com.rendyhd.vicu.ui.components.collab.PersonAvatar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.screens.taskdetail.EditorBackState
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.CommentSegment
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.DueDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val collab = LocalCollab.current
    val day = LocalClockDay.current
    val fmt = LocalDateFormat.current
    val task = state.task?.takeIf { state.taskId == taskId }
    val access = collab.access(task?.projectId ?: 0L)
    val me = collab.state.me
    val members = access.members
    val collabMembers = remember(members) { members.map { it.toCollabUser() } }

    PredictiveBackHandler(enabled = backEnabled) { progress ->
        try {
            progress.collect { backState?.progress = it.progress }
        } catch (e: CancellationException) {
            backState?.let { scope.launch { it.springBack() } }
            throw e
        }
        onClose()
    }

    // A project that stops being shared takes its comments with it; a task that is gone too.
    var wasShared by remember(taskId) { mutableStateOf(false) }
    var hadTask by remember(taskId) { mutableStateOf(false) }
    LaunchedEffect(access.shared, task != null) {
        if (task != null) hadTask = true else if (hadTask) onClose()
        if (access.shared) wasShared = true else if (wasShared) onClose()
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
                        // Not offered with an API token: Vikunja does not let tokens follow tasks (section 9.7).
                        if (state.canFollow) {
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
                                onSend = { text ->
                                    val html = Collaboration.encodeCommentHtml(text, collabMembers)
                                    if (html.isEmpty()) {
                                        false
                                    } else {
                                        viewModel.post(html)
                                        true
                                    }
                                },
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
                                val html = Collaboration.encodeCommentHtml(text, collabMembers)
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
        AlertDialog(
            onDismissRequest = { pendingDelete = 0L },
            title = { Text("Delete comment?") },
            text = { Text("This cannot be undone.") },
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

/** A comment's text with each mention as "@Name" in `primary`, semibold. */
private fun commentText(segments: List<CommentSegment>, mentionColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        for (segment in segments) {
            when (segment) {
                is CommentSegment.Text -> append(segment.text)
                is CommentSegment.Mention -> withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)) {
                    append("@")
                    append(segment.label)
                }
            }
        }
    }

/**
 * One comment: avatar, name and time, the text. A long press on your own comment highlights it and
 * offers Edit and Delete (also as screen-reader actions). A comment waiting to sync shows that in
 * place of its time and cannot be changed until it has been sent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(
    comment: TaskComment,
    time: String,
    members: List<User>,
    me: Long?,
    canWrite: Boolean,
    selected: Boolean,
    editing: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
) {
    val collabMembers = remember(members) { members.map { it.toCollabUser() } }
    val decoded = remember(comment.comment, collabMembers) { Collaboration.decodeCommentHtml(comment.comment, collabMembers) }
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
    val body = remember(decoded, primary) { commentText(decoded.segments, primary) }

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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancelEdit) { Text("Cancel") }
                    Button(onClick = { onSaveEdit(draft.text) }, enabled = draft.text.isNotBlank()) { Text("Save") }
                }
            } else {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (comment.pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
 * The composer: a pill text field (Enter is a new line; the send button sends) and, while "@" and
 * a few letters stand before the caret, the matching members as chips above it. The draft is kept
 * per task across configuration changes and process death.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    taskId: Long,
    members: List<User>,
    me: Long?,
    onSend: (String) -> Boolean,
) {
    var value by rememberSaveable(taskId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val mention = remember(value.text, value.selection) {
        if (value.selection.collapsed) Collaboration.mentionQueryAt(value.text, value.selection.start) else null
    }
    val suggestions = remember(mention, members, me) {
        mention?.let { m -> Collaboration.mentionSuggestions(m.query, members, me) { it.toCollabUser() } }.orEmpty()
    }
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
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
                            val edit = Collaboration.applyMention(value.text, value.selection.start, mention, person.username)
                            value = TextFieldValue(edit.text, TextRange(edit.caret))
                        },
                        label = { Text(Collaboration.displayName(person.toCollabUser())) },
                        avatar = { PersonAvatar(person, 24.dp) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .background(scheme.surface, RoundedCornerShape(24.dp))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = "Comment. Type @ to mention",
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            val canSend = value.text.isNotBlank()
            FilledIconButton(
                onClick = { if (onSend(value.text)) value = TextFieldValue() },
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
