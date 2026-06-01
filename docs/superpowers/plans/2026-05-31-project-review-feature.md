# Project Review (Spaced Repetition) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the desktop "Review" feature to Android — periodic project health-checks on a configurable cadence (default 14 days), with a Review screen (Due / All-tracked tabs), mark-reviewed + undo, set-cadence, exclude, a drawer entry with an overdue badge, and review settings.

**Architecture:** Review state lives entirely in each project's `description` as a parseable footer (no schema change — `Project.description` already round-trips through DTO/entity/mapper, and `ProjectRepository.update()` already sends the full object, satisfying the Go zero-value rule). We port desktop's `review-metadata.ts` to a pure Kotlin util (TDD'd), add a `ReviewPrefsStore` (DataStore), a `ReviewViewModel` driving a flat sorted project list, a `ReviewScreen`, a nav route + drawer entry/badge, and a settings section. Mutations rewrite the footer and call `projectRepository.update()`.

**Tech Stack:** Kotlin, Jetpack Compose / Material 3, DataStore, Hilt, java.time, kotlinx coroutines/Flow, JUnit.

**Scope note:** This plan implements a **flat, sorted** Due/All-tracked list (the core value). Desktop's inline-accordion *hierarchical tree* and in-row task editing are deliberately deferred as a follow-up (noted at the end).

---

## Background (verified facts)

Footer grammar (desktop `src/renderer/lib/review-metadata.ts`):
```
<description>\n\n---\n**Vicu review**: <date|never|excluded>[ · every N days]
```
- Prefix `**Vicu review**:`, separator `---`, middle dot `·` (U+00B7).
- `MARKER_REGEX = /(?:^|\n)---\s*\n\*\*Vicu review\*\*:\s*(.+?)\s*$/`
- `ISO_DATE = /^\d{4}-\d{2}-\d{2}$/`, `CADENCE_REGEX = /^every\s+(\d+)\s*(d|day|days|w|week|weeks)$/i`
- State `never | reviewed | excluded`; `lastReviewedAt` ISO local date; `cadenceDaysOverride` (null = use global). Cadence always serialized as **days** even if entered as weeks.
- Status: excluded → not overdue; never / no last → overdue immediately; reviewed → `next = last + cadence`, `isOverdue = (today > next)`.
- Settings defaults: `enabled=true, default_cadence_days=14, exclude_inbox=true`.
- Selectors drop archived projects, drop inbox when `exclude_inbox`, drop `excluded`, sort by `daysUntilDue` ascending (never-reviewed floats to top). Badge = count of overdue projects. Sidebar entry hidden when disabled.

Android facts:
- `Project` (`domain/model/Project.kt`) carries `description: String = ""`, `hexColor`, `isArchived`, `parentProjectId`, `id`, `title`. `UpdateProjectDto` already includes `description` and the full object (`ProjectMapper.toUpdateDto`).
- `ProjectRepository.update(project): NetworkResult<Project>` (`ProjectRepositoryImpl.kt:48`) sends the full DTO, writes through Room, returns domain. No offline queue for projects.
- `ProjectRepository.getAll(): Flow<List<Project>>` from Room.
- `AuthManager.getInboxProjectId()` (suspend) yields the inbox id.
- DataStore store template: `data/local/BehaviorPrefsStore.kt` (Hilt-constructed `@Singleton`, no DI module entry).
- Screen VM template: `ui/screens/anytime/AnytimeViewModel.kt`. Nav: `ui/navigation/Routes.kt`, `AppNavHost.kt`; drawer: `DrawerContent.kt` + `DrawerViewModel.kt`; route resolution in `VicuApp.kt` (~263-285).
- Settings combine in `SettingsViewModel` is near the 5-flow nesting limit — add the review flow into an inner sub-combine.

## File Structure

- Create: `util/ReviewMetadata.kt` — pure parse/serialize/status logic.
- Create test: `app/src/test/java/com/rendyhd/vicu/util/ReviewMetadataTest.kt`.
- Create: `data/local/ReviewPrefsStore.kt` — DataStore for enabled/cadence/excludeInbox.
- Create: `ui/screens/review/ReviewViewModel.kt`, `ui/screens/review/ReviewScreen.kt`.
- Modify: `ui/navigation/Routes.kt`, `ui/navigation/AppNavHost.kt`, `ui/navigation/DrawerContent.kt`, `ui/navigation/DrawerViewModel.kt`, `ui/.../VicuApp.kt`.
- Modify: `ui/screens/settings/SettingsViewModel.kt`, `ui/screens/settings/SettingsScreen.kt`.

---

### Task 1: Port review metadata logic (TDD)

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/util/ReviewMetadata.kt`
- Test: `app/src/test/java/com/rendyhd/vicu/util/ReviewMetadataTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReviewMetadataTest {
    @Test
    fun `parse empty description is never`() {
        val m = ReviewMetadata.parse("")
        assertEquals(ReviewState.NEVER, m.state)
        assertNull(m.lastReviewedAt)
        assertNull(m.cadenceDaysOverride)
    }

    @Test
    fun `parse reviewed with weekly cadence converts to days`() {
        val desc = "Body\n\n---\n**Vicu review**: 2026-05-01 · every 2 weeks"
        val m = ReviewMetadata.parse(desc)
        assertEquals(ReviewState.REVIEWED, m.state)
        assertEquals("2026-05-01", m.lastReviewedAt)
        assertEquals(14, m.cadenceDaysOverride)
    }

    @Test
    fun `parse excluded`() {
        val m = ReviewMetadata.parse("x\n\n---\n**Vicu review**: excluded")
        assertEquals(ReviewState.EXCLUDED, m.state)
    }

    @Test
    fun `serialize reviewed with cadence`() {
        val m = ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", 14)
        assertEquals("**Vicu review**: 2026-05-01 · every 14 days", ReviewMetadata.serialize(m))
    }

    @Test
    fun `serialize excluded omits cadence`() {
        val m = ReviewMetadata(ReviewState.EXCLUDED, null, 30)
        assertEquals("**Vicu review**: excluded", ReviewMetadata.serialize(m))
    }

    @Test
    fun `upsert into empty description has no leading newlines`() {
        val out = ReviewMetadata.upsert("", ReviewMetadata(ReviewState.NEVER, null, null))
        assertEquals("---\n**Vicu review**: never", out)
    }

    @Test
    fun `upsert preserves body and replaces existing footer`() {
        val first = ReviewMetadata.upsert("Body", ReviewMetadata(ReviewState.NEVER, null, null))
        val second = ReviewMetadata.upsert(first, ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", null))
        assertTrue(second.startsWith("Body\n\n---\n"))
        assertEquals(ReviewState.REVIEWED, ReviewMetadata.parse(second).state)
        // body must not accumulate footers
        assertEquals(1, Regex("Vicu review").findAll(second).count())
    }

    @Test
    fun `status never is overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.NEVER, null, null), 14, LocalDate.of(2026, 5, 31),
        )
        assertTrue(s.isOverdue)
        assertNull(s.daysUntilDue)
    }

    @Test
    fun `status reviewed within cadence is not overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.REVIEWED, "2026-05-30", null), 14, LocalDate.of(2026, 5, 31),
        )
        assertFalse(s.isOverdue)
        assertEquals(13L, s.daysUntilDue)
        assertEquals(14, s.effectiveCadenceDays)
    }

    @Test
    fun `status reviewed past cadence is overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.REVIEWED, "2026-05-01", 14), 30, LocalDate.of(2026, 5, 31),
        )
        assertTrue(s.isOverdue)            // override 14 used, not global 30
        assertEquals(-15L, s.daysUntilDue)
    }

    @Test
    fun `status excluded is not overdue`() {
        val s = ReviewMetadata.computeStatus(
            ReviewMetadata(ReviewState.EXCLUDED, null, null), 14, LocalDate.of(2026, 5, 31),
        )
        assertFalse(s.isOverdue)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ReviewMetadataTest"`
