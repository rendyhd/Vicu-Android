package com.rendyhd.vicu.widget

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskWidgetStatesTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val day = ClockDay(LocalDate(2026, 10, 12), zone)
    private val storage = InMemoryTokenStorage()
    private val todayBoundaries = mutableListOf<String>()

    private val today = (1L..25L).map { TaskEntity(id = it, title = "Task $it", projectId = if (it == 25L) 9 else 1, dueDate = "2026-10-12T10:00:00Z") }

    private val taskDao = object : TaskDao by FakeTaskDao() {
        override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> {
            todayBoundaries += startOfTomorrow
            return today
        }

        override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> =
            today.filter { it.projectId == projectId }
    }

    private val projects = FakeProjectDao(
        listOf(
            ProjectEntity(id = 1, title = "Home"),
            ProjectEntity(id = 9, title = "Old", isArchived = true),
        ),
    )

    private val states = TaskWidgetStates(
        taskDao = taskDao,
        projectDao = projects,
        taskRepository = FakeTaskRepository(),
        tokenStorage = storage,
        customListStore = CustomListStore(InMemoryPreferencesDataStore()),
        widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
        behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
        time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
        collaborationContext = { CustomListContext.NONE },
    )

    @Test
    fun aBlockStartingTodayIsOnTheTodayWidgetAndAStartDateAloneIsNot() = runTest {
        val rows = listOf(
            TaskEntity(id = 1, title = "Block", projectId = 1, startDate = "2026-10-12T09:00:00Z", endDate = "2026-10-12T09:30:00Z"),
            TaskEntity(id = 2, title = "Start only", projectId = 1, startDate = "2026-10-12T09:00:00Z"),
            TaskEntity(id = 3, title = "Several days", projectId = 1, startDate = "2026-10-12T09:00:00Z", endDate = "2026-10-14T09:00:00Z"),
        )
        val dao = object : TaskDao by FakeTaskDao() {
            override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> = rows
        }
        val widget = TaskWidgetStates(
            taskDao = dao,
            projectDao = projects,
            taskRepository = FakeTaskRepository(),
            tokenStorage = storage,
            customListStore = CustomListStore(InMemoryPreferencesDataStore()),
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
            collaborationContext = { CustomListContext.NONE },
        )

        val state = widget.prepare(day).build(WidgetConfig(WidgetViewType.TODAY, "", "Today"))

        assertEquals(listOf(1L), state.tasks.map { it.id })
    }

    @Test
    fun aWidgetShowsTwentyRowsAndCountsAllWithoutArchivedProjects() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.TODAY, "", "Today"))
        // Task 25 belongs to an archived project.
        assertEquals(24, state.totalCount)
        assertEquals(TaskWidgetStates.MAX_WIDGET_TASKS, state.tasks.size)
        assertEquals("Home", state.tasks.first().projectName)
        assertNull(state.error)
        // The boundary of the day it was built for: the start of the local tomorrow.
        assertEquals(listOf("2026-10-12T22:00:00Z"), todayBoundaries)
    }

    @Test
    fun aWidgetForAnArchivedProjectSaysSo() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.PROJECT, "9", "Old"))
        assertEquals(TaskWidgetStates.PROJECT_UNAVAILABLE, state.error)
        assertTrue(state.tasks.isEmpty())
    }

    @Test
    fun theWidgetPreferencesAreCarried() = runTest {
        val state = states.prepare(day).build(WidgetConfig(WidgetViewType.PROJECT, "1", "Home"))
        assertTrue(state.smartAdd)
        assertTrue(state.contextNav)
        assertEquals(0L, state.addToProjectId)
        assertEquals("Home", state.viewName)
    }

    @Test
    fun signedOutNeedsAServerAndAToken() = runTest {
        assertFalse(states.isSignedIn())
        storage.storeVikunjaUrl("https://vikunja.test")
        assertFalse(states.isSignedIn())
        storage.storeApiToken("tk_1", 0L)
        assertTrue(states.isSignedIn())
        assertEquals(TaskWidgetStates.SIGNED_OUT, states.signedOut().error)
    }

    @Test
    fun aProjectWidgetListsInTheProjectsSyncedOrderBeforeTakingRows() = runTest {
        val rows = listOf(
            TaskEntity(id = 1, title = "b undated", projectId = 4, position = 200.0),
            TaskEntity(id = 2, title = "a dated later", projectId = 4, dueDate = "2026-10-20T10:00:00Z", position = 100.0),
            TaskEntity(id = 3, title = "c dated sooner", projectId = 4, dueDate = "2026-10-13T10:00:00Z", position = 300.0),
            TaskEntity(id = 4, title = "d undated", projectId = 4, position = 50.0),
        )
        val dao = object : TaskDao by FakeTaskDao() {
            override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> {
                assertEquals(-1, limit, "the whole list is read, then sorted")
                return rows
            }
        }
        val entries = com.rendyhd.vicu.data.repository.InMemorySyncedEntries()
        val orders = com.rendyhd.vicu.data.repository.TaskOrderPreferences(entries)
        val widget = TaskWidgetStates(
            taskDao = dao,
            projectDao = FakeProjectDao(listOf(ProjectEntity(id = 4, title = "Garden"))),
            taskRepository = FakeTaskRepository(),
            tokenStorage = storage,
            customListStore = CustomListStore(InMemoryPreferencesDataStore()),
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone),
            collaborationContext = { CustomListContext.NONE },
            taskOrders = orders,
        )
        val config = WidgetConfig(WidgetViewType.PROJECT, "4", "Garden")

        assertEquals(listOf(3L, 2L, 4L, 1L), widget.prepare(day).build(config).tasks.map { it.id }, "default: dated first, then manual")

        orders.setProjectOrder(4, com.rendyhd.vicu.domain.model.TaskOrder.TITLE)
        assertEquals(listOf(2L, 1L, 3L, 4L), widget.prepare(day).build(config).tasks.map { it.id })
    }
}
