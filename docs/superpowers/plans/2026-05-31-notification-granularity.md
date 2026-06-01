# Notification Granularity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Android notifications to desktop parity: (1) a second "afternoon" daily summary, (2) per-type toggles (overdue / due-today / upcoming) that gate the digest, and (3) a default per-task reminder offset synthesized when a due date is set.

**Architecture:** Add new keys to `NotificationPrefsStore`; split `DailySummaryScheduler` into per-slot (morning/afternoon) periodic jobs; have `DailySummaryWorker` read prefs and gate each bucket; re-schedule the afternoon slot on boot; extend the Settings Notifications tab with toggles, a second time picker, and an offset/relative-to picker; port desktop's `buildDefaultReminder` to a pure util and synthesize a reminder at task-creation time.

**Tech Stack:** Kotlin, DataStore, WorkManager, Jetpack Compose / Material 3, Hilt, java.time, JUnit.

---

## Background (verified facts)

Desktop defaults to mirror (`src/main/config.ts`):

| Setting | Default |
|---|---|
| morning summary enabled / time | `true` / `08:00` |
| afternoon summary enabled / time | `false` / `16:00` |
| overdue / due-today / upcoming | `true` / `true` / `false` |
| default reminder offset (seconds before due, 0=off, -1=at due) | `0` |
| default reminder relative_to | `due_date` |

Desktop offset options (`NotificationSettings.tsx`): None=0, At due time=-1, 5m=300, 15m=900, 30m=1800, 1h=3600, 3h=10800, 1d=86400. `buildDefaultReminder` (`src/renderer/lib/default-reminder.ts`): offset 0 or null due → null; offset -1 → `{reminder: due, relative_period: 0, relative_to}`; offset>0 → `{reminder: due - offset, relative_period: -offset, relative_to}`.

Android current state:
- `NotificationPrefsStore` (`data/local/NotificationPrefsStore.kt`): `taskRemindersEnabled, dailySummaryEnabled, dailySummaryHour=8, dailySummaryMinute=0, soundEnabled`.
- `DailySummaryScheduler` (`notification/DailySummaryScheduler.kt`): single `WORK_NAME="daily_summary"`, `scheduleIfEnabled(enabled,hour,minute)`, `schedule(hour,minute)`, `cancel()`.
- `DailySummaryWorker` (`worker/DailySummaryWorker.kt`): counts all three buckets unconditionally via `taskDao.countOverdue/countDueToday/countUpcoming`; no prefs injected.
- `BootReceiver` re-schedules the (morning) summary only.
- `SettingsScreen.NotificationsTab` (~1187-1285) uses `SwitchRow` + `TimePickerRow`; one `showTimePicker` state + dialog (~267-297).
- `SettingsViewModel` injects `notificationPrefsStore` + `dailySummaryScheduler`; setter pattern `setDailySummaryEnabled/Time` rewires the scheduler.
- `AlarmScheduler.resolveReminderTime` consumes existing `TaskReminder`s; no default-offset concept. `TaskReminder(reminder, relativePeriod, relativeTo)`.

## File Structure

- Modify: `data/local/NotificationPrefsStore.kt` — new keys/fields/getters/setters.
- Modify: `notification/DailySummaryScheduler.kt` — per-slot work names + overloads.
- Modify: `worker/DailySummaryWorker.kt` — inject prefs, gate buckets.
- Modify: `notification/BootReceiver.kt` — re-schedule afternoon.
- Modify: `ui/screens/settings/SettingsScreen.kt` — toggles, 2nd time picker, offset/relative pickers.
- Modify: `ui/screens/settings/SettingsViewModel.kt` — setters + afternoon scheduler rewire.
- Create: `util/DefaultReminder.kt` + `app/src/test/java/com/rendyhd/vicu/util/DefaultReminderTest.kt` — synthesis + tests.
- Modify: the task-creation ViewModel (`ui/screens/taskentry/TaskEntryViewModel.kt`) — synthesize default reminder.

---

