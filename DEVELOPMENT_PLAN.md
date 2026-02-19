# Vicu Android — Claude Code Development Plan

## How to Use This Document

Phase-by-phase guide for building the Vicu Android app with Claude Code. Each phase has:
- **Goal**, **Mode** (plan/code), **Prompt(s)**, **Test checkpoint**, **Dependencies**

**Rules:**
- Start complex phases in **plan mode** — Claude Code reads CLAUDE.md, proposes, then implements
- Test each phase on a real device/emulator before proceeding
- Commit after each successful phase
- Keep `docs.json` (Vikunja API spec) in the project root

---

## Phase 0 — Project Scaffolding

**Goal**: Empty Android project with all dependencies, build config, and package structure.
**Mode**: `plan` → `code`

### Prompt 0.1 (plan)

```
Read CLAUDE.md thoroughly. Create a new Android project for Vicu:

1. Full package directory structure from CLAUDE.md
2. build.gradle.kts (app + project) with ALL dependencies from the tech stack. Use libs.versions.toml.
3. Configure: minSdk 26, targetSdk 35, compileSdk 35, Kotlin 2.1.20, Compose compiler plugin, KSP for Hilt+Room, kotlinx-serialization plugin, Hilt plugin
4. @HiltAndroidApp Application class
5. Minimal MainActivity with @AndroidEntryPoint and empty Compose scaffold
6. Theme.kt with Material 3 dynamic colors (light/dark/system) + blue fallback
7. Placeholder AppNavHost with empty routes: Setup, Inbox, Today, Upcoming, Anytime, Logbook, Projects, Settings, Search, TaskDetail
8. Bottom navigation bar with 5 tabs: Inbox, Today, Upcoming, Projects, Settings

Show the full libs.versions.toml and both build.gradle.kts files before coding.
```

### Prompt 0.2 (code)

```
Implement the plan. Verify the app compiles, launches, shows bottom nav with 5 tabs (each displaying placeholder text), and dark mode works by toggling device setting.
```

### Test Checkpoint 0
- [ ] `./gradlew assembleDebug` succeeds
- [ ] App launches, bottom nav shows 5 tabs, each switches content
- [ ] Dark/light mode works

---

## Phase 1 — Data Layer Foundation

**Goal**: Room database, DAOs, entities, Retrofit API service, repository interfaces.
**Mode**: `plan` → `code`

### Prompt 1.1 (plan)

```
Read CLAUDE.md and docs.json. Build the complete data layer:

**Room entities** (data/local/entity/):
- TaskEntity: ALL fields from models.Task in docs.json — id, title, description, done, done_at, due_date, priority, project_id, created, updated, created_by (embedded), labels (TypeConverter→JSON), reminders (TypeConverter→JSON), repeat_after, repeat_mode, percent_done, is_favorite, position, hex_color, bucket_id, attachments (TypeConverter→JSON), related_tasks (TypeConverter→JSON). Use Long for dates (epoch millis).
- ProjectEntity: id, title, description, hex_color, parent_project_id, position, is_archived, is_favorite, created, updated
- LabelEntity: id, title, hex_color, created, updated
- PendingActionEntity: id (autoGenerate), actionType (CREATE/UPDATE/DELETE/COMPLETE/ADD_LABEL/REMOVE_LABEL), entityType, entityId, payload (JSON), retryCount, maxRetries=5, status (PENDING/IN_PROGRESS/FAILED/COMPLETED), createdAt
- AttachmentEntity: id, taskId, fileName, mimeType, fileSize, createdAt

**DAOs**: TaskDao (getByFilter, getById, getByProjectId, getDueBeforeDate, getOverdue, getDone, search by title, upsert/upsertAll/deleteById — list queries return Flow), ProjectDao (getAll Flow, getById, getChildren, upsert/upsertAll/deleteById), LabelDao (getAll Flow, getById, upsertAll, deleteById), PendingActionDao (getPending Flow, getRetryable, insert, updateStatus, deleteCompleted, replaceForEntity), AttachmentDao (getByTaskId Flow, upsert, deleteById)

**VikunjaDatabase**: All entities, all DAOs, TypeConverters, version 1.

**Retrofit API** (data/remote/api/):
- VikunjaApiService: suspend functions for ALL endpoints in CLAUDE.md's Key Endpoints table
- DTO classes (@Serializable): TaskDto, ProjectDto, LabelDto, UserDto, OidcProviderDto, AttachmentDto, TaskRelationDto, ProjectViewDto, TaskPositionDto, plus request/response wrappers
- Mapper extensions: DTO↔Entity↔Domain

**Repository interfaces** (domain/repository/): TaskRepository, ProjectRepository, LabelRepository, AttachmentRepository

**Hilt modules**: DatabaseModule (DB + all DAOs), NetworkModule (OkHttpClient with logging, Retrofit, ApiService)

**NetworkResult** sealed class: Success<T>, Error(message, code), Loading

Show entity field lists and DAO signatures before coding.
```

