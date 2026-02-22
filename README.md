# Vicu Android

A native Android task manager powered by [Vikunja](https://vikunja.io/).

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android%208%2B-brightgreen)
![Built with](https://img.shields.io/badge/kotlin%20+%20compose%20+%20material%203-7F52FF)

<!-- screenshot: main-screens -->

## What is Vicu Android?

Vicu Android is the mobile companion to [Vicu](https://github.com/rendyhd/Vicu), bringing the same focused task management workflow to your phone. It connects to any Vikunja instance and organizes your tasks around when they need to happen: tasks land in your **Inbox**, get scheduled into **Today** or **Upcoming**, sit in **Anytime** as your open backlog, and end up in the **Logbook** when done. A home screen widget and per-task reminders keep you on top of things without opening the app.

Both Vicu apps talk to the same Vikunja backend, so changes sync between desktop and mobile automatically.

## Features

- **Smart lists** — Inbox, Today, Upcoming, Anytime, Logbook
- **Custom lists** — User-defined filtered views by project, due date, labels, and sort order
- **Projects with sections** — Collapsible child projects, position-based ordering
- **Labels** — Full CRUD with custom colors, multi-select picker with inline creation
- **Subtasks** — Parent-child task relationships via task relations
- **Reminders** — Per-task reminders (absolute time or relative to due date) + configurable daily summary notifications
- **Attachments** — Upload, download, and share files on any task
- **Recurring tasks** — Displays recurrence info (daily, weekly, monthly, custom intervals)
- **Home screen widget** — Configurable Glance widget showing Today, Inbox, Upcoming, or any project/custom list with tap-to-complete checkboxes
- **Offline-first** — Room DB as source of truth, pending action queue with automatic background sync
- **Dark / light / system themes** — Material 3 dynamic color support
- **Swipe gestures** — Swipe right to complete, swipe left to schedule
- **Authentication** — OIDC (SSO), username/password with TOTP two-factor, or manual API token entry

## Vicu vs the official Vikunja app

The [official Vikunja frontend](https://vikunja.io/) is a full project management suite — Kanban, Gantt, table views, team collaboration, and more. Vicu is a personal task manager that trades all of that for speed and a focused workflow. Both talk to the same backend, so you can use them side by side.

## Getting started

### Download

Grab the latest APK from [GitHub Releases](https://github.com/rendyhd/Vicu-Android/releases).

### First launch

1. Enter your Vikunja server URL
2. Choose an authentication method (OIDC, password, or API token)
3. Select your Inbox project
4. Start managing tasks

### Build from source

You'll need Android Studio (Ladybug or newer) and a running [Vikunja](https://vikunja.io/docs/) instance.

```bash
git clone https://github.com/rendyhd/Vicu-Android.git
cd Vicu-Android
./gradlew assembleDebug
```

Install the debug APK on a connected device or emulator:

```bash
./gradlew installDebug
```

## Building

```bash
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK (requires signing config)
./gradlew test                 # Unit tests
./gradlew connectedAndroidTest # Instrumented tests
```

For a signed release build, create a `keystore.properties` file in the project root:

```properties
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Tech stack

| Layer | Library |
|-------|---------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Networking | Retrofit + OkHttp |
| Serialization | kotlinx-serialization |
| Local DB | Room (KSP) |
| Preferences | DataStore |
| Background work | WorkManager |
| Widgets | Glance |
| Auth (OIDC) | AppAuth-Android |
| Token storage | Tink |
| Images | Coil |

## Architecture

MVVM with unidirectional data flow. Room is the single source of truth — the UI observes `StateFlow<UiState>` from ViewModels, which call Repository suspend functions backed by Room DAOs and Retrofit. Writes are optimistic: tasks update locally first, then sync to the server via a background action queue.

```
UI (Compose) → ViewModel (StateFlow) → Repository → Room + Retrofit
                                                   ↘ PendingActionQueue → SyncWorker
```

## See also

- **[Vicu](https://github.com/rendyhd/Vicu)** — desktop app (Windows) with Quick Entry, Quick View, and Obsidian integration
- **[Vikunja](https://vikunja.io/)** — the open-source backend that powers Vicu

## License

[MIT](LICENSE)
