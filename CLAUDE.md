# CLAUDE.md - Vicu Android

This file provides guidance to Claude Code when working on the Vicu Android app.

## Project Overview

**Vicu Android** - a native Android task manager powered by [Vikunja](https://vikunja.io/) as the backend. Kotlin, Compose Multiplatform UI and Material 3. It is the mobile companion to the Vicu desktop app (Electron + React + TypeScript) and the two apps ship together (both are 1.10.0 at the time of writing). Both talk to the same Vikunja server and must interpret synced data the same way (see "Cross-app contract").

**Design inspiration**: Things 3. Clean, minimal UI with generous whitespace, collapsible sections, circular animated checkboxes, and a prominent FAB (+) for quick task entry.

### Reference
The desktop app is at `C:\Users\rendy\vscode\vicu`. Consult its source when you need implementation details not covered here. The shared documents (`docs/cross-app-semantics-v1.md`, `docs/description-format-v1.md`) and the fixtures in `test-fixtures/` are kept identical in both repos.

## Commands

```bash
./gradlew test                  # All JVM unit tests: :app and :shared (commonTest + androidHostTest)
./gradlew lint                  # Android lint (see the note below)
./gradlew assembleDebug         # Debug APK (applicationId com.rendyhd.vicu.debug, installs beside the release)
./gradlew :app:assembleRelease  # Release APK with R8 (signs when keystore.properties exists)
./gradlew installDebug          # Build and install on a connected device or emulator
./gradlew connectedAndroidTest  # Instrumented tests (app/src/androidTest; needs a device)

./gradlew :shared:testAndroidHostTest --tests "com.rendyhd.vicu.worker.SyncEngineFailedActionsTest"
./gradlew :app:testDebugUnitTest --tests "com.rendyhd.vicu.di.KoinGraphTest"
```

The gate for a change is `./gradlew test lint assembleDebug`; CI runs exactly that, and a release additionally builds `:app:assembleRelease`. Use JDK 17 or newer (CI uses Temurin 17; Android Studio's bundled JDK works locally).

Lint runs on `:app` (the shared module uses the AGP Kotlin Multiplatform library plugin, which has no lint task for its main sources). There is no lint baseline: keep `./gradlew lint` free of errors. There is no ktlint or detekt.

## Technology Stack

| Layer | Library | Version |
|-------|---------|---------|
| Language | Kotlin | 2.3.21 |
| Build | Android Gradle Plugin / Gradle | 9.0.1 / 9.1.0 |
| UI | Compose Multiplatform (runtime, foundation) + Material 3 | 1.11.1 / 1.9.0 |
| App-only UI | Jetpack Compose BOM (widgets, tooling) | 2026.01.01 |
| Navigation | Navigation Compose (JetBrains), type-safe `@Serializable` routes | 2.9.2 |
| DI | Koin (core, android, androidx-workmanager, compose) | 4.0.2 |
| Networking | Ktor client (OkHttp engine on Android, Darwin on iOS) | 3.0.1 |
| Serialization | kotlinx-serialization-json | 1.11.0 |
| Dates | kotlinx-datetime | 0.6.2 |
| Local DB | Room (KMP, bundled SQLite, KSP) | 2.7.1 |
| Preferences | DataStore Preferences | 1.1.4 |
| Background | WorkManager | 2.11.0 |
| Widgets | Jetpack Glance | 1.1.1 |
| Images | Coil 3 (OkHttp fetcher) | 3.3.0 |
| Token storage | Tink Android (AEAD over DataStore) | 1.19.0 |
| Description editor | Cascade editor | 1.9.0 |
| Drag and drop | Reorderable | 2.4.3 |

Versions live in `gradle/libs.versions.toml`. Compose Multiplatform dependencies are direct coordinates (the plugin's `compose.*` accessors are deprecated and the plugin is not applied). There is no Hilt, Retrofit or AppAuth. Annotation processing is KSP only (Room); never kapt. The KSP version must match the Kotlin version line.

## Module layout

Two Gradle modules:

- **`:shared`** - Kotlin Multiplatform library (`com.android.kotlin.multiplatform.library` plugin, namespace `com.rendyhd.vicu.shared`). Almost all code lives in `commonMain`: data layer, sync engine, repositories, ViewModels, Compose screens and components. `androidMain` has the platform actuals (token storage, DataStore and Room builders, files, network monitor, OIDC launcher, pickers). `iosMain` has stubs (see `docs/ios-status.md`). Tests: `commonTest` (the bulk, run on the JVM) and `androidHostTest` (SQL against sqlite-jdbc using the exported Room schemas).
- **`:app`** - the Android application (`com.rendyhd.vicu`): `MainActivity`, `VicuApplication`, the Android DI modules, alarms and notifications, Glance widgets, WorkManager workers, the OIDC login activity, the quick-add tile. Tests: `src/test` (JVM) and `src/androidTest` (instrumented).

Package map (`com.rendyhd.vicu`):

```
shared/commonMain
  auth/               AuthManager, AccountSession, SessionCleanup, PasswordLoginHandler, OidcHandler, Totp, TokenStorage
  data/local/         VikunjaDatabase (Room), DAOs, entities, migrations, DataStore preference stores, LocalDataWiper
  data/remote/        KtorClientFactory, BaseUrlHolder, api/VikunjaApiService, DTOs, MergePatches
  data/mapper/        DTO <-> entity <-> domain mappers
  data/repository/    *RepositoryImpl, ListPositioner, RoutineArchiveStore
  data/sync/          TaskRefresher, LabelRefresher, ProjectRefresher, ScreenRefresher, CarrierFinder, SyncStaleness
  domain/             models and repository interfaces (pure Kotlin)
  di/                 KoinModules.kt (shared modules)
  ui/                 VicuApp, navigation/, screens/<name>/(Screen + ViewModel), components/, theme/
  util/               DayClock, DueDates, DateUtils, RecurrenceUtils, parser/ (quick-add), routine*, reminder*, envelopes
  worker/             SyncEngine, PeriodicSyncRunner, DailySummaryRun (platform-free work run by the Android workers)
app/main
  di/AppModule.kt     Android modules (appModule, viewModelModule, workerModule)
  notification/       AlarmScheduler, DailySummaryScheduler, RoutineAlarmScheduler, receivers, channels
  widget/             TaskListWidget, RoutineWidget, their state, receivers and workers
  worker/             SyncWorker, PeriodicSyncWorker, DailySummaryWorker, TokenRefreshWorker, RoutineMaintenanceWorker
  auth/               OidcLoginActivity (WebView) and Android auth hooks
```

## Architecture

### MVVM with unidirectional data flow

```
UI (Compose)  -> observes StateFlow<UiState> from a ViewModel
ViewModel     -> calls repository functions, reads repository Flows
Repository    -> Room is the source of truth; writes are optimistic and queued; refreshers pull from the server
Remote        -> VikunjaApiService over a Ktor client; AuthManager supplies the token
```

### Dependency injection (Koin)

- `sharedModules` (in `shared/.../di/KoinModules.kt`): `databaseModule`, `networkModule`, `repositoryModule`, `commonModule`. `androidAppModules` (in `app/.../di/AppModule.kt`): `appModule` (Android implementations of the platform interfaces, schedulers, OkHttp and Coil), `viewModelModule` (`viewModelOf`), `workerModule` (`workerOf`). `VicuApplication` starts them together.
- Definitions are positional lambdas such as `TaskRepositoryImpl(taskDao = get(), ...)`. Adding a constructor parameter to a ViewModel, repository, refresher or worker means wiring it in the module as well. The compiler does not check that.
- **`KoinGraphTest`** (`app/src/test/.../di/`) builds every single, factory and ViewModel through Koin's `checkModules`, with only the Android edges replaced (empty Context, generated Room class with empty DAOs, a network monitor that registers nothing), and resolves each worker constructor argument. Run it after any DI change. A missing binding fails it with the missing type.
- Platform differences go through small interfaces implemented in `app` or `androidMain`: `PlatformRepositoryHooks` (alarms, widgets, sync trigger, completion sound), `PlatformAuthHooks`, `PlatformSettingsHooks`, `PlatformFiles`, `NetworkMonitor`, `TokenStorage`.
- Shared singletons worth knowing: `DayClock` (the local day as a flow), `TimeSource` (now and zone, replaced by fakes in tests), `AppMessages` (app-wide snackbar), `NavigationTicker`, `ScreenRefresher` and `SyncStaleness`, `ListPositioner`, `CarrierFinder`, `AppDispatchers`.

### Local data (Room)

`VikunjaDatabase` (database file `vicu_database`) is at **schema version 3**: `tasks` (with an indexed `isMetadata` column that marks hidden carrier and archive tasks so list queries can skip them in SQL), `projects`, `labels`, `pending_actions`, `attachments` and `routine_occurrence_archive`. Migrations are in `DatabaseMigrations.kt`. Room exports the schema to `shared/schemas/` through the KSP argument `room.schemaLocation` set in `shared/build.gradle.kts`; commit the new `N.json` with any schema change, add a `Migration`, and extend `TaskMetadataMigrationTest`-style tests (they run the exported schema in sqlite-jdbc). Preference stores (`*PrefsStore`, `CustomListStore`, `SyncCursorStore`, ...) are DataStore files created through `createDataStore`.

### Offline-first and sync

- Every write goes to Room first (optimistic) and, when it must reach the server, into the `pending_actions` queue. Entity types are `task`, `label` and `project`. Updates are queued as JSON merge patches (only the changed fields); a later patch for the same entity is merged into the queued one (`queuePatchActionMerging`, `mergePatchPayloads` in `QueueMerge.kt`), and an old queue entry that still holds a whole object is turned into a patch when it is replayed. Project edits, archiving and review results are queued offline the same way as task edits. A change to an offline-created task is folded into its still-pending create. Offline-created tasks get negative temp ids from a persisted, decreasing counter (`TempIdGenerator`); `SyncEngine` remaps them when the create succeeds.
- `SyncEngine.performSync()` replays the queue (creates first) and then refreshes. **One sync runs at a time for the whole process** (a companion mutex; `SyncEngine.exclusive` for operations that rewrite the queue or wipe data). A 404 only drops a queued change when a follow-up request proves the thing is gone. Actions that fail permanently are kept as failed for 14 days; the app shows a banner with retry and discard. A session that needs sign-in leaves the queue untouched.
- Triggers: `SyncWorker` (immediate and when online), `PeriodicSyncWorker` (every 30 minutes; also repairs reminder alarms and widgets, even when the server is unreachable), and the screens' own refresh through `ScreenRefresher`. Task refresh is incremental (`SyncCursorStore`); the Logbook loads in pages.
- `LocalDataWiper` is the only place that clears local data. Work that has not reached the server survives a re-login, a logout and "clear cache" unless the user explicitly discards it or the account is gone; `SessionCleanup` and the Settings dialogs enforce the confirmation.
- Completed tasks stay visible, struck through, in the current screen for 5 s or until the user navigates away (`CompletionHold` in the screen's ViewModel; the row also stays put through a rotation), and a collapsed row joins one app-wide "Completed, Undo" snackbar (`CompletionToastCenter`). See "Design system, theme and motion".

### Vikunja API v2

Base URL `<server>/api/v2/` (a server in a sub-path works). The authoritative spec is the server's `GET /api/v2/openapi.json` (Vikunja 2.4.0 or newer is required; the desktop repo keeps a copy as `api-docs.json`). `docs/api-v2-migration-report.md` has the endpoint-by-endpoint migration from v1. The old v1 `docs.json` was removed.

- List endpoints answer with a pagination envelope; `VikunjaApiService` unwraps it and fetches all pages.
- Create is `POST` (HTTP 201), update is `PATCH` with JSON Merge Patch (`MergePatches` builds the patch from the previous and current object and only includes writable fields), delete returns 204 with no body.
- **Go zero values**: sending a field you did not mean to change zeroes it on the server, so never send a whole object back; send only what changed. To clear a due date send `"due_date": null`.
- The null date is `0001-01-01T00:00:00Z`; exclude it from due-date filters.

| Action | Method | Endpoint (relative to `/api/v2/`) |
|--------|--------|----------|
| List / search tasks | GET | `tasks?filter=...&sort_by=...&order_by=...&q=...&expand=subtasks` |
| Create / update / delete task | POST / PATCH / DELETE | `projects/{id}/tasks`, `tasks/{id}` |
| Projects and labels | GET / POST / PATCH / DELETE | `projects`, `projects/{id}`, `labels`, `labels/{id}` |
| Add / remove label on a task | POST / DELETE | `tasks/{id}/labels`, `tasks/{id}/labels/{labelId}` |
| Attachments | GET / POST / DELETE | `tasks/{id}/attachments`, `tasks/{id}/attachments/{attId}` (download streams) |
| Subtask relations | POST / DELETE | `tasks/{id}/relations`, `tasks/{id}/relations/{kind}/{otherId}` |
| Project views | GET | `projects/{id}/views`, `projects/{id}/views/{viewId}/tasks` (position order) |
| Task position | PUT | `tasks/{id}/position` |
| Server info, current user | GET | `info`, `user` |
| Password login, refresh, logout | POST | `login`, `user/token/refresh`, `logout` |
| OIDC token exchange | POST | `auth/openid/{provider}/callback` |
| API tokens | GET / POST / DELETE | `tokens`, `routes` |

### Smart lists

| List | Source | Notes |
|------|--------|-------|
| Inbox | the configured Inbox project | Ordered by the project's list view (position); the user can drag to reorder. Observes the project id, says so when none is selected. |
| Today | open tasks whose local due date is today or earlier | Overdue section above Today; ends at the start of local tomorrow |
| Upcoming | open tasks whose local due date is tomorrow or later | |
| Anytime | all open tasks except the Inbox | Grouped by project at every level |
| Logbook | done tasks | Loaded in pages |
| Review | projects by review status | Same math as desktop |
| Routines | routine carrier tasks | See Routines |
| Tag | one label | Filters before hiding nested subtasks |
| Custom list | user-defined filter | See Custom lists |

Server filters use local-day boundaries converted to UTC; the client then filters exactly (`DueDates`, `CustomListFilterBuilder`).

## Cross-app contract

`docs/cross-app-semantics-v1.md` is the contract for how both apps interpret synced data (due dates, local days, weeks, custom-list windows, review math, quick-add parsing, routine merge and archive). It is identical in the desktop repo, together with `docs/description-format-v1.md`. The machine-readable vectors are in `test-fixtures/` and must stay byte-identical in both repos:

- `cross-app-semantics-v1.json` (loaded by `CrossAppFixture`, `DueDatesFixtureTest`, `CustomListFixtureTest`, ...)
- `nlp-corpus-v1.json` (`NlpCorpusTest`)
- `routine-archive-v1.json` (`RoutineArchiveFixtureTest`, `RoutineMergeFixtureTest`)
- `description-format-v1.json`
- `design-tokens-v1.json` (the design-system values: `docs/design-system-v1.md`; `cross-app-semantics-v1.json` also holds the `completion` and `dateDisplay` vectors)

When behavior changes, change the fixture first, in both repos, in the same release. Fixtures write local wall-clock times; tests convert them with an explicit zone, and the routine vectors run with each contract zone as the system zone.

`docs/design-system-v1.md` is the design-system contract (colour roles and contrast, label chips, priority marks, motion, haptics). `SharedContractIdentityTest` compares all shared fixtures and docs with a desktop checkout next to this repo (skipped when absent; Gradle does not track those files, so use `--rerun` to force it).

Rules that follow from the contract and are easy to break:

- **Time comes from `TimeSource` and the day from `DayClock`.** Never call `Clock.System` or `TimeZone.currentSystemDefault()` in logic that needs testing. `DayClock` re-reads the local day at midnight, on resume and on a date, time or zone change (`DayChangeReceiver`); screens collect it instead of freezing the date they saw.
- **All due dates go through `DueDates`.** A date-only due date is stored as local 23:59:59; legacy 00:00:00 values are read as date-only. **All date text goes through `DateDisplay`** (see "Design system, theme and motion").
- Weeks start on Monday. Today is the local calendar date.

## Custom lists, routines and hidden sync metadata

Both features store data in ordinary Vikunja tasks that the apps hide from every list (`isMetadata`, `CustomListEnvelope.isAnyMetadataTask`):

- **Custom lists** sync through a carrier task whose description holds a `vicu-custom-lists:v1` marker (`CustomListEnvelope`, `CustomListRepositoryImpl`). `CarrierFinder` finds the carrier by remembered id before searching. A list has a name, icon, project and label filters, a due-date window with an **Include overdue tasks** switch, and a sort; unknown fields from a newer client are preserved. They are evaluated on the device by one evaluator (`CustomListFilterBuilder.visibleTasks`, fed by `customListSource`), so the screen and the widget agree; the server filter the same class builds is only a superset of the exact window.
- **Routines** keep their definition and recent history in carrier tasks (`vicu-routine:` marker, `RoutineEnvelope`; merge rules in the contract). A routine keeps its last 400 days; older history moves to **archive parts on the server**: done tasks titled "Vicu routine archive" in the project of the routine's main carrier, read and written by `RoutineArchiveStore` straight through the API (never through Room). `routine_occurrence_archive` in Room holds history that older versions kept only on the phone until `SyncEngine` has uploaded it once (the upload needs a connection). Carriers are created done in one request and skip list positioning. The routine CSV export guards cells a spreadsheet would run as formulas.

## Authentication

Three methods, chosen on the setup screen (server URL, discover, authenticate, pick the Inbox project):

1. **OIDC (SSO)** - a WebView (`OidcLoginActivity`) intercepts the redirect; the code is exchanged at Vikunja's own callback endpoint (not at the identity provider).
2. **Password + TOTP** - `POST login` with `long_token`. A 412 problem with code 1017 means a TOTP code is required. An empty error detail from the server falls back to a readable message.
3. **API token** - pasted by the user and tested against `user`.

`AuthManager` holds the session: the JWT with its rotating refresh cookie, proactive refresh, a backoff policy per failure kind, a mutex so refreshes never overlap, and a fallback to the backup API token (`POST tokens`, titled per device) before it reports `NeedsReAuth`. `KtorClientFactory` adds the bearer token and treats `login`, `info`, `user/token/refresh` and `auth/openid/...` as token-free by whole path segment. Tokens are stored by `AndroidSecureTokenStorage` (Tink AEAD, Android Keystore master key). `AccountSession` and `SessionCleanup` handle account switches and sign-out.

## Reminders, notifications and widgets

- **Per-task reminders**: exact alarms (`AlarmScheduler`, `ReminderAlarmCoordinator`, `ReminderAlarmRegistry`) planned from `task.reminders` by the pure rules in `ReminderAlarms`; relative reminders count from the date named in `relative_to`. An alarm re-checks the task when it fires, so a completed or removed task leaves no ghost reminder. "Mark Complete" in a notification completes and never reopens; "Snooze" goes through `SnoozeStore`. `BootReceiver`, the periodic sync and app start repair the alarms. Needs `SCHEDULE_EXACT_ALARM` handling on Android 12+ (a banner explains it).
- **Daily summaries** (morning and optional afternoon): each slot is a chain of one-time works (`DailySummaryScheduler`, `DailySummaryWorker`) aimed at the local wall-clock time, re-queued by the run itself and made sure at app start. Counting follows the desktop rules (`DailySummary`).
- **Routine reminders**: `RoutineAlarmScheduler` and the routine action receivers.
- **Notification channels**: `task_reminders`, `daily_summary` and `routine_reminders` (see `NotificationChannelManager`).
- **Widgets** (Glance, not Compose: different imports): `TaskListWidget` (Today, Inbox, Upcoming, Anytime, a project or a custom list; tap to complete) and `RoutineWidget`. State files live under `WidgetStateFiles` and are excluded from backups. Taps and notifications open the app through a typed `ViewTarget`; its Intent wire form is frozen because installed widgets and posted notifications hold it.

## UI notes

- Navigation: `AppNavHost` with `@Serializable` routes (`Routes.kt`), one top-level navigation behavior (`TopLevelNavigation`), a drawer with a nested project tree, and configurable bottom-bar slots. The task detail is a sheet that autosaves edits and keeps them across process death.
- Quick add parses natural language as you type (`util/parser/`, Todoist or Vikunja syntax); the rules are shared with the desktop and covered by the NLP corpus.
- Lists: swipe right to complete, swipe left to schedule; drag handles reorder; every reorderable row also has Move up and Move down actions for TalkBack; small controls have 48dp targets. Multi-select ends when the user navigates away (`NavigationTicker`).
- Search shows cached matches at once (titles, descriptions, completed tasks) and refreshes in the background.
- Descriptions are HTML in Vikunja; the Cascade editor edits them and unknown blocks are preserved (`docs/description-format-v1.md`).
- Attachments: open or share any file type, streamed to the cache, checked against the server's size limit before upload.
- Share target: a share becomes a task with the shared text as title and description and shared files attached; it survives rotation.
- Settings has four tabs in a `PrimaryScrollableTabRow`: General, Projects, Notifications, Gestures. Projects holds the Inbox choice, the project list (tap a row to edit it, drag or Move up / Move down within its level, the row menu for the rest, archived projects in a collapsed Archived group), Display, Review with the projects excluded from it, Labels and Custom Lists. Every project action (create, add subproject, edit, set as Inbox, archive, restore, delete, mark reviewed, include in review again, sibling moves) goes through `ProjectActions` (`ui/screens/shared/`, a Koin single), whether it starts in Settings, at the drawer's + or in the project screen's menu, so each answers with the same message everywhere; add a new project action there, not in a ViewModel.

## Design system, theme and motion

`docs/design-system-v1.md` is the contract (identical in the desktop repo) and `test-fixtures/design-tokens-v1.json` holds its values (byte-identical; the `android` block has the Material 3 colour schemes generated from the seed #0A66D1, custom colours, contrast pairs and the motion scheme). Change the doc and the fixture in both repos first; the tests below fail when code drifts from the fixture.

- **Theme** (`ui/theme/`): `VicuTheme(themeMode, dynamicColor)` in `Theme.kt` picks `VicuLightColorScheme` / `VicuDarkColorScheme` (`Color.kt`) and applies `Typography` (`Type.kt`) and `VicuShapes` (`Shape.kt`). The Vicu scheme is the default. **Use device colours** (Settings, General, appearance; `ThemePrefsStore.useDeviceColors`, default off, Android 12 and later) switches to the wallpaper scheme (`dynamicLightColorScheme` / `dynamicDarkColorScheme`). Material 3 does not cover everything, so `VicuColors` (`VicuColors.kt`: due today, done, swipe complete and schedule as `VicuColorRole` with on-colour and containers; priority colours; smart list identity colours) is provided through `LocalVicuColors` (read `LocalVicuColors.current`). Overdue has no custom colour: it is `colorScheme.error`. With device colours on, `VicuColors.harmonizedWith(colorScheme.primary)` shifts the status and swipe colours towards the wallpaper primary; priority and identity colours keep their hue so marks and list icons stay told apart. Identity colours only ever colour list icons, never text. Components never hard-code a colour: take it from `MaterialTheme.colorScheme`, `LocalVicuColors`, `LabelChipColors`, `PriorityMark` or `SmartListIdentity`.
- **Harmonize port** (`ui/theme/color/`: `Blend.kt` with the reduced `Hct`, `Cam16.kt`, `HctSolver.kt`, `ColorUtils.kt`): a small port of material-color-utilities' `Blend.harmonize` in common Kotlin (Apache 2.0 header kept), because MDC is not a dependency and is not to be added. `HarmonizeVectorsTest` (`androidHostTest`) checks it against vectors generated once with material-color-utilities 0.4.0 (`harmonize-vectors-v1.json`); `HarmonizedColorsTest` pins what harmonising may and may not change. `Contrast.kt` has `contrastRatio` and `ensureContrast`.
- **`VicuMotion`** (`Motion.kt`) is a plain `object` holding the motion tokens: the six scheme slots (`fastSpatial`, `defaultSpatial`, `slowSpatial`, `fastEffects`, `defaultEffects`, `slowEffects` as `VicuSpring` damping and stiffness, with `...Spec()` functions) and the named motions (`fade.fast`, `fade.base`, `move`, `moveExpressive`, `pop`, page, stagger, check and strike draw). It does not implement `MotionScheme` and is not passed to `MaterialTheme`: Compose Multiplatform material3 1.9.0 (and androidx 1.4.0) keep `MotionScheme` internal and `MaterialTheme` has no public `motionScheme` parameter, so components read `VicuMotion` directly. Material 3 Expressive (`MotionScheme.expressive()`, FAB menu, floating toolbar, connected button groups, `LoadingIndicator`) is deferred until a stable multiplatform material3 exposes it (recorded in the design doc); revisit then and keep `VicuMotion` unless the factory's values equal the tokens. Every animation needs a reduced variant (fade only, no transform or overshoot) that follows the system animator scale.
- **Token tests** (`shared/src/commonTest/.../ui/theme/`): `DesignTokensFixture` loads `design-tokens-v1.json` through `CrossAppFixture.readTestFixture`; `DesignTokensTest` compares both colour schemes, typography sizes and weights (nothing under 11 sp), shape radii, custom, priority and identity colours and the motion scheme and named motions with the fixture; `AndroidContrastTest` resolves the `android.contrast` pairs per theme. Component level: `LabelChipColorsTest`, `PriorityMarkTest`, `SmartListIdentityTest`. `SharedContractIdentityTest` (`util/`) compares every shared fixture (`design-tokens-v1.json` included) and doc (`design-system-v1.md` included) with a desktop checkout next to this repo (`VICU_DESKTOP_DIR` overrides; skipped when absent; Gradle does not track those files, so use `--rerun`).
- **Date display** (`util/DateDisplay.kt`): `DateDisplay.format` and `formatDue` phrase a date for a `DateContext` (row, row in Today, row in a day group, chip, day and full headers, logbook group and time) from a `DateDisplayFormat` (device locale and 12 or 24 hour clock: `DateDisplayFormat.system(is24Hour)`, `LocalIs24Hour` in the UI). It is the same rule set as the desktop `src/shared/date-display.ts` (contract section 8); English is built from fixed names and patterns, never platform locale data, so java.time and ICU agree, and the `dateDisplay` vectors of `cross-app-semantics-v1.json` run in `DateDisplayTest`. Do not format a date for display with `DateTimeFormatter` or string templates in a screen.
- **Completion hold** (contract section 7): `CompletionHoldMachine` (`ui/screens/shared/`) is the pure state machine, the same as the desktop `src/shared/completion-hold.ts` (no timers: every call takes the time; `CompletionHold.HOLD_MILLIS` 5 s, `TOAST_MILLIS` 6 s), run against the `completion` vectors in `CompletionHoldMachineTest`. What the app runs is `CompletionHold` (keeps the row in place, struck through, for the hold; ends on undo, on a new destination through `NavigationTicker`, or on a failed change) and `CompletionToastCenter` (collapsed rows merge into "Completed" / "3 completed" with Undo on the app-wide snackbar, the 6 s clock restarting per row). The phone has no pointer, so the engagement half of the machine is unused here. Tests: `CompletionHoldTest`, `CompletionHoldViewModelsTest`.

## Privacy and security

- Release builds log no requests and no debug output: `BuildInfo.isDebug` (set from `BuildConfig.DEBUG`) gates Ktor logging and the auth log file, and R8 strips `Log.v/d/i` and `Logger.d/i`.
- Backups and device transfer exclude the database, tokens, keyset, alarm and widget state, and the auth log (`backup_rules.xml`, `data_extraction_rules.xml`, database name `vicu_database`).
- Cleartext http is allowed because self-hosted servers often run on a LAN; the setup screen warns when an http address points outside the local network.

## Testing

All of these are plain JVM tests (no device):

- `shared/src/commonTest` - repositories and the sync engine against in-memory fakes (`FakeTaskDao`, `FakeTaskServer`, Ktor `MockEngine`), ViewModels with `FakeRepositories`, the contract fixtures, the quick-add corpus, date and routine logic. Test doubles for time (`TestTimeSources`) and preferences (`InMemoryPreferencesDataStore`) live there.
- `shared/src/androidHostTest` - SQL and migrations run in sqlite-jdbc from the exported schemas.
- `app/src/test` - the Koin graph, schedulers, widget state, notification helpers.
- `app/src/androidTest` - instrumented UI tests; they need a device and are not part of the gate.

The design-token tests (`DesignTokensTest`, `AndroidContrastTest`, `HarmonizeVectorsTest`, ...) and `SharedContractIdentityTest` are described under "Design system, theme and motion".

Android classes are stubbed in JVM tests with `isReturnDefaultValues = true`. Coroutine tests use `kotlinx-coroutines-test` (`runTest`, virtual time).

## UI verification harness and device safety

**Device commands go only to the emulator `emulator-5554`, never to a physical phone** (a wireless phone is often connected). Use `adb -s emulator-5554 ...` and `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`; never a bare `adb`, `-d`, `-e`, `adb connect` or `adb devices` to pick a target. The harness enforces this: `scripts/ui-verify/droid.mjs` passes `-s emulator-5554` on every call, rejects the flags that pick another device, and aborts on import unless `emulator-5554` is up.

`scripts/ui-verify/` is a small Node tool (its own `package.json`, dependency `pngjs` for cropping; run `npm install` there once; not part of the Gradle build or the gate). `out/` and `node_modules/` are git-ignored. It drives the debug app (`com.rendyhd.vicu.debug`) over adb with real taps, swipes and key events:

- `shots.mjs`: captures by scenario (ids `a1` to `a12` follow the design-review plan, `baseline` is the default). `node shots.mjs --scenario baseline --theme light|dark|both`; `--wave N` (every scenario of that wave); `--list`; `--run NAME` (write to `out/NAME-<scenario>/` so `out/baseline` stays intact); `--token <path>` (the a10 first-run scenario signs in again); `--query <text>` (search step). Captures land in `out/<scenario>/<theme>-<step>.png`. A step that cannot run prints FAIL and the run exits 1, WARN lines are expectations the app does not meet yet and never fail the run, stub scenarios print STUB and pass. It restores night mode, font scale and animator scale when it ends; motion captures slow the animator scale and put it back to 1.
- `a11y.mjs`: accessibility report from a UI dump (rules `unnamed-clickable`, `checkbox`, `small-target` under 48 dp). `node a11y.mjs --screens today,editor` (screen names: `SCREENS` in `nav.mjs`), `--strict` exits 1 on any finding. Findings are also written to `out/a11y/<screen>.json`.
- `login.mjs` signs the debug app in with an API token (`--server`, default `http://10.0.2.2:3456`; `--inbox`; `--force` runs `pm clear` on the debug app first); `nav.mjs` has the navigation helpers; `droid.mjs` the adb driver. The token is read from the desktop repo's `scripts/ui-verify/.local` (default `../vicu/scripts/ui-verify/.local`), typed through adb and never printed.
- The data is the desktop harness' seeded Docker Vikunja (`node scripts/ui-verify/seed.mjs` in the `vicu` repo; the emulator reaches it at `10.0.2.2:3456`). Both harnesses share that server and its dataset, so do not run them at the same time. Install the build first: `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`.

## CI / Releases

- `.github/workflows/ci.yml` runs `./gradlew test lint assembleDebug` on every push and pull request (JDK 17, Gradle cache).
- `.github/workflows/release.yml` builds a **signed release APK** when a GitHub release is published, with the repository secrets `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD`, and attaches `app-release.apk`.
- **When creating a release**: bump `versionCode` and `versionName` in `app/build.gradle.kts`, write the notes in `docs/releases/vX.Y.Z.md`, then `gh release create <tag> --title "..." --notes-file docs/releases/vX.Y.Z.md` **without attaching a local APK**. CI builds and uploads the signed APK. Never attach a debug APK manually.

## Platforms

Android 8.0+ (minSdk 26, compile and target SDK 36). The shared module declares iOS targets, but they do not compile: the UI in `commonMain` imports Android APIs. No iOS work is planned; see `docs/ios-status.md`.

## Key Gotchas

1. **Merge patches**: send only changed fields; a whole-object update zeroes fields you did not mean to touch.
2. **Null date** is `0001-01-01T00:00:00Z`; clearing a due date is `"due_date": null`.
3. **Glance is not Compose**: different imports and a different layout system.
4. **KSP version must match the Kotlin version line**; Room's schema location is a KSP argument, not the Room Gradle plugin.
5. **`SCHEDULE_EXACT_ALARM`**: check and prompt on Android 12+/14+.
6. **Password login 412**: TOTP required; a 403 is wrong credentials.
7. **Position reorder** needs the project's list view id from the views endpoint.
8. **Subtasks** are task relations, not a separate model.
9. **AlarmManager is cleared on reboot**: `BootReceiver` and the periodic sync re-schedule.
10. **Never read the clock directly** in testable logic: use `TimeSource` and `DayClock`.
11. **New constructor parameter means a Koin edit**: run `KoinGraphTest`.
12. **`./gradlew test` covers `:shared` through its own `test` task** (an alias of `testAndroidHostTest`, because the Kotlin Multiplatform library plugin has none).
13. **Line endings**: several files are CRLF or mixed. Edit them in place without converting whole files, and write new files with LF.
14. **Device commands go only to `emulator-5554`** (`adb -s emulator-5554`, `ANDROID_SERIAL=emulator-5554`); never to a physical phone.
15. **`VicuMotion` is not a `MotionScheme`** and is not passed to `MaterialTheme`; read it directly and give every animation a reduced variant.
16. **No hard-coded colours or date strings in screens**: colours come from the theme and `LocalVicuColors`, date text from `DateDisplay`.
