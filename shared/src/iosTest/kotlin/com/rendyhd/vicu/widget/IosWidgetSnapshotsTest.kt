package com.rendyhd.vicu.widget

import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.data.local.CustomListStore
import com.rendyhd.vicu.data.local.RoutinePrefsStore
import com.rendyhd.vicu.data.local.WidgetPrefsStore
import com.rendyhd.vicu.data.local.dao.TaskDao
import com.rendyhd.vicu.data.local.entity.ProjectEntity
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.model.Routine
import com.rendyhd.vicu.domain.model.RoutineDay
import com.rendyhd.vicu.domain.model.RoutineDraft
import com.rendyhd.vicu.domain.model.RoutineOccurrenceRecord
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.RoutineParseIssue
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.ui.FakeTaskRepository
import com.rendyhd.vicu.util.CustomListContext
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.FixedTimeSource
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.worker.SyncRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class IosWidgetSnapshotsTest {
    private val zone = TimeZone.of("Europe/Amsterdam")
    private val time = FixedTimeSource(Instant.parse("2026-10-12T08:00:00Z"), zone)
    private val folder = NSTemporaryDirectory() + "widgets-test-" + NSUUID().UUIDString
    private val json = Json { ignoreUnknownKeys = true }
    private val storage = InMemoryTokenStorage()

    private val today = listOf(
        TaskEntity(id = 1, title = "Water the plants", projectId = 1, dueDate = "2026-10-12T10:00:00Z"),
        TaskEntity(id = 2, title = "Pay rent", projectId = 1, dueDate = "2026-10-11T21:59:59Z"),
    )
    private val taskDao = object : TaskDao by FakeTaskDao() {
        override suspend fun getTodayTasksSync(startOfToday: String, startOfTomorrow: String): List<TaskEntity> = today
        override suspend fun getByProjectIdSync(projectId: Long, limit: Int): List<TaskEntity> =
            today.filter { it.projectId == projectId }
    }

    private val doneCalls = mutableListOf<Pair<Long, Boolean>>()
    private val tasks = object : TaskRepository by FakeTaskRepository() {
        override suspend fun setDoneInBackground(taskId: Long, done: Boolean): NetworkResult<Task> {
            doneCalls += taskId to done
            return NetworkResult.Error("not stored")
        }
    }

    private val routineCalls = mutableListOf<String>()
    private val routines = object : RoutineRepository {
        override fun observeActive(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeArchived(): Flow<List<Routine>> = flowOf(emptyList())
        override fun observeRoutine(routineId: String): Flow<Routine?> = flowOf(null)
        override fun observeDay(date: String): Flow<RoutineDay> = flowOf(RoutineDay(date, emptyList()))
        override fun observeHistory(routineId: String): Flow<List<RoutineOccurrenceRecord>> = flowOf(emptyList())
        override fun observeIssues(): Flow<List<RoutineParseIssue>> = flowOf(emptyList())
        override suspend fun create(draft: RoutineDraft): NetworkResult<Routine> = NetworkResult.Error("no")
        override suspend fun update(routineId: String, draft: RoutineDraft): NetworkResult<Routine> = NetworkResult.Error("no")
        override suspend fun setOccurrenceStatus(
            routineId: String,
            date: String,
            slotId: String,
            status: OccurrenceStatus,
            note: String,
        ): NetworkResult<Routine> {
            routineCalls += "$routineId $date $slotId $status"
            return NetworkResult.Error("not stored")
        }
        override suspend fun archive(routineId: String, archived: Boolean): NetworkResult<Routine> = NetworkResult.Error("no")
        override suspend fun deletePermanently(routineId: String): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun finalizeAndPrune(): NetworkResult<Unit> = NetworkResult.Success(Unit)
        override suspend fun exportCsv(): String = ""
    }

    private var syncs = 0
    private var reloads = 0

    private fun TestScope.snapshots(): IosWidgetSnapshots {
        val customLists = CustomListStore(InMemoryPreferencesDataStore())
        val states = TaskWidgetStates(
            taskDao = taskDao,
            projectDao = FakeProjectDao(listOf(ProjectEntity(id = 1, title = "Home"))),
            taskRepository = tasks,
            tokenStorage = storage,
            customListStore = customLists,
            widgetPrefsStore = WidgetPrefsStore(InMemoryPreferencesDataStore()),
            behaviorPrefsStore = BehaviorPrefsStore(InMemoryPreferencesDataStore()),
            time = time,
            collaborationContext = { CustomListContext.NONE },
        )
        val requester = SyncRequester(scope = backgroundScope, sync = { syncs++; true }, isSignedIn = { true })
        return IosWidgetSnapshots(
            states = states,
            customListStore = customLists,
            routineRepository = routines,
            routinePrefs = RoutinePrefsStore(InMemoryPreferencesDataStore()),
            taskRepository = tasks,
            dayClock = DayClock(backgroundScope, time, ticking = false),
            syncRequester = { requester },
            appScope = backgroundScope,
            folder = { folder },
        ).also {
            it.bridge = object : AppleWidgetBridge {
                override fun reloadAllTimelines() {
                    reloads++
                }
            }
        }
    }

    private fun write(path: String, text: String) {
        SystemFileSystem.createDirectories(Path(path).parent!!)
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
    }

    private fun read(path: String): String = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    private fun exists(path: String) = SystemFileSystem.exists(Path(path))

    private fun catalog(): WidgetCatalog = json.decodeFromString(WidgetCatalog.serializer(), read("$folder/catalog.json"))

    /** A catalog as versions before the account tag wrote it: no account, no ids. */
    private fun olderCatalog(signedIn: Boolean) {
        val (light, dark) = WidgetSnapshots.palettes()
        val entries = listOf(WidgetCatalogEntry(key = "project-1", name = "Home", kind = "project"))
        // Without encodeDefaults the new fields (all at their defaults) are left out.
        write("$folder/catalog.json", Json.encodeToString(WidgetCatalog.serializer(), WidgetCatalog(signedIn, entries, light, dark)))
        assertFalse(read("$folder/catalog.json").contains("account"))
    }

    private suspend fun signIn() {
        storage.storeVikunjaUrl("https://vikunja.test")
        storage.storeApiToken("tk_1", 0L)
    }

    @AfterTest
    fun cleanUp() {
        fun delete(path: Path) {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
            SystemFileSystem.delete(path, mustExist = false)
        }
        delete(Path(folder))
    }

    @Test
    fun theWidgetButtonsAreStoredAsTheAppWouldThenSynced() = runTest {
        val widgets = snapshots()
        write("$folder/actions/0001-a.json", """{"kind":"completeTask","taskId":7}""")
        write(
            "$folder/actions/0002-b.json",
            """{"kind":"routineStatus","routineId":"r1","date":"2026-10-12","slotId":"s1","status":"COMPLETED"}""",
        )
        // Not finished by the extension yet: left for the next time.
        write("$folder/actions/0003-c.json.part", """{"kind":"completeTask","taskId":8}""")

        assertTrue(widgets.drainActions())
        runCurrent()

        // An explicit completion, never a toggle.
        assertEquals(listOf(7L to true), doneCalls)
        assertEquals(listOf("r1 2026-10-12 s1 COMPLETED"), routineCalls)
        assertEquals(1, syncs)
        assertFalse(exists("$folder/actions/0001-a.json"))
        assertFalse(exists("$folder/actions/0002-b.json"))
        assertTrue(exists("$folder/actions/0003-c.json.part"))
        assertFalse(widgets.drainActions())
    }

    @Test
    fun everyViewIsWrittenForTodayAndTomorrowAndStaleOnesGo() = runTest {
        signIn()
        val widgets = snapshots()
        write("$folder/views/project-99.json", "{}")

        widgets.write()

        val catalog = json.decodeFromString(WidgetCatalog.serializer(), read("$folder/catalog.json"))
        assertTrue(catalog.signedIn)
        assertEquals(listOf("today", "inbox", "upcoming", "anytime", "project-1"), catalog.views.map { it.key })
        assertFalse(exists("$folder/views/project-99.json"))

        val todayView = json.decodeFromString(TaskWidgetSnapshot.serializer(), read("$folder/views/today.json"))
        assertEquals(listOf("2026-10-12", "2026-10-13"), todayView.days.map { it.date })
        assertEquals(listOf("Pay rent", "Water the plants"), todayView.days.first().rows.map { it.title }.sorted())
        assertTrue(exists("$folder/routines.json"))
        assertEquals(1, reloads)

        // Nothing changed: WidgetKit is not asked again (background reloads are budgeted).
        widgets.write()
        assertEquals(1, reloads)
    }

    @Test
    fun projectsAndListsCarryTheAccountTheyWereChosenUnder() = runTest {
        signIn()
        val widgets = snapshots()

        widgets.write()

        val catalog = catalog()
        val account = catalog.account.orEmpty()
        assertTrue(account.isNotEmpty())
        assertEquals(account, read("$folder/account"))
        // Smart lists show whichever account is signed in, so their ids carry none.
        assertEquals(listOf("today", "inbox", "upcoming", "anytime", "project-1@$account"), catalog.views.map { it.id })
        assertEquals(listOf("today", "inbox", "upcoming", "anytime", "project-1"), catalog.views.map { it.key })
        // Nothing was set up before the ids carried the account.
        assertEquals("", catalog.untaggedAccount)
    }

    @Test
    fun anotherAccountGetsANewTagAndTheOldOnesWidgetTapsAreDropped() = runTest {
        signIn()
        val widgets = snapshots()
        widgets.write()
        val first = catalog().account
        write("$folder/actions/0001-a.json", """{"kind":"completeTask","taskId":7}""")

        // Sign-out or another account: LocalDataWiper.wipeEverything through IosRepositoryHooks.
        widgets.retireAccount()
        widgets.write()

        val second = catalog().account.orEmpty()
        assertNotEquals(first, second)
        assertEquals("project-1@$second", catalog().views.last().id)
        // Task 7 was the old account's: not completed in the new one.
        assertTrue(doneCalls.isEmpty())
        assertFalse(exists("$folder/actions/0001-a.json"))
    }

    @Test
    fun theTagLastsUntilTheAccountsDataIsWiped() = runTest {
        signIn()
        val widgets = snapshots()
        widgets.write()
        val tag = catalog().account

        // Signed out without a wipe (the session expired), then the same account again.
        storage.clear()
        widgets.write()
        assertEquals("", catalog().account)
        signIn()
        widgets.write()

        assertEquals(tag, catalog().account)
    }

    @Test
    fun widgetsSetUpBeforeTheTagBelongToTheAccountSignedInThen() = runTest {
        signIn()
        olderCatalog(signedIn = true)
        val widgets = snapshots()

        widgets.write()
        val tag = catalog().account
        assertEquals(tag, catalog().untaggedAccount)
        widgets.write()
        assertEquals(tag, catalog().untaggedAccount)

        widgets.retireAccount()
        widgets.write()
        assertNotEquals(tag, catalog().account)
        assertEquals(tag, catalog().untaggedAccount)
    }

    @Test
    fun widgetsSetUpBeforeTheTagWhileSignedOutBelongToNoAccount() = runTest {
        signIn()
        olderCatalog(signedIn = false)

        snapshots().write()

        assertEquals("", catalog().untaggedAccount)
    }

    @Test
    fun signedOutLeavesNoAccountDataForTheWidgets() = runTest {
        signIn()
        val widgets = snapshots()
        widgets.write()
        storage.clear()

        widgets.write()

        val catalog = json.decodeFromString(WidgetCatalog.serializer(), read("$folder/catalog.json"))
        assertFalse(catalog.signedIn)
        assertTrue(catalog.views.isEmpty())
        assertTrue(SystemFileSystem.list(Path("$folder/views")).isEmpty())
        assertFalse(exists("$folder/routines.json"))
        assertEquals(2, reloads)
    }
}