Expected: FAIL with "Unresolved reference: ReviewMetadata".

- [ ] **Step 3: Implement `ReviewMetadata.kt`**

```kotlin
package com.rendyhd.vicu.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ReviewState { NEVER, REVIEWED, EXCLUDED }

data class ReviewMetadata(
    val state: ReviewState,
    val lastReviewedAt: String?,   // ISO local date "YYYY-MM-DD"
    val cadenceDaysOverride: Int?, // null = use global default
)

data class ReviewStatus(
    val metadata: ReviewMetadata,
    val effectiveCadenceDays: Int,
    val nextReviewAt: LocalDate?,
    val isOverdue: Boolean,
    val daysSinceReviewed: Long?,
    val daysUntilDue: Long?,
)

object ReviewMetadata {
    private const val PREFIX = "**Vicu review**:"
    private const val SEPARATOR = "---"
    private const val DOT = "·"

    private val MARKER_REGEX = Regex("(?:^|\\n)---\\s*\\n\\*\\*Vicu review\\*\\*:\\s*(.+?)\\s*$")
    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val CADENCE_REGEX = Regex("^every\\s+(\\d+)\\s*(d|day|days|w|week|weeks)$", RegexOption.IGNORE_CASE)

    // Factory mirrors `ReviewMetadata(state, last, cadence)` data-class call sites in tests.
    operator fun invoke(state: ReviewState, lastReviewedAt: String?, cadenceDaysOverride: Int?) =
        com.rendyhd.vicu.util.ReviewMetadata(state, lastReviewedAt, cadenceDaysOverride)

    fun parse(description: String?): com.rendyhd.vicu.util.ReviewMetadata {
        val default = com.rendyhd.vicu.util.ReviewMetadata(ReviewState.NEVER, null, null)
        if (description.isNullOrEmpty()) return default
        val match = MARKER_REGEX.find(description) ?: return default
        val raw = match.groupValues[1].trim()
        val parts = raw.split(DOT)
            .flatMap { it.split(" | ") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return default
        val head = parts[0]
        val (state, last) = when {
            head.equals("excluded", true) ->
                return com.rendyhd.vicu.util.ReviewMetadata(ReviewState.EXCLUDED, null, null)
            head.equals("never", true) -> ReviewState.NEVER to null
            ISO_DATE.matches(head) -> ReviewState.REVIEWED to head
            else -> return default
        }
        var cadence: Int? = null
        if (parts.size > 1) {
            val cm = CADENCE_REGEX.find(parts[1])
            if (cm != null) {
                val n = cm.groupValues[1].toIntOrNull()
                val unit = cm.groupValues[2].lowercase()
                if (n != null) cadence = if (unit.startsWith("w")) n * 7 else n
            }
        }
        return com.rendyhd.vicu.util.ReviewMetadata(state, last, cadence)
    }

    fun serialize(meta: com.rendyhd.vicu.util.ReviewMetadata): String {
        if (meta.state == ReviewState.EXCLUDED) return "$PREFIX excluded"
        val head = if (meta.state == ReviewState.REVIEWED && meta.lastReviewedAt != null) {
            meta.lastReviewedAt
        } else {
            "never"
        }
        val c = meta.cadenceDaysOverride
        return if (c != null && c > 0) "$PREFIX $head $DOT every $c days" else "$PREFIX $head"
    }

    fun stripFooter(description: String): String {
        val m = MARKER_REGEX.find(description) ?: return description.trimEnd()
        return description.substring(0, m.range.first).trimEnd()
    }

    fun upsert(description: String, meta: com.rendyhd.vicu.util.ReviewMetadata): String {
        val body = stripFooter(description)
        val footer = "$SEPARATOR\n${serialize(meta)}"
        return if (body.isEmpty()) footer else "$body\n\n$footer"
    }

    fun computeStatus(
        meta: com.rendyhd.vicu.util.ReviewMetadata,
        globalCadenceDays: Int,
        today: LocalDate = LocalDate.now(),
    ): ReviewStatus {
        val cadence = meta.cadenceDaysOverride ?: globalCadenceDays
        if (meta.state == ReviewState.EXCLUDED) {
            return ReviewStatus(meta, cadence, null, false, null, null)
        }
        val last = meta.lastReviewedAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (meta.state != ReviewState.REVIEWED || last == null) {
            return ReviewStatus(meta, cadence, null, true, null, null)
        }
        val next = last.plusDays(cadence.toLong())
        val daysSince = ChronoUnit.DAYS.between(last, today)
        val daysUntil = ChronoUnit.DAYS.between(today, next)
        return ReviewStatus(meta, cadence, next, daysUntil < 0, daysSince, daysUntil)
    }

    /** Local-calendar today as YYYY-MM-DD, used when marking reviewed. */
    fun todayLocalIsoDate(): String = LocalDate.now().toString()
}
```

