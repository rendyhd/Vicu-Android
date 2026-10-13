# Settings and how they carry over

Vicu keeps the user's choices in five kinds of places, and each carries over differently: to the
desktop app and other phones, across a sign-out or an account switch, through a backup to a new
device, or only across the app being stopped in the background. This document lists every one
of them for Android and iOS, says what happens to each, and compares the desktop app.

Read it before adding a setting (the checklist is in section 10) and before making a setting
sync with the desktop (section 11).

## Summary

| Where | What | Reaches the desktop and other devices | Survives sign-out | In a backup |
|---|---|---|---|---|
| The Vikunja server (section 1) | Custom lists, routines and their history, each project's review state, manual task positions, project and label fields | Yes | Yes (it is on the server) | Not needed |
| Device preferences, DataStore (section 2) | Almost everything on the Settings screen | No | Yes, except the ones holding the account's ids | Android: most; iOS: none |
| The session, `TokenStorage` (section 3) | Server, sign-in method, tokens, **the Inbox project** | No | No | Android: no; iOS: partly (see 8.2) |
| Widget configuration and state (section 4) | Which view each widget shows, the rows it last drew | No | Android: cleared; iOS: kept | No |
| Saved UI state (section 5) | Open dialogs, drafts, the screen and tab | No | Not applicable | No |

Neither app reads or writes Vikunja's own user settings (`settings` in `GET user`,
`PUT user/settings/general`, `frontend_settings`, `default_project_id`, `week_start`,
`timezone`). Weeks start on Monday (contract section 2), and the time zone, language and 12 or
24 hour clock come from the device.

There is no export or import of settings. The only export is the Routines screen's "Copy history
as CSV" (`RoutineRepositoryImpl.exportCsv`).

---

## 1. Synced through the Vikunja server

These are the only choices the user sees on every device and in the desktop app. Both apps
implement each format, and `docs/cross-app-semantics-v1.md` is the contract for them.

| Data | How it is stored on the server | Merge | Unknown fields from a newer app | Code |
|---|---|---|---|---|
| Custom lists (name, icon, filter, sort, list order) | One done task titled `Vicu custom lists (sync metadata — do not delete)` in the Inbox project. Its description is `<!-- vicu-custom-lists:v1:<base64url JSON> -->` (at most 512 KiB) | Per list, the newest revision wins. A revision is wall time, counter and device id. A deleted list is a tombstone (`value: null`), and the list order is one more record (contract section 3) | Kept in list values and filters (contract section 3). Dropped at the document level | `CustomListEnvelope`, `CustomListRepositoryImpl`, `CarrierFinder` |
| Routines (definition and the last 400 days of history) | One done task per routine, titled with its name, in the Inbox project. Marker `<!-- vicu-routine:v1:... -->` | Contract section 6.3 | Dropped on mobile, which decodes into typed classes. The desktop keeps them inside the definition and the occurrences | `RoutineEnvelope`, `RoutineRepositoryImpl` |
| Routine history older than 400 days | Done tasks titled `Vicu routine archive`, marker `vicu-routine:archive:v1`, in the project of the routine's main carrier | Contract section 6.4 | Not applicable | `RoutineArchiveStore` (API only, never Room) |
| A project's review state (last reviewed, excluded, its own cadence) | Footer in the project description: `---` then `**Vicu review**: 2026-10-01 · every 21 days` | The last write wins; only `description` is sent | The description text above the footer is kept | `ReviewMetadata` |
| Manual task order | Positions in the project's list view (`PUT tasks/{id}/position`) | Server | Not applicable | `ListPositioner`, `TaskRepository.applyPositions` |
| Project order, nesting, colour, favourite, archived; labels | Ordinary project and label fields, sent as merge patches | Server | Not applicable | `ProjectRepositoryImpl`, `LabelRepositoryImpl` |

How the carrier tasks work:

