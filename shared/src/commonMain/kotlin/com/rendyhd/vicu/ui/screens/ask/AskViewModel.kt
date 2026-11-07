package com.rendyhd.vicu.ui.screens.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettings
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.ask.Applied
import com.rendyhd.vicu.ai.ask.AskBackend
import com.rendyhd.vicu.ai.ask.AskList
import com.rendyhd.vicu.ai.ask.AskSession
import com.rendyhd.vicu.ai.ask.AskState
import com.rendyhd.vicu.ai.ask.Conversation
import com.rendyhd.vicu.ai.ask.ConversationStore
import com.rendyhd.vicu.ai.ask.Proposal
import com.rendyhd.vicu.ai.ask.Proposals
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ai.talk.SpokenConfirmation
import com.rendyhd.vicu.data.repository.WeeklyReviewPreferences
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.speech.SpeechOutput
import com.rendyhd.vicu.ui.screens.guided.GuidedWorldSource
import com.rendyhd.vicu.util.AgendaItem
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.deviceLocaleTag
import com.rendyhd.vicu.util.randomUuid
import com.rendyhd.vicu.voice.HoldToTalk
import com.rendyhd.vicu.voice.OpenMic
import com.rendyhd.vicu.voice.SelectingSpeechInput
import com.rendyhd.vicu.voice.SpeechAvailability
import com.rendyhd.vicu.voice.SpeechError
import com.rendyhd.vicu.voice.TurnListener
import com.rendyhd.vicu.voice.TurnSender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Talk's microphone (`docs/ai-assistant-v1.md`, sections 3.7 and 6): on until turned off, the words of
 * the turn so far, and whether an answer is being read aloud (the microphone pauses meanwhile).
 */
data class TalkMic(val on: Boolean = false, val words: String = "", val level: Float = 0f, val speaking: Boolean = false)

/** Where Ask was opened from (the top bar's Ask button), for the context block (section 5.1). */
class AskEntry {
    var list: AskList = AskList("Inbox")

    /** Opened to talk (Try it in Settings): the microphone turns on once. */
    var startTalking: Boolean = false
}

/** The agenda of an empty conversation (semantics 14.8) and the Inbox, for the starters' kind (5.7). */
data class AskGuide(val agenda: List<AgendaItem> = emptyList(), val inboxId: Long? = null)

data class AskUiState(
    val session: AskState? = null,
    val settings: AiSettings = AiSettings(),
    val history: List<Conversation> = emptyList(),
    /** Tasks that `[task:id]` marks of this conversation show. */
    val tasks: Map<Long, Task> = emptyMap(),
    val talk: TalkMic = TalkMic(),
) {
    /** The microphone is on: messages are spoken and answers read aloud. */
    val talkMode: Boolean get() = talk.on
}