### Prompt 1.2 (code)

```
Implement the data layer. Key requirements:
- TypeConverters: Long↔Date, List<LabelDto>↔JSON, List<TaskReminderDto>↔JSON, List<TaskRelationDto>↔JSON using kotlinx-serialization
- NULL_DATE_EPOCH constant for 0001-01-01T00:00:00Z in millis
- PendingActionDao.replaceForEntity: DELETE existing for same entityType+entityId before inserting (dedup)
- VikunjaApiService PUT for creates, POST for updates (Vikunja convention)
```

### Test Checkpoint 1
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Instrumented test: insert TaskEntity, query back, verify all fields including TypeConverter round-trips
- [ ] Instrumented test: insert + replace PendingAction deduplication works

---

## Phase 2 — Authentication (3 methods)

**Goal**: Setup flow with OIDC, password+TOTP, and API token login. Persistent auth with token refresh.
**Mode**: `plan` → `code` (most complex phase)

### Prompt 2.1 (plan)

```
Read CLAUDE.md's Authentication section and the desktop app's auth implementation. Build the complete auth system supporting ALL THREE methods:

**SecureTokenStorage** (auth/): Encrypted DataStore (Tink) for JWT, API token, provider key, Vikunja URL, auth method. Flow-based reads, suspend writes, clear().

**OidcHandler** (auth/):
- discoverProviders(url): GET /api/v1/auth/openid/callback → List<OidcProvider>
- buildAuthRequest(provider): AppAuth with PKCE, redirect vikunja://callback
- handleAuthResponse(response): extract auth code
- exchangeCodeForJwt(code, provider): POST /api/v1/auth/openid/{key}/callback → JWT

**PasswordLoginHandler** (auth/):
- login(url, username, password, totpPasscode?): POST /api/v1/login
- Handle HTTP 412 → return NeedsTOTP result so UI prompts for code
- Handle HTTP 403 → return InvalidCredentials
- On success: store JWT, create backup API token (fire-and-forget)

**AuthManager** (auth/):
- StateFlow<AuthState>: Authenticated/NeedsReAuth/Unauthenticated
- getToken(), getTokenSync(): JWT → refresh → API token → NeedsReAuth
- login(url, method, credentials), logout()
- loginPassword(url, username, password, totpPasscode?) with TOTP retry
- initialize(): check stored tokens at startup, schedule refresh

**AuthInterceptor**: Add Bearer token, proactive JWT refresh if <5min to expiry
**TokenAuthenticator**: Handle 401, Mutex for concurrent refresh, max 2 retries

**SetupScreen + SetupViewModel**:
- Step 1: Enter Vikunja URL → "Continue"
- Step 2: Discover auth methods (GET /api/v1/info for local_enabled + openid_connect.enabled)
  - If OIDC: show "Sign in with {provider}" buttons
  - If local auth enabled: show "Sign in with password" option
  - Always show "Use API token instead" link
- Step 3a (OIDC): Launch AppAuth → success → fetch projects
- Step 3b (Password): Username + password fields → submit → if 412 → Step 3b-TOTP
- Step 3b-TOTP: TOTP code field → submit → success → fetch projects
- Step 3c (API token): Enter token → test connection → fetch projects
- Step 4: Select inbox project → "Save & Start"

**AndroidManifest.xml**: AppAuth redirect activity with vikunja://callback intent filter

Show class signatures and the full setup screen step flow before coding.
```

### Prompt 2.2 (code)

```
Implement the auth plan. Critical details:
- OIDC redirect URI: "vikunja://callback" 
- OIDC discovery is GET {url}/api/v1/auth/openid/callback (NOT .well-known)
- Auth method discovery is GET {url}/api/v1/info → check auth.local.enabled and auth.openid_connect.enabled
- Password login body: { "username": "...", "password": "...", "long_token": true }
- TOTP retry: same body + "totp_passcode": "..."
- JWT parsing: manual base64 decode of middle segment to read "exp" claim
- After setup success, navigate to InboxRoute and clear backstack
- On app startup: check AuthManager state → Unauthenticated = SetupRoute, else InboxRoute
```

