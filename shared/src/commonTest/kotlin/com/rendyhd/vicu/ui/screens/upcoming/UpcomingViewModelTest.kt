package com.rendyhd.vicu.ui.screens.upcoming

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.ui.fakeScreenRefresher
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.SchedulerTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The Upcoming list groups by local day and regroups when the day rolls over. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingViewModelTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `at midnight tomorrow's heading becomes today and its tasks leave the list`() = runTest {
        val zone = TimeZone.UTC
        val tasks = FakeTaskRepository()
        val projects = FakeProjectRepository(listOf(Project(id = 1, title = "Home")))
        val labels = FakeLabelRepository()
        val time = SchedulerTimeSource(testScheduler, Instant.parse("2026-10-06T23:30:00Z"), zone)
        val vm = UpcomingViewModel(
            taskRepository = tasks,
            projectRepository = projects,
            labelRepository = labels,
            refresher = fakeScreenRefresher(tasks, projects, labels),
            dayClock = DayClock(backgroundScope, time),
        )
        tasks.upcomingTasks.value = listOf(
            Task(id = 1, title = "A", projectId = 1, dueDate = "2026-10-07T23:59:59Z"),
            Task(id = 2, title = "B", projectId = 1, dueDate = "2026-10-08T23:59:59Z"),
        )
        runCurrent()
        assertEquals(listOf("Tomorrow", "Thursday"), vm.uiState.value.days.map { it.label })

        advanceTimeBy(31.minutes)
        runCurrent()

        assertEquals(listOf("Tomorrow"), vm.uiState.value.days.map { it.label })
        assertEquals(listOf(2L), vm.uiState.value.days.single().tasks.map { it.id })
    }
}
