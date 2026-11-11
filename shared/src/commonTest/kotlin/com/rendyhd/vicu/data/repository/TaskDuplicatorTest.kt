package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.ProjectAccess
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.model.TaskReminder
import com.rendyhd.vicu.domain.model.User
import com.rendyhd.vicu.ui.FakeCollaborationRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.Permission
import com.rendyhd.vicu.util.RelationKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Duplicate (docs/cross-app-semantics-v1.md section 15). */
class TaskDuplicatorTest {
    private val maya = User(7, "maya", "Maya")
    private val tasks = FakeTaskRepository()
    private val labels = FakeLabelRepository()
    private var nextId = 100L

    init {
        tasks.createHandler = { NetworkResult.Success(it.copy(id = nextId++)) }
        tasks.createSubtaskHandler = { _, child -> NetworkResult.Success(child.copy(id = nextId++)) }
    }

    private fun duplicator(shared: Boolean = false) = TaskDuplicator(
        tasks,
        labels,
        FakeCollaborationRepository(
            CollabState(me = 1, projects = mapOf(5L to ProjectAccess(shared = shared, members = emptyList(), permission = Permission.WRITE))),
        ),
    )

    private val original = Task(
        id = 1,
        title = "Pack for Lisbon",
        description = "<p>Passport</p>",
        done = true,
        doneAt = "2026-10-30T10:00:00Z",
        dueDate = "2026-11-02T22:59:59Z",
        startDate = "2026-11-01T08:00:00Z",
        endDate = "2026-11-01T08:30:00Z",
        priority = 3,
        projectId = 5,
        repeatAfter = 86_400,
        repeatMode = 0,
        position = 4.0,
        labels = listOf(Label(id = 11, title = "travel")),
        reminders = listOf(TaskReminder(relativePeriod = -3600, relativeTo = "due_date")),
        assignees = listOf(maya),
    )

    @Test
    fun copiesTheFieldsAsAnOpenTask() = runTest {
        val result = duplicator().duplicate(original) as NetworkResult.Success
        val sent = tasks.created.single()
        assertEquals(0, sent.id)
        assertEquals("Pack for Lisbon", sent.title)
        assertEquals("<p>Passport</p>", sent.description)
        assertEquals(original.dueDate, sent.dueDate)
        assertEquals(original.startDate, sent.startDate)
        assertEquals(original.endDate, sent.endDate)
        assertEquals(3, sent.priority)
        assertEquals(5, sent.projectId)
        assertEquals(86_400, sent.repeatAfter)
        assertEquals(original.reminders, sent.reminders)
        assertFalse(sent.done)
        assertEquals("", sent.doneAt)
        assertEquals(0.0, sent.position)
        assertEquals(listOf(100L to 11L), labels.addedToTask)
        assertTrue(result.data.complete)
    }

    @Test
    fun assigneesOnlyOnASharedProject() = runTest {
        val privately = FakeCollaborationRepository()
        TaskDuplicator(tasks, labels, privately).duplicate(original)
        assertTrue(privately.assigned.isEmpty())

        val shared = FakeCollaborationRepository(
            CollabState(me = 1, projects = mapOf(5L to ProjectAccess(shared = true, members = listOf(maya), permission = Permission.WRITE))),
        )
        val copy = (TaskDuplicator(tasks, labels, shared).duplicate(original) as NetworkResult.Success).data.copy
        assertEquals(listOf(Triple(copy.id, 7L, true)), shared.assigned)
    }

    @Test
    fun subtasksComeAlongNestedAndOpen() = runTest {
        val grandchild = Task(id = 3, title = "Adapter", projectId = 5, done = true)
        val child = Task(id = 2, title = "Chargers", projectId = 5, labels = listOf(Label(id = 12, title = "tech")), relatedTasks = mapOf(RelationKind.SUBTASK to listOf(grandchild)))
        tasks.put(child)
        tasks.put(grandchild)
        val parent = original.copy(relatedTasks = mapOf(RelationKind.SUBTASK to listOf(Task(id = 2, title = "Chargers"))))

        duplicator().duplicate(parent)

        val (parentCopy, childCopy) = tasks.createdSubtasks
        assertEquals(100L, parentCopy.first)
        assertEquals("Chargers", parentCopy.second.title)
        assertEquals("Adapter", childCopy.second.title)
        assertFalse(childCopy.second.done)
        assertEquals(101L, childCopy.first)
        assertTrue(101L to 12L in labels.addedToTask)
    }

    @Test
    fun undoDeletesEveryCopySubtasksFirstAndNeverAnOriginal() = runTest {
        val grandchild = Task(id = 3, title = "Adapter", projectId = 5)
        val child = Task(id = 2, title = "Chargers", projectId = 5, relatedTasks = mapOf(RelationKind.SUBTASK to listOf(grandchild)))
        tasks.put(child)
        tasks.put(grandchild)
        val parent = original.copy(relatedTasks = mapOf(RelationKind.SUBTASK to listOf(Task(id = 2, title = "Chargers"))))
        val first = (duplicator().duplicate(parent) as NetworkResult.Success).data
        val second = (duplicator().duplicate(original.copy(id = 9)) as NetworkResult.Success).data
        // The copy, its subtask, that subtask's subtask.
        assertEquals(listOf(100L, 101L, 102L), first.made)
        assertEquals(listOf(103L), second.made)

        assertTrue(duplicator().remove(listOf(first, second)) is NetworkResult.Success)

        assertEquals(listOf(103L, 102L, 101L, 100L), tasks.deleted)
    }

    @Test
    fun aRelationCycleEnds() = runTest {
        val loop = Task(id = 2, title = "Loop", projectId = 5, relatedTasks = mapOf(RelationKind.SUBTASK to listOf(Task(id = 1, title = "Back"))))
        tasks.put(loop)
        duplicator().duplicate(original.copy(relatedTasks = mapOf(RelationKind.SUBTASK to listOf(loop))))
        assertEquals(1, tasks.createdSubtasks.size)
    }

    @Test
    fun aFailedLabelMakesItIncompleteAndAFailedCreateAnError() = runTest {
        val failingLabels = object : com.rendyhd.vicu.domain.repository.LabelRepository by labels {
            override suspend fun addToTask(taskId: Long, labelId: Long): NetworkResult<Unit> = NetworkResult.Error("refused")
        }
        val result = TaskDuplicator(tasks, failingLabels, FakeCollaborationRepository()).duplicate(original) as NetworkResult.Success
        assertFalse(result.data.complete)

        tasks.createHandler = { NetworkResult.Error("offline and refused") }
        assertTrue(duplicator().duplicate(original) is NetworkResult.Error)
    }
}