### Test Checkpoint 2
- [ ] Setup screen shows on first launch
- [ ] URL entry → discovers auth methods correctly
- [ ] OIDC login: Chrome Custom Tab → auth → returns with JWT
- [ ] Password login: credentials → success. Wrong password → error. TOTP required → TOTP step → success
- [ ] API token login: enter token → test → projects load → inbox selection
- [ ] Auth persists across restart
- [ ] Logout clears tokens, returns to setup
- [ ] Force-expire JWT → falls back to API token or prompts re-auth

---

## Phase 3 — Core Screens (Inbox, Today, Upcoming)

**Goal**: Three main screens with real Vikunja data, pull-to-refresh, FAB, task completion with undo.
**Mode**: `code`

### Prompt 3.1

```
Build Inbox, Today, and Upcoming screens with real data. Follow offline-first from CLAUDE.md:

**TaskRepositoryImpl**: For each view, return Flow<List<Task>> from Room. Background refresh fetches from API → upserts into Room. Use filter strings from CLAUDE.md's Smart List mapping. Handle Go zero-value gotcha on all updates.

**ViewModels**: UiState data class (tasks, isLoading, isRefreshing, error). On init: observe Room Flow + trigger background refresh. Expose refresh() for pull-to-refresh.

**TaskItem composable**: Checkbox (simple for now), title, subtitle (project name), due date badge (red=overdue, orange=today, gray=future), priority dot (colored circle by priority level), recurrence icon (Repeat), attachment icon (Paperclip), reminder icon (Bell). All indicators matching desktop TaskRow.

**Today screen**: Group into "Overdue" (red header) and "Today" sections with sticky headers.
**Upcoming screen**: Group by date headers (Tomorrow, weekday names, full dates for later).
**Inbox**: Flat list sorted by created desc.

**Empty states**: Centered icon + title + subtitle.
**PullToRefreshBox** on all screens.
**FAB**: Blue circle "+" bottom-right. Toast on click for now.

**Task completion**: Tap checkbox → optimistic Room update (done=true, done_at=now) → API call with FULL task object → on failure queue PendingAction. Show Snackbar "Task completed" with "Undo". Keep completed task visible with strikethrough until navigation (CompletedTasksMap in ViewModel).

**Uncomplete**: From Logbook, allow uncomplete (done=false). Same optimistic pattern.
```

### Test Checkpoint 3
- [ ] Inbox shows tasks from inbox project
- [ ] Today shows overdue + today tasks in sections
- [ ] Upcoming shows future tasks grouped by date
- [ ] Pull-to-refresh works
- [ ] Checking task → strikethrough + Snackbar with Undo
- [ ] Undo reverts completion
- [ ] Empty states display
- [ ] Offline: airplane mode shows cached tasks
- [ ] Priority dots, recurrence icons, attachment icons show correctly

---

## Phase 4 — Remaining List Screens + Navigation

**Goal**: Anytime, Logbook, Project (with sections), Tag, Custom List, Search, Project list.
**Mode**: `code`

### Prompt 4.1

```
Build all remaining list screens:

**AnytimeScreen**: All open tasks excluding inbox, grouped by project name (collapsible headers).

**LogbookScreen**: Completed tasks sorted by done_at desc. Show completion date. Support uncomplete.

**ProjectScreen(projectId)**: 
- Fetch project views (GET /projects/{id}/views) to find "list" view → use its viewId
- Fetch tasks through view API for position-based sorting
- Show parent project tasks at top
- Show child projects as collapsible SECTIONS (SectionHeader with title, task count, animated chevron)
- Each section contains that child project's tasks
- "Add section" button at bottom → creates child project
- Drag-to-reorder tasks within section (long-press to start, position update via API)
- Move tasks between sections via long-press → "Move to..." action sheet

**TagScreen(labelId)**: Open tasks filtered by label, grouped by project.

**CustomListScreen(listId)**: Build filter query from stored CustomList.filter config (project_ids, due_date_filter, sort_by, order_by, include_done, include_today_all_projects, label_ids). Use same filter-to-query logic as desktop CustomListView.tsx.

**SearchScreen**: Material 3 SearchBar, debounced 300ms, GET /tasks/all?s={query}. Recent searches in DataStore.

**ProjectListScreen** (Projects tab): All projects as list with color dot, title, task count. Nested hierarchy with indentation. Favorites section at top. Tap → ProjectScreen.
```

