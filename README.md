# Vicu for Android and iOS

**From a thought to done, on every device.** Vicu is a calm, fast task manager for your own [Vikunja](https://vikunja.io/) server, on Android, iPhone and iPad. It keeps your tasks on the device, so it opens and works without a connection, and picks up where your desktop left off.

**[vicutodo.app](https://vicutodo.app/)** · [iPhone and iPad (TestFlight)](https://testflight.apple.com/join/c6wuvXrH) · [Android (Google Play beta)](#get-vicu) · [Android APK](https://github.com/rendyhd/Vicu-Mobile/releases/latest) · [Vicu for desktop](https://github.com/rendyhd/Vicu)

[![Website](https://img.shields.io/badge/website-vicutodo.app-ee9a4d)](https://vicutodo.app/)
![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android%208%2B%20%7C%20iOS%2017%2B-brightgreen)
[![Ko-fi](https://img.shields.io/badge/support-Ko--fi-ff5e5b?logo=ko-fi&logoColor=white)](https://ko-fi.com/rendyhd)

<p>
  <img alt="Today on Android" width="24%" src="https://vicutodo.app/assets/img/screens/android-today-light.webp">
  <img alt="Quick add above the keyboard" width="24%" src="https://vicutodo.app/assets/img/screens/android-quick-add-light.webp">
  <img alt="Today on iPhone" width="24%" src="https://vicutodo.app/assets/img/screens/ios-today-light.webp">
  <img alt="A shared project on iPhone" width="24%" src="https://vicutodo.app/assets/img/screens/ios-shared-light.webp">
</p>

## Get Vicu

- **iPhone and iPad** (iOS and iPadOS 17 or later): [join the public beta on TestFlight](https://testflight.apple.com/join/c6wuvXrH). Install Apple's TestFlight app, open the link on your device, and new versions arrive automatically.
- **Android** (8 or later), any of:
  - **Google Play beta**: join the [vicu-beta Google Group](https://groups.google.com/g/vicu-beta) with the Google account on your phone, [become a tester](https://play.google.com/apps/testing/com.rendyhd.vicu), then install Vicu from [Google Play](https://play.google.com/store/apps/details?id=com.rendyhd.vicu).
  - [Add to Obtainium](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/rendyhd/Vicu-Mobile) to follow the GitHub releases.
  - The latest APK from [GitHub Releases](https://github.com/rendyhd/Vicu-Mobile/releases/latest).

  The Play version and the APK are the same build signed with the same key, so you can switch without reinstalling or losing data.
- **Desktop** (Windows, macOS, Linux): [Vicu](https://github.com/rendyhd/Vicu).

You need a [Vikunja](https://vikunja.io/) **2.4.0 or newer** server you can reach: your own or [Vikunja Cloud](https://vikunja.cloud/). Vicu has no server or account of its own and collects nothing.

## How it works

Organized around when, not where. No boards and no Gantt charts: a task lands in your **Inbox**, gets a day, and ends up in the **Logbook**. Projects and labels are there when you need them.

| List | What it shows |
|------|---------------|
| **Inbox** | Where new tasks land, from quick add, your voice, a widget or a share |
| **Today** | Due today, an Overdue section for what slipped, and today's routines |
| **Upcoming** | Everything from tomorrow on, grouped by day |
| **Anytime** | Your open backlog: every task outside the Inbox |
| **Logbook** | What you finished, grouped by the day you did it |

The **bottom bar** keeps the Inbox first; the other three slots are any smart lists, projects or custom lists you choose. Android, iPhone and iPad run one app from one Kotlin codebase, and the desktop app reads your tasks by the same rules ([`docs/cross-app-semantics-v1.md`](docs/cross-app-semantics-v1.md)), so a due date, a custom list or a task order means the same thing everywhere.

## Capture

- **Quick add** sits right above the keyboard: type a task in your own words and watch the chips (project, labels, date, priority) follow. Expand it to continue in the full editor.
- **Drag + onto a day or project** to start there.
- **Hold + and say it.** Your words appear as you speak, with the date, project and labels picked out. Let go to add it, slide down to cancel.
- **Share to Vicu** from any app: a link, photo or file becomes a task, with the files attached.
- **On Android**: a Quick Settings tile, the Quick add widget with a mic and a + button, and **Add task** and **Dictate** when you hold the app icon.
- **On iPhone and iPad**: "Hey Siri, add a task in Vicu", an **Add task** control for Control Center, the Lock Screen and the Action Button, and **Add task** and **Dictate** when you hold the app icon.
- **Works offline**: whatever you capture without a connection is sent once you are back.

## Type it the way you think

Dates, times, projects, labels, priority, repeats and places are read as you type, highlighted, and shown as chips you can remove. Everything else is the title.

| Token | Examples |
|-------|----------|
| Days and dates | `tomorrow`, `friday`, `next Monday`, `in 3 days`, `jan 15`, `10/15` (in your phone's date order) |
| Times | `3pm`, `at 14:00`, `in 2 hours` |
| Due today | `Buy milk !` |
| Projects, labels | `#work`, `#"side project"`, `@errand`, `@"grocery list"` |
| Priority | `p1`–`p4`, `!urgent`, `!high`, `!medium`, `!low` |
| Repeats | `every friday`, `every 3 days`, `daily` (as the last word) |
| Places | `when I get home`, `when I leave work` |

Prefer Vikunja's syntax? Switch to `*label` and `+project` in **Settings, General, Input Parsing**. Mobile and desktop share one parser and one test corpus ([`test-fixtures/nlp-corpus-v1.json`](test-fixtures/nlp-corpus-v1.json)).

## Organize

- **Projects** with subprojects, progress rings and open counts in the drawer, and one **⋮** menu: edit, add a subproject, sort, set as Inbox, review, archive, delete.
- **Sections** split a project into collapsible groups with their own headers and counts.
- **Custom lists**: your own views by project, label, due window (with or without overdue), priority, recent changes, assignee or unread comments. They sync, so the desktop shows the same lists. Put one in the bottom bar or on a widget.
- **Labels** with colours, created inline from the picker, and a page each.
- **Routines**: habits next to your tasks, up to five times a day, with reminders, history and CSV export. Never overdue.
- **Project review**: step through your projects on a cadence to keep them current.
- **Plan**: an optional timeline for the day. Tap or drag a task onto a time, drag its handle to change the length, or type `tomorrow at 3pm for 30m`. Plan my day takes you from leftovers to a finished day in four steps, with the assistant's suggestions if you want them ([how it works](docs/planner.md)). A block is the task's own start and end date, so the web app and other clients see it too.
- **Task order** per project: due date, priority, title, newest, oldest or manual. Subprojects follow their parent, and widgets follow the order too.
- **Search** titles, notes and finished tasks.

## Working with tasks

- **The task editor** is a sheet over your list. Properties you set show as rows; the rest wait under **Add**. **Today**, **Tomorrow**, **This weekend** and **Next week** move a task with one tap, with Undo.
- **One When sheet** for every date: type "next friday 9am", pick a quick choice, or use the calendar.
- **Swipe** right to complete, left to schedule, with a small tap when the action commits.
- **A second to change your mind**: a finished task stays for five seconds, then collapses. One Undo covers a run of them.
- **Subtasks**, repeating tasks on any interval, and attachments of any type.
- **Drag to reorder**, with a tick as the task passes each position.

## Reminders and sharing

- **Reminders** at a set time or relative to the due date, with **Mark complete** and **Snooze** on the notification, and a **default reminder** for every new dated task.
- **Location reminders**: "Call mom when I get home." Vicu reminds you when you arrive at or leave a saved place, an address or where you are now, also with the app closed. Places are saved in **Settings, Notifications, Places**; their names sync, where they are stays on the phone. Needs location access set to **Always**.
- **Daily summary**: a morning and an afternoon notification with what is overdue, due today and coming up.
- **Share a project** with people on your server to view, edit or manage it, from **Edit project, Shared with**.
- **Assign and comment**: see who is on a task, talk in a thread, mention people with `@`, and follow a task with the bell.
- **Lists for shared work**: Assigned to me, Waiting on others, Unread comments, Changed this week.

If you share nothing, none of this shows. With an API token sign-in, give the token the permissions for comments, assignees and sharing; Vicu tells you which one is missing.

## Widgets

- **Tasks widget** for any list, smart or custom, with tap-to-complete.
- **Routines widget** with today's routines.
- **Quick add widget** on Android, with a mic and a + button.

On Android the widgets follow the Vicu colours or your wallpaper colours and refresh right after a tap; on iPhone and iPad, choose the list in **Edit Widget**.

## Sync and offline

- **Offline first**: your tasks live on the device, so the app opens and works without a connection. Changes wait in a queue and go out in order when you are back; failed ones are shown with retry and discard.
- **No overwrites**: Vicu sends only what you changed, so an edit on your phone never undoes one from your computer.
- **Settings that follow you**: your Inbox, task orders, custom lists, places and location reminders are the same on every device. Device choices such as the theme and the bottom bar stay on each device ([`docs/settings.md`](docs/settings.md)).
- **Nothing in between**: your data goes only to your Vikunja server. No account, no analytics, no ads. On iPhone and iPad your sign-in stays in the Keychain. See the [privacy policy](docs/privacy-policy.md).

**Hidden tasks.** Vikunja has no place for an app's own data, so Vicu keeps custom lists, routines and synced settings in tasks it marks done and hides everywhere. You may see them in other Vikunja apps; leave them in place ([`docs/hidden-tasks.md`](docs/hidden-tasks.md)).

## Look and feel

- Light, dark or system, in the Vicu colours, or your wallpaper colours on Android 12 and later. Every colour pair is checked for readable contrast.
- Motion with a purpose: tabs cross-fade, predictive back, lists move smoothly, counts roll. With animations off, all of it becomes a plain fade.
- Accessible: TalkBack actions on every row, Dynamic Type on iPhone and iPad, haptics for completions, swipes and reordering. Priority shows without colour: one to three bars, and a square for urgent. A left-handed option moves +.

## iPhone and iPad

The same app as on Android, with iOS gestures and system settings. A few things differ because of the platform: iOS keeps a limited number of notifications waiting, so Vicu plans the soonest reminders ahead; background sync runs when iOS allows it; some single sign-on providers, Google among them, do not allow sign-in inside an app (sign in through your browser instead, or use your password or an API token); and device colours and pasting an image into notes are Android only. The full list is in [docs/ios.md](docs/ios.md#differences-from-android).

## First launch

Enter your server URL, sign in with single sign-on (OpenID Connect), a username and password (with two-factor) or an API token, and choose your Inbox project (if another device already chose one, Vicu suggests it). If your identity provider signs you in with a passkey (Pocket ID, for example), choose "Sign in through your browser" and add `com.rendyhd.vicu:/oidc-callback` once as a redirect (callback) URL of the Vikunja client at the provider: passkeys don't work in the in-app sign-in. Allow notifications so reminders can reach you, then add a widget, the Quick Settings tile or the Add task control, and save your places. On Android, a server with a certificate from your own certificate authority works once that certificate is installed on the phone.

## Building from source

Kotlin with Compose Multiplatform and Material 3, with Ktor, Room and Koin. `shared` holds the screens, view models, sync and data layer; `app` is the Android app with its Glance widgets; `iosApp` is the iOS app with its WidgetKit widgets and share extension. JDK 17 or newer; the iOS app needs a Mac with Xcode 26 and XcodeGen.

```bash
./gradlew test
```

```bash
./gradlew installDebug
```

`./gradlew lint` runs Android lint, `./gradlew :shared:iosSimulatorArm64Test` the shared tests on the iOS simulator, and `iosApp/scripts/generate.sh` generates the Xcode project. Building the iOS app is described in [docs/ios.md](docs/ios.md), the architecture and conventions in [`CLAUDE.md`](CLAUDE.md), the rules shared with the desktop app in [`docs/cross-app-semantics-v1.md`](docs/cross-app-semantics-v1.md) and [`docs/design-system-v1.md`](docs/design-system-v1.md) (with identical fixtures in [`test-fixtures/`](test-fixtures/)), and release notes in [`docs/releases/`](docs/releases/).

Found a bug or have an idea? [Open an issue](https://github.com/rendyhd/Vicu-Mobile/issues).

## See also

- **[vicutodo.app](https://vicutodo.app/)**: the tour, with screenshots of mobile and desktop
- **[Vicu](https://github.com/rendyhd/Vicu)**: the desktop app for Windows, macOS and Linux, with Quick Entry, Quick View, voice input, Obsidian and browser links
- **[Vikunja](https://vikunja.io/)**: the open-source backend that powers Vicu
- **[Privacy policy](docs/privacy-policy.md)**: Vicu collects nothing; your data goes only to your own Vikunja server

## Support Vicu

Vicu is free and open source, with no ads, no account and no paid tier. If it helps you, you can [support it on Ko-fi](https://ko-fi.com/rendyhd).

## License

MIT
