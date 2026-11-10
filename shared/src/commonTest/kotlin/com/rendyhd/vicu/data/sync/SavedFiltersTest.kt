package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.ui.components.shared.RuleEditing
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved filters (cross-app semantics 3.7): never projects, imported once on first connect. */
class SavedFiltersTest {

    private fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}"""

    private val projects = page(
        """{"id":4,"title":"Home"}""",
        """{"id":-1,"title":"Favorites"}""",
        """{"id":-2,"title":"High Priority"}""",
        """{"id":-4,"title":"Broken"}""",
    )

    private fun api(requests: MutableList<String> = mutableListOf()): VikunjaApiService {
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/projects" -> respond(projects, HttpStatusCode.OK, authTestJsonHeaders)
                    "/filters/1" -> respond(
                        """{"id":1,"title":"High Priority","filters":{"filter":"done = false && priority > 2","sort_by":["priority"],"order_by":["desc"]}}""",
                        HttpStatusCode.OK,
                        authTestJsonHeaders,
                    )
                    "/filters/3" -> respond("""{"id":3,"title":"Broken","filters":{"filter":"priority >> 2"}}""", HttpStatusCode.OK, authTestJsonHeaders)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        return VikunjaApiService(client, authTestJson)
    }

    /** Lists and the import record, the way the repository keeps them. */
    private class FakeLists : CustomListRepository {
        val all = MutableStateFlow<List<CustomList>>(emptyList())
        val imported = MutableStateFlow<Set<Long>>(emptySet())
        override val lists: Flow<List<CustomList>> = all
        override val syncStatus: StateFlow<CustomListSyncStatus> = MutableStateFlow(CustomListSyncStatus.Idle)
        override val importedSavedFilters: Flow<Set<Long>> = imported.map { it }
        override suspend fun importLists(lists: List<CustomList>, filterIds: Collection<Long>) {
            all.value = all.value + lists
            imported.value = imported.value + filterIds
        }
        override suspend fun upsert(customList: CustomList) {
            all.value = all.value.filterNot { it.id == customList.id } + customList
        }
        override suspend fun delete(id: String) {
            all.value = all.value.filterNot { it.id == id }
        }
        override suspend fun reorder(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun clearLocal() = Unit
        override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
    }

    @Test
    fun `the project refresh keeps pseudo-projects out and remembers the saved filters`() = runTest {
        val dao = FakeProjectDao()
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        ProjectRefresher(dao, FakePendingActionDao(), api(), ProjectMapper(), store).refresh()

        assertEquals(listOf(4L), dao.getAllIds())
        assertEquals(listOf(SavedFilterRef(1, "High Priority"), SavedFilterRef(3, "Broken")), store.known.first())
    }

    @Test
    fun `getAllProjects never returns a pseudo-project`() = runTest {
        assertEquals(listOf(4L), api().getAllProjects().map { it.id })
    }

    @Test
    fun `first connect imports every readable filter once`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists()
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()
        var id = 0
        val importer = SavedFilterImporter(apiService, store, lists, newId = { "list-${++id}" })

        assertTrue(importer.afterSync(CustomListSyncStatus.Idle))

        val imported = lists.all.value.single()
        assertEquals("High Priority", imported.name)
        assertEquals(1L, imported.savedFilterId)
        assertEquals("done = false && priority > 2", imported.filter.rule)
        assertEquals("priority" to "desc", imported.filter.sortBy to imported.filter.orderBy)
        assertEquals(setOf(1L), lists.imported.value)
        assertTrue(store.isFirstConnectDone())

        // A later sync imports nothing again, and the broken filter stays on offer.
        assertFalse(importer.afterSync(CustomListSyncStatus.Idle))
        assertEquals(1, lists.all.value.size)
        assertEquals(listOf(SavedFilterRef(3, "Broken")), importer.offer.first())
    }

    @Test
    fun `nothing is imported before the lists were read`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists()
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()
        val importer = SavedFilterImporter(apiService, store, lists)

        assertFalse(importer.afterSync(CustomListSyncStatus.Offline("offline")))
        assertTrue(lists.all.value.isEmpty())
        assertFalse(store.isFirstConnectDone())
    }

    @Test
    fun `a filter another device imported is not imported again`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists().apply { imported.value = setOf(1L) }
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()

        assertFalse(SavedFilterImporter(apiService, store, lists).afterSync(CustomListSyncStatus.Idle))
        assertTrue(lists.all.value.isEmpty())
        assertTrue(store.isFirstConnectDone())
    }

    @Test
    fun `Not now hides the offer until another filter appears`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val importer = SavedFilterImporter(api(), store, FakeLists())
        store.setKnown(listOf(SavedFilterRef(1, "A")))
        importer.dismiss(importer.offer.first())
        assertTrue(importer.offer.first().isEmpty())

        store.setKnown(listOf(SavedFilterRef(1, "A"), SavedFilterRef(2, "B")))
        assertEquals(listOf(SavedFilterRef(2, "B")), importer.offer.first())
    }

    // --- The editor's names -------------------------------------------------------------------

    private val labels = listOf(RuleEditing.Named(1, "Errand"), RuleEditing.Named(2, "Home"))
    private val projectNames = listOf(RuleEditing.Named(10, "Work"), RuleEditing.Named(11, "Garden shed"))

    @Test
    fun `the editor shows names and saves ids`() {
        val rule = "labels in 1, 2 && project = 11 && priority > 2"
        val shown = RuleEditing.toDisplay(rule, labels, projectNames)
        assertEquals("labels in Errand, Home && project = Garden shed && priority > 2", shown)
        assertEquals(RuleEditing.Saved.Rule(rule), RuleEditing.fromDisplay(shown, labels, projectNames))
    }

    @Test
    fun `an unknown name or an invalid rule cannot be saved`() {
        val unknown = RuleEditing.fromDisplay("labels = Garage", labels, projectNames)
        assertTrue(unknown is RuleEditing.Saved.Problem && "Garage" in unknown.message)
        assertTrue(RuleEditing.fromDisplay("lables = 1", labels, projectNames) is RuleEditing.Saved.Problem)
        assertEquals(RuleEditing.Saved.Rule(null), RuleEditing.fromDisplay("  ", labels, projectNames))
    }

    @Test
    fun `suggestions follow what is typed`() {
        fun labelsAt(text: String) = RuleEditing.suggest(text, text.length, labels, projectNames).map { it.label }
        assertTrue("due_date" in labelsAt("du"))
        assertTrue("not in" in labelsAt("labels "))
        assertEquals(listOf("Home"), labelsAt("labels in Errand, Ho"))
        assertTrue("now/d" in labelsAt("due_date < "))
        assertEquals(listOf("&&", "||"), labelsAt("priority > 2 "))
    }
}