### Prompt 4.2

```
Build navigation and deep links:
- All screens wired into bottom nav (Projects tab = ProjectListScreen)
- Deep links: vikunja://task/{id}, vikunja://today, vikunja://task/new
- Back button behavior correct across all screens
- SavedStateHandle preserves scroll position
- Custom list management: add route for CustomListScreen(listId)
```

### Test Checkpoint 4
- [ ] All 5 bottom nav tabs work
- [ ] Anytime groups by project with collapsible headers
- [ ] Logbook shows completed tasks, uncomplete works
- [ ] Project screen shows sections (child projects) with tasks
- [ ] Task ordering matches Vikunja view positions
- [ ] Tag screen filters correctly
- [ ] Custom list applies filter correctly
- [ ] Search returns debounced results
- [ ] Project list shows hierarchy + favorites

---

## Phase 5 — Task Entry & Detail Sheet

**Goal**: FAB opens task entry. Tapping task opens full detail sheet with all editing capabilities.
**Mode**: `plan` → `code`

### Prompt 5.1 (plan)

```
Design the task entry and detail sheets matching desktop's expanded TaskRow functionality:

**TaskEntrySheet** (ModalBottomSheet via FAB):
- Auto-focused title TextField
- Optional description below
- Action chips row: Date, Project, Labels, Reminder, Priority
- "!" prefix → strip, set due_date to today
- Default project = inbox (or current project if on ProjectScreen)
- Save on tap or Enter, dismiss on swipe

**TaskDetailSheet** (ModalBottomSheet via task tap):
- Editable title + description (auto-resize)
- **Labels section**: colored chips, "+" to open LabelPickerSheet (shows all labels with checkboxes, "Create new" at bottom with name + color picker)
- **Due date**: badge tap → DatePickerSheet (Today, Tomorrow, Next Week, Pick date...)
- **Reminders**: list of active reminders, "+" → ReminderPickerSheet (pick absolute time, or relative period before due date)
- **Priority**: colored dot, tap cycles 0→1→2→3→4→0
- **Project**: chip, tap → ProjectPickerSheet (dropdown of all projects)
- **Subtasks section**: list of related tasks (relation_kind: subtask), each with checkbox + title. "Add subtask" input at bottom → creates task + relation.
- **Attachments section**: file list (icon + name + size), "Add" button → file picker (multipart upload), tap to open/share, swipe/button to delete
- **Recurrence display**: read-only formatted label ("Every 2 weeks", "Monthly on the 15th")
- **Delete button** with confirmation AlertDialog
- Auto-save on dismiss: diff fields, send full object if changed

Show the layout and all sub-sheet designs before coding.
```

### Prompt 5.2 (code)

```
Implement all sheets. Critical details:
- Modifier.imePadding() on all sheets
- FocusRequester auto-focus title in TaskEntrySheet
- "!" prefix: strip from title, set due_date to end of today
- Go zero-value: always send full task on update
- Labels: PUT /tasks/{id}/labels to add, DELETE /tasks/{id}/labels/{labelId} to remove
- Subtasks: PUT /tasks/{id}/relations with { other_task_id, relation_kind: "subtask" }
- Attachments: multipart PUT /tasks/{id}/attachments, open via share intent
- ReminderPickerSheet: support both absolute time (DateTimePicker) and relative period (dropdown: "At due date", "5 min before", "15 min before", "1 hour before", "1 day before")
- LabelPickerSheet inline create: text field + color grid → PUT /labels → add to task
- Wire FAB on ALL list screens to open TaskEntrySheet
```

### Test Checkpoint 5
- [ ] FAB opens entry sheet, keyboard auto-focuses
- [ ] Create task with title → appears in correct list
- [ ] Set date, project, labels, priority during creation
- [ ] "!" prefix sets today
- [ ] Tap task → detail sheet with all fields populated
- [ ] Edit title, description, due date, project, priority — all save on dismiss
- [ ] Add/remove labels
- [ ] Add/remove reminders
- [ ] Create subtask → appears in subtask section
- [ ] Upload attachment → appears in list. Download/open works. Delete works.
- [ ] Delete task with confirmation
- [ ] Offline: creating a task works (queued)

