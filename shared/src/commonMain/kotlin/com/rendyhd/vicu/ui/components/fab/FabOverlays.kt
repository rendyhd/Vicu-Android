package com.rendyhd.vicu.ui.components.fab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.repository.ProCapture
import com.rendyhd.vicu.data.repository.ProCaptureSource
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.components.shared.snackbarColors
import com.rendyhd.vicu.ui.components.task.tokenBgColor
import com.rendyhd.vicu.ui.components.task.tokenTextColor
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.ui.theme.rememberReduceMotion
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.voice.TaskDefaults
import com.rendyhd.vicu.voice.VoiceCapture
import com.rendyhd.vicu.voice.VoicePhase
import com.rendyhd.vicu.voice.VoicePreview
import com.rendyhd.vicu.voice.voicePreview
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Room the card and the hint leave under them for the + button: its 56 dp, and 16 dp on each side. */
private val ABOVE_FAB = 88.dp

/**
 * What the + button draws over the screens: the voice card while it listens, the + following the
 * finger while it is dragged, and the one-time hint. Placed by the app over the content area, the
 * same box as [AppFab].
 */
@Composable
fun BoxScope.FabOverlays(controller: FabController, fabAlignStart: Boolean) {
    val reduceMotion = rememberReduceMotion()
    val held by controller.holdToTalk.state.collectAsState()
    // Locked, smart dictation's sheet has taken over: the card goes while the last words come in.
    val capture = held?.takeIf { !it.locked }
    val hintVisible by controller.hint.visible.collectAsState()
    // A session outlives no screen: a recreated activity (rotation) starts without one.
    DisposableEffect(controller) { onDispose { controller.holdToTalk.cancel() } }
    // Hands-free, back closes the card (while held, the finger is on the +).
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = capture?.handsFree == true,
    ) { controller.holdToTalk.cancel() }

    // The hint sits above the + until it is used or closed.
    AnimatedVisibility(
        visible = hintVisible && capture == null && controller.dragPoint == null,
        modifier = Modifier
            .align(if (fabAlignStart) Alignment.BottomStart else Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, bottom = ABOVE_FAB),
        enter = riseIn(reduceMotion),
        exit = riseOut(reduceMotion),
    ) {
        FabHintBubble(onClose = controller.hint::dismiss)
    }

    // A light scrim keeps the words readable over a busy list; it also takes no touches (the finger is on the +).
    AnimatedVisibility(
        visible = capture != null,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(VicuMotion.fadeBase.spec()),
        exit = fadeOut(VicuMotion.fadeFast.spec()),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)))
    }

    // The last capture stays drawn while the card animates out.
    var shown by remember { mutableStateOf<VoiceCapture?>(null) }
    capture?.let { shown = it }
    AnimatedVisibility(
        visible = capture != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, bottom = ABOVE_FAB),
        enter = riseIn(reduceMotion),
        exit = riseOut(reduceMotion),
    ) {
        shown?.let { VoiceCard(it, controller, reduceMotion) }
    }

    DragGhost(controller)
}

/** The card and the hint come up from the + (slide and fade), or only fade with reduced motion. */
private fun riseIn(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(VicuMotion.fadeBase.spec())
    } else {
        fadeIn(VicuMotion.fadeBase.spec()) + slideInVertically(VicuMotion.defaultSpatialSpec()) { it / 4 }
    }