> Note: the `operator fun invoke` lets `ReviewMetadata(state, last, cadence)` read as a constructor at call sites while `ReviewMetadata` is also the `object` holding the functions. If you prefer, rename the object to `ReviewMetadataLogic` and call the data class directly — but then update all call sites consistently. The plan below assumes the `object ReviewMetadata` + `invoke` form.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ReviewMetadataTest"`
Expected: PASS (all methods).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/ReviewMetadata.kt app/src/test/java/com/rendyhd/vicu/util/ReviewMetadataTest.kt
git commit -m "Port review-metadata parse/serialize/status to Kotlin"
```

---

### Task 2: ReviewPrefsStore

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/data/local/ReviewPrefsStore.kt`

- [ ] **Step 1: Create the store (mirror BehaviorPrefsStore)**

```kotlin
package com.rendyhd.vicu.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ReviewPrefs(
    val enabled: Boolean = true,
    val defaultCadenceDays: Int = 14,
    val excludeInbox: Boolean = true,
)

private val Context.reviewPrefsDataStore by preferencesDataStore(name = "review_prefs")

@Singleton
class ReviewPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("review_enabled")
        val KEY_DEFAULT_CADENCE = intPreferencesKey("review_default_cadence_days")
        val KEY_EXCLUDE_INBOX = booleanPreferencesKey("review_exclude_inbox")
    }

    fun getPrefs(): Flow<ReviewPrefs> = context.reviewPrefsDataStore.data.map { p ->
        ReviewPrefs(
            enabled = p[KEY_ENABLED] ?: true,
            defaultCadenceDays = p[KEY_DEFAULT_CADENCE] ?: 14,
            excludeInbox = p[KEY_EXCLUDE_INBOX] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) {
        context.reviewPrefsDataStore.edit { it[KEY_ENABLED] = v }
    }

    suspend fun setDefaultCadenceDays(v: Int) {
        context.reviewPrefsDataStore.edit { it[KEY_DEFAULT_CADENCE] = v.coerceIn(1, 365) }
    }

    suspend fun setExcludeInbox(v: Boolean) {
        context.reviewPrefsDataStore.edit { it[KEY_EXCLUDE_INBOX] = v }
    }
}
```

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/data/local/ReviewPrefsStore.kt
git commit -m "Add ReviewPrefsStore (enabled, default cadence, exclude inbox)"
```

---

### Task 3: ReviewViewModel

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/ui/screens/review/ReviewViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

```kotlin
package com.rendyhd.vicu.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ReviewPrefs
import com.rendyhd.vicu.data.local.ReviewPrefsStore
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.ReviewMetadata
import com.rendyhd.vicu.util.ReviewState
import com.rendyhd.vicu.util.ReviewStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReviewTab { DUE, ALL }

