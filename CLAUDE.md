# CLAUDE.md — Vicu Android

This file provides guidance to Claude Code when working on the Vicu Android app.

## Project Overview

**Vicu Android** — a native Android task management app powered by [Vikunja](https://vikunja.io/) as the backend. Built with **Kotlin + Jetpack Compose + Material 3**. This is a companion to the existing Vicu desktop app (Electron + React + TypeScript). The Android app must reach feature parity with the desktop version while following native Android conventions.

**Design inspiration**: Things 3 (iOS). Clean, minimal UI with generous whitespace, collapsible sections, circular animated checkboxes, and a prominent FAB (+) for quick task entry.

### Reference
The desktop (Windows) Vicu app is at `C:\Users\rendy\vscode\vicu`. Consult its source when you need implementation details not covered in this file.

## Commands

```bash
./gradlew assembleDebug        # Debug APK
./gradlew installDebug         # Build + install on connected device/emulator
./gradlew assembleRelease      # Release APK (requires signing config)
./gradlew test                 # Unit tests
./gradlew connectedAndroidTest # Instrumented tests
./gradlew ktlintCheck          # Lint check
./gradlew ktlintFormat         # Auto-fix lint issues
```

## Technology Stack

| Layer | Library | Version |
|-------|---------|---------|
| Language | Kotlin | 2.1.20 |
| UI | Jetpack Compose (BOM) | 2026.01.01 |
| Design | Material 3 | via Compose BOM |
| Navigation | Navigation Compose | 2.9.x |
| DI | Hilt | 2.57.x |
| Networking | Retrofit 3 + OkHttp 4 | 3.0.0 / 4.12.0 |
| Serialization | kotlinx-serialization-json | 1.8.x |
| Local DB | Room (KSP) | 2.7.x |
| Preferences | DataStore Preferences | 1.1.x |
| Background | WorkManager | 2.11.x |
| Widgets | Jetpack Glance | 1.1.x |
| Auth (OIDC) | AppAuth-Android | 0.11.2 |
| Token storage | Tink Android | 1.19.x |
| Images | Coil 3 | 3.3.x |
| Annotation processing | KSP (not KAPT) | matching Kotlin version |

**CRITICAL**: Use `ksp()` for ALL annotation processors (Hilt, Room). Never use `kapt()`.

## Architecture

### MVVM with Unidirectional Data Flow

```
UI Layer (Compose) → observes StateFlow<UiState> from ViewModel
ViewModel Layer    → calls Repository suspend functions
Domain Layer       → pure Kotlin interfaces + data classes
Data Layer         → Repository (Room = source of truth), Retrofit (remote), PendingActionQueue (offline)
```

### Package Structure

```
com.vicu.app/
├── data/
│   ├── remote/api/              → VikunjaApiService.kt, DTOs (@Serializable)
│   ├── remote/interceptor/      → AuthInterceptor.kt, TokenAuthenticator.kt
│   ├── local/entity/            → TaskEntity, ProjectEntity, LabelEntity, PendingActionEntity, AttachmentEntity
│   ├── local/dao/               → TaskDao, ProjectDao, LabelDao, PendingActionDao, AttachmentDao
│   ├── local/VikunjaDatabase.kt
│   ├── repository/              → *RepositoryImpl classes
│   └── mapper/                  → DTO ↔ Entity ↔ Domain extensions
├── domain/
│   ├── model/                   → Task, Project, Label, Attachment, Reminder, TaskRelation
│   └── repository/              → Repository interfaces
├── ui/
│   ├── navigation/              → AppNavHost.kt, Routes.kt
│   ├── screens/                 → setup/, today/, inbox/, upcoming/, anytime/, logbook/, project/, tag/, customlist/, search/, settings/, taskdetail/
│   ├── components/
│   │   ├── task/                → TaskItem, AnimatedCheckbox, TaskDueBadge, PriorityDot, SubtaskList, TaskEntrySheet
│   │   ├── picker/              → DatePickerSheet, ProjectPickerSheet, LabelPickerSheet, ReminderPickerSheet, AttachmentSheet
│   │   ├── section/             → SectionHeader, SectionGroup, AddSectionButton
│   │   └── shared/              → EmptyState, SearchBar, SyncStatusIndicator, CustomListDialog
│   └── theme/                   → Theme.kt, Color.kt, Type.kt
├── auth/                        → AuthManager, OidcHandler, PasswordLoginHandler, SecureTokenStorage
├── di/                          → NetworkModule, DatabaseModule, RepositoryModule, AuthModule
├── widget/                      → TaskListWidget, TaskWidgetReceiver, TaskWidgetState, WidgetConfigActivity
├── worker/                      → DailySummaryWorker, SyncWorker, RescheduleRemindersWorker
├── notification/                → NotificationChannelManager, AlarmScheduler, AlarmReceiver, BootReceiver
└── util/                        → NetworkResult.kt, DateUtils.kt, RecurrenceUtils.kt, Constants.kt
```

## Vikunja API

### Docs

Full OpenAPI spec in `docs.json`. Reference for all endpoints.

### CRITICAL: Go zero-value problem

When updating tasks/projects, **always send the complete object**. Sending `{ "done": true }` alone zeros out `due_date`, `priority`, `labels`, etc.

### Null Date

Vikunja null date = `0001-01-01T00:00:00Z`. Always exclude from due date filters.

### Key Endpoints

| Action | Method | Endpoint |
|--------|--------|----------|
| List tasks (filtered) | GET | `/tasks/all?filter=...&sort_by=...&order_by=...` |
| Create task | PUT | `/projects/{id}/tasks` |
| Update task | POST | `/tasks/{id}` |
| Delete task | DELETE | `/tasks/{id}` |
| Search tasks | GET | `/tasks/all?s={query}` |
| List/Create/Update/Delete projects | GET/PUT/POST/DELETE | `/projects`, `/projects/{id}` |
| List/Create/Update/Delete labels | GET/PUT/PUT/DELETE | `/labels`, `/labels/{id}` |
| Add/Remove label on task | PUT/DELETE | `/tasks/{id}/labels`, `/tasks/{id}/labels/{labelId}` |
| List/Upload/Download/Delete attachments | GET/PUT/GET/DELETE | `/tasks/{id}/attachments`, `/tasks/{id}/attachments/{attId}` |
| Create/Delete task relation (subtasks) | PUT/DELETE | `/tasks/{id}/relations` |
| Get project views | GET | `/projects/{id}/views` |
| Get view tasks (position-sorted) | GET | `/projects/{id}/views/{viewId}/tasks` |
| Update task position | POST | `/tasks/{taskId}/position` |
| Discover auth methods | GET | `/info` |
| Discover OIDC providers | GET | `/auth/openid/callback` |
| OIDC token exchange | POST | `/auth/openid/{provider}/callback` |
| Password login | POST | `/login` |
| Create API token | PUT | `/tokens` |
| Get current user | GET | `/user` |

### Smart List → Filter Mapping

| Smart List | Filter | Sort |
|-----------|--------|------|
| Inbox | `done = false && project_id = {inbox_id}` | `created` desc |
| Today | `done = false && due_date <= '{eot}' && due_date != '{null}'` | `due_date` asc |
| Upcoming | `done = false && due_date > '{eot}' && due_date != '{null}'` | `due_date` asc |
| Anytime | `done = false` (exclude inbox client-side) | `updated` desc |
| Logbook | `done = true` | `done_at` desc |
| Project | via view API for position sorting | `position` asc |
| Tag | `done = false` (filter by label client-side) | `updated` desc |

## Authentication (3 methods)

### 1. OIDC (SSO)
AppAuth-Android + Chrome Custom Tab. Discover providers from Vikunja, exchange code via Vikunja's endpoint (NOT IdP directly), receive JWT, create backup API token.

### 2. Password + TOTP
POST `/api/v1/login` with `{ username, password, long_token: true }`. HTTP 412 = TOTP required → prompt for code, retry with `totp_passcode`. HTTP 403 = wrong credentials.

### 3. API Token
Manual entry, test via GET `/api/v1/user`.

### Token Management
OkHttp Interceptor (proactive JWT refresh) + Authenticator (reactive 401). Fallback: JWT → re-auth → API token → NeedsReAuth. Uses Mutex for concurrent safety.

### Setup Screen Flow
URL → discover auth methods → show OIDC/password/API token options → authenticate → select inbox project → save & start.

## Task Detail Sheet (ModalBottomSheet)

Triggered by tapping any task. Supports:
- Editable title + description (auto-save on dismiss)
- Labels (colored chips + LabelPickerSheet with inline creation)
- Due date (DatePickerSheet: Today, Tomorrow, Next Week, custom)
- Reminders (ReminderPickerSheet: absolute time or relative period)
- Priority (cycle: none → low → medium → high → urgent)
- Project (ProjectPickerSheet)
- Subtasks (via task relations, create new inline)
- Attachments (list + upload via file picker + download/share + delete)
- Recurrence display (read-only, e.g. "Every 2 weeks")
- Delete with confirmation
- "!" prefix in title → strip and set due_date to today

## Project Sections

Child projects act as sections within a parent project. Shows:
- Parent tasks at top
- Each child as a collapsible section header
- Tasks movable between sections (changes project_id)
- "Add section" creates a new child project
- Position-based ordering via Vikunja view system (viewId required)

## Custom Lists

User-defined filter presets. Each has: id, name, icon, filter config (project_ids, sort_by, order_by, due_date_filter, label_ids, include_done, include_today_all_projects). Stored in DataStore. CRUD via CustomListDialog.

## Notifications

1. **Daily summary**: WorkManager periodic (24h), morning + optional afternoon, individual or grouped
2. **Per-task reminders**: AlarmManager exact alarms from task.reminders[], re-scheduled on reboot/sync
3. **Settings**: master toggle, times, overdue/today/upcoming toggles, sound, persistent, test button
4. **Channels**: task_reminders (HIGH), daily_summary (DEFAULT), overdue_alerts (HIGH), sync_status (LOW)
5. **Actions**: click → navigate to task, "Mark Complete" and "Snooze 15min" via BroadcastReceiver

## Home Screen Widget (Glance)

Scrollable task list, configurable view (Today/Inbox/Upcoming/Anytime/project/custom list), tap task → detail, tap header → new task, checkbox → complete, periodic + immediate refresh, responsive sizing.

## Offline-First

Room = source of truth. Optimistic writes with PendingActionEntity queue. SyncWorker processes on connectivity. Completed tasks stay visible with strikethrough until navigation (undo window).

## Labels System

Full CRUD. Colored chips on tasks. LabelPickerSheet for assign/remove with inline creation. Stored in Room, synced with API.

## Attachments System

List/upload/download/delete. File picker for upload (multipart). Open via Android share intent. Shown in TaskDetailSheet.

## Completed Tasks & Undo

Completed tasks remain visible (strikethrough) in current view until navigation away. Undo snackbar reverts done=false. Uses ViewModel-scoped map merged into Room Flow.

## Key Gotchas

1. **Go zero-value problem**: Always send complete objects on update
2. **Null date**: `0001-01-01T00:00:00Z`
3. **Glance ≠ Compose**: Different imports
4. **KSP version must match Kotlin**
5. **SCHEDULE_EXACT_ALARM**: Check + prompt on Android 14+
6. **OkHttp Authenticator**: Mutex for concurrent refresh
7. **Password login 412**: TOTP required signal
8. **Position reorder**: Requires viewId from project views API
9. **Subtasks**: Task relations, not a separate model
10. **AlarmManager reboot**: BootReceiver must re-schedule all
