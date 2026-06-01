# Task Relations UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users view, add, and remove full task relations (related / blocking / blocked-by / duplicate-of / duplicates / precedes / follows, plus read-only parent) in the task detail sheet — generalizing the existing subtask-only relation handling.

**Architecture:** The API, DTOs, mapper, Room persistence, and a generic `deleteRelation(taskId, kind, otherId)` already exist. We add: (1) a `RelationKind` label/constants util, (2) a repository `createRelation` that relates to an *existing* task and re-fetches both tasks, (3) a done-inclusive task search for the picker, (4) ViewModel state/methods exposing grouped relations + a debounced search, and (5) a Relations section + task-picker dialog in `TaskDetailScreen`. The existing dedicated "Subtasks" section (which creates *new* child tasks) is left untouched.

**Tech Stack:** Kotlin, Jetpack Compose / Material 3, Retrofit, Room, Hilt, coroutines/Flow, JUnit.

---

## Background (verified facts)

- Vikunja `relation_kind` wire strings (`docs.json` 8471): `unknown, subtask, parenttask, related, duplicateof, duplicates, blocking, blocked, precedes, follows, copiedfrom, copiedto`. **Wire value is `precedes` (one `e`)** despite the Go varname `RelationKindPreceeds`.
- Vikunja auto-creates the reciprocal relation on the *other* task — so after add/delete, re-fetch both tasks.
- Existing API (`VikunjaApiService.kt:96-105`): `createRelation(taskId, CreateRelationDto(otherTaskId, relationKind))` and `deleteRelation(taskId, relationKind, otherTaskId)` — both already generic.
- `CreateRelationDto` (`AuthDtos.kt:69-73`) exists.
- `TaskRepositoryImpl.deleteRelation` (lines ~260-275) already generic and re-fetches the base task. `createSubtask` (lines ~233-258) creates a *new* task — not reusable for relating an existing one.
- `Task.relatedTasks: Map<String, List<Task>>` (`Task.kt:31`) populated by `TaskMapper` (partial Task objects: id/title/description/done/dates/priority/projectId/repeat).
- `TaskDetailViewModel` reads only `relatedTasks["subtask"]` (line ~90). `TaskDetailScreen` subtask section is lines ~362-429.
- `TaskDao.searchByTitle` filters `done = 0`; a done-inclusive query is needed for `duplicateof`.
- Search debounce pattern lives in `SearchViewModel` (`taskRepository.searchByTitle` + `refreshAll(mapOf("s" to q))`).
- Picker structural template: `ui/components/picker/ProjectPickerDialog.kt` (AlertDialog + LazyColumn + clickable rows).

## File Structure

- Create: `app/src/main/java/com/rendyhd/vicu/util/RelationKind.kt` — wire constants + display labels + selectable/displayable lists.
- Create: `app/src/test/java/com/rendyhd/vicu/util/RelationKindTest.kt` — label/list unit test.
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt` — add `searchByTitleIncludingDone`.
- Modify: `app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt` — add `createRelation` + `searchByTitleIncludingDone`.
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt` — implement both; also re-fetch other task in `deleteRelation`.
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt` — grouped relations state, add/remove methods, debounced relation search.
- Create: `app/src/main/java/com/rendyhd/vicu/ui/components/picker/RelationTaskPickerDialog.kt` — kind selector + task search/picker.
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt` — Relations section + wire the picker.

---