data class ReviewItem(
    val project: Project,
    val status: ReviewStatus,
)

data class ReviewUiState(
    val tab: ReviewTab = ReviewTab.DUE,
    val due: List<ReviewItem> = emptyList(),
    val all: List<ReviewItem> = emptyList(),
    val reviewedThisSession: Set<Long> = emptySet(),
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val undo: Project? = null,   // previous project state for the last mark-reviewed
    val error: String? = null,
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val reviewPrefsStore: ReviewPrefsStore,
    private val authManager: AuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val inboxId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch { inboxId.value = authManager.getInboxProjectId() }
        viewModelScope.launch {
            combine(
                projectRepository.getAll(),
                reviewPrefsStore.getPrefs(),
                inboxId,
                _uiState,
            ) { projects, prefs, inbox, state ->
                buildState(projects, prefs, inbox, state)
            }.collect { built -> _uiState.value = built }
        }
        refresh()
    }

    private fun buildState(
        projects: List<Project>,
        prefs: ReviewPrefs,
        inbox: Long?,
        state: ReviewUiState,
    ): ReviewUiState {
        val tracked = projects
            .asSequence()
            .filterNot { it.isArchived }
            .filterNot { prefs.excludeInbox && inbox != null && it.id == inbox }
            .map { ReviewItem(it, ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), prefs.defaultCadenceDays)) }
            .filter { it.status.metadata.state != ReviewState.EXCLUDED }
            .sortedBy { it.status.daysUntilDue ?: Long.MIN_VALUE }
            .toList()
        val due = tracked.filter { it.status.isOverdue || it.project.id in state.reviewedThisSession }
        return state.copy(
            all = tracked,
            due = due,
            enabled = prefs.enabled,
            isLoading = false,
        )
    }

    fun setTab(tab: ReviewTab) = _uiState.update { it.copy(tab = tab) }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                projectRepository.refreshAll()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun markReviewed(project: Project) {
        viewModelScope.launch {
            val prev = project
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(ReviewState.REVIEWED, ReviewMetadata.todayLocalIsoDate(), it.cadenceDaysOverride) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            _uiState.update {
                it.copy(reviewedThisSession = it.reviewedThisSession + project.id, undo = prev)
            }
            projectRepository.update(updated)
        }
    }

    fun setCadence(project: Project, days: Int?) {
        viewModelScope.launch {
            val meta = ReviewMetadata.parse(project.description)
                .let { ReviewMetadata(it.state, it.lastReviewedAt, if (days != null && days > 0) days else null) }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            projectRepository.update(updated)
        }
    }

    fun setExcluded(project: Project, excluded: Boolean) {
        viewModelScope.launch {
            val current = ReviewMetadata.parse(project.description)
            val meta = if (excluded) {
                ReviewMetadata(ReviewState.EXCLUDED, null, null)
            } else {
                ReviewMetadata(ReviewState.NEVER, null, current.cadenceDaysOverride)
            }
            val updated = project.copy(description = ReviewMetadata.upsert(project.description, meta))
            projectRepository.update(updated)
        }
    }

    fun undo() {
        val prev = _uiState.value.undo ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(reviewedThisSession = it.reviewedThisSession - prev.id, undo = null)
            }
            projectRepository.update(prev)
        }
    }

    fun dismissUndo() = _uiState.update { it.copy(undo = null) }
}
```

> Note: combining `_uiState` into its own `combine` can loop. To avoid a feedback loop, the `buildState` above only ever sets `all/due/enabled/isLoading` from inputs and **carries** `tab/reviewedThisSession/undo/isRefreshing/error` from the incoming `state` (via `state.copy`). Since `markReviewed/setTab/undo` mutate those carried fields and the recombine re-derives `due` from them, this converges. If StateFlow distinct-until-changed still flickers, split into two flows: keep `reviewedThisSession/tab` as separate `MutableStateFlow`s and `combine` them with projects+prefs+inbox instead of folding the whole `_uiState`.

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL. (Confirm `Project` has `isArchived: Boolean` and `id: Long`; adjust the predicate if `isArchived` is nullable.)
```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/review/ReviewViewModel.kt
git commit -m "Add ReviewViewModel (tracked/due lists, mark/cadence/exclude/undo)"
```

---

### Task 4: ReviewScreen

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/ui/screens/review/ReviewScreen.kt`