---

## Phase 6 — Labels CRUD & Custom Lists

**Goal**: Full label management and custom list create/edit/delete.
**Mode**: `code`

### Prompt 6.1

```
Build full label management and custom lists:

**Label management** (accessible from Settings > Labels section):
- List all labels with color dot + title
- Create: name + hex color picker → PUT /labels
- Edit: tap label → edit name/color → PUT /labels/{id}
- Delete: swipe or button → confirmation → DELETE /labels/{id}
- LabelRepositoryImpl: CRUD with Room + API sync

**Custom list management** (accessible from Settings > Custom Lists or Projects tab):
- CustomListDialog (matching desktop's CustomListDialog.tsx):
  - Name input
  - Project filter (multi-select from project list)
  - Due date filter dropdown (All, Overdue, Today, This week, This month, Has due date, No due date)
  - Label filter (multi-select)
  - Sort by (due_date, created, updated, priority) + order (asc/desc)
  - Include completed toggle
  - Include today from all projects toggle
- Create → store in DataStore, show in sidebar/projects list
- Edit → update in DataStore
- Delete → remove from DataStore
- Custom lists appear in Projects tab and in widget config picker
```

### Test Checkpoint 6
- [ ] Create label with name + color → appears in label picker
- [ ] Edit label name/color
- [ ] Delete label → removed from all task displays
- [ ] Create custom list with filters → shows tasks matching filter
- [ ] Edit custom list → filter updates
- [ ] Delete custom list
- [ ] Custom lists appear in widget config options

---

## Phase 7 — Notifications & Reminders

**Goal**: Daily summaries, per-task exact reminders, notification actions.
**Mode**: `plan` → `code`

### Prompt 7.1 (plan)

```
Read CLAUDE.md's Notifications section and desktop's src/main/notifications.ts. Build the complete system:

**NotificationChannelManager**: 4 channels at app startup.

**AlarmScheduler**: 
- scheduleTaskReminder(taskId, title, triggerTimeMillis): setExactAndAllowWhileIdle
- cancelTaskReminder(taskId)
- scheduleAllReminders(): read all tasks with future reminders from Room
- checkExactAlarmPermission(): prompt if needed

**AlarmReceiver**: show notification on alarm, actions: "Mark Complete", "Snooze 15min". Tap → TaskDetailRoute.

**BootReceiver**: RECEIVE_BOOT_COMPLETED → expedited WorkRequest to reschedule all alarms.

**DailySummaryWorker**: Fetch overdue/today/upcoming from Room. ≤3 → individual, >3 → InboxStyle summary. Clicking → TodayRoute.

**RescheduleRemindersWorker**: Called by BootReceiver, after task sync, and after settings change.

**NotificationActionReceiver**: Mark Complete (toggle done via repository), Snooze (re-schedule +15min).

**Settings** (matching desktop NotificationSettings.tsx):
- notifications_enabled, daily_reminder_enabled + time, secondary_reminder_enabled + time
- overdue/today/upcoming toggles, sound toggle, persistent toggle
- Test notification button

Show all receiver intent actions and WorkManager scheduling.
```

### Prompt 7.2 (code)

```
Implement notifications. Critical:
- Register all receivers in manifest
- SCHEDULE_EXACT_ALARM: check canScheduleExactAlarms(), dialog explaining why, link to Settings
- DailySummaryWorker: PeriodicWorkRequest(24h) with calculateInitialDelay to user's morning time
- After any task mutation that changes reminders, call scheduleAllReminders()
- NotificationChannelManager.createChannels() in Application.onCreate()
- Notification click: TaskStackBuilder for proper back stack
```

### Test Checkpoint 7
- [ ] Channels appear in system notification settings
- [ ] Test notification button works
- [ ] Task with reminder → alarm fires at correct time (test with 1 min from now)
- [ ] Notification actions: Mark Complete works, Snooze reschedules
- [ ] Tap notification → opens task detail
- [ ] Daily summary fires at configured time
- [ ] Reboot → reminders still fire
- [ ] Settings changes take effect immediately

---

## Phase 8 — Home Screen Widget

**Goal**: Glance widget with configurable view, task display, and quick actions.
**Mode**: `plan` → `code`

### Prompt 8.1 (plan)