/**
 * Ask and Talk (`docs/ai-assistant-v1.md`, sections 5 and 6): the conversation, its history,
 * proposal cards with Apply and Decline (Undo on the app snackbar), and the open microphone (3.7):
 * each turn is the next message, talking again cancels a turn still running, answers are read aloud
 * while the microphone pauses, and an open card is answered by voice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModel(
    private val providers: AiProviders,
    private val settingsStore: AiSettingsStore,
    private val backend: AskBackend,
    private val proposals: Proposals,
    private val store: ConversationStore,
    private val tasks: TaskRepository,
    private val messages: AppMessages,
    private val speech: SelectingSpeechInput,
    private val voice: SpeechOutput,
    private val dayClock: DayClock,
    private val time: TimeSource,
    private val entry: AskEntry,
    guidedWorld: GuidedWorldSource,
    weeklyReview: WeeklyReviewPreferences,
) : ViewModel() {
    private val session = MutableStateFlow<AskSession?>(null)
    private val talk = MutableStateFlow(TalkMic())
    private val list: AskList get() = entry.list
    private var turn: Job? = null

    private var mic: OpenMic? = null
    private val sender = TurnSender()

    /** The conversation's length before the spoken turn still running: talking again goes back to it. */
    private var rollback: Int? = null

    /** A cancelled turn being taken out of the conversation or closed; the next message waits for it. */
    private var withdrawing: Job? = null

    private val sessionState = session.flatMapLatest { it?.state ?: flowOf(null) }

    private val knownTasks = combine(sessionState.map { it?.conversation?.knownTaskIds.orEmpty() }, tasks.getAllTasksFlat()) { ids, all ->
        if (ids.isEmpty()) emptyMap() else all.filter { it.id in ids }.associateBy { it.id }
    }

    val uiState: StateFlow<AskUiState> = combine(
        sessionState,
        settingsStore.settings,
        store.conversations,
        knownTasks,
        talk,
    ) { state, settings, history, known, mic ->
        AskUiState(state, settings, history, known, mic)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskUiState())

    /** What an empty conversation starts with (section 5.7): built on the device, nothing is sent for it. */
    val guide: StateFlow<AskGuide> = combine(guidedWorld.world, weeklyReview.last) { world, last ->
        AskGuide(Guided.agenda(world, last), world.inboxId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskGuide())

    /** The starters for the list Ask was opened from (section 5.7). */
    fun starters(inboxId: Long?): List<String> = AiContract.starters(startersKind(entry.list, inboxId))

    init {
        viewModelScope.launch {
            val last = store.all().firstOrNull()
            open(last?.takeUnless(::isStale) ?: Conversation(id = randomUuid()))
        }
        // A card came: the turn has its answer (talking is now the answer to the card, 6.1), and with
        // the microphone on, the card's question is read aloud.
        viewModelScope.launch {
            sessionState.map { it?.pending?.proposal?.callId }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                sender.finished()
                rollback = null
                if (talk.value.on) speakCardQuestion()
            }
        }
    }

    /** Where the user came from, for the context block (section 5.1). */
    fun setList(name: String, projectId: Long?, customListId: String?) {
        entry.list = AskList(name, projectId, customListId)
    }

    /** The name of the list Ask was opened from, for its header; empty when it has none. */
    fun listTitle(): String = entry.list.name

    /** Ask opened again: a conversation left alone for a while makes way for a new one (it stays in the history). */
    fun onOpened() {
        val s = session.value ?: return
        if (s.state.value.busy || s.conversation.messages.isEmpty()) return
        if (isStale(s.conversation)) newChat()
    }

    /** Nothing said in it for as long as the context block is good for (section 5.1). */
    private fun isStale(conversation: Conversation): Boolean =
        time.now().toEpochMilliseconds() - conversation.updatedAt > AiContract.limits.contextRefreshMinutes * 60_000L

    fun open(conversation: Conversation) {
        turn?.cancel()
        // A conversation saved halfway through a tool call loses that unanswered call.
        val messages = conversation.messages.let { m ->
            val last = m.lastOrNull()
            if (last is ChatMessage.Assistant && last.toolCalls.isNotEmpty()) m.dropLast(1) else m
        }
        session.value = AskSession(providers, settingsStore, backend, proposals, store, conversation.copy(messages = messages), time, ::randomUuid)
        sender.reset()
        rollback = null
    }

    fun newChat() {
        open(Conversation(id = randomUuid()))
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (session.value?.conversation?.id == id) newChat()
        }
    }

    fun send(text: String, spoken: Boolean = false) {
        val s = session.value ?: return
        val pending = s.state.value.pending
        val cardOpen = pending != null || s.state.value.lingering != null
        if (cardOpen && spoken) {
            // Section 6.1: an exact yes or no answers the card.
            val language = uiState.value.settings.speechLanguage.takeIf { it != "auto" } ?: deviceLocaleTag()
            when (SpokenConfirmation.answer(text, language)) {
                SpokenConfirmation.Answer.YES -> {
                    sender.finished()
                    return apply()
                }
                SpokenConfirmation.Answer.NO -> {
                    sender.finished()
                    return decline()
                }
                SpokenConfirmation.Answer.OTHER -> Unit
            }
        }
        if (s.state.value.busy && pending == null && withdrawing == null) {
            if (spoken) sender.finished()
            return
        }
        // A spoken turn can be taken back when the person talks again, unless it answered past a card.
        val takeBack = spoken && !cardOpen
        val before = withdrawing
        rollback = if (takeBack && before == null) s.conversation.messages.size else null
        turn = viewModelScope.launch {
            before?.join()
            if (takeBack) rollback = s.conversation.messages.size
            s.send(text, list, talk = spoken)
            if (spoken) {
                sender.finished()
                rollback = null
                speakLastAnswer(s)
            }
        }
    }

    fun retry() {
        val s = session.value ?: return
        turn = viewModelScope.launch {
            s.retry(list, talk.value.on)
            if (talk.value.on) speakLastAnswer(s)
        }
    }

    /**
     * Applies the open card, or else the lingering one; the change gets one message with Undo
     * (section 5.4).
     */
    fun apply() {
        val s = session.value ?: return
        val pending = s.state.value.pending
        val card = pending ?: s.state.value.lingering ?: return
        viewModelScope.launch {
            val applied = proposals.apply(card.proposal, dayClock.day.value.zone)
            if (pending != null) s.decide(applied) else s.decideLingering(applied)
            announce(card.proposal, applied)
        }
    }

    /** The user changed a date on the card. */
    fun editCard(proposal: Proposal) {
        session.value?.replaceCard(proposal)
    }

    fun decline() {
        val s = session.value ?: return
        val pending = s.state.value.pending
        val card = pending ?: s.state.value.lingering ?: return
        val declined = Applied.declined(card.proposal.skipped)
        if (pending != null) s.decide(declined) else viewModelScope.launch { s.decideLingering(declined) }
    }

    fun completeTask(task: Task) {
        viewModelScope.launch { tasks.setDone(task.id, !task.done) }
    }

    private fun announce(proposal: Proposal, applied: Applied) {
        val n = applied.appliedIds.size
        val text = when {
            n == 0 -> "Nothing was changed"
            proposal is Proposal.Create -> if (n == 1) "Added 1 task" else "Added $n tasks"
            proposal is Proposal.Complete && proposal.done -> if (n == 1) "Completed 1 task" else "Completed $n tasks"
            else -> if (n == 1) "Changed 1 task" else "Changed $n tasks"
        }
        val undo = applied.undo
        val created = applied.appliedIds.singleOrNull().takeIf { proposal is Proposal.Create }
        if (undo == null) messages.post(text) else messages.post(text, "Undo", openTaskId = created) { viewModelScope.launch { undo() } }
    }

    private suspend fun speakLastAnswer(s: AskSession) {
        val last = s.conversation.messages.lastOrNull() as? ChatMessage.Assistant ?: return
        speak(AskSession.TASK_MARK.replace(last.text, "").trim())
    }

    /** The question that came with an open card (Talk ends with one, section 6). */
    private suspend fun speakCardQuestion() {
        val s = session.value ?: return
        val asking = s.conversation.messages.lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant
        speak(asking?.let { AskSession.TASK_MARK.replace(it.text, "").trim() }.orEmpty())
    }

    /**
     * Reads [text] aloud with the microphone on. The microphone pauses meanwhile, since it would hear
     * the device; through headphones it keeps listening, and talking stops the voice (section 3.7).
     */
    private suspend fun speak(text: String) {
        val settings = settingsStore.current()
        val m = mic
        if (text.isEmpty() || m == null || !settings.speakAnswers || !voice.isAvailable) return
        val pause = !voice.playsPrivately
        if (pause) m.pause()
        talk.update { it.copy(speaking = true, words = "", level = 0f) }
        val language = settings.speechLanguage.takeIf { it != "auto" }
        voice.speak(text, language) {
            viewModelScope.launch {
                talk.update { it.copy(speaking = false) }
                if (mic === m) m.resume()
            }
        }
    }

    // --- Talk's microphone (sections 3.7 and 6) ----------------------------------------------

    /**
     * The microphone button: on or off; while an answer is read aloud, it stops the voice and listens
     * at once.
     */
    fun toggleMic() {
        when {
            talk.value.speaking -> {
                voice.stop()
                talk.update { it.copy(speaking = false) }
                mic?.resume()
            }
            mic != null -> micOff()
            else -> micOn()
        }
    }

    /** Ask opened to talk: the microphone turns on. */
    fun startTalkingIfAsked() {
        if (!entry.startTalking) return
        entry.startTalking = false
        micOn()
    }

    fun micOn() {
        if (mic != null) return
        when (speech.availability()) {
            SpeechAvailability.READY -> Unit
            SpeechAvailability.NEEDS_PERMISSION -> {
                speech.requestPermission { granted -> if (granted) micOn() }
                return
            }
            SpeechAvailability.DENIED -> {
                messages.post("Allow the microphone in Settings to talk to Vicu.", "Settings") { speech.openSettings() }
                return
            }
            SpeechAvailability.UNAVAILABLE -> {
                messages.post("Speech recognition is not available on this device.")
                return
            }
        }
        sender.reset()
        talk.value = TalkMic(on = true)
        mic = speech.openMic(listener)
    }

    fun micOff() {
        mic?.stop()
        mic = null
        if (talk.value.speaking) voice.stop()
        sender.reset()
        talk.value = TalkMic()
    }

    private val listener = object : TurnListener {
        override fun onWords(text: String) = talk.update { it.copy(words = HoldToTalk.joinWords(sender.waiting, text)) }

        override fun onLevel(level: Float) = talk.update { it.copy(level = level) }

        override fun onSpeechStart() {
            // Through headphones the voice may still be reading: talking stops it.
            if (talk.value.speaking) {
                voice.stop()
                talk.update { it.copy(speaking = false) }
            }
            val s = session.value
            val state = s?.state?.value
            if (s != null && state != null && state.busy && state.pending == null && rollback != null && sender.interrupt()) {
                withdraw(s)
            }
            talk.update { it.copy(words = sender.waiting) }
        }

        override fun onTurn(text: String) {
            talk.update { it.copy(words = "") }
            sender.turn(text)?.let { send(it, spoken = true) }
        }

        override fun onOff(error: SpeechError?) {
            mic = null
            talk.value = TalkMic()
            if (error != null) messages.post("The microphone stopped. Tap it to talk again.")
        }
    }

    /** Talking again cancelled the running turn: the conversation goes back to before it (section 3.7). */
    private fun withdraw(s: AskSession) {
        val to = rollback ?: return
        rollback = null
        endTurn { s.withdraw(to) }
    }

    /**
     * The Stop button, while the model works and no card is open: the turn ends, what was asked stays
     * in the conversation and the app says "Stopped.", as the desktop does.
     */
    fun stop() {
        val s = session.value ?: return
        val state = s.state.value
        if (!state.busy || state.pending != null) return
        rollback = null
        sender.reset()
        endTurn { s.stopped() }
    }

    /** Cancels the running turn, then runs [after]; the next message waits for both. */
    private fun endTurn(after: suspend () -> Unit) {
        val running = turn
        turn = null
        val job = viewModelScope.launch {
            running?.cancelAndJoin()
            after()
        }
        withdrawing = job
        job.invokeOnCompletion { viewModelScope.launch { if (withdrawing === job) withdrawing = null } }
    }

    override fun onCleared() {
        voice.stop()
        mic?.stop()
        mic = null
    }

    private fun Set<Long>?.orEmpty(): Set<Long> = this ?: emptySet()

    companion object {
        /** The kind of list the starters are chosen by: `today`, `upcoming`, `inbox`, `project` or `other`. */
        fun startersKind(list: AskList, inboxId: Long?): String = when {
            list.customListId != null -> "other"
            list.projectId != null && list.projectId == inboxId -> "inbox"
            list.projectId != null -> "project"
            list.name == "Today" -> "today"
            list.name == "Upcoming" -> "upcoming"
            list.name == "Inbox" -> "inbox"
            else -> "other"
        }
    }
}
