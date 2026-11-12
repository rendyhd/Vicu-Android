package com.rendyhd.vicu.util

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.ProCapture
import com.rendyhd.vicu.data.repository.ProCaptureSource
import com.rendyhd.vicu.data.repository.ProLedgerRepository
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.location.LocationAccess
import com.rendyhd.vicu.location.LocationPermissionPlatform
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.location.PlacePhrase
import com.rendyhd.vicu.location.PlacePhrases
import com.rendyhd.vicu.ui.screens.taskdetail.withoutLineBreaks
import com.rendyhd.vicu.ui.screens.taskentry.TaskEntryUiState
import com.rendyhd.vicu.ui.screens.taskentry.resolveEntryFields
import com.rendyhd.vicu.ui.screens.taskentry.resolveParsedLabels
import com.rendyhd.vicu.util.parser.ParsedBlock
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.parser.extractBangToday
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.toLocalDateTime

/**
 * Makes a task from one sentence, the way quick add reads it ("Call mom tomorrow 3pm #family"),
 * without a screen: for a voice command (Siri, the Android voice add). The task goes to the
 * project the text names, otherwise the Inbox, and is stored on the device first and queued, so
 * it is never lost and the caller gets an answer at once; the sync sends it.
 *
 * "When I get home" or "when I leave work" for a saved place also sets the task's location
 * reminder ([PlacePhrases]).
 */
