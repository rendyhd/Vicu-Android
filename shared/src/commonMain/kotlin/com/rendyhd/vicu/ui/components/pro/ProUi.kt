package com.rendyhd.vicu.ui.components.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.data.repository.ProActions
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.components.picker.MonthCalendar
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.VicuDragHandle
import com.rendyhd.vicu.ui.components.task.labelChipColors
import com.rendyhd.vicu.ui.theme.LocalVicuColors
import com.rendyhd.vicu.ui.theme.VicuChipShape
import com.rendyhd.vicu.util.parseHexArgb
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.ProScreens
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Pro as the rows, the editor and the Pro screens see it (docs/cross-app-semantics-v1.md section
 * 21): the shared [ProWorld] and what can be changed. Provided once at the app root; without a
 * provider, or while Pro is off, every task reads as without Pro and nothing is drawn.
 */
@Immutable
class ProUi(val world: ProWorld = ProWorld.OFF, val actions: ProActions? = null)

val LocalPro = compositionLocalOf { ProUi() }

/** The icons of Pro's drawer views. */
object ProIcons {
    val FLAGGED: ImageVector = Icons.Outlined.Flag
    val WAITING_ON: ImageVector = Icons.Outlined.HourglassEmpty
    val PEOPLE: ImageVector = Icons.Outlined.People
}

/**
 * A person's mark (design system 9): a circle with their initials, in their label's colour, where
 * a promise of theirs has its checkbox, because it is not yours to tick. With [onClick] it is a
 * 48 dp button that opens the verdicts.
 */
@Composable
fun PersonMark(
    name: String,
    hexColor: String,
    modifier: Modifier = Modifier,
    circle: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val chip = remember(hexColor, isDark) { parseHexArgb(hexColor)?.let { labelChipColors(it, isDark) } }
    val initials = remember(name) { ProScreens.initials(name) }
    val target = if (onClick != null) {
        modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = "Give a verdict", onClick = onClick)
            .semantics { contentDescription = "$name's promise" }
    } else {
        modifier.clearAndSetSemantics { contentDescription = name }
    }
    Box(modifier = target, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(circle)
                .background(chip?.fill ?: scheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = chip?.text ?: scheme.onSecondaryContainer,
                maxLines = 1,
            )
        }
    }
}

/**
 * A label's Pro menu (contract 21.2, 21.5): Put on hold or Make active while the availability layer
 * is on, This is a person or Not a person while the promises layer is on. Nothing while Pro is off.
 */
@Composable
fun LabelProMenu(labelId: Long) {
    val pro = LocalPro.current
    val world = pro.world
    if (!world.availabilityOn && !world.promisesOn) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Label options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (world.availabilityOn) {
                val held = world.labelOnHold(labelId)
                DropdownMenuItem(
                    text = { Text(if (held) "Make active" else "Put on hold") },
                    onClick = {
                        open = false
                        pro.actions?.setLabelOnHold(labelId, !held)
                    },
                )
            }
            if (world.promisesOn) {
                val person = world.isPerson(labelId)
                DropdownMenuItem(
                    text = { Text(if (person) "Not a person" else "This is a person") },
                    onClick = {
                        open = false
                        pro.actions?.setPerson(labelId, !person)
                    },
                )
            }
        }
    }
}

/** The flag after a flagged task's title (design system 9): filled, orange, named "Flagged". */
@Composable
fun FlagMark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(
        imageVector = Icons.Filled.Flag,
        contentDescription = "Flagged",
        tint = LocalVicuColors.current.priorityHigh,
        modifier = modifier.size(size),
    )
}

/** A small chip in the accent tint: "Next" on a sequential project's first task, "In recap" on a sent promise. */
@Composable
fun ProTintChip(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        modifier = modifier
            .background(scheme.primary.copy(alpha = 0.12f), VicuChipShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = scheme.primary,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

/** Available, Remaining or All (contract 21.2), above a list while the availability layer is on. */
@Composable
fun ProViewSwitch(view: AvailabilityView, onChange: (AvailabilityView) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            AvailabilityView.AVAILABLE to "Available",
            AvailabilityView.REMAINING to "Remaining",
            AvailabilityView.ALL to "All",
        ).forEach { (option, label) ->
            FilterChip(
                selected = view == option,
                onClick = { onChange(option) },
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = "Show $label" },
            )
        }
    }
}