- [ ] **Step 1: Create the screen**

```kotlin
package com.rendyhd.vicu.ui.screens.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.util.parseHexColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.undo) {
        val prev = state.undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Marked “${prev.title}” reviewed",
            actionLabel = "Undo",
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissUndo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val reviewedCount = state.reviewedThisSession.size
            val remaining = state.due.count { !state.reviewedThisSession.contains(it.project.id) }
            val total = (reviewedCount + remaining).coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { reviewedCount.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(selectedTabIndex = if (state.tab == ReviewTab.DUE) 0 else 1) {
                Tab(
                    selected = state.tab == ReviewTab.DUE,
                    onClick = { viewModel.setTab(ReviewTab.DUE) },
                    text = { Text("Due (${state.due.size})") },
                )
                Tab(
                    selected = state.tab == ReviewTab.ALL,
                    onClick = { viewModel.setTab(ReviewTab.ALL) },
                    text = { Text("All tracked (${state.all.size})") },
                )
            }
            val items = if (state.tab == ReviewTab.DUE) state.due else state.all
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.tab == ReviewTab.DUE) "You're caught up." else "No tracked projects.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.project.id }) { item ->
                        ReviewRow(
                            item = item,
                            reviewed = item.project.id in state.reviewedThisSession,
                            onMarkReviewed = { viewModel.markReviewed(item.project) },
                            onSetCadence = { days -> viewModel.setCadence(item.project, days) },
                            onExclude = { viewModel.setExcluded(item.project, true) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    item: ReviewItem,
    reviewed: Boolean,
    onMarkReviewed: () -> Unit,
    onSetCadence: (Int?) -> Unit,
    onExclude: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.size(10.dp).clip(CircleShape)
                .androidxBackground(parseHexColor(item.project.hexColor) ?: Color.Gray),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.project.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = stalenessLabel(item), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        if (reviewed) {
            Text("✓ Reviewed")
        } else {
            OutlinedButton(onClick = onMarkReviewed) { Text("Mark reviewed") }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(7, 14, 30, 90).forEach { d ->
                DropdownMenuItem(text = { Text("Cadence: every $d days") }, onClick = {
                    onSetCadence(d); menuOpen = false
                })
            }
            DropdownMenuItem(text = { Text("Use default cadence") }, onClick = {
                onSetCadence(null); menuOpen = false
            })
            DropdownMenuItem(text = { Text("Exclude from review") }, onClick = {
                onExclude(); menuOpen = false
            })
        }
    }
}

private fun stalenessLabel(item: ReviewItem): String {
    val s = item.status
    return when {
        s.metadata.state.name == "NEVER" -> "Never reviewed"
        s.daysUntilDue == null -> "Never reviewed"
        s.daysUntilDue < 0 -> "Overdue ${-s.daysUntilDue}d"
        s.daysUntilDue == 0L -> "Due today"
        else -> "Due in ${s.daysUntilDue}d"
    }
}

// Small helper to avoid importing background twice; replace with Modifier.background if preferred.
private fun Modifier.androidxBackground(color: Color): Modifier =
    this.then(androidx.compose.foundation.background(color))
```

