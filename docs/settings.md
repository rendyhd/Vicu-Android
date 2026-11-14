# Settings and how they carry over

Vicu keeps the user's choices in five kinds of places, and each carries over differently: to the
desktop app and other phones, across a sign-out or an account switch, through a backup to a new
device, or only across the app being stopped in the background. This document lists every one
of them for Android and iOS, says what happens to each, and compares the desktop app.

Read it before adding a setting (the checklist is in section 10) and before making a setting
sync with the desktop (section 11).

## Changes in 2.2.0

This document describes the apps before 2.2.0. That release (branch `feat/synced-data`, plan in
`docs/superpowers/plans/2026-10-18-synced-data.md`) changes these points on Android and iOS:

- **The Inbox project syncs.** It is the `inbox_project_id` entry of the preferences carrier
  (section 1 below gains a row: `vicu-prefs:v1`, `SyncedPreferences`, contract section 11). The
  session still holds the Inbox the screens read, and `SyncedPreferences` keeps it in step: choosing
  an Inbox writes the entry, a sync that brings another device's choice moves this device's Inbox.
- **The task order** (global and per project) is a synced setting too (`TaskOrderPreferences`).
- **Saved places and location reminders** sync without positions: each place's id and name
  (`place.<id>`) and each task's reminder at a saved place (`location_reminder.<taskId>`), contract
  section 11.7, through `LocationRepository`. Where a place is stays in `SavedPlacesStore`; a
  reminder at an unsaved spot, or of a task without its server id yet, stays in
  `LocationReminderStore`. The desktop picks places for a task's reminder and cannot create them;
  only phones remind.
- A new account-specific store, `SyncedPrefsStore` (`synced_prefs`): cleared by `wipeEverything`,
  excluded from Android backup, counted as unsynced work at sign-out.
