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

## Getting started

### Download

Grab the latest APK from [GitHub Releases](https://github.com/rendyhd/Vicu-Android/releases).

### First launch

1. Enter your Vikunja server URL
2. Choose an authentication method (OIDC, password, or API token)
3. Select your Inbox project
4. Start managing tasks

## See also

- **[Vicu](https://github.com/rendyhd/Vicu)** — desktop app (Windows) with Quick Entry, Quick View, and Obsidian integration
- **[Vikunja](https://vikunja.io/)** — the open-source backend that powers Vicu

## License

[MIT](LICENSE)