### Task 1: RelationKind constants + labels (TDD)

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/util/RelationKind.kt`
- Test: `app/src/test/java/com/rendyhd/vicu/util/RelationKindTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationKindTest {
    @Test
    fun `wire value for precedes has one e`() {
        assertEquals("precedes", RelationKind.PRECEDES)
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("Blocked by", RelationKind.label(RelationKind.BLOCKED))
        assertEquals("Duplicate of", RelationKind.label(RelationKind.DUPLICATEOF))
        assertEquals("Parent task", RelationKind.label(RelationKind.PARENTTASK))
    }

    @Test
    fun `subtask and parenttask are not user-selectable`() {
        assertFalse(RelationKind.SELECTABLE.contains(RelationKind.SUBTASK))
        assertFalse(RelationKind.SELECTABLE.contains(RelationKind.PARENTTASK))
        assertTrue(RelationKind.SELECTABLE.contains(RelationKind.BLOCKING))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.RelationKindTest"`
Expected: FAIL with "Unresolved reference: RelationKind".

- [ ] **Step 3: Create `RelationKind.kt`**

```kotlin
package com.rendyhd.vicu.util

/** Vikunja relation_kind wire values + display helpers. */
object RelationKind {
    const val SUBTASK = "subtask"
    const val PARENTTASK = "parenttask"
    const val RELATED = "related"
    const val DUPLICATEOF = "duplicateof"
    const val DUPLICATES = "duplicates"
    const val BLOCKING = "blocking"
    const val BLOCKED = "blocked"
    const val PRECEDES = "precedes" // wire value has ONE 'e' despite Go varname
    const val FOLLOWS = "follows"
    const val COPIEDFROM = "copiedfrom"
    const val COPIEDTO = "copiedto"

    /** Kinds offered when adding a relation. subtask has its own dedicated section. */
    val SELECTABLE = listOf(RELATED, BLOCKING, BLOCKED, DUPLICATEOF, DUPLICATES, PRECEDES, FOLLOWS)

    /** Kinds rendered in the generic Relations section (parenttask shown read-only). */
    val DISPLAYABLE = listOf(
        PARENTTASK, RELATED, BLOCKING, BLOCKED, DUPLICATEOF, DUPLICATES,
        PRECEDES, FOLLOWS, COPIEDFROM, COPIEDTO,
    )

    fun label(kind: String): String = when (kind) {
        SUBTASK -> "Subtask"
        PARENTTASK -> "Parent task"
        RELATED -> "Related"
        DUPLICATEOF -> "Duplicate of"
        DUPLICATES -> "Duplicates"
        BLOCKING -> "Blocking"
        BLOCKED -> "Blocked by"
        PRECEDES -> "Precedes"
        FOLLOWS -> "Follows"
        COPIEDFROM -> "Copied from"
        COPIEDTO -> "Copied to"
        else -> kind.replaceFirstChar { it.uppercase() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.RelationKindTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/RelationKind.kt app/src/test/java/com/rendyhd/vicu/util/RelationKindTest.kt
git commit -m "Add RelationKind constants and display labels"
```

---

### Task 2: Done-inclusive task search (DAO + repository)

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt` (next to `searchByTitle`, ~line 51)
- Modify: `app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt`
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt`

- [ ] **Step 1: Add the DAO query**

In `TaskDao.kt`, directly below the existing `searchByTitle`:

```kotlin
@Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%'")
fun searchByTitleIncludingDone(query: String): Flow<List<TaskEntity>>
```

- [ ] **Step 2: Add the repository interface method**

In `domain/repository/TaskRepository.kt`, near the other relation/search methods:

```kotlin
fun searchByTitleIncludingDone(query: String): kotlinx.coroutines.flow.Flow<List<com.rendyhd.vicu.domain.model.Task>>
suspend fun createRelation(taskId: Long, otherTaskId: Long, relationKind: String): NetworkResult<Unit>
```

(`createRelation` is implemented in Task 3; declaring it here keeps the interface complete.)

- [ ] **Step 3: Implement `searchByTitleIncludingDone` in `TaskRepositoryImpl.kt`**

Place next to the existing `searchByTitle` impl:

```kotlin
override fun searchByTitleIncludingDone(query: String): Flow<List<Task>> =
    taskDao.searchByTitleIncludingDone(query).map { list ->
        list.map { with(taskMapper) { it.toDomain() } }
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: FAIL — `createRelation` declared in interface but not yet implemented. This is expected; Task 3 implements it. (If you prefer green-at-every-step, add the interface method in Task 3 instead. Otherwise proceed to Task 3 before compiling.)

- [ ] **Step 5: Commit (after Task 3 compiles)**

Defer the commit until Task 3 completes so the tree compiles. (Stage both together there.)

---

### Task 3: Repository `createRelation` (relate existing task) + reciprocal refetch

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt`

- [ ] **Step 1: Implement `createRelation`**

Add next to `deleteRelation`:

```kotlin
override suspend fun createRelation(
    taskId: Long,
    otherTaskId: Long,
    relationKind: String,
): NetworkResult<Unit> {
    return try {
        api.createRelation(
            taskId,
            com.rendyhd.vicu.data.remote.api.CreateRelationDto(
                otherTaskId = otherTaskId,
                relationKind = relationKind,
            ),
        )
        // Re-fetch base task; Vikunja auto-creates the reciprocal on the other task.
        val dto = api.getTask(taskId)
        taskDao.upsert(with(taskMapper) { dto.toEntity() })
        try {
            val otherDto = api.getTask(otherTaskId)
            taskDao.upsert(with(taskMapper) { otherDto.toEntity() })
        } catch (e: Exception) {
            // Best-effort: the other task's cache refresh is non-critical.
        }
        NetworkResult.Success(Unit)
    } catch (e: Exception) {
        NetworkResult.Error(e.localizedMessage ?: "Failed to create relation")
    }
}
```

- [ ] **Step 2: Also refetch the other task in `deleteRelation`**

In the existing `deleteRelation`, after `taskDao.upsert(with(taskMapper) { dto.toEntity() })` (the base re-fetch), add the same best-effort other-task refresh:

```kotlin
try {
    val otherDto = api.getTask(otherTaskId)
    taskDao.upsert(with(taskMapper) { otherDto.toEntity() })
} catch (e: Exception) {
    // Best-effort.
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (interface from Task 2 now fully implemented).

- [ ] **Step 4: Commit (Tasks 2 + 3 together)**

```bash
git add app/src/main/java/com/rendyhd/vicu/data/local/dao/TaskDao.kt app/src/main/java/com/rendyhd/vicu/domain/repository/TaskRepository.kt app/src/main/java/com/rendyhd/vicu/data/repository/TaskRepositoryImpl.kt
git commit -m "Add createRelation (existing task) + done-inclusive search to TaskRepository"
```

---

### Task 4: ViewModel — grouped relations state, add/remove, debounced search

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt`

- [ ] **Step 1: Add `relations` to `TaskDetailUiState`**

Next to `subtasks` (line ~41):

```kotlin
val relations: Map<String, List<Task>> = emptyMap(),
```

- [ ] **Step 2: Populate `relations` in the `getById` collector**

Where `subtasks` is computed (line ~90), add directly after it:

```kotlin
val relations = task.relatedTasks
    .filterKeys { it in com.rendyhd.vicu.util.RelationKind.DISPLAYABLE }
    .filterValues { it.isNotEmpty() }
```

Then include `relations = relations` in both `_uiState.update { it.copy(...) }` calls that already set `subtasks = subtasks` (lines ~101 and ~107).

- [ ] **Step 3: Add add/remove methods**

Near `createSubtask` (line ~229):

```kotlin
fun addRelation(otherTaskId: Long, relationKind: String) {
    val task = _uiState.value.task ?: return
    viewModelScope.launch {
        when (val result = taskRepository.createRelation(task.id, otherTaskId, relationKind)) {
            is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
            else -> {}
        }
    }
}

fun removeRelation(relationKind: String, otherTaskId: Long) {
    val task = _uiState.value.task ?: return
    viewModelScope.launch {
        when (val result = taskRepository.deleteRelation(task.id, relationKind, otherTaskId)) {
            is NetworkResult.Error -> _uiState.update { it.copy(error = result.message) }
            else -> {}
        }
    }
}
```

- [ ] **Step 4: Add the debounced relation-search exposure**

Add class-level fields (top of the ViewModel body) and annotate the class with the opt-ins. Add these imports: `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.stateIn`, `kotlinx.coroutines.flow.debounce`, `kotlinx.coroutines.flow.flatMapLatest`, `kotlinx.coroutines.flow.flowOf`, `kotlinx.coroutines.FlowPreview`, `kotlinx.coroutines.ExperimentalCoroutinesApi`.

```kotlin
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
private val _relationSearchQuery = MutableStateFlow("")

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
val relationSearchResults: StateFlow<List<Task>> = _relationSearchQuery
    .debounce(250)
    .flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList())
        else taskRepository.searchByTitleIncludingDone(q)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

fun setRelationSearchQuery(q: String) {
    _relationSearchQuery.value = q
    if (q.isNotBlank()) {
        viewModelScope.launch { taskRepository.refreshAll(mapOf("s" to q)) }
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailViewModel.kt
git commit -m "Expose grouped relations + add/remove + relation search in TaskDetailViewModel"
```

---

### Task 5: Relation task-picker dialog (kind selector + search)

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/ui/components/picker/RelationTaskPickerDialog.kt`

- [ ] **Step 1: Create the dialog composable**

Mirror `ProjectPickerDialog` structure (AlertDialog + LazyColumn). It first lets the user pick a kind, then search/select a task; confirming calls back with `(otherTaskId, relationKind)`.

```kotlin
package com.rendyhd.vicu.ui.components.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.RelationKind

@Composable
fun RelationTaskPickerDialog(
    searchResults: List<Task>,
    onQueryChange: (String) -> Unit,
    onConfirm: (otherTaskId: Long, relationKind: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedKind by remember { mutableStateOf(RelationKind.RELATED) }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add relation") },
        text = {
            Column {
                // Kind selector
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items(RelationKind.SELECTABLE) { kind ->
                        FilterChip(
                            selected = kind == selectedKind,
                            onClick = { selectedKind = kind },
                            label = { Text(RelationKind.label(kind)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChange(it)
                    },
                    label = { Text("Search tasks") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(searchResults) { task ->
                        Text(
                            text = task.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(task.id, selectedKind) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

> Note: confirm happens on row tap (selecting the task), so `confirmButton` is intentionally empty. `AssistChip` import may be unused — remove it if the linter flags it.

- [ ] **Step 2: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/components/picker/RelationTaskPickerDialog.kt
git commit -m "Add RelationTaskPickerDialog (kind selector + task search)"
```

---

### Task 6: Relations section in TaskDetailScreen

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt`

- [ ] **Step 1: Collect search results + picker visibility state**

Near the existing local state (`subtaskInput`, `showSubtaskInput`, lines ~105-106), add:

```kotlin
var showRelationPicker by remember { mutableStateOf(false) }
val relationSearchResults by viewModel.relationSearchResults.collectAsStateWithLifecycle()
```

Ensure `import androidx.lifecycle.compose.collectAsStateWithLifecycle` is present (used elsewhere in the project; add if missing).

- [ ] **Step 2: Render the Relations section**

Immediately after the subtask section (after the `"add_subtask"` item, ~line 429), add the grouped relations list + an "Add relation" row:

```kotlin
if (state.relations.isNotEmpty()) {
    item(key = "divider_relations") {
        Text(
            text = "Relations",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    state.relations.forEach { (kind, tasks) ->
        item(key = "relation_header_$kind") {
            Text(
                text = com.rendyhd.vicu.util.RelationKind.label(kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
        items(tasks, key = { "relation_${kind}_${it.id}" }) { related ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = related.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // parenttask is read-only (reciprocal of subtask); no delete affordance.
                if (kind != com.rendyhd.vicu.util.RelationKind.PARENTTASK) {
                    IconButton(onClick = { viewModel.removeRelation(kind, related.id) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove relation",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
item(key = "add_relation") {
    TextButton(
        onClick = {
            viewModel.setRelationSearchQuery("")
            showRelationPicker = true
        },
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add relation")
    }
}
```

Confirm these imports exist (add any missing): `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Add`, `androidx.compose.material.icons.filled.Close`, `androidx.compose.material3.IconButton`, `androidx.compose.material3.Icon`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.width`, `androidx.compose.foundation.layout.size`, `androidx.compose.ui.Alignment`.

- [ ] **Step 3: Render the picker dialog**

Outside the `LazyColumn` (at the same level as other sheets/dialogs in this composable), add:

```kotlin
if (showRelationPicker) {
    RelationTaskPickerDialog(
        searchResults = relationSearchResults.filter { it.id != state.task?.id },
        onQueryChange = { viewModel.setRelationSearchQuery(it) },
        onConfirm = { otherId, kind ->
            viewModel.addRelation(otherId, kind)
            showRelationPicker = false
        },
        onDismiss = { showRelationPicker = false },
    )
}
```

Add import `com.rendyhd.vicu.ui.components.picker.RelationTaskPickerDialog`.

- [ ] **Step 4: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual verification**

Run: `./gradlew installDebug`, open a task, scroll to Relations. Confirm: existing relations group by kind; tapping "Add relation" opens the dialog; picking a kind + a task adds it and it appears under the right header after the sheet's data refreshes; the remove (×) button deletes a relation; reopening the *other* task shows the reciprocal relation.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskdetail/TaskDetailScreen.kt
git commit -m "Add Relations section + task picker to task detail sheet"
```

---

## Self-Review

- **Spec coverage:** view (Task 4/6) + add (Task 3/5/6) + remove (Task 4/6) of all selectable relation kinds; parent shown read-only; reciprocal consistency via dual re-fetch (Task 3). Subtask section untouched (still creates new tasks).
- **No placeholders:** all steps carry concrete code; the only manual step (Task 6 Step 5) is explicit verification.
- **Type consistency:** `createRelation(taskId, otherTaskId, relationKind)`, `removeRelation(relationKind, otherTaskId)`, `addRelation(otherTaskId, relationKind)`, `setRelationSearchQuery(q)`, `relationSearchResults` names are used identically across VM (Task 4), dialog (Task 5), and screen (Task 6). `RelationKind.*` constants are the single source of wire strings.
- **Known follow-ups (not blocking):** related Task objects from the mapper are partial (no labels/attachments) — the relation rows only show title, which is fine. If you later want priority/due on relation rows, extend `TaskMapper`'s partial-Task construction.
