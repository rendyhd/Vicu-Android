package com.rendyhd.vicu.ai.ask

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiText
import com.rendyhd.vicu.ai.dictation.ContextProject
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListFilter
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.domain.model.TaskSignal
import com.rendyhd.vicu.util.Collaboration
import com.rendyhd.vicu.util.CollabUser
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.CustomListFilterBuilder
import com.rendyhd.vicu.util.DueDates
import com.rendyhd.vicu.util.HiddenTasks
import com.rendyhd.vicu.util.ReminderAlarms
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.TimeBlocks
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.isRecurring
import com.rendyhd.vicu.util.sortProjectTasks
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Everything the Ask tools read, taken at one moment (`docs/ai-assistant-v1.md`, section 5).
 * Hidden tasks (semantics 10.1) are already left out of [tasks].
 */
class AskSnapshot(
    tasks: List<Task>,
    val projects: List<Project>,
    val labels: List<Label>,
    val customLists: List<CustomList>,
    val collab: CollabState,
    val signals: Map<Long, TaskSignal>,
    val order: TaskOrderSettings,
    val inboxId: Long?,
    val today: LocalDate,
    val zone: TimeZone,
    val reviewCadenceDays: Int,
    val reviewEnabled: Boolean,
    val reviewExcludeInbox: Boolean,
    /** The day the account's weeks start on (contract section 2.1): the `this_week` window and `week_starts`. */
    val weekStart: WeekStart = WeekStart.DEFAULT,
) {
    val tasks: List<Task> = tasks.filterNot { HiddenTasks.isHidden(it.description) }
    val byId: Map<Long, Task> = this.tasks.associateBy { it.id }
    val projectById: Map<Long, Project> = projects.associateBy { it.id }
    private val parentOf = projects.associate { it.id to it.parentProjectId }

    /** A project's titles from the top, joined with ` / ` (section 4.1). */
    fun path(projectId: Long): String {
        val titles = mutableListOf<String>()
        val seen = HashSet<Long>()
        var current: Long? = projectId
        while (current != null && current != 0L && seen.add(current)) {
            val p = projectById[current] ?: break
            titles += p.title
            current = p.parentProjectId
        }
        return titles.reversed().joinToString(" / ")
    }

    fun isArchived(projectId: Long): Boolean {
        val seen = HashSet<Long>()
        var current: Long? = projectId
        while (current != null && current != 0L && seen.add(current)) {
            val p = projectById[current] ?: return false
            if (p.isArchived) return true
            current = p.parentProjectId
        }
        return false
    }

    fun canWrite(projectId: Long): Boolean {
        val project = projectById[projectId] ?: return false
        if (isArchived(projectId)) return false
        val permission = collab.projects[projectId]?.permission ?: project.maxPermission
        return permission != 0
    }

    fun isShared(projectId: Long): Boolean = collab.access(projectId).shared

    /** Projects the user can write to, not archived, by lowercased path (section 4.1). */
    fun writableProjects(): List<ContextProject> = projects
        .filter { canWrite(it.id) }
        .map { ContextProject(it.id, it.title, path(it.id)) }
        .sortedWith(compareBy({ it.path.lowercase() }, { it.path }, { it.id }))

    private val activeProjectIds: Set<Long> = projects.filter { !isArchived(it.id) }.mapTo(HashSet()) { it.id }

    // --- Task summaries (section 5.2) --------------------------------------------------------

    fun summary(task: Task): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("done", task.done)
        put("due", dueText(task.dueDate)?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
        put("project_id", task.projectId)
        put("project", path(task.projectId))
        putJsonArray("labels") { task.labels.forEach { add(JsonPrimitive(it.title)) } }
        put("priority", task.priority)
        put("repeats", isRecurring(task.repeatAfter, task.repeatMode))
        put("has_notes", AiText.hasNotes(task.description))
        put("subtask_count", task.relatedTasks["subtask"]?.size ?: 0)
        val shared = isShared(task.projectId)
        put("shared", shared)
        if (shared) {
            val me = collab.me
            val creator = task.createdBy
            if (creator.id != 0L && creator.id != me) put("by", name(creator.id, creator.username, creator.name))
            putJsonArray("assignees") {
                Collaboration.orderPeople(task.assignees, me) { it.id }.forEach {
                    add(JsonPrimitive(if (it.id == me) "You" else name(it.id, it.username, it.name)))
                }
            }
            val signal = signals[task.id]
            put("comment_count", signal?.commentCount ?: 0)
            put("unread", signal?.isUnread ?: false)
        }
    }

    private fun name(id: Long, username: String, name: String?) = Collaboration.displayName(CollabUser(id, username, name))

    /** `YYYY-MM-DD` for a date-only due date, `YYYY-MM-DDTHH:mm` with a time, or null. */
    fun dueText(dueDate: String?): String? {
        if (dueDate.isNullOrBlank()) return null
        val instant = runCatching { Instant.parse(dueDate) }.getOrNull() ?: return null
        if (instant.toString().startsWith("0001-")) return null
        val local = instant.toLocalDateTime(zone)
        return if (DueDates.isDateOnly(instant, zone)) {
            local.date.toString()
        } else {
            "${local.date}T${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        }
    }

    // --- get_overview ------------------------------------------------------------------------

    fun overview(): JsonObject {
        val open = tasks.filter { !it.done && it.projectId in activeProjectIds }
        val overdue = open.count { DueDates.isOverdue(it.dueDate, today, zone) }
        // The Today list's own rule (contract section 2): a block starting today counts too.
        val dueToday = TimeBlocks.splitToday(open, today, zone).today.size
        val upcoming = open.count { DueDates.isUpcoming(it.dueDate, today, zone) }
        val inbox = inboxId?.let { id -> open.count { it.projectId == id } } ?: 0
        val anytime = open.count { it.projectId != inboxId }
        return buildJsonObject {
            putJsonObject("counts") {
                put("inbox", inbox)
                put("today", dueToday)
                put("overdue", overdue)
                put("upcoming", upcoming)
                put("anytime", anytime)
            }
            putJsonArray("review_due") {
                if (reviewEnabled) {
                    projects.asSequence()
                        .filter { !isArchived(it.id) }
                        .filterNot { reviewExcludeInbox && it.id == inboxId }
                        .map { it to ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), reviewCadenceDays, today) }
                        .filter { (_, status) -> status.metadata.state != ReviewState.EXCLUDED && status.isOverdue }
                        .sortedBy { (p, _) -> path(p.id).lowercase() }
                        .forEach { (p, _) -> add(buildJsonObject { put("id", p.id); put("path", path(p.id)) }) }
                }
            }
            putJsonArray("projects") {
                projects.filter { !isArchived(it.id) }
                    .sortedWith(compareBy({ path(it.id).lowercase() }, { path(it.id) }, { it.id }))
                    .forEach { p ->
                        add(
                            buildJsonObject {
                                put("id", p.id)
                                put("path", path(p.id))
                                put("inbox", p.id == inboxId)
                                put("writable", canWrite(p.id))
                                put("shared", isShared(p.id))
                            },
                        )
                    }
            }
            putJsonArray("labels") {
                labels.sortedWith(compareBy({ it.title.lowercase() }, { it.title }, { it.id }))
                    .forEach { add(buildJsonObject { put("id", it.id); put("title", it.title) }) }
            }
            putJsonArray("custom_lists") {
                customLists.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) }
            }
        }
    }

    // --- find_tasks --------------------------------------------------------------------------

    /** [args] matched the tool's schema. Returns `{"tasks", "more"}` or an error object. */
    fun findTasks(args: JsonObject): JsonObject {
        val limits = AiContract.limits
        val projectIds = (args["project_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        val labelIds = (args["label_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        val due = (args["due"] as? JsonPrimitive)?.contentOrNull
        val includeOverdue = (args["include_overdue"] as? JsonPrimitive)?.booleanOrNull
        val dueFrom = (args["due_from"] as? JsonPrimitive)?.contentOrNull
        val dueTo = (args["due_to"] as? JsonPrimitive)?.contentOrNull
        val priorityMin = (args["priority_min"] as? JsonPrimitive)?.intOrNull
        val status = (args["status"] as? JsonPrimitive)?.contentOrNull ?: "open"
        val assigned = (args["assigned"] as? JsonPrimitive)?.contentOrNull
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: limits.findTasksDefaultLimit).coerceIn(1, limits.findTasksMaxLimit)

        val from = dueFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return invalid("due_from is not a date") }
        val to = dueTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return invalid("due_to is not a date") }

        // The custom-list evaluator decides the due window, priority, labels, projects and assignment.
        val filter = CustomListFilter(
            projectIds = projectIds.orEmpty(),
            labelIds = labelIds.orEmpty(),
            dueDateFilter = due ?: "all",
            includeOverdue = includeOverdue,
            includeDone = status != "open",
            priorityFilter = priorityMin?.let { min -> (min.coerceIn(0, 5)..5).toList() }.orEmpty(),
            assigned = assigned,
        )
        val context = CustomListContext.of(collab, signals, weekStart)
        val allowed = if (projectIds.isNullOrEmpty()) activeProjectIds else activeProjectIds + projectIds
        val words = text?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        val matches = CustomListFilterBuilder.applyClientSideFilters(tasks, filter, today, zone, context)
            .asSequence()
            .filter { it.projectId in allowed }
            .filter { status != "done" || it.done }
            .filter { task ->
                if (from == null && to == null) return@filter true
                val day = DueDates.localDateOf(task.dueDate, zone) ?: return@filter false
                (from == null || day >= from) && (to == null || day <= to)
            }
            .filter { task ->
                if (words.isEmpty()) return@filter true
                val haystack = (task.title + "\n" + AiText.htmlToPlain(task.description)).lowercase()
                words.all { it in haystack }
            }
            .toList()
        val sorted = sortProjectTasks(matches)
        return buildJsonObject {
            put("tasks", JsonArray(sorted.take(limit).map { summary(it) }))
            put("more", (sorted.size - limit).coerceAtLeast(0))
        }
    }

    // --- list_view ---------------------------------------------------------------------------

    fun listView(args: JsonObject, logbook: List<Task>): JsonObject {
        val view = (args["view"] as? JsonPrimitive)?.contentOrNull ?: return invalid("view is missing")
        val page = ((args["page"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1)
        val open = tasks.filter { !it.done }
        val rows: List<Task> = when (view) {
            "inbox" -> {
                val id = inboxId ?: return error("not_found", "No Inbox is chosen.")
                sortProjectTasks(open.filter { it.projectId == id }, order.orderFor(id, projects))
            }
            "today" -> {
                val split = TimeBlocks.splitToday(open.filter { it.projectId in activeProjectIds }, today, zone)
                split.overdue.sortedBy { it.dueDate } + sortProjectTasks(split.today)
            }
            "upcoming" -> open.filter { it.projectId in activeProjectIds && DueDates.isUpcoming(it.dueDate, today, zone) }
                .sortedWith(compareBy({ Instant.parse(it.dueDate) }, { it.position }, { it.id }))
            "anytime" -> projects.filter { !isArchived(it.id) && it.id != inboxId }
                .sortedWith(compareBy({ path(it.id).lowercase() }, { it.id }))
                .flatMap { p -> sortProjectTasks(open.filter { it.projectId == p.id }, order.orderFor(p.id, projects)) }
            "logbook" -> logbook.filterNot { HiddenTasks.isHidden(it.description) }
                .sortedByDescending { it.doneAt }
            "project" -> {
                val id = (args["project_id"] as? JsonPrimitive)?.longOrNull ?: return invalid("project_id is missing")
                if (id !in projectById) return error("not_found", "No project $id.")
                sortProjectTasks(open.filter { it.projectId == id }, order.orderFor(id, projects))
            }
            "custom_list" -> {
                val id = (args["custom_list_id"] as? JsonPrimitive)?.contentOrNull ?: return invalid("custom_list_id is missing")
                val list = customLists.firstOrNull { it.id == id } ?: return error("not_found", "No custom list $id.")
                CustomListFilterBuilder.visibleTasks(tasks, list.filter, today, zone, activeProjectIds, CustomListContext.of(collab, signals, weekStart))
            }
            else -> return invalid("unknown view $view")
        }
        val size = AiContract.limits.listViewPageSize
        val slice = rows.drop((page - 1) * size).take(size)
        return buildJsonObject {
            put("tasks", JsonArray(slice.map { summary(it) }))
            put("page", page)
            put("has_more", rows.size > page * size)
        }
    }

    // --- get_task ----------------------------------------------------------------------------

    fun getTask(args: JsonObject): JsonObject {
        val id = (args["task_id"] as? JsonPrimitive)?.longOrNull ?: return invalid("task_id is missing")
        val task = byId[id] ?: return error("not_found", "No task $id.")
        val notes = AiText.htmlToPlain(task.description)
        val max = AiContract.limits.getTaskNotesMaxChars
        return buildJsonObject {
            put("task", summary(task))
            put("notes", if (notes.length > max) notes.take(max) + "…" else notes)
            put(
                "subtasks",
                buildJsonArray {
                    task.relatedTasks["subtask"].orEmpty().forEach { child -> (byId[child.id] ?: child).takeUnless { HiddenTasks.isHidden(it.description) }?.let { add(summary(it)) } }
                },
            )
            putJsonArray("reminders") {
                task.reminders.forEach { reminder ->
                    // A relative reminder counts from the task's dates as they are now.
                    val at = ReminderAlarms.resolveTriggerMillis(reminder, task)?.let { Instant.fromEpochMilliseconds(it).toString() }
                    val text = dueText(at) ?: return@forEach
                    add(JsonPrimitive(text))
                }
            }
            put("repeat", repeatText(task)?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
        }
    }

    private fun repeatText(task: Task): String? {
        if (!isRecurring(task.repeatAfter, task.repeatMode)) return null
        if (task.repeatMode == com.rendyhd.vicu.util.RECURRENCE_MODE_MONTHLY) return "every month"
        val seconds = task.repeatAfter
        val from = if (task.repeatMode == com.rendyhd.vicu.util.RECURRENCE_MODE_FROM_COMPLETION) " after completion" else ""
        val unit = when {
            seconds % com.rendyhd.vicu.util.RECURRENCE_YEAR_SECONDS == 0L -> seconds / com.rendyhd.vicu.util.RECURRENCE_YEAR_SECONDS to "year"
            seconds % com.rendyhd.vicu.util.RECURRENCE_WEEK_SECONDS == 0L -> seconds / com.rendyhd.vicu.util.RECURRENCE_WEEK_SECONDS to "week"
            seconds % com.rendyhd.vicu.util.RECURRENCE_DAY_SECONDS == 0L -> seconds / com.rendyhd.vicu.util.RECURRENCE_DAY_SECONDS to "day"
            seconds % 3_600L == 0L -> seconds / 3_600L to "hour"
            else -> seconds to "second"
        }
        val (n, name) = unit
        return (if (n == 1L) "every $name" else "every $n ${name}s") + from
    }

    private fun invalid(detail: String) = error("invalid_arguments", detail)

    private fun error(code: String, detail: String) = buildJsonObject {
        put("error", code)
        put("detail", detail)
    }

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
}
