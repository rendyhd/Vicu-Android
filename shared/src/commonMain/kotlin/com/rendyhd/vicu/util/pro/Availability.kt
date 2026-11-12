package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskOrderSettings
import com.rendyhd.vicu.util.HiddenTasks
import com.rendyhd.vicu.util.sortProjectTasks
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement

/** The reasons in the order rows show them; a row shows the first. */
enum class AvailabilityReason(val key: String) {
    STARTS("starts"),
    ON_HOLD("on_hold"),
    WAITING("waiting"),
    AFTER("after"),
    SEQUENCE("sequence"),
    THEIRS("theirs"),
}

enum class AvailabilityView(val key: String) {
    AVAILABLE("available"),
    REMAINING("remaining"),
    ALL("all"),
    ;

    companion object {
        fun read(value: JsonElement?): AvailabilityView? = value.asText()?.let { read(it) }
        fun read(value: String?): AvailabilityView? = entries.firstOrNull { it.key == value }
    }
}

data class TaskAvailability(
    val available: Boolean,
    val reasons: List<AvailabilityReason>,
    /** The first task of a sequential project (the "Next" pill). */
    val next: Boolean,
    /** Open tasks it waits on ("after"), for the row text. */
    val blockers: List<Long>,
)

class AvailabilityContext internal constructor(
    /** Tasks in a blocked-by loop; their loop is ignored. Sorted. */
    val cycles: List<Long>,
    private val answer: (Task) -> TaskAvailability,
) {
    fun of(task: Task): TaskAvailability = answer(task)
}

/**
 * Availability (docs/cross-app-semantics-v1.md section 21.2), the same as the desktop's
 * `src/shared/pro/availability.ts`: whether an open task can be done now, and if not, why. Pure and
 * O(n): the context is built once from every open task and answers each task from precomputed maps.
 */
object Availability {
    private val OPEN = TaskAvailability(available = true, reasons = emptyList(), next = false, blockers = emptyList())

    private fun parentOf(task: Task): Long? =
        task.relatedTasks["parenttask"]?.firstOrNull()?.id?.takeIf { it > 0 }

    private fun blockedBy(task: Task): List<Task> = task.relatedTasks["blocked"].orEmpty()

    /** The edges inside a strongly connected component of the blocked-by graph (and self loops), as `from>to`. */
    private fun loops(edges: Map<Long, List<Long>>): Set<String> {
        // Iterative Tarjan; an edge inside one component is a loop edge.
        var index = 0
        val indices = HashMap<Long, Int>()
        val lowlinks = HashMap<Long, Int>()
        val onStack = HashSet<Long>()
        val stack = ArrayList<Long>()
        val component = HashMap<Long, Int>()
        var componentId = 0

        class Frame(val node: Long, var next: Int)

        for (root in edges.keys) {
            if (root in indices) continue
            val work = ArrayList<Frame>()
            work.add(Frame(root, 0))
            indices[root] = index
            lowlinks[root] = index++
            stack.add(root)
            onStack.add(root)
            while (work.isNotEmpty()) {
                val frame = work.last()
                val targets = edges[frame.node].orEmpty()
                if (frame.next < targets.size) {
                    val target = targets[frame.next++]
                    if (target !in edges) continue
                    if (target !in indices) {
                        indices[target] = index
                        lowlinks[target] = index++
                        stack.add(target)
                        onStack.add(target)
                        work.add(Frame(target, 0))
                    } else if (target in onStack) {
                        lowlinks[frame.node] = minOf(lowlinks.getValue(frame.node), indices.getValue(target))
                    }
                    continue
                }
                work.removeAt(work.lastIndex)
                if (work.isNotEmpty()) {
                    val parent = work.last().node
                    lowlinks[parent] = minOf(lowlinks.getValue(parent), lowlinks.getValue(frame.node))
                }
                if (lowlinks[frame.node] == indices[frame.node]) {
                    var member: Long
                    do {
                        member = stack.removeAt(stack.lastIndex)
                        onStack.remove(member)
                        component[member] = componentId
                    } while (member != frame.node)
                    componentId++
                }
            }
        }
        val loopEdges = HashSet<String>()
        for ((from, targets) in edges) {
            for (to in targets) {
                if (to in edges && component[from] == component[to]) loopEdges.add("$from>$to")
            }
        }
        return loopEdges
    }

