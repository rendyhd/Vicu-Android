package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.ProListView
import com.rendyhd.vicu.data.local.VerdictSnooze
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.AppDispatchers
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TimeSource
import com.rendyhd.vicu.util.pro.AreaProject
import com.rendyhd.vicu.util.pro.Availability
import com.rendyhd.vicu.util.pro.AvailabilityContext
import com.rendyhd.vicu.util.pro.AvailabilityView
import com.rendyhd.vicu.util.pro.LedgerRules
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person
import com.rendyhd.vicu.util.pro.ProLayer
import com.rendyhd.vicu.util.pro.ProLedger
import com.rendyhd.vicu.util.pro.ProPrefs
import com.rendyhd.vicu.util.pro.ProScreens
import com.rendyhd.vicu.util.pro.ProSettings
import com.rendyhd.vicu.util.pro.ProjectMode
import com.rendyhd.vicu.util.pro.Promises
import com.rendyhd.vicu.util.pro.TaskAvailability
import com.rendyhd.vicu.util.pro.ViewFiltered
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the Pro screens and rows read (docs/cross-app-semantics-v1.md section 21), built once for
 * the app from every open task, the projects, the labels, the synced preferences and the ledger,
 * the desktop's `AvailabilityProvider` and `usePromiseWorld` in one. [OFF] while Pro (or both of
 * its phone layers) is off: every question then answers as a device without Pro would.
 */