- Carriers are created done (so the server's open-task queries skip them) and in one request.
  Both apps hide them from every list. Mobile does it with the `isMetadata` column, set by
  `CustomListEnvelope.isAnyMetadataTask`; the desktop uses `metadata-tasks.ts`. **Both recognise
  only the `vicu-custom-lists:` and `vicu-routine:` markers.** A carrier with a new marker would
  show up as a done task in an older version (in the Logbook and in search).
- `CarrierFinder` finds the custom-list carrier. It first fetches the ids it remembers
  (`CarrierIdStore`, kept per server). For carriers another device created, it runs a `q`
  search for the marker name (at most once a minute), and a full scan of done tasks only as the
  fallback (a remembered id is gone, or once a day). The desktop does the same in
  `carrier-discovery.ts`. When two devices each created a carrier, the documents are merged and
  the carrier with the lowest id is the one written. Routine carriers are found by the task
  refresh instead (`TaskRefresher.ROUTINE_CARRIER_QUERY`).
- New carriers go in the **Inbox project, which is a choice of each device** (section 3). The
  apps refuse to create one before an Inbox is chosen. Two devices with different Inbox projects
  put their carriers in different projects. They still find each other's, because the search
  covers every project.
- If the Inbox project is shared, the carriers in it are visible to the other members.

---

## 2. Device preferences (DataStore)

Each store is a Preferences DataStore created through `createDataStore` and registered in
`databaseModule` (`di/KoinModules.kt`).

- Android: `files/datastore/<name>.preferences_pb`.
- iOS: `Application Support/datastore/<name>.preferences_pb`. The whole folder is excluded from
  backup (`DataStoreCreator.ios.kt`).

In the table, "Sign-out" is `LocalDataWiper.wipeEverything`. It runs on a sign-out and on a
sign-in that is a different account, or that follows a sign-out (section 6). "Backup" is Android
cloud backup and device transfer (section 8). The desktop column names the desktop
`config.json` key for the same choice. Those are set separately on each app; none of them syncs.

| Store (file) | What it holds, and where it is set | Sign-out | Android backup | Desktop counterpart |
|---|---|---|---|---|
| `ThemePrefsStore` (`theme_prefs`) | `theme_mode` system, light or dark; `use_device_colors` (Android 12 and later). General, Appearance | Kept | Included | `theme` |
| `BehaviorPrefsStore` (`behavior_prefs`) | Completion sound on and the chosen sound; confirm before delete; "Move dated tasks out of Inbox"; swipe-to-schedule action; keep the entry sheet open; left-handed + button; subtask display; subproject display; progress rings and open task counts in the drawer. General, and Projects, Display | Kept | Included | `task_completion_sound_enabled` / `_path`, `confirm_before_delete`, `subtask_display`, `show_project_progress` |
| `NlpPrefsStore` (`nlp_prefs`) | Quick-add parsing on, Todoist or Vikunja syntax, "!" means today. General, Input parsing | Kept | Included | `nlp_enabled`, `nlp_syntax_mode`, `exclamation_today` |
| `NotificationPrefsStore` (`notification_prefs`) | Task reminders; morning and afternoon summaries with their times; what the summary counts (overdue, due today, upcoming); sound; the default reminder (offset and what it is relative to). Notifications tab | Kept (the summaries are cancelled with the account's background work) | Included | `notifications_*` keys |
| `ReviewPrefsStore` (`review_prefs`) | Review on; default cadence (14 days); leave the Inbox out. Projects, Review | Kept | Included | `review.enabled`, `.default_cadence_days`, `.exclude_inbox` |
| `LogbookPrefsStore` (`logbook_prefs`) | Logbook retention on, and its days. General, Logbook | Kept | Included | None |
| `RoutinePrefsStore` (`routine_prefs`) | Routines on and shown in Today (General, Routines); "Pause all reminders" (Routines menu); this device's routine device id | Partly: keeps the two General switches, drops the pause and the device id | Excluded | `routines_enabled`, `routines_in_today` |
| `WidgetPrefsStore` (`widget_prefs`) | Widget smart add, widget opens in context. General, Widget. Read by both platforms' widgets | Cleared | Included | None |
| `BottomBarPrefsStore` (`bottom_bar_prefs`) | The three bottom bar slots (smart list, project or custom list, with icon and colour), smart list colours. General, Bottom bar. Holds project and list ids | Cleared | Included | None |
| `LabelOrderPrefsStore` (`label_order_prefs`) | The drawer's label order (label ids). Dragging labels in the drawer. Vikunja has no label position | Cleared | Included | None |
| `ProjectSectionPrefsStore` (`project_section_prefs`) | Collapsed sections, per project. Collapsing a section in a project | Cleared | Included | `sidebar_collapsed_projects` (the sidebar, not sections) |
| `CustomListStore` (`custom_lists`) | The local copy of the synced custom lists and their sync state (`custom_lists_sync_v1`: device id, document, dirty, carrier id, last sync), plus the older plain list kept for widgets and downgrades | Cleared (`clearLocal`) | Excluded | `custom_lists`, `custom_lists_sync` |

Stores that hold app state, not settings:

| Store (file) | What it holds | Sign-out | Android backup |
|---|---|---|---|
| `CarrierIdStore` (`carrier_ids`) | The carrier task ids found so far, per server | Cleared | Excluded |
| `SyncCursorStore` (`sync_cursor`) | The incremental task refresh cursor and the last full reconcile | Cleared, also by a cache clear | Excluded |
| `TempIdGenerator` (`temp_ids`) | The negative id counter for offline creates and the temporary-to-real id map | Never cleared | Excluded |
| `SnoozeStore` (`snooze_prefs`) | Snoozed reminders | Emptied with the alarms | Excluded |
| `ReminderAlarmRegistry` (`reminder_alarm_registry`) | Android's scheduled reminder alarms | Emptied with the alarms | Excluded |
| `NotificationPermissionStore` (`notification_permission`) | How often the app asked for the notification permission, and whether the after-setup prompt ran | Kept | Included |

---

## 3. The session (`TokenStorage`)

`TokenStorage` holds more than tokens. It also holds the server address, the sign-in method and
OIDC provider, whether the server speaks v2, the user id, the token expiries, the id of the
backup API token this device created, the install id, and **the Inbox project**.

- Android (`AndroidSecureTokenStorage`): DataStore `auth_prefs`. The JWT, the refresh token and
  the API token are encrypted with Tink AES-256-GCM, under a keyset in SharedPreferences
  `vicu_keyset_prefs` wrapped by the Android Keystore key `vicu_master_key`. The other values are
  plain.
- iOS (`IosKeychainTokenStorage`): the three tokens are in the Keychain
  (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). Everything else is in
  `NSUserDefaults.standardUserDefaults`. Keychain items outlive an uninstall, so the first start
  after an install deletes any left behind.
- `clear()` (sign-out) removes everything except the install id. The install id gives the backup
  API token its title suffix (`ApiTokenTitle`), and tokens with the same suffix are treated as
  this device's own leftovers and deleted (`AuthManager.cleanupSiblingTokens`).
- **The Inbox project is a device choice.** It is set during setup and in Settings, Projects,
  Inbox project, and is cleared on sign-out and on an account switch. The desktop has its own
  (`inbox_project_id`), and neither app reads Vikunja's `default_project_id`.

---

## 4. Widgets

| | Android | iOS |
|---|---|---|
| Which view a widget shows | DataStore `widget_configs`, key `config_<appWidgetId>`, written by `WidgetConfigActivity` | The widget's WidgetKit configuration (`SelectViewIntent`; ids such as `today`, `project-12`, `list-<id>`). WidgetKit stores it, not the app |
| What a widget shows | Glance state files `widget_state_*` and `routine_widget_state_*` in the no-backup folder (`WidgetStateFiles`) | JSON files in the App Group (`widgets/catalog.json`, `widgets/views/<key>.json`, `widgets/routines.json`), written by `IosWidgetSnapshots` |
| Widget preferences | `WidgetPrefsStore` (section 2) | The same store |
| On sign-out | Configurations cleared (`clearWidgetConfigurations`); the widgets fall back to Today | Configurations kept. The snapshots are rewritten to the signed-out state |
| Backup | Excluded | The App Group container is not marked excluded |

`TaskWidgetStates` (common code) builds what both platforms' task widgets show, from the
same queries as the screens.

---

## 5. Saved UI state

This is not settings. It is what lets the app come back where it was after the system stops it
in the background: the screen and back stack, the Settings tab and the open dialog, the task
sheet and its unsaved draft, comment drafts, a pending share, picker steps.

- Android: `rememberSaveable`, `SavedStateHandle` (`ProjectViewModel`, `CustomListViewModel`,
  `TagViewModel`) and the navigation back stack go into the activity's saved instance state. It
  survives process death and is not backed up.
- iOS: `UiStateCodec` encodes the same registry to JSON, stamped with the build, into SwiftUI
  `@SceneStorage("vicu.uiState")`. It is restored only by the same build and not after a restore
  that crashed. It is dropped when the user swipes the app away. A type the codec does not know
  is dropped and logged, so a new type kept in `rememberSaveable` needs a codec entry
  (`docs/ios.md`).

---

## 6. Sign-out, account switch, re-login and clearing the cache

| Event | Room database | Account-keyed preferences (bottom bar, label order, collapsed sections, widget toggles, custom lists, carrier ids, routine device id) | Device preferences (theme, behaviour, NLP, notifications, review, logbook, routines on) | Session and Inbox |
|---|---|---|---|---|
| Sign-out (`SessionCleanup`) | Emptied | Cleared | Kept | Cleared (install id kept) |
| Sign-in to a different server or user (`AccountSwitch` says WIPE_ALL) | Emptied | Cleared | Kept | Inbox cleared and chosen again; the new sign-in's values written over the old |
| Sign-in after a sign-out, or on a fresh or restored install | Emptied (no account on record means WIPE_ALL) | Cleared | Kept | Set by the sign-in; the Inbox is chosen again |
| Sign-in again after the session expired (same account still on record) | Kept | Kept | Kept | Tokens replaced |
| General, Clear Cache & Re-sync (`clearCaches`) | Server data without queued changes removed; the queue and the old routine archive kept | Kept | Kept | Kept |
| The same, with "discard unsynced changes" ticked (`discardUnsyncedAndClearCaches`) | Also the queue and every task, label and comment | Kept, including unsynced custom-list changes | Kept | Kept |

Every wipe waits for a running sync and blocks new ones (`SyncEngine.exclusive`). Work that has
not reached the server is never lost without the user confirming: sign-out and an account
switch count the queued changes and unsynced custom-list changes and ask first.

`wipeEverything` also cancels the account's reminders, snoozes, routine alarms and (on iOS)
pending notifications, stops its background work, and redraws the widgets.

---

## 7. Desktop

The desktop keeps its settings in one JSON file, `config.json`, in Electron's `userData` folder
(`src/main/config.ts`, type `AppConfig` in `src/shared/config-types.ts`). Tokens are in
`auth.json`, encrypted with `safeStorage`. Renderer changes are sent as partial patches and
merged in the main process.

- It syncs exactly what section 1 lists, through the same carriers and the same review footer.
  Everything else in `config.json` is local: theme, clock format, notifications, NLP, review
  defaults, routines switches, hotkeys, window state, Quick Entry and Quick View, Obsidian and
  browser links.
- A change of server or account resets `ACCOUNT_SPECIFIC_KEYS` (collapsed sidebar projects,
  custom lists and their sync state, Quick Entry default project, secondary projects, the Quick
  View filter, last used project and label, last username).
- The desktop column in section 2 lists the settings both apps have. Each app keeps its own
  value: a user who changes the theme or the quick-add syntax on one does it again on the other.

---

## 8. Backups and moving to a new device

### 8.1 Android

`allowBackup` is on, with the same exclusions in `backup_rules.xml` and in both sections of
`data_extraction_rules.xml`:

- Excluded: `auth_prefs`, `widget_configs`, `snooze_prefs`, `reminder_alarm_registry`,
  `temp_ids`, `sync_cursor`, `carrier_ids`, `custom_lists`, `routine_prefs`, `auth_debug.log`,
  the `vicu_database` files, `vicu_keyset_prefs.xml` and `routine_alarm_ids.xml`. The Glance
  widget state is in the no-backup folder.
- Included: `theme_prefs`, `behavior_prefs`, `nlp_prefs`, `notification_prefs`, `review_prefs`,
  `logbook_prefs`, `notification_permission`, `widget_prefs`, `bottom_bar_prefs`,
  `label_order_prefs` and `project_section_prefs`.

A restored phone has no session, so its first sign-in is a WIPE_ALL. That clears four of the
included stores again (`widget_prefs`, `bottom_bar_prefs`, `label_order_prefs`,
`project_section_prefs`). Theme, behaviour, quick add, notifications, review and logbook choices
do come back.

### 8.2 iOS

- Excluded from backup: the DataStore folder (every preference), the database, the background
  activity log, and all of Application Support in debug builds.
- The tokens are in the Keychain with "this device only" and never move to another device.
- **Included**, because nothing excludes them: `NSUserDefaults` (server address, sign-in method,
  user id, Inbox project, backup token id, install id), the App Group container (widget snapshots
  with task titles, pending shares), and the copied completion sound in release builds.

So an iPhone restored from a backup comes back with no preferences but with the old session's
identity and Inbox. Its first sign-in to the same account is a KEEP, and the Inbox choice is
skipped. See section 9 for the problems this causes.

---

## 9. Known gaps

Found while writing this document. None of them loses data the user cannot get back from the
server.

1. **iOS restores the install id.** `TokenStorage.getInstallId` says the id "is not backed up, so
   a restored device gets a new one". That holds on Android, but on iOS the id is in
   `NSUserDefaults`. An iPad or second iPhone restored from a backup shares the id with the
   original. Its first backup-token creation then treats the original's backup token as a
   leftover of its own and deletes it (`AuthManager.cleanupSiblingTokens`). The fix is to keep
   the install id, and probably the rest of the session values, out of the backup: in the
   Keychain with "this device only", or in a file in an excluded folder.
2. **Backups differ by platform.** Android brings theme, notification, quick-add and review
   choices to a new phone; iOS brings none of them, but does bring the session identity and
   Inbox (8.2).
3. **iOS widget configurations survive an account switch.** Android clears them. On iOS a widget
   set to `project-12` shows the new account's project 12 if one exists, and none otherwise.
   `IosRepositoryHooks` does not override `clearWidgetConfigurations`.
4. **The iOS App Group is not excluded from backup.** The widget snapshots hold task titles,
   which Android deliberately keeps out of backups (`WidgetStateFiles`). A share saved before a
   sign-out is imported into the next account's Inbox.
5. **Android backs up `notification_permission`.** After a restore it can stop the after-setup
   permission prompt, although the system permission does not carry over.
6. **The completion sound does not survive a move.** On Android the `content://` grant is not
   restored. On iOS release builds the file is backed up but the path pointing to it (in
   `behavior_prefs`) is not.
7. **Sign-out resets two device choices.** The widget toggles and "Pause all reminders" are
   reset with the account-keyed stores, although they hold no ids. Theme and the other device
   preferences are kept.
8. **Routine payloads drop unknown fields on mobile.** The desktop keeps them inside the
   definition and the occurrences, so a field added by a newer desktop is lost the next time this
   app writes the routine. Custom lists keep them in list values and filters.
9. **"Discard unsynced changes" keeps unsynced custom-list changes**, although the sign-out
   warning counts them as unsynced work.
10. **`TempIdGenerator` is never cleared.** After an account switch it still holds the old
    account's temporary-to-real id map. This looks harmless, because temporary ids are never
    reused.

---

## 10. Adding a setting

1. **Decide whose it is.**
   - A choice about this device (how things look or behave here) goes in an existing or new
     preference store.
   - A choice that names the account's projects, labels or lists must be cleared in
     `LocalDataWiper.wipeEverything` (and is usually excluded from Android backup, since the
     ids mean nothing on another account).
   - A choice the user expects to see on the desktop and other phones has to sync (section 11).
2. **Store it.** Add the key to a `*PrefsStore` in `data/local/`, or add a store: create it
   with `createDataStore(get(), "<name>")` in `databaseModule` and run `KoinGraphTest` and
   `IosKoinGraphTest`.
3. **Backups.** On Android a new DataStore file is backed up unless it is added to
   `backup_rules.xml` and to both sections of `data_extraction_rules.xml`. Exclude secrets,
   account ids and state that is rebuilt from the server. On iOS every preference store is
   already excluded.
4. **Wipe.** Add the store to `LocalDataWiper` when it holds account ids, and give it a
   `clear()` that keeps any device-only keys (`RoutinePrefsStore.clear` is the example).
5. **Widgets.** If the widgets read it, call `PlatformRepositoryHooks.updateWidgets()` after a
   change and read it in `TaskWidgetStates` or `WidgetSnapshots`, so both platforms follow.
6. **Document it** in section 2 of this file. When the desktop has the same choice, name its
   `config.json` key.

---

## 11. Making a setting sync with the desktop

A device preference is invisible to the desktop. When a setting changes what the user sees in a
way they expect to match across devices (the open question for the task order in
[PR #34](https://github.com/rendyhd/Vicu-Android/pull/34)), the server has to hold it. These are
the places the Vikunja v2 API offers, as found in its OpenAPI spec (2.4.0):

| Place | Fits | Problems |
|---|---|---|
| A carrier task, like custom lists | Any per-user document. Both apps already have the envelope, discovery (`CarrierFinder`) and per-record last-writer-wins merge, and it works offline and with every sign-in method | A hidden task in the Inbox project, which older versions show unless they hide all `vicu-` markers. Members of a shared Inbox can see it |
| The project description, like the review footer | Per-project data that may be shared with the project's members | Visible text in Vikunja's web UI. Shared with every member, so not a personal choice. Cannot hold app-wide values |
| Vikunja user settings, `frontend_settings` | Free-form JSON stored on the user and returned by `GET user` | `PUT user/settings/general` **replaces** the general settings (name, reminders, default project, week start, time zone, language), so every write must send all of them back. Vikunja's own web frontend writes `frontend_settings` too. An API token reaches only the route groups it was created with (contract 9.7) |
| A project view's `filter` (`sort_by`, `order_by`) | A server-side sort per view | Admin-only to change, shared by every member, changes Vikunja's web UI as well, and cannot express "dated first by due date, then manual" |

Whichever place is chosen, the format, the merge and the meaning of each value go in
`docs/cross-app-semantics-v1.md` with vectors in `test-fixtures/`, in both repos in the same
release.