- Gap 8 (unknown routine fields), gap 9 (discard keeps unsynced custom-list changes) and gap 11
  (an account switch leaves the old session's values) are fixed.

## Changes with shared routines

Branch `feat/shared-routines` (design `docs/shared-routines.md`, contract sections 6.8 and 6.9):

- A routine can be **shared** through a project the user already shares (`definition.shared`, in
  the routine itself). Its carrier and archive parts then live in that project instead of the
  Inbox, and the people of that project read and write it.
- Two new **per-person synced preferences** in the preferences carrier (contract 11.3): turning
  off the reminders of a routine someone shared with you (`routine_reminders.<routineId>`, value
  `"off"`, null to turn them on again) and hiding it for yourself (`routine_hidden.<routineId>`,
  `true`, null to show it again). `RoutinePersonalPreferences` reads and writes them; Settings,
  General, Routines lists the hidden ones. They are the account's own: they follow it to every
  device and never change the routine for the other person.

## Changes with project categories

Contract section 13; design-system section 5 for the header.

- **Week starts on** (`week_start`, contract section 2.1, `WeekStartPreferences`; Settings, General,
  Preferences) is a synced preference of the account: Monday (the default, written as null), Sunday
  or Saturday. The desktop has the same setting under Settings, Preferences.
- **Task templates** (`template.<id>`, contract section 18, `TaskTemplateStore`) and **the order of a
  task's subtasks** (`subtask_order.<taskId>`, section 19, `SubtaskOrderPreferences`) are synced
  preferences of the account: on every device of that person, never shared with others.
- **Project categories** are synced preferences of the account: `category.<id>` (name and order)
  and `category.project.<projectId>` (the category a top-level project is in), written by
  `ProjectActions` through `ProjectCategoryPreferences` (several keys as one change,
  `SyncedEntries.setAll`). Two people who share a project each file it in a category of their own.
  With none, the project list is as before.
- Which categories are collapsed in the drawer is per device and lasts as long as the app runs
  (`DrawerSectionsExpanded`, like collapsed projects). The desktop keeps it in `config.json`
  (`sidebar_collapsed_categories`, account-specific).

## Changes with guided organizing

Contract section 14; `docs/ai-assistant-v1.md` section 9 for the suggestions.

- **The last weekly review** is a synced preference of the account: `weekly_review.last`, the
  local date it was finished (`"YYYY-MM-DD"`), written by Finish in the weekly review through
  `WeeklyReviewPreferences`. Ask's agenda offers the weekly review while it is 7 days old or more,
  on every device.
- Guided sessions keep nothing of their own: the queue, the choices and Undo all last while the
  screen is open. The suggestions use Ask's provider, model and agreement; there is no setting of
  their own.

## Changes with the day planner

Contract section 16, and 11.3 for the two synced keys. Plan my day is section 14.10; the user guide is
`docs/planner.md`.

- **Default length and work hours** are synced preferences of the account: `planner.default_length`
  (minutes, one of 15, 30, 45, 60, 75, 90) and `planner.hours` (`["HH:mm", "HH:mm"]`), written through
  `PlannerPreferences`. `null` is the default (30 minutes, 08:00 to 18:00) on every device, and a
  value that is not valid is refused.
- **Per device**, in `BehaviorPrefsStore`: the planner on or off (`planner_enabled`, off by default;
  off means no Plan entry, no Day switch on Today and nothing extra fetched), routines on the
  timeline (`planner_show_routines`, on), whether Today opens on Day (`planner_today_day_view`, off)
  and the drawer's last list (`planner_source`). The last list can name a project; it stays through a
  sign-out, and a project the account does not have reads as the first list.
- **Events and calendars** (contract section 20, `docs/planner.md`): your own Events are synced
  preferences of the account (`event.<id>`, written through `PlanCalendars`); an imported calendar
  file (20.10, the gear's Import calendar file) only adds such Events, in one change, and keeps
  nothing of the file. The phone's calendars are per device: the permission (Android READ_CALENDAR,
  iOS EventKit full access; asked when Calendars is opened in the Plan gear) and each calendar's
  choices (shown, colour, counts as busy, Busy only) in `CalendarPrefsStore` (`calendar_prefs`), keyed
  by the provider's or EventKit's calendar id, which differs per device. No event is stored: they are
  read from the calendar provider or EventKit while Plan is shown.
- **The evening nudge** is per device too: `planner_nudge` in `BehaviorPrefsStore`, the time as `HH:mm`,
  null while it is off. It is set in Settings, General, Plan, in the gear on Plan and on the last step of
  Plan my day, and it keeps quiet while the planner is off. Plan my day has no setting of its own: its
  suggestions use Ask's provider, model and agreement, and its choices last while the session is open.
- A time block itself is the task's own `start_date` and `end_date`: it shows on every device,
  whether the planner is on there or not.

## Changes with Pro

Contract section 21; plan `docs/superpowers/plans/2026-11-11-vicu-pro.md` in the desktop repo,
section 9.1. Pro only adds: with it off (the default) nothing below is read, written or shown.

- **Per device**, in `BehaviorPrefsStore`, never synced, kept through a sign-out (the desktop's
  `config.json` keys of the same names): the switch `pro_enabled` (off; turning it off writes nothing
  else), `pro_layers` (JSON, a layer not stored is on; layers a newer version stored are kept),
  `pro_list_views` (Available, Remaining or All per list key), `pro_focus` (categories and projects),
  `pro_assistant`, `pro_verdict_snooze` ("Ask me tomorrow" per promise) and `pro_onboarding_seen`.
  `BehaviorPrefs.isProEnabled(layer)` is the one check; this app has no standalone mode.
- **Synced preferences** of the account (contract 11.3), written only from Pro screens through
  `ProPreferences`: `pro.area`, `project_mode.<id>`, `project_status.<id>`, `label_status.<id>`,
  `label_kind.<id>` and `repeat_rule.<taskId>`. `SyncedPrefKeys.isWritableProValue` refuses values
  the registry does not allow; null resets a key on every device. Every version keeps them.
- **The Pro ledger** (contract 21.7): a hidden carrier `vicu-pro:v1` per account with archive parts
  `vicu-pro-archive:v1` per quarter, synced by `ProLedgerRepository` only while Pro is on. Its local
  copy is a new account-specific store, `ProLedgerStore` (`pro_ledger`): cleared by `wipeEverything`
  and by discarding unsynced work, excluded from Android backup, counted as unsynced work at sign-out
  while Pro is on. Turning Pro off keeps the copy as it is.

## Summary

| Where | What | Reaches the desktop and other devices | Survives sign-out | In a backup |
|---|---|---|---|---|
| The Vikunja server (section 1) | Custom lists, routines and their history, each project's review state, manual task positions, project and label fields | Yes | Yes (it is on the server) | Not needed |
| Device preferences, DataStore (section 2) | Almost everything on the Settings screen | No | Yes, except the ones holding the account's ids | Android: most; iOS: none |
| The session, `TokenStorage` (section 3) | Server, sign-in method, tokens, **the Inbox project** | No | No | No |
| Widget configuration and state (section 4) | Which view each widget shows, the rows it last drew | No | Android: cleared; iOS: kept, but a project or custom list of the old account is no longer shown | No |
| Saved UI state (section 5) | Open dialogs, drafts, the screen and tab | No | Not applicable | No |

Neither app writes Vikunja's own user settings (`settings` in `GET user`,
`PUT user/settings/general`, `frontend_settings`, `default_project_id`, `week_start`,
`timezone`), and they read only two of them, as suggestions: `default_project_id` for the Inbox at
setup, and `week_start` for the "Use Sunday" line under Week starts on. Weeks start on Monday
unless the account chose another week start in Vicu (contract section 2.1), and the time zone,
language and 12 or 24 hour clock come from the device.

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