### Task 1: Extend NotificationPrefsStore

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/local/NotificationPrefsStore.kt`

- [ ] **Step 1: Add fields to `NotificationPrefs`**

```kotlin
data class NotificationPrefs(
    val taskRemindersEnabled: Boolean = true,
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    val soundEnabled: Boolean = true,
    // Afternoon summary
    val afternoonSummaryEnabled: Boolean = false,
    val afternoonSummaryHour: Int = 16,
    val afternoonSummaryMinute: Int = 0,
    // Per-type digest gating
    val notifyOverdueEnabled: Boolean = true,
    val notifyDueTodayEnabled: Boolean = true,
    val notifyUpcomingEnabled: Boolean = false,
    // Default per-task reminder offset (seconds before due; 0 = off, -1 = at due time)
    val defaultReminderOffset: Int = 0,
    val defaultReminderRelativeTo: String = "due_date",
)
```

- [ ] **Step 2: Add the preference keys**

In the `companion object`, alongside the existing keys:

```kotlin
private val KEY_AFTERNOON_ENABLED = booleanPreferencesKey("afternoon_summary_enabled")
private val KEY_AFTERNOON_HOUR = intPreferencesKey("afternoon_summary_hour")
private val KEY_AFTERNOON_MINUTE = intPreferencesKey("afternoon_summary_minute")
private val KEY_NOTIFY_OVERDUE = booleanPreferencesKey("notify_overdue_enabled")
private val KEY_NOTIFY_DUE_TODAY = booleanPreferencesKey("notify_due_today_enabled")
private val KEY_NOTIFY_UPCOMING = booleanPreferencesKey("notify_upcoming_enabled")
private val KEY_DEFAULT_REMINDER_OFFSET = intPreferencesKey("default_reminder_offset")
private val KEY_DEFAULT_REMINDER_RELATIVE_TO = stringPreferencesKey("default_reminder_relative_to")
```

Confirm imports: `androidx.datastore.preferences.core.intPreferencesKey`, `androidx.datastore.preferences.core.stringPreferencesKey`.

- [ ] **Step 3: Map them in `getPrefs()`**

Add to the `NotificationPrefs(...)` construction inside `getPrefs().map { p -> ... }`:

```kotlin
afternoonSummaryEnabled = p[KEY_AFTERNOON_ENABLED] ?: false,
afternoonSummaryHour = p[KEY_AFTERNOON_HOUR] ?: 16,
afternoonSummaryMinute = p[KEY_AFTERNOON_MINUTE] ?: 0,
notifyOverdueEnabled = p[KEY_NOTIFY_OVERDUE] ?: true,
notifyDueTodayEnabled = p[KEY_NOTIFY_DUE_TODAY] ?: true,
notifyUpcomingEnabled = p[KEY_NOTIFY_UPCOMING] ?: false,
defaultReminderOffset = p[KEY_DEFAULT_REMINDER_OFFSET] ?: 0,
defaultReminderRelativeTo = p[KEY_DEFAULT_REMINDER_RELATIVE_TO] ?: "due_date",
```

- [ ] **Step 4: Add setters**

```kotlin
suspend fun setAfternoonSummaryEnabled(v: Boolean) =
    context.notificationPrefsDataStore.edit { it[KEY_AFTERNOON_ENABLED] = v }.let {}
suspend fun setAfternoonSummaryTime(hour: Int, minute: Int) {
    context.notificationPrefsDataStore.edit {
        it[KEY_AFTERNOON_HOUR] = hour
        it[KEY_AFTERNOON_MINUTE] = minute
    }
}
suspend fun setNotifyOverdueEnabled(v: Boolean) =
    context.notificationPrefsDataStore.edit { it[KEY_NOTIFY_OVERDUE] = v }.let {}
suspend fun setNotifyDueTodayEnabled(v: Boolean) =
    context.notificationPrefsDataStore.edit { it[KEY_NOTIFY_DUE_TODAY] = v }.let {}