private fun riseOut(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(VicuMotion.fadeFast.spec())
    } else {
        fadeOut(VicuMotion.fadeFast.spec()) + slideOutVertically(VicuMotion.fastEffectsSpec()) { it / 4 }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCard(capture: VoiceCapture, controller: FabController, reduceMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val preview = rememberVoicePreview(capture.text, capture.defaults)
    val isDark = scheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .semantics { paneTitle = "Say a task" },
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                MicPulse(level = capture.level, listening = capture.phase == VoicePhase.LISTENING, reduceMotion = reduceMotion)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    val words = capture.text
                    if (words.isBlank()) {
                        Text(
                            text = when (capture.phase) {
                                VoicePhase.LISTENING -> "Listening…"
                                VoicePhase.FINISHING -> "Turning your words into text…"
                                VoicePhase.HEARD -> "Didn't catch that"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = highlighted(words, preview, isDark),
                            style = MaterialTheme.typography.titleMedium,
                            // Hands-free, a screen reader reads the words once an utterance is over.
                            modifier = if (capture.handsFree && capture.phase == VoicePhase.HEARD) {
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier
                            },
                        )
                    }
                    if (preview.pills.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            preview.pills.forEach { pill ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = tokenBgColor(pill.type, isDark),
                                    contentColor = tokenTextColor(pill.type, isDark),
                                ) {
                                    Text(
                                        pill.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (capture.canLock && !capture.handsFree) {
                    // Where the finger goes to keep talking: smart dictation, marked with the assistant's sparkle.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = scheme.onSurfaceVariant)
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (capture.handsFree) {
                HandsFreeButtons(capture, controller)
            } else if (capture.phase == VoicePhase.FINISHING) {
                // The finger is off: a speech model on the phone can take a while with the last words.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = controller.holdToTalk::cancel) { Text("Cancel") }
                }
            } else {
                Text(
                    text = when {
                        capture.cancelArmed -> "Let go to cancel"
                        capture.canLock -> "Let go to add · slide up to keep talking · down to cancel"
                        else -> "Let go to add · slide down to cancel"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (capture.cancelArmed) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HandsFreeButtons(capture: VoiceCapture, controller: FabController) {
    val voice = controller.holdToTalk
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = voice::cancel) { Text("Cancel") }
        if (capture.canLock) {
            // Smart dictation: the microphone stays on and the words become task cards.
            TextButton(onClick = voice::lock) { Text("Keep talking") }
        }
        Spacer(Modifier.size(8.dp))
        if (capture.phase == VoicePhase.HEARD && capture.text.isBlank()) {
            Button(onClick = voice::listenAgain) { Text("Try again") }
        } else {
            Button(onClick = voice::release, enabled = capture.phase != VoicePhase.FINISHING) {
                Text(if (capture.phase == VoicePhase.LISTENING) "Done" else "Add")
            }
        }
    }
}

/**
 * The microphone with a ring that swells with the voice. With reduced motion the ring does not
 * grow: it only gets stronger, so the voice still shows without movement.
 */
@Composable
internal fun MicPulse(level: Float, listening: Boolean, reduceMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val target = if (listening) level else 0f
    val animated by animateFloatAsState(target, VicuMotion.fastEffects.spec(), label = "micLevel")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(48.dp)
                .scale(if (reduceMotion) 1f else 0.8f + animated * 0.45f)
                .background(
                    scheme.primary.copy(alpha = if (reduceMotion) 0.08f + animated * 0.4f else 0.24f),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(40.dp)
                .background(if (listening) scheme.primary else scheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Mic,
                // The card's title says what this is.
                contentDescription = null,
                tint = if (listening) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The words with the parsed parts in the quick add colours. */
private fun highlighted(text: String, preview: VoicePreview, isDark: Boolean): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    for (token in preview.tokens) {
        val start = token.start.coerceIn(0, text.length)
        val end = token.end.coerceIn(0, text.length)
        if (start >= end) continue
        builder.addStyle(SpanStyle(color = tokenTextColor(token.type, isDark), background = tokenBgColor(token.type, isDark)), start, end)
    }
    return builder.toAnnotatedString()
}

/** [voicePreview] with the user's parser settings, projects and Inbox. */
@Composable
private fun rememberVoicePreview(text: String, defaults: TaskDefaults): VoicePreview {
    val nlpPrefs: NlpPrefsStore = koinInject()
    val projectRepository: ProjectRepository = koinInject()
    val authManager: AuthManager = koinInject()
    val time: TimeSource = koinInject()
    val config by nlpPrefs.config.collectAsState(initial = ParserConfig())
    val projects by projectRepository.getAll().collectAsState(initial = emptyList())
    val inboxId by produceState(0L, authManager) { value = authManager.getInboxProjectId() ?: 0L }
    val locations: LocationRepository = koinInject()
    val places by locations.places.collectAsState(initial = emptyList())
    val proCapture: ProCaptureSource = koinInject()
    val pro by proCapture.capture.collectAsState(initial = ProCapture.OFF)
    val day = LocalClockDay.current
    val format = LocalDateFormat.current
    return remember(text, config, projects, inboxId, day, format, defaults, places, pro) {
        voicePreview(text, config, time.now().toLocalDateTime(day.zone), day, projects, inboxId, format, defaults, places, pro)
    }
}

/** The + under the finger while it is dragged to a header. */
@Composable
private fun BoxScope.DragGhost(controller: FabController) {
    val point = controller.dragPoint
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() })
    if (point == null) return
    val half = with(LocalDensity.current) { (GHOST_SIZE / 2).toPx() }
    Surface(
        modifier = Modifier
            .offset { IntOffset((point.x - origin.x - half).roundToInt(), (point.y - origin.y - half).roundToInt()) }
            .size(GHOST_SIZE),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
    }
}

@Composable
private fun FabHintBubble(onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme.snackbarColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.content,
        shadowElevation = 4.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(HINT_TEXT, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close the hint", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Shown in a little bubble above the + after the fifth task added with it. */
internal const val HINT_TEXT = "Hold + to say a task"

private val GHOST_SIZE = 56.dp
private const val SCRIM_ALPHA = 0.32f
