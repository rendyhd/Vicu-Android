package com.rendyhd.vicu.voice

import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DateDisplayFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoicePhase {
    /** The recognizer is listening. */
    LISTENING,

    /** An utterance is over and nothing listens: hands-free waits for Add, a hold for the finger to lift. */
    HEARD,

    /** The finger lifted: waiting a moment for the recognizer's last words. */
    FINISHING,
}

/** What the voice card shows. */
data class VoiceCapture(
    val text: String = "",
    val level: Float = 0f,
    val phase: VoicePhase = VoicePhase.LISTENING,
    /** The finger slid down far enough that letting go cancels. */
    val cancelArmed: Boolean = false,
    /** Started without a hold (a screen reader's "Say a task", the app icon's Dictate): the card has Add and Cancel buttons. */
    val handsFree: Boolean = false,
    /** What the list the + was held in gives the task (its project, Today's date). */
    val defaults: TaskDefaults = TaskDefaults(),
    /** Sliding up to the lock is offered: smart dictation is on and ready. */
    val canLock: Boolean = false,
    /** Locked: the card is gone and the words go to smart dictation once the recognizer gives its last. */
    val locked: Boolean = false,
)

/** What [HoldToTalk.begin] did. */
enum class VoiceBegin {
    /** Listening; the card is up. */
    STARTED,

    /** The permission prompt is up; the user holds + again once it is answered. */
    ASKED_PERMISSION,

    /** The system's speech screen took over; it adds the task itself. */
    SYSTEM_SPEECH,

    /** Not allowed, and no system screen: a message says where to allow it. */
    DENIED,

    /** No recognizer at all: the caller opens quick add instead. */
    UNAVAILABLE,
}

/** A task added from the words: the confirmation to show, and how to take it back (null when it failed). */
class VoiceAddResult(val message: String, val taskId: Long? = null, val undo: (suspend () -> Unit)? = null)

/**
 * The session behind holding the + button: listens while the finger is down and shows the words
 * ([state], drawn by the voice card), adds the task when it lifts ([release]) through the same path
 * as the other voice adds ([addTask], `TextTaskCreator`) with the defaults of the list it was held
 * in, and says what was added on the app snackbar, with Undo. This is quick capture, with no model;
 * sliding up to the lock ([lock]) hands over to smart dictation instead (`docs/ai-assistant-v1.md`,
 * section 4).
 *
 * Android's recognizer ends an utterance at a pause. While the finger is still down the next
 * utterance is heard too and joined to the words so far, so a pause does not cut the task short.
 */