suspend fun setNotifyUpcomingEnabled(v: Boolean) =
    context.notificationPrefsDataStore.edit { it[KEY_NOTIFY_UPCOMING] = v }.let {}
suspend fun setDefaultReminderOffset(seconds: Int) =
    context.notificationPrefsDataStore.edit { it[KEY_DEFAULT_REMINDER_OFFSET] = seconds }.let {}
suspend fun setDefaultReminderRelativeTo(value: String) =
    context.notificationPrefsDataStore.edit { it[KEY_DEFAULT_REMINDER_RELATIVE_TO] = value }.let {}
```

(Match the exact `edit { }` style already used by the existing setters in this file; the `.let {}` is only to keep a `Unit` return — drop it if the existing setters don't use it.)

- [ ] **Step 5: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/data/local/NotificationPrefsStore.kt
git commit -m "Add afternoon summary, per-type, and default-offset notification prefs"
```

---

### Task 2: Per-slot scheduling in DailySummaryScheduler

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/DailySummaryScheduler.kt`

- [ ] **Step 1: Introduce slot-aware work names + overloads**

Replace the `companion object` and the three methods with:

```kotlin
companion object {
    private const val WORK_NAME_MORNING = "daily_summary"
    private const val WORK_NAME_AFTERNOON = "daily_summary_afternoon"
    const val SLOT_MORNING = "morning"
    const val SLOT_AFTERNOON = "afternoon"
    private fun workName(slot: String) =
        if (slot == SLOT_AFTERNOON) WORK_NAME_AFTERNOON else WORK_NAME_MORNING
}

// Backward-compatible morning overloads (keep existing callers compiling)
fun scheduleIfEnabled(enabled: Boolean, hour: Int, minute: Int) =
    scheduleIfEnabled(SLOT_MORNING, enabled, hour, minute)
fun schedule(hour: Int, minute: Int) = schedule(SLOT_MORNING, hour, minute)
fun cancel() = cancel(SLOT_MORNING)

fun scheduleIfEnabled(slot: String, enabled: Boolean, hour: Int, minute: Int) {
    if (!enabled) { cancel(slot); return }
    schedule(slot, hour, minute)
}

fun schedule(slot: String, hour: Int, minute: Int) {
    val now = LocalDateTime.now()
    var target = now.with(LocalTime.of(hour, minute))
    if (!target.isAfter(now)) target = target.plusDays(1)
    val initialDelay = Duration.between(now, target)
    val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf("slot" to slot))
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        workName(slot), ExistingPeriodicWorkPolicy.UPDATE, request,
    )
}

fun cancel(slot: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(slot))
}
```

Add import `androidx.work.workDataOf` if missing.

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL (old callers use the no-slot overloads).
```bash
git add app/src/main/java/com/rendyhd/vicu/notification/DailySummaryScheduler.kt
git commit -m "Support per-slot (morning/afternoon) daily summary scheduling"
```

---

### Task 3: Gate digest buckets by prefs in DailySummaryWorker

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/worker/DailySummaryWorker.kt`

- [ ] **Step 1: Inject `NotificationPrefsStore`**

Add to the worker's `@AssistedInject` constructor parameter list (after the existing `taskMapper`):

```kotlin
private val notificationPrefsStore: com.rendyhd.vicu.data.local.NotificationPrefsStore,
```

(`DailySummaryWorker` is a Hilt worker; constructor injection of a `@Singleton` store is supported.)

- [ ] **Step 2: Read prefs and gate each count in `doWork()`**

Replace the three unconditional count lines:

```kotlin
val overdueCount = taskDao.countOverdue(startOfToday)
val todayCount = taskDao.countDueToday(startOfToday, endOfToday)
val upcomingCount = taskDao.countUpcoming(endOfToday)
```

with:

```kotlin
val prefs = notificationPrefsStore.getPrefs().first()
val overdueCount = if (prefs.notifyOverdueEnabled) taskDao.countOverdue(startOfToday) else 0
val todayCount = if (prefs.notifyDueTodayEnabled) taskDao.countDueToday(startOfToday, endOfToday) else 0
val upcomingCount = if (prefs.notifyUpcomingEnabled) taskDao.countUpcoming(endOfToday) else 0
```