/** The view switch at the top of a list; nothing while the availability layer is off ([view] null). */
fun LazyListScope.proViewSwitchItem(view: AvailabilityView?, onChange: (AvailabilityView) -> Unit) {
    if (view != null) item(key = "pro_view_switch", contentType = "pro_view_switch") { ProViewSwitch(view, onChange) }
}

/** The "not available yet" line at the end of an Available list. */
fun LazyListScope.proHiddenItem(view: AvailabilityView?, hidden: Int, onShowRemaining: () -> Unit) {
    if (view == AvailabilityView.AVAILABLE && hidden > 0) {
        item(key = "pro_hidden", contentType = "pro_hidden") { ProHiddenSummary(hidden, onShowRemaining) }
    }
}

/** Under an Available list: how many open tasks wait, with a way to see them. */
@Composable
fun ProHiddenSummary(count: Int, onShow: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ProScreens.hiddenSummary(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShow) { Text("Show remaining") }
    }
}

/** The entry sheet's promise fields, shown while the title names a person (contract 21.6). */
@Composable
fun PromiseCaptureFields(
    mine: Boolean,
    words: String,
    doneWhen: String,
    onWords: (String) -> Unit,
    onDoneWhen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wordsLabel = if (mine) "What you said" else "In their words"
        OutlinedTextField(
            value = words,
            onValueChange = onWords,
            label = { Text(wordsLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = doneWhen,
            onValueChange = onDoneWhen,
            label = { Text("We'll see it's done when") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The verdicts of a promise of theirs (design system 9): Delivered, Late, Not yet, New date,
 * Dropped and Ask me tomorrow (once per due day). Not yet asks a date and what is in the way, New
 * date a date. A task already done asks "Delivered by Jan, or did you do it?" instead (21.6).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VerdictSheet(
    task: Task,
    personName: String,
    canSnooze: Boolean,
    onDismiss: () -> Unit,
) {
    val actions = LocalPro.current.actions
    val today = LocalClockDay.current.date
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var dateText by rememberSaveable { mutableStateOf(today.plus(1, DateTimeUnit.DAY).toString()) }
    var blocker by rememberSaveable { mutableStateOf("") }
    val date = remember(dateText) { runCatching { LocalDate.parse(dateText) }.getOrNull() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { VicuDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (task.done) {
                Text(
                    "Delivered by $personName, or did you do it?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        actions?.verdict(task, "delivered")
                        onDismiss()
                    }) { Text("Delivered by $personName") }
                    OutlinedButton(onClick = {
                        actions?.verdict(task, "taken_over")
                        onDismiss()
                    }) { Text("I did it") }
                }
                return@Column
            }
            val dated = asking
            if (dated == null) {
                Text(
                    "From $personName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProScreens.VERDICT_ORDER.forEach { kind ->
                        OutlinedButton(onClick = {
                            if (kind == "not_yet" || kind == "renegotiated") {
                                asking = kind
                            } else {
                                actions?.verdict(task, kind)
                                onDismiss()
                            }
                        }) { Text(ProScreens.verdictLabel(kind)) }
                    }
                    if (canSnooze) {
                        OutlinedButton(onClick = {
                            actions?.snooze(task)
                            onDismiss()
                        }) { Text("Ask me tomorrow") }
                    }
                }
            } else {
                Text(
                    if (dated == "not_yet") "Ask again on" else "New date",
                    style = MaterialTheme.typography.titleSmall,
                )
                MonthCalendar(
                    selected = date,
                    onSelect = { picked -> if (picked >= today) dateText = picked.toString() },
                )
                if (dated == "not_yet") {
                    OutlinedTextField(
                        value = blocker,
                        onValueChange = { blocker = it },
                        label = { Text("What is in the way?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { asking = null }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = date != null,
                        onClick = {
                            actions?.verdict(task, dated, date, blocker.takeIf { dated == "not_yet" })
                            onDismiss()
                        },
                    ) { Text("Save") }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A line of muted italic text: a promise's words on a row (design system 9). */
@Composable
fun PromiseWords(words: String, modifier: Modifier = Modifier) {
    Text(
        text = "“$words”",
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