```
Read CLAUDE.md's widget section. Build the Glance widget:

**TaskListWidget**: SizeMode.Responsive (compact: count only, medium: 3 tasks, large: scrollable list). Header with view name + count. Tap task → detail, tap header → new task, checkbox → complete.

**TaskWidgetState** (@Serializable): selectedViewType, tasks (id, title, projectName, dueDate, done), lastUpdated.

**WidgetConfigActivity**: Dropdown — Today, Inbox, Upcoming, Anytime, specific project, custom list. Save → first update.

**Updates**: updatePeriodMillis=30min, WorkManager periodic 15min, immediate after task mutations.

Show Glance composable structure and XML declarations.
```

### Prompt 8.2 (code)

```
Implement widget. CRITICAL Glance details:
- Import from androidx.glance.* NOT androidx.compose.*
- GlanceModifier, not Modifier
- actionStartActivity for deep links, actionRunCallback<ToggleTaskCallback>() for checkbox
- State from Room (NOT API — widget must work offline)
- WidgetConfigActivity: get glanceId from GlanceAppWidgetManager using appWidgetId from intent
- Custom lists must be available in config dropdown
```

### Test Checkpoint 8
- [ ] Widget in picker, config activity on place
- [ ] Displays tasks from selected view
- [ ] Tap task → app opens to detail
- [ ] Tap header → new task mode
- [ ] Checkbox completes task
- [ ] Updates when tasks change in app
- [ ] Dark mode support
- [ ] Works after reboot
- [ ] Different instances show different views
- [ ] Custom list option works in config

---

## Phase 9 — Offline Sync Queue

**Goal**: Robust offline support, pending action queue, automatic retry, sync indicator.
**Mode**: `code`

### Prompt 9.1

```
Build the complete sync system:

**SyncWorker** (CoroutineWorker): Triggered by periodic (15min + network constraint), connectivity change, manual. Process:
1. Get retryable pending actions (PENDING or FAILED with retryCount < max)
2. For each (ordered by createdAt): set IN_PROGRESS, attempt API, handle success/retriable-fail/permanent-fail/401
3. After queue: full refresh of tasks/projects/labels from API
4. Update widgets

**SyncManager**: triggerSync(), observeSyncStatus() StateFlow (Idle/Syncing/Error/LastSync)

**Repository updates**: Every mutation tries API → on network failure queues PendingAction. Dedup via replaceForEntity. After create: replace temp negative-ID with real server ID.

**Connectivity observer**: ConnectivityManager.NetworkCallback → trigger sync

**UI**: SyncStatusIndicator composable (in app bar or bottom nav) showing pending count
```

### Test Checkpoint 9
- [ ] Airplane mode: create/complete/edit tasks → changes saved locally
- [ ] Network restored → SyncWorker syncs all pending
- [ ] Server has correct data after sync
- [ ] Duplicate edits offline → single API call
- [ ] Sync indicator shows pending count
- [ ] Permanent failures removed from queue

---

## Phase 10 — Settings Screen

**Goal**: Full settings matching desktop's 3-tab layout.
**Mode**: `code`

### Prompt 10.1

```
Build Settings with 3 tabs:

**General tab**:
- Connection: Vikunja URL, auth status display (OIDC → "Signed in via SSO" + username, Password → "Signed in as {username}", API token → masked token + test button), Sign Out, inbox project selector
- Appearance: Theme (Light/Dark/System segmented button)
- Labels: full CRUD list (links to label management from Phase 6)
- Custom Lists: list + create/edit/delete (links to custom list management from Phase 6)
- Data: clear cache + re-sync button, pending actions count + "Sync now" button

**Notifications tab**: Full notification settings from Phase 7

**Gestures tab** (replaces desktop's keyboard shortcuts):
- Visual guide showing: swipe right = complete, swipe left = schedule, tap = detail, long-press = reorder, FAB = new task

Auto-save on change. Theme changes apply immediately. Sign Out clears everything.
```

### Test Checkpoint 10
- [ ] All settings display with current values
- [ ] Theme switch applies immediately
- [ ] Auth status shows correctly for each method
- [ ] Sign Out clears and returns to setup
- [ ] Clear cache empties Room, triggers re-sync
- [ ] Sync now processes queue
- [ ] Settings persist across restart

---

## Phase 11 — Polish & Animations

**Goal**: Things 3-quality interactions.
**Mode**: `code`