Add import `kotlinx.coroutines.flow.first`. The existing `if (total == 0) return Result.success()` guard now also covers the all-toggles-off case.

- [ ] **Step 3: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/worker/DailySummaryWorker.kt
git commit -m "Gate daily summary buckets by per-type notification prefs"
```

---

### Task 4: Re-schedule afternoon slot on boot

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/notification/BootReceiver.kt`

- [ ] **Step 1: Add the afternoon schedule call**

After the existing morning `dailySummaryScheduler.scheduleIfEnabled(...)` block:

```kotlin
dailySummaryScheduler.scheduleIfEnabled(
    DailySummaryScheduler.SLOT_AFTERNOON,
    prefs.afternoonSummaryEnabled,
    prefs.afternoonSummaryHour,
    prefs.afternoonSummaryMinute,
)
```

(The existing morning call can stay as-is using the no-slot overload, or be made explicit with `DailySummaryScheduler.SLOT_MORNING` for symmetry.)

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/notification/BootReceiver.kt
git commit -m "Re-schedule afternoon daily summary on boot"
```

---

### Task 5: ViewModel setters + afternoon scheduler rewire

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add setters mirroring `setDailySummaryEnabled/Time`**

```kotlin
fun setAfternoonSummaryEnabled(enabled: Boolean) {
    viewModelScope.launch {
        notificationPrefsStore.setAfternoonSummaryEnabled(enabled)
        val prefs = uiState.value.notificationPrefs
        dailySummaryScheduler.scheduleIfEnabled(
            DailySummaryScheduler.SLOT_AFTERNOON,
            enabled, prefs.afternoonSummaryHour, prefs.afternoonSummaryMinute,
        )
    }
}

fun setAfternoonSummaryTime(hour: Int, minute: Int) {
    viewModelScope.launch {
        notificationPrefsStore.setAfternoonSummaryTime(hour, minute)
        val prefs = uiState.value.notificationPrefs
        if (prefs.afternoonSummaryEnabled) {
            dailySummaryScheduler.schedule(DailySummaryScheduler.SLOT_AFTERNOON, hour, minute)
        }
    }
}

fun setNotifyOverdueEnabled(v: Boolean) =
    viewModelScope.launch { notificationPrefsStore.setNotifyOverdueEnabled(v) }.let {}
fun setNotifyDueTodayEnabled(v: Boolean) =
    viewModelScope.launch { notificationPrefsStore.setNotifyDueTodayEnabled(v) }.let {}
fun setNotifyUpcomingEnabled(v: Boolean) =
    viewModelScope.launch { notificationPrefsStore.setNotifyUpcomingEnabled(v) }.let {}
fun setDefaultReminderOffset(seconds: Int) =
    viewModelScope.launch { notificationPrefsStore.setDefaultReminderOffset(seconds) }.let {}
fun setDefaultReminderRelativeTo(value: String) =
    viewModelScope.launch { notificationPrefsStore.setDefaultReminderRelativeTo(value) }.let {}
```

Confirm `import com.rendyhd.vicu.notification.DailySummaryScheduler` is present.

- [ ] **Step 2: Compile + commit**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsViewModel.kt
git commit -m "Add settings actions for afternoon summary, per-type toggles, default offset"
```

---

### Task 6: Settings UI — toggles, second time picker, offset/relative pickers

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: Define the offset + relative-to option constants**

Near the top of the file (file-level `private val`s, beside other constants):

```kotlin
private val REMINDER_OFFSET_OPTIONS = listOf(
    "None" to 0,
    "At due time" to -1,
    "5 min before" to 300,
    "15 min before" to 900,
    "30 min before" to 1800,
    "1 hour before" to 3600,
    "3 hours before" to 10800,
    "1 day before" to 86400,
)
private val REMINDER_RELATIVE_OPTIONS = listOf(
    "Due date" to "due_date",
    "Start date" to "start_date",
    "End date" to "end_date",
)
```

