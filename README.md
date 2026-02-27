# Vicu Android

A native Android task manager powered by [Vikunja](https://vikunja.io/).

![slide1 001](https://github.com/user-attachments/assets/83925b87-cfbb-4fc8-b796-f69b9dc303aa)


![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android%208%2B-brightgreen)
![Built with](https://img.shields.io/badge/kotlin%20+%20compose%20+%20material%203-7F52FF)

<!-- screenshot: main-screens -->

## What is Vicu Android?

Vicu Android is the mobile companion to [Vicu](https://github.com/rendyhd/Vicu), bringing the same focused task management workflow to your phone. It connects to any Vikunja instance and organizes your tasks around when they need to happen: tasks land in your **Inbox**, get scheduled into **Today** or **Upcoming**, sit in **Anytime** as your open backlog, and end up in the **Logbook** when done. A home screen widget and per-task reminders keep you on top of things without opening the app.

Both Vicu apps talk to the same Vikunja backend, so changes sync between desktop and mobile automatically.

## Capture from anywhere

**Share to Vicu** turns any Android share into a task. Reading an article, browsing a link, or looking at a photo — hit the share button, pick Vicu, and it becomes a task with the title and description pre-filled. Shared files and images are attached automatically.

No copy-pasting, no switching apps. See something, share it, move on.

## Natural language input

Type tasks the way you think. Vicu parses freeform text into structured fields as you type, with real-time color highlighting and autocomplete suggestions.

| Token | Example | Effect |
|-------|---------|--------|
| Dates | `tomorrow`, `next Monday`, `in 3 days` | Sets due date |
| Times | `tomorrow 3pm`, `today at 14:00` | Sets due date + time |
| `!` | `Buy milk !` | Due today |
| Priority | `p1`–`p4`, `!urgent`, `!high`, `!medium`, `!low` | Sets priority |
| Labels | `@shopping`, `@"grocery list"` | Applies labels |
| Projects | `#work`, `#"side project"` | Assigns to project |
| Recurrence | `every 3 days`, `weekly`, `monthly` | Sets repeat interval |

Everything that isn't a recognized token becomes the task title. Tokens can appear anywhere in the input and are shown as dismissible chips below the text field. Tap a chip to remove it.

Two syntax modes are available in Settings: **Todoist** (default — `@` for labels, `#` for projects) and **Vikunja** (`*` for labels, `+` for projects).

## Features

- **Smart lists** — Inbox, Today, Upcoming, Anytime, Logbook
- **Custom lists** — User-defined filtered views by project, due date, labels, and sort order
- **Projects with sections** — Collapsible child projects, position-based ordering
- **Labels** — Full CRUD with custom colors, multi-select picker with inline creation
- **Subtasks** — Parent-child task relationships via task relations
- **Reminders** — Per-task reminders (absolute time or relative to due date) + configurable daily summary notifications
- **Attachments** — Upload, download, and share files on any task
- **Recurring tasks** — Daily, weekly, monthly, or custom intervals
- **Home screen widget** — Configurable Glance widget showing Today, Inbox, Upcoming, or any project/custom list with tap-to-complete checkboxes
- **Offline-first** — Room DB as source of truth, pending action queue with automatic background sync
- **Dark / light / system themes** — Material 3 dynamic color support
- **Swipe gestures** — Swipe right to complete, swipe left to schedule
- **Authentication** — OIDC (SSO), username/password with TOTP two-factor, or manual API token entry

## Getting started

### Download

[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/rendyhd/Vicu-Android)

Or grab the latest APK from [GitHub Releases](https://github.com/rendyhd/Vicu-Android/releases).

### First launch

1. Enter your Vikunja server URL
2. Choose an authentication method (OIDC, password, or API token)
3. Select your Inbox project
4. Start managing tasks

## See also

- **[Vicu](https://github.com/rendyhd/Vicu)** — desktop app (Windows/macOS) with Quick Entry, Quick View, Obsidian integration, and browser linking
- **[Vikunja](https://vikunja.io/)** — the open-source backend that powers Vicu

## License

[MIT](LICENSE)