class ProWorld internal constructor(
    val availabilityOn: Boolean,
    val promisesOn: Boolean,
    val now: Instant,
    val today: LocalDate,
    val zone: TimeZone,
    /** Every open task the device holds, hidden ones left out. */
    val open: List<Task>,
    /** Done tasks that have a promise in the ledger (completed elsewhere, delivered for a recap). */
    val donePromiseTasks: List<Task>,
    val ledger: ProLedger,
    /** Every synced preference value (Pro's keys, the categories, the task orders). */
    val prefs: Map<String, JsonElement?>,
    val labels: List<Label>,
    val projects: List<Project>,
    private val listViews: Map<String, ProListView>,
    private val snoozes: Map<String, VerdictSnooze>,
    val onboardingSeen: Boolean,
    private val context: AvailabilityContext?,
) {
    val on: Boolean get() = availabilityOn || promisesOn

    val openById: Map<Long, Task> by lazy { open.associateBy { it.id } }

    private val labelsById: Map<Long, Label> by lazy { labels.associateBy { it.id } }

    private val areaProjects: Map<Long, AreaProject> by lazy {
        projects.associate { it.id to AreaProject(it.id, it.parentProjectId.takeIf { parent -> parent > 0 }) }
    }

    private val area: List<String> by lazy { ProSettings.readArea(prefs[ProSettings.AREA_KEY]) }

    /** The labels marked as a person, by title. */
    val people: List<Label> by lazy {
        val ids = ProPrefs.personLabelIds(prefs)
        labels.filter { it.id in ids }.sortedBy { it.title.lowercase() }
    }

    /** The people with their remembered aliases, for name matching. */
    val peopleWithAliases: List<Person> by lazy {
        People.peopleWithAliases(people.map { it.id to it.title }, { true }, ledger)
    }

    /** Tasks in a blocked-by loop (the loop is ignored and the After sheet says so). */
    val cycles: List<Long> get() = context?.cycles.orEmpty()

    /** Availability of an open task; null while the layer is off or for a done task. */
    fun availabilityOf(task: Task): TaskAvailability? =
        if (availabilityOn && !task.done) context?.of(task) else null

    /** The row's reason in words, or null when the task is available (or the layer is off). */
    fun reasonText(task: Task, fmt: DateDisplayFormat): String? {
        val availability = availabilityOf(task)?.takeIf { !it.available } ?: return null
        return ProScreens.reasonText(task, availability, { openById[it]?.title }, today, zone, fmt)
    }

    /**
     * The promise of a task while the promises layer is on and its project is in the area
     * (contract 21.1); null otherwise.
     */
    fun promiseFor(task: Task): JsonObject? {
        if (!promisesOn) return null
        val promise = Promises.promiseOf(ledger, task.id) ?: return null
        val inArea = ProSettings.inArea(task.projectId, area, areaProjects) { id ->
            (prefs["category.project.$id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        return if (inArea) promise else null
    }

    fun label(id: Long?): Label? = id?.let { labelsById[it] }

    /** A person's name; "Someone" when the label is gone. */
    fun nameOf(personId: Long?): String = label(personId)?.title ?: "Someone"

    fun isPerson(labelId: Long): Boolean = ProPrefs.isPersonLabel(prefs, labelId)
    fun labelOnHold(labelId: Long): Boolean = ProPrefs.labelOnHold(prefs, labelId)
    fun projectMode(projectId: Long): ProjectMode = ProPrefs.projectMode(prefs, projectId)
    fun projectOnHold(projectId: Long): Boolean = ProPrefs.projectOnHold(prefs, projectId)

    /** The estimate of a task in minutes, or null. */
    fun estimateOf(taskId: Long): Int? = ledger.estimateOf(taskId)

    /** "Ask me tomorrow" of a task's promise, as the promise rules read it. */
    fun snoozeOf(taskId: Long): com.rendyhd.vicu.util.pro.VerdictSnooze? {
        val stored = snoozes[taskId.toString()] ?: return null
        val due = runCatching { LocalDate.parse(stored.due) }.getOrNull() ?: return null
        val until = runCatching { LocalDate.parse(stored.until) }.getOrNull() ?: return null
        return com.rendyhd.vicu.util.pro.VerdictSnooze(due, until)
    }

    /** The promise of [task] is due a verdict today (decision 10). */
    fun needsVerdict(task: Task): Boolean =
        Promises.needsVerdict(task, promiseFor(task), today, snoozeOf(task.id), zone)

    fun canSnooze(task: Task): Boolean = Promises.canSnooze(task, promiseFor(task), snoozeOf(task.id), zone)

    /** Tasks done without a final verdict on a promise of theirs: asked about once (21.6). */
    val completedElsewhere: List<Task> by lazy {
        donePromiseTasks.filter { Promises.completedElsewhere(it, promiseFor(it)) }
    }

    /**
     * What a row shows of Pro (design system 9): its reason, the Next chip, the flag and the
     * promise. Null while Pro is off, so a row is drawn as without Pro.
     */
    fun rowOf(task: Task, fmt: DateDisplayFormat): ProRow? {
        if (!on) return null
        val availability = availabilityOf(task)
        val promise = promiseFor(task)
        val open = promise != null && LedgerRules.finalVerdictOf(promise) == null
        val dir = (promise?.get("dir") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val personId = (promise?.get("person") as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        val person = label(personId)
        return ProRow(
            reason = if (availability != null && !availability.available) reasonText(task, fmt) else null,
            next = availability?.next == true,
            flagged = availabilityOn && task.isFavorite,
            theirs = open && dir == "theirs",
            mine = open && dir == "mine",
            personId = personId?.takeIf { promise != null },
            personName = person?.title ?: "Someone",
            personHex = person?.hexColor.orEmpty(),
            words = (promise?.get("words") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() },
            sent = Promises.isSent(promise),
            hasPromise = promise != null,
        )
    }

    /** What list [listKey] shows (Available by default), while the availability layer is on. */
    fun listView(listKey: String): AvailabilityView? {
        if (!availabilityOn) return null
        return when (listViews[listKey] ?: ProListView.AVAILABLE) {
            ProListView.AVAILABLE -> AvailabilityView.AVAILABLE
            ProListView.REMAINING -> AvailabilityView.REMAINING
            ProListView.ALL -> AvailabilityView.ALL
        }
    }

    companion object {
        val OFF = ProWorld(
            availabilityOn = false,
            promisesOn = false,
            now = Instant.fromEpochMilliseconds(0),
            today = LocalDate(1970, 1, 1),
            zone = TimeZone.UTC,
            open = emptyList(),
            donePromiseTasks = emptyList(),
            ledger = LedgerRules.empty(),
            prefs = emptyMap(),
            labels = emptyList(),
            projects = emptyList(),
            listViews = emptyMap(),
            snoozes = emptyMap(),
            onboardingSeen = false,
            context = null,
        )
    }
}

/** Pro's part of a task row (design system 9). */
data class ProRow(
    /** The first reason the task is not available, in words. */
    val reason: String?,
    /** The first task of a sequential project. */
    val next: Boolean,
    val flagged: Boolean,
    /** An open promise of theirs: the person's mark instead of the checkbox. */
    val theirs: Boolean,
    /** An open promise of yours. */
    val mine: Boolean,
    /** The promise's person (its label chip is not repeated on the row). */
    val personId: Long?,
    val personName: String,
    val personHex: String,
    /** Their words, in italics. */
    val words: String?,
    /** Copied in a recap. */
    val sent: Boolean,
    val hasPromise: Boolean,
) {
    /** "From Jan" or "For Jan" while the promise is open. */
    val promiseText: String?
        get() = when {
            theirs -> "From $personName"
            mine -> "For $personName"
            else -> null
        }
}

/** A list's Available / Remaining / All choice, as a list screen applies it. [view] is null while the layer is off. */
class ProListState(
    val view: AvailabilityView?,
    private val availabilityOf: (Task) -> TaskAvailability?,
) {
    /** [tasks] as the choice shows them; unchanged while the layer is off. */
    fun filter(tasks: List<Task>): ViewFiltered =
        view?.let { ProScreens.filter(tasks, it, availabilityOf) } ?: ViewFiltered(tasks, 0)

    companion object {
        val OFF = ProListState(null) { null }
    }
}

/**
 * Builds the [ProWorld] while Pro is on and shares it with every screen. While Pro is off only the
 * device settings are read (the switch itself): no task, ledger or preference flow is collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProWorldSource(
    private val behaviorPrefs: BehaviorPrefsStore,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val synced: SyncedEntries,
    private val proLedger: ProLedgerRepository,
    private val dayClock: DayClock,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    private data class Switches(val availability: Boolean, val promises: Boolean)

    private data class Device(
        val listViews: Map<String, ProListView>,
        val snoozes: Map<String, VerdictSnooze>,
        val onboardingSeen: Boolean,
    )

    private class Stored(val open: List<Task>, val projects: List<Project>, val labels: List<Label>)
    private class Synced(val prefs: Map<String, JsonElement?>, val ledger: ProLedger, val device: Device)

    /** The world, shared; started lazily so building the app graph starts nothing. */
    val world: StateFlow<ProWorld> by lazy {
        behaviorPrefs.getPrefs()
            .map { Switches(it.isProEnabled(ProLayer.AVAILABILITY), it.isProEnabled(ProLayer.PROMISES)) }
            .distinctUntilChanged()
            .flatMapLatest { switches ->
                if (!switches.availability && !switches.promises) flowOf(ProWorld.OFF) else live(switches)
            }
            .flowOn(dispatchers.default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ProWorld.OFF)
    }

    /** A list screen's view choice and its availability; [ProListState.OFF] while the layer is off. */
    fun listState(listKey: String): Flow<ProListState> = world
        .map { world ->
            val view = world.listView(listKey) ?: return@map ProListState.OFF
            ProListState(view, world::availabilityOf)
        }

    private fun live(switches: Switches): Flow<ProWorld> {
        // A start date passes while the app is open: look again every minute.
        val minute = flow {
            while (true) {
                emit(time.now())
                delay(60_000)
            }
        }
        val open = taskRepository.getAllOpenTasksFlat()
        val device = behaviorPrefs.getPrefs()
            .map { Device(it.proListViews, it.proVerdictSnooze, it.proOnboardingSeen) }
            .distinctUntilChanged()
        val stored = combine(open, projectRepository.getAll(), labelRepository.getAll()) { tasks, projects, labels ->
            Stored(tasks, projects.filter { !it.isArchived }, labels)
        }
        val syncedValues = combine(synced.entries, proLedger.ledger, device) { prefs, ledger, dev -> Synced(prefs, ledger, dev) }
        // The done tasks the ledger names: read again whenever the open tasks or the ledger change.
        val donePromises: Flow<List<Task>> = if (switches.promises) {
            combine(open, proLedger.ledger) { tasks, ledger ->
                val openIds = tasks.mapTo(HashSet()) { it.id }
                ledger.records("promises").keys.mapNotNullTo(HashSet()) { key -> key.toLongOrNull()?.takeIf { it != 0L && it !in openIds } }
            }.distinctUntilChanged().mapLatest { ids ->
                if (ids.isEmpty()) emptyList() else taskRepository.getByIds(ids).filter { it.done }
            }
        } else {
            flowOf(emptyList())
        }
        return combine(stored, syncedValues, dayClock.day, minute, donePromises) { s, v, day, now, done ->
            val context = if (switches.availability) {
                Availability.context(
                    now = now,
                    zone = day.zone,
                    tasks = s.open,
                    projects = s.projects.map { AreaProject(it.id, it.parentProjectId.takeIf { parent -> parent > 0 }) },
                    prefs = v.prefs,
                    ledger = v.ledger,
                )
            } else {
                null
            }
            ProWorld(
                availabilityOn = switches.availability,
                promisesOn = switches.promises,
                now = now,
                today = day.date,
                zone = day.zone,
                open = s.open,
                donePromiseTasks = done,
                ledger = v.ledger,
                prefs = v.prefs,
                labels = s.labels,
                projects = s.projects,
                listViews = v.device.listViews,
                snoozes = v.device.snoozes,
                onboardingSeen = v.device.onboardingSeen,
                context = context,
            )
        }
    }
}