- [ ] **Step 2: Add second time-picker state + dialog**

Beside the existing `showTimePicker` state (~line 126):

```kotlin
var showAfternoonTimePicker by remember { mutableStateOf(false) }
var showOffsetPicker by remember { mutableStateOf(false) }
var showRelativePicker by remember { mutableStateOf(false) }
```

Duplicate the existing time-picker `AlertDialog` block (~267-297) as a second block guarded by `showAfternoonTimePicker`, initialized from `state.notificationPrefs.afternoonSummaryHour/Minute` and calling `viewModel.setAfternoonSummaryTime(h, m)` on confirm, `showAfternoonTimePicker = false` on dismiss.

- [ ] **Step 3: Add the new rows to `NotificationsTab`**

After the existing daily-summary time row block, add the afternoon controls and per-type toggles:

```kotlin
item(key = "notif_afternoon_enabled") {
    SwitchRow(
        label = "Afternoon Summary",
        description = "A second daily digest in the afternoon",
        checked = state.notificationPrefs.afternoonSummaryEnabled,
        onCheckedChange = { viewModel.setAfternoonSummaryEnabled(it) },
    )
}
if (state.notificationPrefs.afternoonSummaryEnabled) {
    item(key = "notif_afternoon_time") {
        TimePickerRow(
            label = "Afternoon Time",
            hour = state.notificationPrefs.afternoonSummaryHour,
            minute = state.notificationPrefs.afternoonSummaryMinute,
            onClick = { showAfternoonTimePicker = true },
        )
    }
}
item(key = "notif_overdue") {
    SwitchRow(
        label = "Include Overdue",
        description = "Count overdue tasks in the summary",
        checked = state.notificationPrefs.notifyOverdueEnabled,
        onCheckedChange = { viewModel.setNotifyOverdueEnabled(it) },
    )
}
item(key = "notif_due_today") {
    SwitchRow(
        label = "Include Due Today",
        description = "Count tasks due today in the summary",
        checked = state.notificationPrefs.notifyDueTodayEnabled,
        onCheckedChange = { viewModel.setNotifyDueTodayEnabled(it) },
    )
}
item(key = "notif_upcoming") {
    SwitchRow(
        label = "Include Upcoming",
        description = "Count tomorrow's tasks in the summary",
        checked = state.notificationPrefs.notifyUpcomingEnabled,
        onCheckedChange = { viewModel.setNotifyUpcomingEnabled(it) },
    )
}
item(key = "notif_default_offset") {
    val label = REMINDER_OFFSET_OPTIONS.firstOrNull {
        it.second == state.notificationPrefs.defaultReminderOffset
    }?.first ?: "None"
    SettingsValueRow(
        label = "Default Reminder",
        value = label,
        onClick = { showOffsetPicker = true },
    )
}
if (state.notificationPrefs.defaultReminderOffset != 0) {
    item(key = "notif_default_relative") {
        val label = REMINDER_RELATIVE_OPTIONS.firstOrNull {
            it.second == state.notificationPrefs.defaultReminderRelativeTo
        }?.first ?: "Due date"
        SettingsValueRow(
            label = "Relative To",
            value = label,
            onClick = { showRelativePicker = true },
        )
    }
}
```

