package com.rendyhd.vicu.ui.screens.taskentry

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.NlpPrefsStore
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.data.repository.InMemorySyncedEntries
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.location.FakeLocationPermission
import com.rendyhd.vicu.location.GeoPoint
import com.rendyhd.vicu.location.LocationAccess
import com.rendyhd.vicu.location.LocationRepository
import com.rendyhd.vicu.location.LocationTrigger
import com.rendyhd.vicu.location.SyncedLocation
import com.rendyhd.vicu.location.testLocations
import com.rendyhd.vicu.ui.FakeAttachmentRepository
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.FakeProjectRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.AppMessage
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.TextTaskCreator
import com.rendyhd.vicu.util.parser.TokenType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.datetime.TimeZone

/**
 * "When I get home" typed in the quick add: the phrase is highlighted, the place chip shows the
 * reminder, and the saved task gets it, like the same words said to Siri.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEntryPlaceTest {

    @BeforeTest
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private val zone = TimeZone.currentSystemDefault()

    private class Rig(
        val tasks: FakeTaskRepository,
        val locations: LocationRepository,
        val permission: FakeLocationPermission,
        val messages: MutableList<AppMessage>,
        val vm: TaskEntryViewModel,
        val authScope: CoroutineScope,
    )

    private fun TestScope.rig(access: LocationAccess = LocationAccess.ALWAYS): Rig {
        val tasks = FakeTaskRepository().apply {
            createHandler = { NetworkResult.Success(it.copy(id = 500)) }
        }
        // Work was saved on another phone: its name synced, its position is not known here.
        val locations = testLocations(
            InMemorySyncedEntries(mapOf(SyncedLocation.placeKey("w") to SyncedLocation.placeValue("Work"))),
        )
        val permission = FakeLocationPermission(access)
        val appMessages = AppMessages()
        val messages = mutableListOf<AppMessage>()
        backgroundScope.launch { appMessages.messages.collect { messages += it } }
        val authScope = CoroutineScope(SupervisorJob())
        val vm = TaskEntryViewModel(
            taskRepository = tasks,
            projectRepository = FakeProjectRepository(listOf(Project(id = 1, title = "Inbox"))),
            labelRepository = FakeLabelRepository(),
            attachmentRepository = FakeAttachmentRepository(),
            authManager = AuthManager(
                platformAuthHooks = RecordingAuthHooks(),
                tokenStorage = InMemoryTokenStorage(),
                apiServiceProvider = { error("no network in this test") },
                appScope = authScope,
                networkMonitor = FakeNetworkMonitor(),
            ),
            nlpPrefsStore = NlpPrefsStore(InMemoryPreferencesDataStore()),
            notificationPrefsStore = NotificationPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            appMessages = appMessages,
            dayClock = DayClock(backgroundScope, FixedTimeSource(Clock.System.now(), zone), ticking = false),
            locations = locations,
            locationPermission = permission,
        )
        runCurrent()
        vm.initWithDefaults(defaultProjectId = 1L)
        runCurrent()
        return Rig(tasks, locations, permission, messages, vm, authScope)
    }

    private suspend fun TestScope.addHome(rig: Rig) {
        rig.locations.addPlace("Home", GeoPoint(52.37, 4.89))
        runCurrent()
    }

    @Test
    fun `the phrase for a saved place is read and the chip says it`() = runTest {
        val rig = rig()
        addHome(rig)

        rig.vm.setTitle("Call mom when I get home")

        val state = rig.vm.uiState.value
        assertEquals("Call mom", rig.vm.effectiveTitle())
        assertEquals("Arrive: Home", state.placeMatch?.let(::entryPlaceChipLabel))
        assertTrue(state.parseResult!!.tokens.any { it.type == TokenType.PLACE })
        rig.authScope.cancel()
    }

    @Test
    fun `saving sets the task's location reminder`() = runTest {
        val rig = rig()
        addHome(rig)
        rig.vm.setTitle("Water the plants when I leave home")

        rig.vm.save()
        runCurrent()

        assertEquals("Water the plants", rig.tasks.created.single().title)
        val reminder = rig.locations.reminderFor(500).first()!!
        assertEquals("Home", reminder.placeName)
        assertEquals(LocationTrigger.LEAVE, reminder.trigger)
        assertEquals(listOf("Undo"), rig.messages.map { it.actionLabel }, "Always and a known position need no word beyond the confirmation")
        rig.authScope.cancel()
    }

    @Test
    fun `a place saved before the title was typed is read too`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Call mom when I get home")
        assertNull(rig.vm.uiState.value.placeMatch)

        addHome(rig)

        assertEquals("Call mom", rig.vm.effectiveTitle())
        rig.authScope.cancel()
    }

    @Test
    fun `tapping the chip switches arrive and leave`() = runTest {
        val rig = rig()
        addHome(rig)
        rig.vm.setTitle("Call mom when I get home")

        rig.vm.togglePlaceTrigger()
        assertEquals("Leave: Home", rig.vm.uiState.value.placeMatch?.let(::entryPlaceChipLabel))

        rig.vm.save()
        runCurrent()
        assertEquals(LocationTrigger.LEAVE, rig.locations.reminderFor(500).first()?.trigger)
        rig.authScope.cancel()
    }

    @Test
    fun `clearing the chip leaves the words in the title and sets no reminder`() = runTest {
        val rig = rig()
        addHome(rig)
        rig.vm.setTitle("Call mom when I get home")

        rig.vm.suppressType(TokenType.PLACE)
        rig.vm.save()
        runCurrent()

        assertEquals("Call mom when I get home", rig.tasks.created.single().title)
        assertNull(rig.locations.reminderFor(500).first())
        rig.authScope.cancel()
    }

    @Test
    fun `without Always the snackbar says so and opens the settings`() = runTest {
        val rig = rig(LocationAccess.WHILE_USING)
        addHome(rig)
        rig.vm.setTitle("Call mom when I get home")

        rig.vm.save()
        runCurrent()

        val message = rig.messages.single()
        assertEquals(TaskEntryViewModel.NEEDS_ALWAYS, message.text)
        message.onAction!!.invoke()
        assertEquals(1, rig.permission.settingsOpened)
        rig.authScope.cancel()
    }

    @Test
    fun `a place picked in the Remind tray is the task's reminder`() = runTest {
        val rig = rig()
        addHome(rig)
        rig.vm.setTitle("Water the plants")
        val home = rig.vm.uiState.value.places.single { it.name == "Home" }

        rig.vm.setPlaceReminder(home, LocationTrigger.ARRIVE)
        rig.vm.togglePlaceTrigger()
        assertEquals(PlacePick(home, LocationTrigger.LEAVE), rig.vm.uiState.value.placeReminder)
        rig.vm.save()
        runCurrent()

        val reminder = rig.locations.reminderFor(500).first()!!
        assertEquals("Home", reminder.placeName)
        assertEquals(LocationTrigger.LEAVE, reminder.trigger)
        rig.authScope.cancel()
    }

    @Test
    fun `a picked place wins over the title's and leaves its words as text`() = runTest {
        val rig = rig()
        addHome(rig)
        val work = rig.vm.uiState.value.places.single { it.name == "Work" }
        rig.vm.setTitle("Call mom when I get home")

        rig.vm.setPlaceReminder(work, LocationTrigger.LEAVE)

        assertEquals("Call mom when I get home", rig.vm.effectiveTitle())
        assertEquals(PlacePick(work, LocationTrigger.LEAVE), rig.vm.uiState.value.placeReminder)
        rig.authScope.cancel()
    }

    @Test
    fun `removing the place reminder sets none`() = runTest {
        val rig = rig()
        addHome(rig)
        rig.vm.setTitle("Call mom when I get home")

        rig.vm.clearPlaceReminder()
        rig.vm.save()
        runCurrent()

        assertEquals("Call mom when I get home", rig.tasks.created.single().title)
        assertNull(rig.locations.reminderFor(500).first())
        rig.authScope.cancel()
    }

    @Test
    fun `the checklist becomes the task's subtasks in its order`() = runTest {
        val rig = rig()
        rig.tasks.createSubtaskHandler = { _, subtask -> NetworkResult.Success(subtask) }
        rig.vm.setTitle("Pack for the trip")
        rig.vm.addSubtask("Passport")
        rig.vm.addSubtask("  ")
        rig.vm.addSubtask("Charger")
        rig.vm.addSubtask("Socks")
        rig.vm.removeSubtask(2)

        rig.vm.save()
        runCurrent()

        assertEquals(listOf(500L, 500L), rig.tasks.createdSubtasks.map { it.first })
        assertEquals(listOf("Passport", "Charger"), rig.tasks.createdSubtasks.map { it.second.title })
        assertEquals(listOf(1L, 1L), rig.tasks.createdSubtasks.map { it.second.projectId })
        rig.authScope.cancel()
    }

    @Test
    fun `a subtask that fails is reported and the rest still go`() = runTest {
        val rig = rig()
        rig.tasks.createSubtaskHandler = { _, subtask ->
            if (subtask.title == "Passport") NetworkResult.Error("Could not add Passport") else NetworkResult.Success(subtask)
        }
        rig.vm.setTitle("Pack for the trip")
        rig.vm.addSubtask("Passport")
        rig.vm.addSubtask("Charger")

        rig.vm.save()
        runCurrent()

        assertEquals(listOf("Passport", "Charger"), rig.tasks.createdSubtasks.map { it.second.title })
        assertEquals("Could not add Passport", rig.messages.single().text)
        assertEquals(500L, rig.vm.uiState.value.savedTaskId)
        rig.authScope.cancel()
    }

    @Test
    fun `a place this phone has not located still gets the reminder and a word`() = runTest {
        val rig = rig()
        rig.vm.setTitle("Print the report when I arrive at work")

        rig.vm.save()
        runCurrent()

        assertEquals("Print the report", rig.tasks.created.single().title)
        assertEquals("w", rig.locations.reminderFor(500).first()?.placeId)
        assertEquals(TextTaskCreator.notLocated("Work"), rig.messages.single().text)
        rig.authScope.cancel()
    }
}
