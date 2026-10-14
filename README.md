# Vicu Android

A native Android task manager powered by [Vikunja](https://vikunja.io/).

![slide1 001](https://github.com/user-attachments/assets/83925b87-cfbb-4fc8-b796-f69b9dc303aa)


![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android%208%2B-brightgreen)
![Built with](https://img.shields.io/badge/kotlin%20+%20compose%20+%20material%203-7F52FF)

<!-- screenshot: main-screens -->

## What is Vicu Android?

Vicu Android is the mobile companion to [Vicu](https://github.com/rendyhd/Vicu), bringing the same focused task management workflow to your phone. It connects to any Vikunja instance and organizes your tasks around when they need to happen: tasks land in your **Inbox**, get scheduled into **Today** or **Upcoming**, sit in **Anytime** as your open backlog, and end up in the **Logbook** when done. A home screen widget and per-task reminders keep you on top of things without opening the app.

Both Vicu apps talk to the same Vikunja backend, so changes sync between desktop and mobile automatically. Since 1.9.0 both apps also read that data by the same rules (`docs/cross-app-semantics-v1.md`), so a due date, a custom list or a project review means the same thing on your phone and on your PC.

## Capture from anywhere

**Share to Vicu** turns any Android share into a task. Reading an article, browsing a link, or looking at a photo — hit the share button, pick Vicu, and it becomes a task with the title and description pre-filled. Shared files and images are attached automatically.

No copy-pasting, no switching apps. See something, share it, move on.

## Natural language input

Type tasks the way you think. Vicu parses freeform text into structured fields as you type, with real-time color highlighting and autocomplete suggestions.

| Token | Example | Effect |
|-------|---------|--------|
| Days | `today`, `tomorrow`, `friday`, `this friday`, `next Monday`, `next week`, `next month`, `in 3 days`, `in 2 weeks` | Sets the due date (date only) |
| Calendar dates | `jan 15`, `15 jan`, `march 3rd`, `2026-10-15`, `10/15` | Sets the due date; a date that has already passed this year means next year |
| Times | `tomorrow 3pm`, `today at 14:00`, `fri 9:30am`, `in 2 hours`, `in 30 minutes`, `at 5pm` | Sets due date + time |
| `!` | `Buy milk !`, `! Buy milk`, `Buy milk!` | Due today |
| Priority | `p1`–`p4`, `!urgent`, `!critical`, `!high`, `!medium`, `!med`, `!low` | Sets priority |
| Labels | `@shopping`, `@"grocery list"` | Applies labels |
| Projects | `#work`, `#"side project"` | Assigns to project |
| Recurrence | `every 3 days`, `every 2 weeks`, `every friday`, `daily`, `weekly`, `biweekly` | Sets repeat interval |

Everything that isn't a recognized token becomes the task title. Tokens can appear anywhere in the input and are shown as dismissible chips below the text field. Tap a chip to remove it.

How dates and recurrence are read:

- A bare weekday or `this <weekday>` is the next one on or after today, so `tuesday` typed on a Tuesday is today. `next <weekday>` is that day in the following week (weeks start on Monday), and `next week` is next Monday.
- A date without a time is stored as a date only. A time without a date means today if it is still ahead, otherwise tomorrow. A time can come before or after the date (`3pm tomorrow`).
- Three-letter weekdays (`mon`, `tue`, `wed`, `thu`, `fri`, `sat`, `sun`) only count after `on`, `next`, `this`, `by` or `due`, or before a time, so "Buy sun cream" has no date. Full weekday names always count.
- Connector words go with the date: "Submit report by friday" becomes "Submit report", "Call at 9am" becomes "Call".
- Slash dates follow your phone's order (`10/15` is October 15 in en-US, `5/11` is 5 November in en-GB). When the first number can't be a month, the order flips (`15/10`).
- `daily`, `weekly`, `monthly`, `yearly`, `annually`, `biweekly` and `fortnightly` make a task recurring only as the last word ("Water plants daily"); "weekly standup" stays a title. `every friday` repeats weekly and sets the due date to the next Friday (today counts), unless the text has another date.
- The `!` shortcut works even when natural language parsing is turned off. An explicit date wins over it ("Call mom tomorrow!" is due tomorrow).

These rules are shared with the desktop app and checked against the same test corpus (`test-fixtures/nlp-corpus-v1.json`).

Two syntax modes are available in Settings: **Todoist** (default — `@` for labels, `#` for projects) and **Vikunja** (`*` for labels, `+` for projects).

## Features

- **Smart lists** — Inbox (drag tasks into your own order), Today with an Overdue section, Upcoming, Anytime, Logbook
- **Custom lists** — User-defined filtered views by project, due date window (with an Include overdue option), labels, and sort order, synced with the desktop app
- **Projects with sections** — Collapsible child projects, position-based ordering
- **Project review** — Step through your projects on a cadence, with the same review status as the desktop app
- **Routines** — Recurring routines with reminders, history, and CSV export; history older than 400 days is archived on your Vikunja server
- **Search** — Titles, descriptions, and completed tasks, with cached matches shown at once
- **Labels** — Full CRUD with custom colors, multi-select picker with inline creation
- **Subtasks** — Parent-child task relationships via task relations
- **Due dates and times** — A date without a time stays a date; a date with a time keeps it; the year shows when it is not this one
- **Reminders** — Per-task reminders (absolute time or relative to due date) + configurable daily summary notifications at your local time
- **Attachments** — Upload, open, and share any file type on any task, up to the server's size limit
- **Recurring tasks** — Daily, weekly, monthly, or custom intervals
- **Home screen widgets** — Configurable Glance widgets showing Today, Inbox, Upcoming, any project or custom list, or your routines, with tap-to-complete checkboxes
- **Offline-first** — Room DB as source of truth; changes you make offline are queued, sent in order when the connection is back, and kept across sign-out and cache clears until you choose to discard them. Failed changes are shown with retry and discard, and a sync runs in the background every 30 minutes
- **Dark / light / system themes** — Material 3 dynamic color support
- **Swipe gestures** — Swipe right to complete, swipe left to schedule
- **Accessibility** — TalkBack actions on every task row, including Move up and Move down wherever a list can be reordered
- **Privacy** — No request logging in release builds, and backups leave out the task database and your sign-in tokens
- **Authentication** — OIDC (SSO), username/password with TOTP two-factor, or manual API token entry

## Getting started

### Download

[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/rendyhd/Vicu-Android)

Or grab the latest APK from [GitHub Releases](https://github.com/rendyhd/Vicu-Android/releases).

Vicu Android needs a Vikunja server running version 2.4.0 or newer.

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