> Note: `parseHexColor` is the existing plain (non-composable) helper used elsewhere in the project for hex→Color (per the codebase's try-catch-in-composable workaround). Confirm its package and import it; if it lives in a different util, adjust the import. The `androidxBackground` helper is only to keep imports tidy — you may replace `Box(... .androidxBackground(color))` with a direct `Modifier.background(color)` import.

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/review/ReviewScreen.kt
git commit -m "Add ReviewScreen (Due/All tabs, mark reviewed, cadence/exclude, undo)"
```

---

### Task 5: Navigation route + drawer entry + badge

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/navigation/Routes.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/navigation/DrawerViewModel.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/navigation/DrawerContent.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/.../VicuApp.kt`

- [ ] **Step 1: Add the route**

In `Routes.kt`, beside the other `@Serializable object` destinations:

```kotlin
@Serializable object ReviewRoute
```

- [ ] **Step 2: Register in AppNavHost**

In `AppNavHost.kt`, beside `composable<LogbookRoute> { ... }`:

```kotlin
composable<ReviewRoute> {
    ReviewScreen(onOpenDrawer = onOpenDrawer)
}
```

Add `import com.rendyhd.vicu.ui.screens.review.ReviewScreen`. (`onOpenDrawer` is already a parameter threaded to the other smart-list composables.)

- [ ] **Step 3: Surface review badge data in DrawerViewModel**

In `DrawerViewModel.kt`: inject `reviewPrefsStore: ReviewPrefsStore`, fold `reviewPrefsStore.getPrefs()` into the existing `combine`, and compute the overdue count from the already-in-scope `projects` + `inboxProjectId`:

```kotlin
// inside the combine transform, with `projects`, `prefs` (ReviewPrefs), and inbox id available:
val reviewOverdue = projects
    .asSequence()
    .filterNot { it.isArchived }
    .filterNot { prefs.excludeInbox && inboxId != null && it.id == inboxId }
    .map { ReviewMetadata.computeStatus(ReviewMetadata.parse(it.description), prefs.defaultCadenceDays) }
    .filter { it.metadata.state != ReviewState.EXCLUDED }
    .count { it.isOverdue }
```

Add `reviewEnabled: Boolean` and `reviewOverdueCount: Int` to `DrawerUiState`, set from `prefs.enabled` and `reviewOverdue`. Imports: `com.rendyhd.vicu.util.ReviewMetadata`, `com.rendyhd.vicu.util.ReviewState`, `com.rendyhd.vicu.data.local.ReviewPrefsStore`.

> If the drawer `combine` is already at its arity limit, nest a sub-`combine(projectFlow, reviewPrefsStore.getPrefs()) { ... }` and feed its result into the outer combine.

- [ ] **Step 4: Render the drawer entry with badge**

In `DrawerContent.kt`, near the Logbook entry (~127-137), add (only when enabled):

```kotlin
if (uiState.reviewEnabled) {
    NavigationDrawerItem(
        label = { Text("Review") },
        selected = currentRoute == "ReviewRoute",
        icon = { Icon(Icons.Outlined.Autorenew, contentDescription = null) },
        badge = {
            if (uiState.reviewOverdueCount > 0) {
                Text(
                    uiState.reviewOverdueCount.toString(),
                    color = Color(0xFF8B5CF6),
                )
            }
        },
        onClick = { onNavigate(ReviewRoute) },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
```

Imports: `androidx.compose.material3.NavigationDrawerItem`, `androidx.compose.material.icons.outlined.Autorenew`, `com.rendyhd.vicu.ui.navigation.ReviewRoute`, `androidx.compose.ui.graphics.Color`. Match the surrounding drawer items' exact param style (the existing `SmartListItem` has no badge slot, which is why this uses `NavigationDrawerItem`).

- [ ] **Step 5: Handle the active-route name in VicuApp**

In `VicuApp.kt` `currentRoute` resolution (~263-285), add a branch mapping the `ReviewRoute` destination to the string `"ReviewRoute"` so the drawer `selected` highlight works, following the existing non-parameterized-object cases (which use `saveState/launchSingleTop/restoreState = true`).

- [ ] **Step 6: Compile + manual check**

Run: `./gradlew installDebug`. Open the drawer → "Review" appears (with a purple count if any project is overdue) when enabled; tapping it opens the screen; marking a project reviewed shows the undo snackbar and drops the badge count.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/navigation/Routes.kt app/src/main/java/com/rendyhd/vicu/ui/navigation/AppNavHost.kt app/src/main/java/com/rendyhd/vicu/ui/navigation/DrawerViewModel.kt app/src/main/java/com/rendyhd/vicu/ui/navigation/DrawerContent.kt
git add "app/src/main/java/com/rendyhd/vicu/ui/VicuApp.kt"
git commit -m "Add Review route, drawer entry, and overdue badge"
```

---

### Task 6: Review settings section

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Wire ReviewPrefs into SettingsViewModel**

Inject `reviewPrefsStore: ReviewPrefsStore`. Add `reviewPrefs: ReviewPrefs = ReviewPrefs()` to `SettingsUiState`. Fold `reviewPrefsStore.getPrefs()` into one of the inner `combine` blocks (e.g. extend the widget-prefs sub-combine from 3 to 4 flows). Add setters:

```kotlin
fun setReviewEnabled(v: Boolean) =
    viewModelScope.launch { reviewPrefsStore.setEnabled(v) }.let {}
fun setReviewDefaultCadence(days: Int) =
    viewModelScope.launch { reviewPrefsStore.setDefaultCadenceDays(days) }.let {}
fun setReviewExcludeInbox(v: Boolean) =
    viewModelScope.launch { reviewPrefsStore.setExcludeInbox(v) }.let {}
```

Imports: `com.rendyhd.vicu.data.local.ReviewPrefs`, `com.rendyhd.vicu.data.local.ReviewPrefsStore`.

- [ ] **Step 2: Render the Review section in GeneralTab**

In `SettingsScreen.kt` `GeneralTab`, add a section (reuse the existing `SwitchRow` and a clickable cadence row). Place near the Preferences section:

```kotlin
item(key = "review_header") {
    Text(
        "Review",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
item(key = "review_enabled") {
    SwitchRow(
        label = "Enable project review tracking",
        description = "Periodically review your projects",
        checked = state.reviewPrefs.enabled,
        onCheckedChange = { viewModel.setReviewEnabled(it) },
    )
}
if (state.reviewPrefs.enabled) {
    item(key = "review_cadence") {
        SettingsValueRow(
            label = "Default review cadence",
            value = "${state.reviewPrefs.defaultCadenceDays} days",
            onClick = { showCadenceDialog = true },
        )
    }
    item(key = "review_exclude_inbox") {
        SwitchRow(
            label = "Exclude Inbox from review",
            description = "Don't track the inbox project",
            checked = state.reviewPrefs.excludeInbox,
            onCheckedChange = { viewModel.setReviewExcludeInbox(it) },
        )
    }
}
```

Add a `showCadenceDialog` state and a cadence picker dialog (reuse `REMINDER_*`-style option list or an `OutlinedTextField` numeric dialog, clamped 1–365):

```kotlin
if (showCadenceDialog) {
    AlertDialog(
        onDismissRequest = { showCadenceDialog = false },
        title = { Text("Default review cadence") },
        text = {
            Column {
                listOf(7, 14, 30, 60, 90).forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setReviewDefaultCadence(d)
                                showCadenceDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = d == state.reviewPrefs.defaultCadenceDays,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("$d days")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showCadenceDialog = false }) { Text("Close") } },
    )
}
```

(`SettingsValueRow` — reuse the same helper introduced in the notification-granularity plan, or inline an equivalent clickable label/value row. `FontWeight` import: `androidx.compose.ui.text.font.FontWeight`.)

- [ ] **Step 3: Compile + manual check**

Run: `./gradlew installDebug`. Settings → General → Review: toggle enable (cadence + exclude rows appear/disappear; the drawer Review entry shows/hides accordingly), change cadence, toggle exclude-inbox, and confirm the Review screen's overdue set changes with cadence.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsViewModel.kt app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt
git commit -m "Add Review settings section (enable, default cadence, exclude inbox)"
```

---

### Task 7: Full build + test gate

- [ ] **Step 1: Run unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL — `ReviewMetadataTest` plus all existing tests pass.

- [ ] **Step 2: Assemble debug**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit any lint/format fixes**

Run: `./gradlew ktlintFormat` then re-build; commit if anything changed:
```bash
git add -A
git commit -m "Format review feature sources"
```

---

## Self-Review

- **Spec coverage:** footer parse/serialize/status (Task 1); settings enabled/cadence/exclude (Tasks 2,6); tracked + due selectors with sort + inbox/archived/excluded filtering (Task 3); Due/All tabs, mark reviewed, set cadence, exclude, undo, progress, empty states (Tasks 3,4); route + drawer entry + overdue badge + hide-when-disabled (Task 5).
- **No placeholders:** core logic and stores are complete; UI tasks carry concrete composables. The few adaptation points (`parseHexColor` import, `Project.isArchived` nullability, drawer/settings `combine` arity, `SettingsValueRow` reuse, `VicuApp` route-name branch) are each called out with the exact code to add.
- **Type consistency:** `ReviewMetadata.parse/serialize/upsert/computeStatus/todayLocalIsoDate`, `ReviewState`, `ReviewStatus`, `ReviewPrefs(enabled, defaultCadenceDays, excludeInbox)`, `ReviewItem`, `ReviewTab`, and VM methods `markReviewed/setCadence/setExcluded/undo/setTab/refresh` are used identically across util, store, VM, screen, drawer, and settings.
- **Deferred (explicit, not gaps):** hierarchical accordion tree + in-row task editing (desktop's redesign); per-project "last reviewed" detail in settings; keyboard navigation (desktop-only). The flat list delivers the full review workflow; the tree is a later enhancement layered on the same `ReviewMetadata`/`ProjectRepository` foundation.
```