class TextTaskCreator(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    private val nlpPrefsStore: NlpPrefsStore,
    private val notificationPrefsStore: NotificationPrefsStore,
    private val dayClock: DayClock,
    private val time: TimeSource = SystemTimeSource,
    private val locations: LocationRepository? = null,
    private val locationPermission: LocationPermissionPlatform? = null,
    /** Pro's phrases while Pro is on (contract 5.5); none in tests. */
    private val proCapture: ProCaptureSource? = null,
    /** Where a captured promise and estimate are kept (contract 21.7); none in tests. */
    private val proLedger: ProLedgerRepository? = null,
) {
    sealed interface Result {
        /** [confirmation] says what was added, for the voice reply or a toast. */
        data class Created(val task: Task, val confirmation: String) : Result

        data class Failed(val message: String) : Result
    }

    /**
     * [defaultProjectId] and [defaultDueDate] are what the list the user is in gives a new task, as
     * its quick add does (holding + on a project screen, on Today); a project or date in the words
     * wins. Without them the task goes to the Inbox, undated.
     */
    suspend fun create(
        text: String,
        format: DateDisplayFormat,
        defaultProjectId: Long? = null,
        defaultDueDate: String? = null,
    ): Result {
        val input = text.withoutLineBreaks().trim()
        if (input.isBlank()) return Result.Failed("Say what the task is.")

        // A cold start (a voice command can launch the app) restores the session first.
        val auth = withTimeoutOrNull(AUTH_WAIT_MS) {
            authManager.authState.first { it != AuthState.Loading }
        }
        if (auth == null || auth == AuthState.Unauthenticated) return Result.Failed("Sign in to Vicu first.")
        val inboxId = authManager.getInboxProjectId()?.takeIf { it > 0 }
            ?: return Result.Failed("Choose an Inbox in Vicu first.")
        val placePhrase = locations?.let { PlacePhrases.find(input, it.allPlaces()) }
        val sentence = placePhrase?.title ?: input

        dayClock.refresh()
        val day = dayClock.day.value
        val config = nlpPrefsStore.config.first()
        val projects = projectRepository.getAll().first()
        val labels = labelRepository.getAll().first()
        val now = time.now().toLocalDateTime(day.zone)
        // A project that is gone or archived since the screen was drawn falls back to the Inbox.
        val defaultProject = defaultProjectId?.takeIf { id -> projects.any { it.id == id && !it.isArchived } } ?: inboxId
        val pro = proCapture?.capture?.first() ?: ProCapture.OFF
        val parsed = if (config.enabled) {
            TaskParser.parse(sentence, pro.config(config), reference = now, zone = day.zone, people = pro.parserPeople)
        } else {
            null
        }

        // The same rules as the quick add sheet with nothing picked by hand (TaskEntryFields.kt).
        val fields = resolveEntryFields(
            TaskEntryUiState(
                title = sentence,
                projectId = defaultProject,
                dueDate = defaultDueDate.orEmpty(),
                allProjects = projects,
                parseResult = parsed,
                parserConfig = config,
            ),
            day.zone,
        )
        var title = parsed?.title ?: sentence
        var dueDate = fields.dueDate
        if (config.bangToday && TokenType.DATE !in config.suppressTypes && fields.dueSource == null) {
            val bang = extractBangToday(title, now)
            if (bang.dueDate != null) {
                title = bang.title
                dueDate = DueDates.bang(day.date, day.zone).toString()
            }
        }
        title = title.trim()
        if (title.isBlank()) return Result.Failed("Say what the task is.")

        val labelIds = if (parsed != null) resolveParsedLabels(parsed.labels, labels, labelRepository) else emptySet()
        val personLabel = pro.personLabelOf(parsed)
        val prefs = notificationPrefsStore.getPrefs().first()
        val plainTask = Task(
            id = 0,
            title = title,
            dueDate = dueDate,
            startDate = fields.blockStart,
            endDate = fields.blockEnd,
            priority = fields.priority,
            projectId = fields.projectId,
            repeatAfter = fields.recurrence.repeatAfter,
            repeatMode = fields.recurrence.repeatMode,
            reminders = listOfNotNull(
                DefaultReminder.build(
                    dueDate = dueDate,
                    offsetSeconds = prefs.defaultReminderOffset,
                    relativeTo = prefs.defaultReminderRelativeTo,
                    blockStart = fields.blockStart,
                    blockEnd = fields.blockEnd,
                ),
            ),
        )
        // Pro on: `starts monday` and `!flag` set their fields, `Jan:` adds that person's label.
        val task = pro.applyToNew(plainTask, parsed, day.zone)
        return when (val result = taskRepository.createQueued(task)) {
            is NetworkResult.Success -> {
                val created = result.data
                for (labelId in labelIds + listOfNotNull(personLabel)) labelRepository.addToTask(created.id, labelId)
                // Pro on: `Jan:` makes the task that person's promise, `~45m` gives its estimate.
                proLedger?.recordCapture(created.id, pro.promiseOf(parsed), pro.estimateOf(parsed))
                placePhrase?.let { phrase ->
                    locations.setReminder(locations.reminderAt(created.id, phrase.place, phrase.trigger))
                }
                val projectName = projects.find { it.id == created.projectId }
                    ?.takeIf { it.id != inboxId }?.title
                val block = parsed?.block.takeIf { fields.blockStart.isNotEmpty() }
                var reply = confirmation(created.title, dueDate, projectName, day, format, placePhrase, block)
                if (placePhrase != null && placePhrase.place.position == null) {
                    reply += ". ${notLocated(placePhrase.place.name)}"
                } else if (placePhrase != null && !hasAlwaysLocation()) {
                    reply += ". $NEEDS_ALWAYS"
                }
                Result.Created(created, reply)
            }
            is NetworkResult.Error -> Result.Failed(result.message)
            NetworkResult.Loading -> Result.Failed("Could not add the task.")
        }
    }

    private fun hasAlwaysLocation(): Boolean {
        val permission = locationPermission ?: return true
        permission.refresh()
        return permission.access.value == LocationAccess.ALWAYS
    }

    companion object {
        private const val AUTH_WAIT_MS = 5_000L
        internal const val NEEDS_ALWAYS = "Set Vicu's location access to Always so it can remind you there"

        /** The place was saved on another device: this phone reminds there once it knows where it is. */
        internal fun notLocated(name: String) = "Set where $name is in Vicu's settings so this phone can remind you there"

        /** `Added "Call mom", tomorrow 15:00`, `Added "Report" to Work` or `Added "Call mom", when you arrive at Home`. */
        internal fun confirmation(
            title: String,
            dueDate: String,
            projectName: String?,
            day: ClockDay,
            format: DateDisplayFormat,
            place: PlacePhrase? = null,
            block: ParsedBlock? = null,
        ): String = buildString {
            append("Added “").append(title).append("”")
            if (projectName != null) append(" to ").append(projectName)
            // The row's phrase reads well aloud ("tomorrow, 15:00", "Friday"), in sentence case. A
            // time block says its times, because its due date has none.
            val due = when {
                block != null -> DateDisplay.formatBlockRow(block.start, block.end, day.date, format)
                dueDate.isBlank() -> ""
                else -> DateDisplay.formatDue(DateContext.ROW, dueDate, day.date, day.zone, format)
            }
            if (due.isNotBlank()) {
                append(", ")
                append(if (due.startsWith("Today") || due.startsWith("Tomorrow")) due.replaceFirstChar { it.lowercase() } else due)
            }
            if (place != null) {
                append(", when you ")
                append(if (place.trigger == LocationTrigger.ARRIVE) "arrive at " else "leave ")
                append(place.place.name)
            }
        }
    }
}