class HoldToTalk(
    private val speech: SpeechInput,
    private val messages: AppMessages,
    private val scope: CoroutineScope,
    private val addTask: suspend (text: String, format: DateDisplayFormat, defaults: TaskDefaults) -> VoiceAddResult,
    /** A task was added by voice (the + hint is then never needed). */
    private val onVoiceAdded: () -> Unit = {},
    /** Whether the lock is offered: smart dictation is on and ready. */
    private val canLock: () -> Boolean = { false },
    /** Locked: smart dictation opens, listening, for the list the + was held in. */
    private val onLock: (TaskDefaults) -> Unit = {},
    /** The words said before the lock (empty when none), once the recognizer gave its last. */
    private val onLockedWords: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<VoiceCapture?>(null)
    val state: StateFlow<VoiceCapture?> = _state.asStateFlow()

    private var format = DateDisplayFormat.system(true)

    /** Words of the utterances already over in this session. */
    private var committed = ""

    /** The words of the utterance in progress. */
    private var partial = ""

    /** Each recognizer run gets its own listener; callbacks of an older run are ignored. */
    private var run = 0
    private var finishWait: Job? = null

    val isActive: Boolean get() = _state.value != null

    /** Whether a hold starting now offers the lock. */
    fun lockOffered(): Boolean = canLock()

    fun begin(format: DateDisplayFormat, handsFree: Boolean = false, defaults: TaskDefaults = TaskDefaults()): VoiceBegin {
        if (isActive) return VoiceBegin.STARTED
        this.format = format
        return when (speech.availability()) {
            SpeechAvailability.READY -> {
                committed = ""
                partial = ""
                _state.value = VoiceCapture(handsFree = handsFree, defaults = defaults, canLock = canLock())
                listen()
                VoiceBegin.STARTED
            }
            SpeechAvailability.NEEDS_PERMISSION -> {
                speech.requestPermission { granted ->
                    // Without a hold (Say a task, the app icon's Dictate) it listens now; a hold is over.
                    if (granted && handsFree) {
                        begin(format, handsFree = true, defaults)
                    } else if (granted) {
                        messages.post("Microphone allowed. Hold + and say the task.")
                    }
                }
                VoiceBegin.ASKED_PERMISSION
            }
            SpeechAvailability.DENIED -> {
                if (speech.openSystemSpeech()) {
                    VoiceBegin.SYSTEM_SPEECH
                } else {
                    messages.post(DENIED_MESSAGE, "Settings") { speech.openSettings() }
                    VoiceBegin.DENIED
                }
            }
            SpeechAvailability.UNAVAILABLE ->
                if (speech.openSystemSpeech()) VoiceBegin.SYSTEM_SPEECH else VoiceBegin.UNAVAILABLE
        }
    }

    /** The finger slid down past the cancel line ([armed]) or back up. */
    fun setCancelArmed(armed: Boolean) {
        _state.value?.let { if (it.cancelArmed != armed) _state.value = it.copy(cancelArmed = armed) }
    }

    /** The finger lifted (or Add was pressed): adds what was heard, unless the cancel line was crossed. */
    fun release() {
        val current = _state.value ?: return
        if (current.locked) return
        if (current.cancelArmed) {
            cancel()
            return
        }
        wrapUp(current)
    }

    /**
     * Slid up to the lock (or Keep talking, hands-free): smart dictation opens and keeps listening;
     * the words said so far become its first once the recognizer gives its last.
     */
    fun lock() {
        val current = _state.value ?: return
        if (!current.canLock || current.locked) return
        val locked = current.copy(locked = true, cancelArmed = false)
        _state.value = locked
        onLock(current.defaults)
        wrapUp(locked)
    }

    private fun wrapUp(current: VoiceCapture) {
        when (current.phase) {
            VoicePhase.LISTENING -> {
                _state.value = current.copy(phase = VoicePhase.FINISHING, level = 0f)
                speech.stop()
                // The recognizer's last words normally come within a moment; do not wait for ever.
                finishWait = scope.launch {
                    delay(speech.finalWaitMs)
                    finish()
                }
            }
            VoicePhase.HEARD -> finish()
            VoicePhase.FINISHING -> Unit
        }
    }

    /** Lets go without adding anything. */
    fun cancel() {
        if (!isActive) return
        val wasLocked = _state.value?.locked == true
        run++
        finishWait?.cancel()
        speech.cancel()
        _state.value = null
        // Smart dictation is open already: it listens without the words said before.
        if (wasLocked) onLockedWords("")
    }

    /** Hands-free: listens again after an utterance that was not understood. */
    fun listenAgain() {
        val current = _state.value ?: return
        if (current.phase != VoicePhase.HEARD) return
        _state.value = current.copy(phase = VoicePhase.LISTENING)
        listen()
    }

    private fun listen() {
        val mine = ++run
        partial = ""
        speech.start(
            object : SpeechListener {
                override fun onPartial(text: String) {
                    if (mine != run) return
                    partial = text
                    _state.value?.let { _state.value = it.copy(text = words()) }
                }

                override fun onLevel(level: Float) {
                    if (mine != run) return
                    _state.value?.let { if (it.phase == VoicePhase.LISTENING) _state.value = it.copy(level = level.coerceIn(0f, 1f)) }
                }

                override fun onFinal(text: String) {
                    if (mine != run) return
                    committed = joinWords(committed, text)
                    partial = ""
                    utteranceOver()
                }

                override fun onError(error: SpeechError) {
                    if (mine != run) return
                    // What was heard before the error still counts.
                    committed = words()
                    partial = ""
                    when (error) {
                        SpeechError.NO_MATCH -> utteranceOver()
                        else -> failed(error)
                    }
                }
            },
        )
    }

    private fun utteranceOver() {
        val current = _state.value ?: return
        when {
            current.phase == VoicePhase.FINISHING -> finish()
            // Still held: keep listening (also after a silence), the next words join these.
            !current.handsFree -> {
                _state.value = current.copy(text = committed)
                listen()
            }
            else -> _state.value = current.copy(text = committed, phase = VoicePhase.HEARD, level = 0f)
        }
    }

    private fun failed(error: SpeechError) {
        val current = _state.value ?: return
        if (current.phase == VoicePhase.FINISHING || committed.isNotBlank()) {
            // Keep what was heard: a hold adds it when the finger lifts, hands-free waits for Add.
            if (current.phase == VoicePhase.FINISHING) {
                finish()
            } else {
                _state.value = current.copy(text = committed, phase = VoicePhase.HEARD, level = 0f)
            }
            return
        }
        run++
        _state.value = null
        messages.post(
            when (error) {
                SpeechError.NETWORK -> "Speech recognition needs a connection right now. Try again online."
                SpeechError.PERMISSION -> DENIED_MESSAGE
                else -> "Could not listen. Try again."
            },
        )
    }

    private fun finish() {
        if (!isActive) return
        run++
        finishWait?.cancel()
        val text = words().trim()
        val current = _state.value
        val defaults = current?.defaults ?: TaskDefaults()
        _state.value = null
        if (current?.locked == true) {
            onLockedWords(text)
            return
        }
        if (text.isBlank()) {
            messages.post(NOTHING_HEARD)
            return
        }
        val format = format
        scope.launch {
            val result = addTask(text, format, defaults)
            val undo = result.undo
            if (undo != null) {
                onVoiceAdded()
                messages.post(result.message, "Undo", openTaskId = result.taskId) { scope.launch { undo() } }
            } else if (result.message.isNotEmpty()) {
                messages.post(result.message)
            }
        }
    }

    private fun words(): String = joinWords(committed, partial)

    companion object {
        /** How long a release waits for the recognizer's last words. */
        const val FINAL_WAIT_MS = 1_500L
        internal const val NOTHING_HEARD = "Didn't catch that. Hold + and say the task."
        internal const val DENIED_MESSAGE = "Vicu needs the microphone to hear a task. Allow it in Settings."

        internal fun joinWords(first: String, second: String): String = when {
            first.isBlank() -> second.trim()
            second.isBlank() -> first.trim()
            else -> "${first.trim()} ${second.trim()}"
        }
    }
}