    /**
     * Builds the context once; `of(task)` then answers in constant time per task (plus its
     * blockers). [tasks] is every open task the device knows (done ones may be given too; they never
     * block); [prefs] the synced preference values by key.
     */
    fun context(
        now: Instant,
        zone: TimeZone,
        tasks: List<Task>,
        projects: List<AreaProject>,
        prefs: Map<String, JsonElement?>,
        ledger: ProLedger? = null,
    ): AvailabilityContext {
        val projectsById = projects.associateBy { it.id }
        fun parentProject(id: Long): Long? = projectsById[id]?.parentProjectId
        val parentMap = projects.associate { it.id to (it.parentProjectId ?: 0L) }
        val orders = TaskOrderSettings.fromEntries(prefs)

        val open = LinkedHashMap<Long, Task>()
        val doneIds = HashSet<Long>()
        for (task in tasks) {
            if (task.done) doneIds.add(task.id) else if (!HiddenTasks.isHidden(task.description)) open[task.id] = task
        }

        // On hold: the project or an ancestor, by status or a parked initiative.
        val holdCache = HashMap<Long, Boolean>()
        fun projectHeld(projectId: Long): Boolean {
            holdCache[projectId]?.let { return it }
            holdCache[projectId] = false
            val initiative = ledger?.value("initiatives", projectId)
            val parent = parentProject(projectId)
            val held = ProPrefs.projectOnHold(prefs, projectId) ||
                initiative?.text("status") == "parked" ||
                (parent != null && parent > 0 && parent in projectsById && projectHeld(parent))
            holdCache[projectId] = held
            return held
        }

        // After: open blockers, without loop edges.
        fun isOpenBlocker(entry: Task): Boolean = when {
            entry.id in open -> true
            entry.id in doneIds -> false
            else -> !entry.done
        }
        val edges = LinkedHashMap<Long, List<Long>>()
        for (task in open.values) edges[task.id] = blockedBy(task).filter(::isOpenBlocker).map { it.id }
        val loopEdges = loops(edges)
        val cycles = mutableSetOf<Long>()
        for (edge in loopEdges) for (id in edge.split('>')) cycles.add(id.toLong())

        // Sequence: per sequential project, its first top-level open task.
        fun topOf(task: Task): Task {
            var current = task
            val seen = mutableSetOf(task.id)
            while (true) {
                val parentId = parentOf(current)
                val parent = parentId?.let { open[it] }
                if (parent == null || parent.projectId != task.projectId || parent.id in seen) return current
                seen.add(parent.id)
                current = parent
            }
        }
        val firstOf = HashMap<Long, Long?>()
        var topsByProject: Map<Long, List<Task>>? = null
        fun sequenceFirst(projectId: Long): Long? {
            if (projectId in firstOf) return firstOf[projectId]
            val tops = topsByProject ?: run {
                val grouped = LinkedHashMap<Long, MutableList<Task>>()
                for (candidate in open.values) {
                    if (ProPrefs.projectMode(prefs, candidate.projectId) != ProjectMode.SEQUENTIAL || topOf(candidate) !== candidate) continue
                    grouped.getOrPut(candidate.projectId) { mutableListOf() }.add(candidate)
                }
                grouped.also { topsByProject = it }
            }
            val order = orders.orderFor(projectId, parentMap)
            val first = sortProjectTasks(tops[projectId].orEmpty(), order).firstOrNull()?.id
            firstOf[projectId] = first
            return first
        }

        return AvailabilityContext(cycles.sorted()) { task ->
            if (task.done) return@AvailabilityContext OPEN
            val reasons = mutableListOf<AvailabilityReason>()
            if (StartDates.startsLater(task, now, zone)) reasons.add(AvailabilityReason.STARTS)
            if (projectHeld(task.projectId)) reasons.add(AvailabilityReason.ON_HOLD)
            if (task.labels.any { ProPrefs.labelOnHold(prefs, it.id) }) reasons.add(AvailabilityReason.WAITING)
            val blockers = (edges[task.id] ?: blockedBy(task).filter(::isOpenBlocker).map { it.id })
                .filter { "${task.id}>$it" !in loopEdges }
            if (blockers.isNotEmpty()) reasons.add(AvailabilityReason.AFTER)
            var next = false
            if (ProPrefs.projectMode(prefs, task.projectId) == ProjectMode.SEQUENTIAL) {
                val first = sequenceFirst(task.projectId)
                val top = if (task.id in open) topOf(task).id else task.id
                if (top != first) reasons.add(AvailabilityReason.SEQUENCE) else next = top == task.id
            }
            val promise = ledger?.value("promises", task.id)
            if (promise?.text("dir") == "theirs" && LedgerRules.finalVerdictOf(promise) == null) reasons.add(AvailabilityReason.THEIRS)
            TaskAvailability(reasons.isEmpty(), reasons, next, blockers)
        }
    }

    /** The tasks a view shows: Available (open, no reason), Remaining (open) or All (as given). */
    fun filterByView(tasks: List<Task>, view: AvailabilityView, context: AvailabilityContext): List<Task> = when (view) {
        AvailabilityView.ALL -> tasks.toList()
        AvailabilityView.REMAINING -> tasks.filter { !it.done }
        AvailabilityView.AVAILABLE -> tasks.filter { !it.done && context.of(it).available }
    }
}