### Prompt 11.1

```
Add Things 3-inspired animations:

**Animated Checkbox**: Canvas, circle fill (spring, MediumBouncy), checkmark path reveal (PathMeasure, 300ms), scale bounce (1.0→1.15→0.95→1.0, 400ms), gray→green. Haptic on tap.

**Swipe gestures**: SwipeToDismissBox — right=complete (green+check), left=schedule (orange+calendar→DatePickerSheet). 35% threshold. Animate row out on complete.

**Section animations**: Chevron rotation 0°→90°, animateItem() on LazyColumn, AnimatedVisibility expand/collapse.

**FAB**: ExtendedFAB "New Task" collapses to icon-only on scroll, animateContentSize().

**Pull-to-refresh**: Themed indicator color per screen.

**Bottom nav**: Badge animations on count change.

**Share intent receiver**: Add intent filter for text/plain → create task from shared text (maps to desktop's Ctrl+V paste-as-task).
```

### Prompt 11.2

```
Visual polish:
- Consistent spacing: 16dp horizontal, 8dp item gap, 24dp section spacing
- Typography: headlineMedium for titles, bodyLarge for task titles, bodyMedium for subtitles, labelSmall for badges
- Due date badges: red pill=overdue, orange=today, gray=future, green=completed
- Project color dots (8dp circle), label colored chips with contrast text
- Loading: shimmer effect, error: retry button
- Navigation transitions: MaterialSharedAxisZ
- Splash screen: SplashScreen API
- App icon: adaptive icon
```

### Test Checkpoint 11
- [ ] Checkbox animation smooth
- [ ] Swipe to complete/schedule works
- [ ] Section expand/collapse smooth
- [ ] FAB expands/collapses on scroll
- [ ] Share intent creates task from shared text
- [ ] Dark mode polished
- [ ] Splash screen + app icon correct
- [ ] No jank on mid-range device

---

## Phase 12 — Integration Testing & Release

**Goal**: E2E testing, performance, release readiness.
**Mode**: `code`

### Prompt 12.1

```
Release prep:

**Tests**:
- Compose UI tests: setup flow (all 3 auth methods), task CRUD, task detail editing, label CRUD
- Unit tests: filter builders, DateUtils, RecurrenceUtils, NetworkResult
- Room DAO tests: all entities, filter queries, pending action dedup

**Performance**: Profile for recompositions, @Stable on data classes, key={task.id} on all LazyColumns, widget battery impact.

**Release config**: Signing, R8 (minify+shrink), ProGuard rules (Retrofit DTOs, Room entities, kotlinx-serialization, AppAuth), adaptive icon, version metadata.
```

### Test Checkpoint 12
- [ ] All tests pass
- [ ] Release APK builds + runs full flow
- [ ] No crashes in 30min active use
- [ ] APK < 10MB
- [ ] Stable memory

---

## Phase Dependencies

```
Phase 0 (Scaffold)
  └→ Phase 1 (Data Layer)
      ├→ Phase 2 (Auth) ← CRITICAL PATH
      │   └→ Phase 3 (Core Screens)
      │       ├→ Phase 4 (All Screens + Sections)
      │       ├→ Phase 5 (Task Entry + Detail)
      │       │   └→ Phase 6 (Labels + Custom Lists)
      │       └→ Phase 9 (Sync Queue)
      ├→ Phase 7 (Notifications) ← after Phase 3
      └→ Phase 8 (Widget) ← after Phase 3
Phase 10 (Settings) ← after Phases 6, 7
Phase 11 (Polish) ← after Phase 5
Phase 12 (Release) ← after everything
```

Phases 7, 8, 9 can run **in parallel** after Phase 3.

---

## Claude Code Tips

1. **Always plan mode for**: Phase 2 (Auth, 3 methods), Phase 5 (detail sheet, many sub-components), Phase 7 (notifications), Phase 8 (widget)
2. **Reference docs.json** for exact API shapes when implementing service calls
3. **Test incrementally** — each prompt should produce something testable
4. **Glance (Phase 8)**: Remind Claude that Glance composables ≠ regular Compose
5. **Password login 412**: This is the TOTP signal, not an error — handle it as a flow step
6. **Position-based reorder**: Must fetch viewId first from project views API
7. **Subtasks**: Task relations with `relation_kind: "subtask"`, not a separate table
8. **Keep CLAUDE.md updated** if architecture decisions change during development