How the carrier tasks work (`docs/hidden-tasks.md` describes them in full):

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
| `ThemePrefsStore` (`theme_prefs`) | `theme_mode` system, light or dark; `pure_black` (black surfaces in the dark theme, for OLED screens); `use_device_colors` (Android 12 and later). General, Appearance | Kept | Included | `theme` (none for `pure_black`) |
| `BehaviorPrefsStore` (`behavior_prefs`) | Completion sound on and the chosen sound; confirm before delete; "Move dated tasks out of Inbox"; what a swipe right and a swipe left do (`swipe_right`, `swipe_left`, Gestures; `swipe_left` replaces the older `schedule_action`, read until it is written); keep the entry sheet open; left-handed + button; subtask display; task row density (`row_density`); subproject display; progress rings and open task counts in the drawer; the drawer's arrangement: the views hidden from it (`hidden_drawer_views`), the bottom bar's views it lists as well (`drawer_bar_views`) and the order of its views and blocks (`drawer_order`, empty for the default); the planner on or off (`planner_enabled`), routines on its timeline (`planner_show_routines`), whether Today opens on Day (`planner_today_day_view`), the evening nudge (`planner_nudge`), the Plan drawer's last list (`planner_source`). General, Gestures, Plan, and Projects, Display | Kept | Included | `task_completion_sound_enabled` / `_path`, `confirm_before_delete`, `subtask_display`, `row_density`, `show_project_progress`, `sidebar_hidden_lists`, `planner_enabled`, `planner_show_routines`, `planner_view`, `planner_source` |
| `NlpPrefsStore` (`nlp_prefs`) | Quick-add parsing on, Todoist or Vikunja syntax, "!" means today. General, Input parsing | Kept | Included | `nlp_enabled`, `nlp_syntax_mode`, `exclamation_today` |
| `NotificationPrefsStore` (`notification_prefs`) | Task reminders; morning and afternoon summaries with their times; what the summary counts (overdue, due today, upcoming); sound; the default reminder (offset and what it is relative to). Notifications tab | Kept (the summaries are cancelled with the account's background work) | Included | `notifications_*` keys |
| `ReviewPrefsStore` (`review_prefs`) | Review on; default cadence (14 days); leave the Inbox out. Projects, Review | Kept | Included | `review.enabled`, `.default_cadence_days`, `.exclude_inbox` |
| `LogbookPrefsStore` (`logbook_prefs`) | Logbook retention on, and its days. General, Logbook | Kept | Included | None |
| `RoutinePrefsStore` (`routine_prefs`) | Routines on, shown in Today and how many due now Today shows (General, Routines); Today's routine section folded (its header); "Pause all reminders" (Routines menu); this device's routine device id | Partly: keeps the General choices and the fold, drops the pause and the device id | Excluded | `routines_enabled`, `routines_in_today`, `routines_today_limit`, `routines_today_folded` |
| `WidgetPrefsStore` (`widget_prefs`) | Widget smart add, widget opens in context, the task widget's voice button (`widget_voice_button`, on unless turned off; Android only, the iOS widget has none). General, Widget. Read by both platforms' widgets | Cleared | Included | None |
| `BottomBarPrefsStore` (`bottom_bar_prefs`) | The three bottom bar slots (smart list, project or custom list, with icon and colour), smart list colours. General, Bottom bar. Holds project and list ids | Cleared | Included | None |
| `LabelOrderPrefsStore` (`label_order_prefs`) | The drawer's label order (label ids). Dragging labels in the drawer. Vikunja has no label position | Cleared | Included | None |
| `ProjectSectionPrefsStore` (`project_section_prefs`) | Collapsed sections, per project. Collapsing a section in a project | Cleared | Included | `sidebar_collapsed_projects` (the sidebar, not sections) |
| `SavedPlacesStore` (`saved_places`) | Where this phone found each saved place (position and address, by place id), and the one-time move flag. The places' ids and names sync (`place.<id>`, `LocationRepository`); positions never do. Notifications, Places, and "Save as place" in the location reminder | Kept | Excluded (privacy: positions stay on the device) | Reads the synced names, has no positions |
| `CustomListStore` (`custom_lists`) | The local copy of the synced custom lists and their sync state (`custom_lists_sync_v1`: device id, document, dirty, carrier id, last sync), plus the older plain list kept for widgets and downgrades | Cleared (`clearLocal`) | Excluded | `custom_lists`, `custom_lists_sync` |
| `CalendarPrefsStore` (`calendar_prefs`) | Each phone calendar's choices on the Plan screen (contract 20.9): shown, colour, counts as busy, Busy only, by the calendar provider's id. Plan settings, Calendars | Kept | Excluded (calendar ids differ per device) | The desktop's calendar links (`calendar-feeds.json`) |
| `AiSettingsStore` (`ai_settings`) | The assistant (`docs/ai-assistant-v1.md`, section 7.2): provider, address, the dictation and Ask models, what Test connection found per model, the feature switches (smart dictation, Ask, Talk, read answers aloud), whether smart dictation opens as a brain dump, the providers agreed to, the speech model, its language, downloads on mobile data. Settings, Assistant. A device setting: an Ollama address is `localhost` on a computer and a network address on a phone, so nothing syncs (synced keys starting with `ai.` are reserved) | Kept | Excluded | The desktop's own assistant settings |

Stores that hold app state, not settings:

| Store (file) | What it holds | Sign-out | Android backup |
|---|---|---|---|
| `CarrierIdStore` (`carrier_ids`) | The carrier task ids found so far, per server | Cleared | Excluded |
| `ProLedgerStore` (`pro_ledger`) | The local copy of the Pro ledger and its sync state (`pro_ledger_v1`: device id, document, dirty, carrier id, last sync); read and synced only while Pro is on | Cleared | Excluded |
| `SyncCursorStore` (`sync_cursor`) | The incremental task refresh cursor and the last full reconcile | Cleared, also by a cache clear | Excluded |
| `TempIdGenerator` (`temp_ids`) | The negative id counter for offline creates, the temporary-to-real id map, and the creates that may have reached the server (`creates_maybe_sent`) | Never cleared | Excluded |
| `SnoozeStore` (`snooze_prefs`) | Snoozed reminders | Emptied with the alarms | Excluded |
| `ReminderAlarmRegistry` (`reminder_alarm_registry`) | Android's scheduled reminder alarms | Emptied with the alarms | Excluded |
| `LocationReminderStore` (`location_reminders`) | The location reminders that stay on this phone: at a spot that is not a saved place, or of a task created offline until it has its server id. Reminders at saved places sync as `location_reminder.<taskId>` (`LocationRepository`) | Cleared | Excluded |
| `NotificationPermissionStore` (`notification_permission`) | How often the app asked for the notification permission, and whether the after-setup prompt ran | Kept | Included |
| `FabHintStore` (`fab_hint`) | The one-time "Hold + to say a task" hint: tasks added through the + button's quick add (counted up to 5), whether the hint was shown, whether a task was added by holding + | Kept | Included |
| `SavedFilterStore` (`saved_filters`) | The account's Vikunja saved filters as the last project read found them (id and title), whether this device ran its first-connect import, the filters put off with Not now, and the imported lists whose first-open notice was closed (contract section 3.7). Which filters any device imported is in the custom-list carrier (`imported_saved_filters`) | Cleared | Excluded |
| `ConversationStore` (`ai_conversations`) | The last 5 Ask and Talk conversations, each up to 100 messages, with the task ids their tools returned | Cleared (they name the old account's tasks) | Excluded |
| `KeptTranscriptStore` (`ai_kept_transcript`) | A dictation the model could not turn into tasks, kept until it is added or discarded | Cleared | Excluded |
| `AiKeyStore` (Android: Tink-encrypted DataStore `ai_keys`; iOS: the Keychain) | The provider's API key, per provider, and per address for a typed provider (Other, Ollama, Open WebUI). Never synced, never logged | Kept (a device setting); "Delete assistant data" removes it | Excluded |
| Speech models (files in `noBackupFilesDir/speech-models`, iOS Application Support excluded from backup) | Downloaded Whisper and Parakeet models, checked by SHA-256 | Kept | Excluded |
| `AndroidSpeechInput` (SharedPreferences `speech_input`, Android only) | Whether the first hold of + asked for the microphone, so a refusal is told apart from never asked | Kept | Included |

---

## 3. The session (`TokenStorage`)

`TokenStorage` holds more than tokens. It also holds the server address, the sign-in method and
OIDC provider, whether the server speaks v2, the user id, the token expiries, the id of the
backup API token this device created, the install id, and **the Inbox project**.

- Android (`AndroidSecureTokenStorage`): DataStore `auth_prefs`. The JWT, the refresh token and
  the API token are encrypted with Tink AES-256-GCM, under a keyset in SharedPreferences
  `vicu_keyset_prefs` wrapped by the Android Keystore key `vicu_master_key`. The other values are
  plain.
- iOS (`IosKeychainTokenStorage`): the three tokens and the install id are in the Keychain
  (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). Everything else is in
  `Application Support/session/session.json`, in a folder excluded from backup. Keychain items
  outlive an uninstall, so the first start after an install (no session file) deletes any left
  behind. Versions before the synced-data release kept the other values in
  `NSUserDefaults.standardUserDefaults`, which iOS backs up; the first start of a newer one moves
  them when the Keychain still holds this device's tokens, and otherwise (the defaults came from
  another device's backup) drops them and makes a new install id (`SessionFirstStart`).
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
| What a widget shows | Glance state files `widget_state_*` and `routine_widget_state_*` in the no-backup folder (`WidgetStateFiles`) | JSON files in the App Group (`widgets/catalog.json`, `widgets/views/<key>.json`, `widgets/routines.json`), written by `IosWidgetSnapshots`; widget button taps wait in `widgets/actions/` until the app reads them |
| Widget preferences | `WidgetPrefsStore` (section 2) | The same store |
| On sign-out | Configurations cleared (`clearWidgetConfigurations`); the widgets fall back to Today | Configurations kept (WidgetKit owns them). `clearWidgetConfigurations` retires the account's tag (`widgets/account`): the ids of projects and custom lists carry it (`project-12@<tag>`), so a widget chosen under another account says to choose another list. Smart lists show whichever account is signed in. Widget taps not taken in yet are dropped. The snapshots are rewritten to the signed-out state |
| Backup | Excluded | Excluded: `widgets/` and `share-inbox/` in the App Group are marked |

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
| Sign-in to a different server or user (`AccountSwitch` says WIPE_ALL) | Emptied | Cleared | Kept | Inbox cleared and chosen again; the new sign-in's values written over the old, and the ones it does not set are left behind (section 9, gap 11) |
| Sign-in after a sign-out, or on a fresh or restored install | Emptied (no account on record means WIPE_ALL) | Cleared | Kept | Set by the sign-in; the Inbox is chosen again |
| Sign-in again after the session expired (same account still on record) | Kept | Kept | Kept | Tokens replaced |
| General, Clear Cache & Re-sync (`clearCaches`) | Server data without queued changes removed; the queue and the old routine archive kept | Kept | Kept | Kept |
| The same, with "discard unsynced changes" ticked (`discardUnsyncedAndClearCaches`) | Also the queue and every task, label and comment | Kept, including unsynced custom-list changes | Kept | Kept |

Every wipe waits for a running sync and blocks new ones (`SyncEngine.exclusive`). Work that has
not reached the server is never lost without the user confirming: sign-out and an account
switch count the queued changes and unsynced custom-list changes and ask first.

`wipeEverything` also cancels the account's reminders, snoozes, routine alarms, location
reminders (and with them the watched regions) and (on iOS) pending notifications, stops its background work, retires its widget configurations, drops the
shares not added yet (iOS keeps them in `share-inbox/`), and redraws the widgets.

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
  `temp_ids`, `sync_cursor`, `carrier_ids`, `custom_lists`, `saved_filters`, `routine_prefs`, `saved_places`,
  `location_reminders`, `calendar_prefs`, `auth_debug.log`, the `vicu_database` files, `vicu_keyset_prefs.xml`,
  `routine_alarm_ids.xml` and `location_regions.xml`. The Glance
  widget state is in the no-backup folder.
- Included: `theme_prefs`, `behavior_prefs`, `nlp_prefs`, `notification_prefs`, `review_prefs`,
  `logbook_prefs`, `notification_permission`, `widget_prefs`, `bottom_bar_prefs`,
  `label_order_prefs` and `project_section_prefs`.

A restored phone has no session, so its first sign-in is a WIPE_ALL. That clears four of the
included stores again (`widget_prefs`, `bottom_bar_prefs`, `label_order_prefs`,
`project_section_prefs`). Theme, behaviour, quick add, notifications, review and logbook choices
do come back.

### 8.2 iOS

- Excluded from backup: the DataStore folder (every preference), the database, the session file
  (`session/`), the background activity log, the App Group's `widgets/` and `share-inbox/`, and
  all of Application Support in debug builds.
- The tokens and the install id are in the Keychain with "this device only" and never move to
  another device.
- **Included**: the copied completion sound in release builds. The user defaults hold no session
  values any more; a backup made by an older version still has them, and the first start after
  restoring it drops them because the Keychain has no tokens (section 3).

So an iPhone restored from a backup comes back signed out, with no preferences and its own
install id. Its first sign-in is a WIPE_ALL and the Inbox is chosen again, as on Android.

---

## 9. Known gaps

Found while writing this document. None of them loses data the user cannot get back from the
server.

1. **Fixed: iOS restored the install id.** The id was in `NSUserDefaults`, which iOS backs up,
   so an iPad or second iPhone restored from a backup shared it with the original, and its first
   backup-token creation deleted the original's backup token as a leftover of its own
   (`AuthManager.cleanupSiblingTokens`). The install id is now in the Keychain ("this device
   only") and the other session values in an excluded file (section 3). On the first start of
   the fixed version a device whose Keychain holds its tokens moves its values; one without them
   is a restored copy, drops the old session (its backup token id included, so it never revokes
   the original's token) and makes a new install id. `keychain_owned_by_install` tells an update
   from a new install there, and is then removed with the rest.
2. **Backups differ by platform.** Android brings theme, notification, quick-add and review
   choices to a new phone; iOS brings none of them (8.2).
3. **Fixed: iOS widget configurations survived an account switch.** WidgetKit keeps them, so a
   widget set to `project-12` showed the new account's project 12. The ids of projects and custom
   lists now carry the account's tag, which `clearWidgetConfigurations` retires on a sign-out or
   account switch; such a widget says "This list is no longer available. Edit the widget to
   choose another." (section 4). Widgets chosen before the fix belong to the account signed in
   when the fixed version first wrote the catalog.
4. **Fixed: the iOS App Group was not excluded from backup.** The widget snapshots hold task
   titles, which Android keeps out of backups (`WidgetStateFiles`). `widgets/` and `share-inbox/`
   are now excluded, and `wipeEverything` empties `share-inbox/` (`discardPendingShares`), so a
   share saved before a sign-out is no longer imported into the next account's Inbox.
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
11. **An account switch leaves the old session's values behind.** On WIPE_ALL `AccountSession`
    clears the Inbox but not `TokenStorage`, and `AuthManager.onLoginSuccess` and
    `onApiTokenLogin` write only what they set. After a switch to an API-token sign-in, the old
    refresh token, OIDC provider and JWT stay stored, and the old server's backup API token is
    never revoked.

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
way they expect to match across devices (the task order in
[PR #34](https://github.com/rendyhd/Vicu-Mobile/pull/34) was the first), the server has to
hold it. These are
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

`docs/synced-data-architecture.md` is the proposal: one preferences carrier, the rules every
hidden task should follow, which settings should sync, and the order of work.
