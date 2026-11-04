package com.rendyhd.vicu.ui.screens.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.ask.AskSession
import com.rendyhd.vicu.ai.ask.PendingProposal
import com.rendyhd.vicu.ai.ask.ProposalOutcome
import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.assistant.DraftCard
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.VicuTopAppBar
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DueDates
import org.koin.compose.viewmodel.koinViewModel

/**
 * Ask: a conversation with the user's model about their tasks (`docs/ai-assistant-v1.md`, section 5),
 * and Talk, the same by voice (section 6). Task marks in answers are real rows; changes come as
 * cards to apply or decline.
 */
@Composable
fun AskScreen(
    onOpenDrawer: () -> Unit,
    onTaskClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onStartGuided: (String) -> Unit = {},
    viewModel: AskViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val session = state.session
    val ready = state.settings.askReady
    var historyOpen by remember { mutableStateOf(false) }
    // The microphone never stays on behind another app or screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.micOff() }
    DisposableEffect(viewModel) { onDispose { viewModel.micOff() } }
    LaunchedEffect(viewModel) { viewModel.startTalkingIfAsked() }

    Scaffold(
        topBar = {
            VicuTopAppBar(
                title = { Text(if (state.talkMode) "Talk" else "Ask") },
                onOpenDrawer = onOpenDrawer,
                extraActions = {
                    Box {
                        IconButton(onClick = { historyOpen = true }, enabled = state.history.isNotEmpty()) {
                            Icon(Icons.Outlined.History, contentDescription = "Earlier conversations")
                        }
                        DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                            for (c in state.history) {
                                DropdownMenuItem(
                                    text = { Text(c.title.ifBlank { "Conversation" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        historyOpen = false
                                        viewModel.open(c)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = viewModel::newChat) { Icon(Icons.Outlined.AddComment, contentDescription = "New chat") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!ready) {
                NotSetUp(onOpenSettings, Modifier.weight(1f))
                return@Column
            }
            if (session?.conversation?.messages?.isEmpty() == true) {
                AskAgenda(
                    items = guide.agenda,
                    starters = viewModel.starters(guide.inboxId),
                    enabled = !session.busy,
                    onItem = onStartGuided,
                    onStarter = { text -> viewModel.send(text) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Conversation(state, viewModel, onTaskClick, Modifier.weight(1f))
            InputBar(state, viewModel)
        }
    }
}

@Composable
private fun NotSetUp(onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text("Ask your tasks anything", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Connect a model you bring (a key for OpenAI, Anthropic, Gemini or OpenRouter, or your own Ollama or Open WebUI), " +
                "test it and switch Ask on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text("Set up the assistant") }
    }
}

@Composable
internal fun Conversation(state: AskUiState, viewModel: AskViewModel, onTaskClick: (Long) -> Unit, modifier: Modifier) {
    val session = state.session ?: return
    val conversation = session.conversation
    val listState = rememberLazyListState()
    val finalAnswer = AiContract.message("finalAnswer")
    LaunchedEffect(conversation.messages.size, session.streaming.length, session.pending, session.busy, session.error, session.stopped) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }
    var confirmBig by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversation.messages.isEmpty()) {
            item(key = "hello") {
                Text(
                    if (state.talkMode) "Just talk, for example: “What's on today?”"
                    else "Ask about your tasks, for example “What is overdue?” or “Move everything for the trip to Friday”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        conversation.messages.forEachIndexed { index, message ->
            when (message) {
                is ChatMessage.User -> {
                    val text = AskSession.visibleText(message.text)
                    if (text != finalAnswer && text.isNotBlank()) item(key = "m$index") { UserBubble(text) }
                }
                is ChatMessage.Assistant -> {
                    if (message.text.isNotBlank()) {
                        item(key = "m$index") { AnswerText(message.text, conversation.knownTaskIds, state.tasks, viewModel, onTaskClick) }
                    }
                    for (call in message.toolCalls) {
                        val outcome = conversation.outcomes[call.id]
                        if (outcome != null) item(key = "o${call.id}") { OutcomeLine(outcome) }
                    }
                }
                is ChatMessage.ToolResults -> Unit
            }
        }
        (session.pending ?: session.lingering)?.let { pending ->
            item(key = "pending") {
                ProposalCard(
                    pending = pending,
                    onApply = { if (pending.proposal.size > AiContract.limits.proposalConfirmTwiceAbove) confirmBig = true else viewModel.apply() },
                    onDecline = viewModel::decline,
                )
            }
        }
        if (session.streaming.isNotBlank()) {
            item(key = "streaming") { AnswerText(session.streaming, conversation.knownTaskIds, state.tasks, viewModel, onTaskClick) }
        }
        if (session.busy && session.streaming.isBlank() && session.pending == null) {
            item(key = "busy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(session.activity ?: "Thinking", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (session.stopped) {
            item(key = "stopped") {
                Column {
                    Text("Stopped.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::retry) { Text("Retry") }
                }
            }
        }
        session.error?.let { error ->
            item(key = "error") {
                Column {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retry) { Text("Retry") }
                }
            }
        }
    }

    if (confirmBig) {
        val n = (session.pending ?: session.lingering)?.proposal?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmBig = false },
            title = { Text("Change $n tasks?") },
            text = { Text("This card changes more than ${AiContract.limits.proposalConfirmTwiceAbove} tasks at once. You can undo it right after.") },
            confirmButton = { TextButton(onClick = { confirmBig = false; viewModel.apply() }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { confirmBig = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** Model text as plain text; a `[task:id]` of a task a tool returned is that task's row (section 5.1). */
@Composable
private fun AnswerText(text: String, known: Set<Long>, tasks: Map<Long, Task>, viewModel: AskViewModel, onTaskClick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var last = 0
        for (match in AskSession.TASK_MARK.findAll(text)) {
            val before = text.substring(last, match.range.first).trim()
            if (before.isNotEmpty()) Text(before, style = MaterialTheme.typography.bodyMedium)
            val id = match.groupValues[1].toLongOrNull()
            val task = id?.takeIf { it in known }?.let { tasks[it] }
            if (task != null) TaskMarkRow(task, onClick = { onTaskClick(task.id) }, onToggle = { viewModel.completeTask(task) })
            last = match.range.last + 1
        }
        val rest = text.substring(last).trim()
        if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TaskMarkRow(task: Task, onClick: () -> Unit, onToggle: () -> Unit) {
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (task.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (task.done) "Open ${task.title} again" else "Complete ${task.title}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val due = DateDisplay.formatDue(DateContext.ROW, task.dueDate, day.date, day.zone, format)
                if (due.isNotBlank()) {
                    Text(
                        due,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (DueDates.isOverdue(task.dueDate, day.date, day.zone) && !task.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeLine(outcome: ProposalOutcome) {
    val text = when (outcome.status) {
        "applied" -> "Applied"
        "partly_applied" -> "Partly applied"
        "declined" -> "Declined"
        else -> "Not applied"
    }
    Text(
        "$text: ${outcome.summary}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A proposal: every task and its new value, before anything changes (section 5.4). */
@Composable
private fun ProposalCard(pending: PendingProposal, onApply: () -> Unit, onDecline: () -> Unit) {
    val proposal = pending.proposal
    val snapshot = pending.snapshot
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (proposal) {
                    is Proposal.Create -> if (proposal.size == 1) "Add 1 task" else "Add ${proposal.size} tasks"
                    is Proposal.Update -> if (proposal.size == 1) "Change 1 task" else "Change ${proposal.size} tasks"
                    is Proposal.Complete -> (if (proposal.done) "Complete " else "Open again ") + if (proposal.size == 1) "1 task" else "${proposal.size} tasks"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            when (proposal) {
                is Proposal.Create -> for (draft in proposal.result.drafts) {
                    DraftCard(draft, proposal.context, proposal.result.dropped.filter { it.ref == draft.ref }, emptySet(), editable = false, onChange = {}, onRemove = null)
                }
                is Proposal.Update -> for (change in proposal.changes) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)) {
                        Text(change.before.title, style = MaterialTheme.typography.bodyMedium)
                        val lines = buildList {
                            change.title?.let { add("Title: $it") }
                            change.due?.let { (local, hasTime) -> add("Due: " + DateDisplay.format(DateContext.CHIP, local, day.date, !hasTime, format)) }
                            if (change.clearDue) add("Due: no date")
                            change.projectId?.let { add("Project: " + snapshot.path(it)) }
                            if (change.labelsAdd.isNotEmpty()) add("Add labels: " + change.labelsAdd.joinToString(", "))
                            if (change.labelsRemove.isNotEmpty()) add("Remove labels: " + change.labelsRemove.joinToString(", ") { it.title })
                            change.priority?.let { add("Priority: " + listOf("none", "low", "medium", "high", "urgent")[it]) }
                            change.appendNotes?.let { add("Add to notes: $it") }
                        }
                        for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is Proposal.Complete -> for (task in proposal.tasks) {
                    Text("• ${task.title}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (proposal.skipped.isNotEmpty()) {
                Text("Skipped:", style = MaterialTheme.typography.labelMedium)
                for (s in proposal.skipped) {
                    val why = when (s.reason) {
                        "not_found" -> "not found"
                        "read_only" -> "you cannot change it"
                        else -> "nothing to change"
                    }
                    Text("• ${s.title ?: "Task ${s.taskId}"}: $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDecline) { Text("Decline") }
                Button(onClick = onApply) { Text("Apply") }
            }
        }
    }
}

@Composable
internal fun InputBar(state: AskUiState, viewModel: AskViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val busy = state.session?.busy == true && state.session.pending == null
    val mic = state.talk
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (mic.on) {
            Text(
                when {
                    mic.speaking -> "Speaking. Tap the microphone to talk."
                    mic.words.isNotBlank() -> mic.words
                    else -> "Listening. Just talk; a pause sends it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.settings.talkReady) {
                Surface(
                    onClick = viewModel::toggleMic,
                    shape = CircleShape,
                    color = if (mic.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (mic.on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = when {
                            mic.speaking -> "Stop the answer and talk"
                            mic.on -> "Microphone on. Tap to stop listening"
                            else -> "Talk: turn the microphone on"
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (mic.on) Icons.Outlined.Mic else Icons.Outlined.MicNone, contentDescription = null)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ask about your tasks") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            if (busy) {
                IconButton(onClick = viewModel::stop) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(text)
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                }
            }
        }
    }
}