> `SettingsValueRow` may not exist. If there is no clickable label/value row composable in this file, add this small private composable (matching `TimePickerRow`'s clickable-row styling):
> ```kotlin
> @Composable
> private fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
>     Row(
>         modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
>             .padding(horizontal = 16.dp, vertical = 14.dp),
>         horizontalArrangement = Arrangement.SpaceBetween,
>         verticalAlignment = Alignment.CenterVertically,
>     ) {
>         Text(label, style = MaterialTheme.typography.bodyLarge)
>         Text(value, style = MaterialTheme.typography.bodyMedium,
>             color = MaterialTheme.colorScheme.onSurfaceVariant)
>     }
> }
> ```

- [ ] **Step 4: Add the offset + relative-to selection dialogs**

Beside the time-picker dialogs, add radio-style selection dialogs:

```kotlin
if (showOffsetPicker) {
    AlertDialog(
        onDismissRequest = { showOffsetPicker = false },
        title = { Text("Default Reminder") },
        text = {
            Column {
                REMINDER_OFFSET_OPTIONS.forEach { (label, seconds) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setDefaultReminderOffset(seconds)
                                showOffsetPicker = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = seconds == state.notificationPrefs.defaultReminderOffset,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showOffsetPicker = false }) { Text("Close") } },
    )
}
if (showRelativePicker) {
    AlertDialog(
        onDismissRequest = { showRelativePicker = false },
        title = { Text("Relative To") },
        text = {
            Column {
                REMINDER_RELATIVE_OPTIONS.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setDefaultReminderRelativeTo(value)
                                showRelativePicker = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == state.notificationPrefs.defaultReminderRelativeTo,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showRelativePicker = false }) { Text("Close") } },
    )
}
```

Confirm imports: `androidx.compose.material3.RadioButton`, `androidx.compose.material3.AlertDialog`, `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.ui.Alignment`, `androidx.compose.foundation.clickable`, `Spacer`, `width`.

- [ ] **Step 5: Compile + manual check**

Run: `./gradlew installDebug`. Settings → Notifications: toggle afternoon (time row appears, picker works); toggle overdue/today/upcoming; pick a default reminder offset (relative-to row appears for non-None). Use the existing "Send test notification" to confirm the digest still fires.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/settings/SettingsScreen.kt
git commit -m "Add afternoon summary, per-type toggles, default reminder offset to Settings UI"
```

---

### Task 7: Default reminder synthesis util (TDD)

**Files:**
- Create: `app/src/main/java/com/rendyhd/vicu/util/DefaultReminder.kt`
- Test: `app/src/test/java/com/rendyhd/vicu/util/DefaultReminderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultReminderTest {
    @Test
    fun `offset zero returns null`() {
        assertNull(DefaultReminder.build("2026-06-01T09:00:00Z", 0, "due_date"))
    }

    @Test
    fun `null or sentinel due returns null`() {
        assertNull(DefaultReminder.build(null, 3600, "due_date"))
        assertNull(DefaultReminder.build("", 3600, "due_date"))
        assertNull(DefaultReminder.build("0001-01-01T00:00:00Z", 3600, "due_date"))
    }

    @Test
    fun `at due time uses zero relative period`() {
        val r: TaskReminder? = DefaultReminder.build("2026-06-01T09:00:00Z", -1, "due_date")
        assertEquals("2026-06-01T09:00:00Z", r?.reminder)
        assertEquals(0L, r?.relativePeriod)
        assertEquals("due_date", r?.relativeTo)
    }

    @Test
    fun `one hour before subtracts and negates`() {
        val r: TaskReminder? = DefaultReminder.build("2026-06-01T09:00:00Z", 3600, "due_date")
        assertEquals(-3600L, r?.relativePeriod)
        // reminder instant is one hour earlier
        assertEquals("2026-06-01T08:00:00Z", r?.reminder)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.DefaultReminderTest"`
Expected: FAIL with "Unresolved reference: DefaultReminder".

- [ ] **Step 3: Implement the util**

```kotlin
package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.TaskReminder
import java.time.Instant

object DefaultReminder {
    private const val NULL_DATE = "0001-01-01T00:00:00Z"

    /**
     * Synthesize a default reminder from the configured offset.
     * offsetSeconds: 0 = disabled, -1 = at due time, >0 = seconds before due.
     */
    fun build(dueDate: String?, offsetSeconds: Int, relativeTo: String): TaskReminder? {
        if (offsetSeconds == 0) return null
        if (dueDate.isNullOrBlank() || dueDate == NULL_DATE) return null

        if (offsetSeconds == -1) {
            return TaskReminder(reminder = dueDate, relativePeriod = 0, relativeTo = relativeTo)
        }
        val due = try { Instant.parse(dueDate) } catch (e: Exception) { return null }
        val trigger = due.minusSeconds(offsetSeconds.toLong())
        return TaskReminder(
            reminder = trigger.toString(),
            relativePeriod = -offsetSeconds.toLong(),
            relativeTo = relativeTo,
        )
    }
}
```

> If a project-wide null-date constant already exists (e.g. `Constants.NULL_DATE` or `DateUtils.isNullDate`), use it instead of the local `NULL_DATE`. `Instant.parse(...).toString()` round-trips ISO-8601 UTC, matching the test's expected `Z` form.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.DefaultReminderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/DefaultReminder.kt app/src/test/java/com/rendyhd/vicu/util/DefaultReminderTest.kt
git commit -m "Add DefaultReminder synthesis util (port of desktop buildDefaultReminder)"
```

---

### Task 8: Apply the default reminder at task creation

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt`

> This task wires Task 7's util into task creation. Read `TaskEntryViewModel.kt` first to locate where the `Task` is assembled and `taskRepository.create(...)` is called.

- [ ] **Step 1: Inject `NotificationPrefsStore`**

Add `private val notificationPrefsStore: com.rendyhd.vicu.data.local.NotificationPrefsStore,` to the `@HiltViewModel`/`@Inject` constructor parameter list.

- [ ] **Step 2: Synthesize and attach the reminder before creating the task**

At the point where the `Task` to create is finalized (it already has a resolved `dueDate` and a `reminders` list), insert — only when the user did not set reminders manually:

```kotlin
val prefs = notificationPrefsStore.getPrefs().first()
val taskToCreate = if (task.reminders.isEmpty()) {
    val synthesized = com.rendyhd.vicu.util.DefaultReminder.build(
        dueDate = task.dueDate,
        offsetSeconds = prefs.defaultReminderOffset,
        relativeTo = prefs.defaultReminderRelativeTo,
    )
    if (synthesized != null) task.copy(reminders = listOf(synthesized)) else task
} else {
    task
}
// ...then call taskRepository.create(taskToCreate) instead of create(task)
```

Add import `kotlinx.coroutines.flow.first`. Adapt variable names (`task`, `dueDate`, `reminders`) to the actual fields in `TaskEntryViewModel` — the field names come from `domain/model/Task.kt` (`dueDate: String`, `reminders: List<TaskReminder>`).

- [ ] **Step 3: Compile**

Run: `./gradlew compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification**

Set a non-None default reminder in Settings, create a task with a due date and no manual reminder, open it: it should carry one reminder at the configured offset; `AlarmScheduler` (already wired via the create→sync path) schedules it. Creating a task with no due date adds no reminder.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/ui/screens/taskentry/TaskEntryViewModel.kt
git commit -m "Synthesize default reminder when creating a task with a due date"
```

---

## Self-Review

- **Spec coverage:** afternoon summary (Tasks 1,2,4,5,6); per-type toggles gating the digest (Tasks 1,3,6); default reminder offset + relative-to (Tasks 1,6,7,8). Boot re-scheduling for the new slot (Task 4).
- **No placeholders:** all code concrete except the two explicitly-flagged adaptation points (Task 6 `SettingsValueRow` fallback; Task 8 field-name adaptation), each with the exact code to use.
- **Type consistency:** `DailySummaryScheduler.SLOT_MORNING/SLOT_AFTERNOON` used identically in Scheduler/ViewModel/BootReceiver; `NotificationPrefs` new field names match across store/VM/UI; `DefaultReminder.build(dueDate, offsetSeconds, relativeTo)` signature matches its single caller and tests.
- **Gotcha carried forward:** `AlarmScheduler.resolveReminderTime` currently bases relative offsets on `dueDate` only (ignores `relative_to` start/end). The default-reminder feature stores `relativeTo`, but start/end-date triggering is out of scope here; if needed later, update `resolveReminderTime` to honor `relativeTo`.
