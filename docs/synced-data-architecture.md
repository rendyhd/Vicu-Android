# Synced app data: architecture

**Status:** proposal, 2026-10-13. Nothing here is implemented yet. The choices marked
**Decision** are the owner's to make; the recommendation is given with each.

This proposes one architecture for the data Vicu keeps on the Vikunja server for itself (the
hidden tasks) and for the settings that should look the same on every device. It builds on what
exists (`docs/hidden-tasks.md`, `docs/settings.md`), fixes the gaps found there, and gives the
task order of [PR #34](https://github.com/rendyhd/Vicu-Android/pull/34) a way to sync with the
desktop.

## 1. Why

- **Different views on different devices.** Many choices that change what a list shows, or how
  typed input is read, are kept per device on both apps: the Inbox project, the quick-add
  syntax, the review defaults, whether routines show in Today. PR #34 adds another (the task
  order). The owner's rule is that a user must not see different views on different platforms.
- **Every synced feature built its own carrier.** Custom lists and routines each have their
  own envelope, discovery and merge. A third (task order) would copy them again.
- **The hidden tasks have gaps** (`docs/hidden-tasks.md`, Known gaps). Nothing checks who
  created a carrier, so a shared project hands one user's custom lists and routines to another.
  Only two marker names are hidden. Mobile drops unknown routine fields. The desktop prunes
  custom-list tombstones after 90 days but mobile does not, so the two rewrite the carrier
  against each other on every sync once a tombstone is that old. Mobile never removes a
  duplicate carrier; the desktop does, and in a shared project it can delete another user's
  carrier that way. The desktop's Inbox, project and Review lists rely on `done = false`
  instead of the marker, and a carrier reopened in the web UI is kept by mobile but dropped by
  the desktop.

## 2. Goals and limits

Goals:

- One mechanism for small synced settings, shared by every setting that should match across
  devices.
- One set of rules for every hidden task: shape, marker, hiding, ownership, discovery,
  duplicates, versions, unknown data.
- Works with what Vikunja 2.4 offers, with every sign-in method (API tokens included), offline
  first, and with older app versions still running.

Not in scope:

- Device settings (theme, sounds, notifications, gestures, widgets, windows and hotkeys) stay on
  the device.
- Custom lists and routines keep their formats. They get the common fixes, not a migration.
- Vikunja's own user settings (`frontend_settings`) are not used (section 10).

## 3. Recommendations at a glance

| # | Question | Recommendation |
|---|---|---|
| 1 | Where do synced settings live? | One generic preferences carrier, `vicu-prefs:v1`, holding keyed entries (section 6) |
| 2 | Whose is a hidden task? | The user who created it (`created_by`). Every other user's apps hide it and ignore it (5.3) |
| 3 | Which hidden tasks are hidden? | Any description with a `<!-- vicu-<name>:` marker, known or not (5.1) |
| 4 | Unknown data from a newer app | Kept at every level of every payload, routines included (5.8) |
| 5 | Deleted entries (tombstones) | Dropped after 90 days by both apps, as a contract rule (5.6) |
| 6 | Duplicate carriers | Merged, then the extras deleted once the kept one holds everything, by both apps (5.5) |
| 7 | Which settings sync? | The ones that change what a list shows or how input is read (section 7). **Decision** |
| 8 | The Inbox project | Synced (section 7.2). **Decision** |
| 9 | Code | One "synced document" engine per app, used by custom lists and preferences (section 8) |
| 10 | Order of work | Groundwork release first, then the preferences carrier (section 11) |

## 4. The layers

```
 Screens and view models
   read typed values: taskOrder(projectId), inboxProjectId, reviewDefaults, nlp, ...
        │
 SyncedPreferences            CustomListRepository            RoutineRepository
   typed keys, defaults         lists, filters, order           definitions, history
        │                            │                              │
        └──────────┬─────────────────┘                              │ (task queue, as today)
                   │                                                │
 SyncedDocumentSync<Doc>  local copy + unsynced flag (DataStore)    │
   read every own carrier, merge, write the canonical one,          │
   read back, prune, delete covered duplicates                      │
                   │                                                │
 CarrierFinder(spec)  remembered ids · q search · full scan · owner filter
                   │                                                │
 VikunjaApiService   GET tasks?q=…&filter=done = true · GET/PATCH tasks/{id} · POST projects/{id}/tasks
```

- **`CarrierFinder`** already takes a `CarrierSpec` (kind, search text, recognizer). It gains
  the owner filter. The routine reconcile in `TaskRefresher` and `RoutineArchiveStore` apply the
  same filter.
- **`SyncedDocumentSync<Doc>`** is today's `CustomListRepositoryImpl.sync` loop, made generic
  over a codec (parse, encode, normalize, merge, prune, compare). Custom lists move onto it
  without changing their format.
- **`SyncedPreferences`** is new. It exposes each setting as a typed `Flow` read from the local
  copy, so reading never waits for the network, and writes go to the local copy with the unsynced
  flag. `TaskOrderPrefsStore` from PR #34 becomes a thin view of it.
- **Routines** keep their own path (the task queue, one carrier per routine, archive parts):
  their payloads are large and per-routine, and the queue already gives them offline creates.

The desktop gets the same split: a generic engine in the main process next to
`custom-list-service.ts`, the local copy in `config.json`, the renderer reading through IPC.

## 5. Rules for every hidden task

These become a new section of `docs/cross-app-semantics-v1.md`, with vectors.

### 5.1 Marker and hiding

- The marker is `<!-- vicu-<kind>:v<N>:<payload> -->`. `<kind>` is lowercase letters, digits
  and hyphens, and may contain a colon-separated sub-kind (`routine:archive`). `<payload>` is
  UTF-8 JSON in base64url without padding.
- **Both apps hide every task whose description matches `<!--\s*vicu-[a-z0-9-]+:`**, whether or
  not they know the kind. That is what lets a later version add a kind without older versions
  showing it.
- **Every list, count and query hides by the marker, never by `done` alone.** A hidden task
  that someone reopened must not appear anywhere.
- Mobile: change `CustomListEnvelope.isAnyMetadataTask` (better: move it to a `HiddenTasks`
  object), and recompute `isMetadata` for the rows already stored with a Room migration (5,
  after version 4) that runs the `LIKE '%<!--%vicu-%:%-->%'` backfill, like version 3 did.
- Desktop: widen the regex in `metadata-tasks.ts`, apply it in the Inbox, project and Review
  lists and the project sections (`use-project-tasks.ts`, `use-project-sections.ts`, the undo
  merges), and subtract archive parts from the sidebar's done count.

### 5.2 Shape

- Created done, in one request, with the null due date, no repeat, no reminders, no labels and
  no assignees. Never positioned in a list view.
- Every write sends done, the null due date, no repeat and no reminders again, so an edit in the
  web UI is undone.
- Title: a readable name that says not to delete it, except routine carriers, whose title is
  the routine's name.
- **A reopened hidden task is still the same carrier.** Apps keep using it (a remembered id is
  fetched whatever its state), stay hiding it by its marker, and the next write marks it done
  again. Today mobile does this and the desktop drops it, which creates a second custom-list
  carrier and loses the routine on the desktop.

### 5.3 Ownership

- **A hidden task belongs to the user who created it** (`created_by.id`, which the server sets
  and nobody can change). An app reads, merges, writes and deletes only its own user's hidden
  tasks. Hidden tasks of other users are still hidden, and otherwise ignored.
- This stops the shared-project leak in both directions: a member of a project that holds
  someone's carriers, and a user who picked someone else's shared project as their Inbox.
- The app needs its user id. `AccountSession` stores it at sign-in; a session from before that
  reads it once with `GET user`. Until it is known, discovery waits.
- A carrier created with an API token belongs to the token's user, so all sign-in methods work.
- Data already merged across users by the leak cannot be told apart afterwards; the fix stops
  further mixing. The release notes should say so.

### 5.4 Placement and discovery

- New carriers go in the Inbox project. The search covers every project, so a carrier that was
  moved, or created by a device with another Inbox, is still found.
- Discovery is `CarrierFinder` (desktop `carrier-discovery.ts`) for every kind except
  routine carriers on mobile, which the task refresh keeps in Room. It fetches the remembered
  ids per server, runs a `q` search for the kind name with `done = true` at most once a minute,
  and lists all done tasks when a remembered id is gone and once a day.

### 5.5 Duplicates

- Every own carrier of a kind is read and merged. The one with the lowest id is written.
- An extra carrier is deleted once the kept one holds everything it holds (after pruning). The
  desktop does this today (`removeDuplicateCarriers`); mobile adds it.

### 5.6 Revisions, tombstones and pruning

- Documents made of records use the custom-list revision: `{wall_time_ms, counter, device_id}`,
  compared in that order. The newest record wins.
- A deletion is a record with `value: null`. Both apps drop tombstones older than 90 days
  before writing. A device that was offline longer than that may bring a deleted record back;
  that is accepted so documents stop growing. Mobile adds `pruneTombstones` to match the
  desktop, which ends the rewrite loop described in section 1.

### 5.7 Versions and damage

- A carrier with a higher version than the app knows makes that kind read-only with "update
  required"; it is never written. Routines get this too: today mobile calls such a routine
  damaged and the desktop leaves it out without a word.
- A malformed carrier is ignored and reported; the valid ones are used.

### 5.8 Unknown data and size

- Unknown fields are kept at every level, in every kind, and written back unchanged. For
  routines that is new on both apps: mobile decodes into typed classes and drops them, and the
  desktop drops unknown top-level fields on merge. Mobile can reuse custom lists'
  `UnknownFieldsSerializer`.
- The decoded JSON stays within 384 KiB when written; 512 KiB is the most an app accepts.

## 6. The preferences carrier

### 6.1 Format

- Title `Vicu preferences (sync metadata — do not delete)`, marker `vicu-prefs:v1`, one per
  account (5.5).
- Document:

```json
{
  "version": 1,
  "entries": {
    "task_order": {
      "value": "due_date_first",
      "revision": { "wall_time_ms": 1792000000000, "counter": 0, "device_id": "4f1c…" }
    },
    "task_order.project.42": {
      "value": "manual",
      "revision": { "wall_time_ms": 1792000500000, "counter": 0, "device_id": "9a07…" }
    },
    "task_order.project.7": {
      "value": null,
      "revision": { "wall_time_ms": 1792000900000, "counter": 1, "device_id": "4f1c…" }
    }
  }
}
```

### 6.2 Rules

- **Merge:** the union of keys; for a key in both documents, the newer revision wins (5.6).
- **Null means default.** Choosing "Default" writes `value: null`, so the reset reaches every
  device. It is pruned after 90 days like any tombstone.
- **Defaults are never written.** A key without an entry uses its default. Only a choice the
  user makes creates an entry. A new device therefore cannot overwrite anyone's settings with its
  defaults.
- **Unknown keys and values are kept.** A key this version does not know is kept and not used.
  A value it does not understand (an order a newer app added) acts as the default and is kept
  unchanged.
- **Per-project keys** are `<setting>.project.<id>`. They are left in place when a project is
  deleted (a few bytes each) rather than guessed away by a device that has not seen every
  project yet.
- **Registry:** the contract lists every key with its type, default and allowed values. A key
  is added to the registry, in both repos, in the release that starts writing it.

### 6.3 Local side and sync

- Mobile: a DataStore file `synced_prefs` holding `{device_id, document, dirty, carrier_task_id,
  last_synced_at}`, the same shape as `custom_lists_sync_v1`. It is cleared by
  `LocalDataWiper.wipeEverything`, excluded from Android backup, and counted as unsynced work at
  sign-out (as custom lists are).
- A change updates the local copy at once, marks it unsynced and asks for a sync. `SyncEngine`
  runs the preferences sync next to the custom lists. Writing needs a connection; until then
  the local copy is what the app uses.
- Desktop: `synced_prefs_sync` in `config.json`, owned by the main process like
  `custom_lists_sync`, added to `ACCOUNT_SPECIFIC_KEYS`.

### 6.4 A new device

- After sign-in, setup runs one preferences sync before it asks for the Inbox, so the synced
  Inbox is preselected (7.2). Without a connection, setup works as today.

### 6.5 Making an existing setting sync

When a release starts syncing a setting that both apps already keep locally:

- After its first successful read of the carrier, each device writes its local value as an entry
  only if the value differs from the default and the carrier has no entry for that key yet.
- If two devices do this for the same key, the later write wins. That is acceptable for a
  one-time move. The release notes say which settings now sync.

## 7. What syncs

### 7.1 Recommended split (**Decision**)

The rule: **sync what changes what a list shows or how typed input is read; keep on the device
what is about this device** (its look, sounds, notifications, gestures, widgets and windows).

| Setting | Mobile today | Desktop today | Recommendation |
|---|---|---|---|
| Task order, global and per project | PR #34 (`TaskOrderPrefsStore`) | none | **Sync** (section 9) |
| Inbox project | `TokenStorage` | `inbox_project_id` | **Sync** (7.2) |
| Quick add on, syntax, "!" means today | `nlp_prefs` | `nlp_enabled`, `nlp_syntax_mode`, `exclamation_today` | **Sync** |
| Review on, default cadence, Inbox left out | `review_prefs` | `review.*` | **Sync**: the per-project review state already syncs, and a different default cadence puts different projects in Review |
| Routines on, routines in Today | `routine_prefs` | `routines_enabled`, `routines_in_today` | **Sync**: changes the Today list |
| Default reminder and what it is relative to | `notification_prefs` | `notifications_default_reminder_offset` / `_relative_to` | **Sync**: changes the tasks created |
| Subtask display, progress rings | `behavior_prefs` | `subtask_display`, `show_project_progress` | Sync (optional) |
| Move dated tasks out of Inbox, subproject display | `behavior_prefs` | none | Sync once the desktop has them |
| Confirm before delete | `behavior_prefs` | `confirm_before_delete` | Device |
| Theme, device colours, completion sound | `theme_prefs`, `behavior_prefs` | `theme`, `task_completion_sound_*` | Device |
| Notifications and daily summaries | `notification_prefs` | `notifications_*` | Device |
| Widgets, bottom bar, swipe action, button side, keep entry open | `widget_prefs`, `bottom_bar_prefs`, `behavior_prefs` | none | Device |
| Windows, hotkeys, Quick Entry and Quick View, Obsidian | none | various | Device |
| Collapsed sections, carrier ids, cursors, last used project | several | several | Device, cleared with the account |

### 7.2 The Inbox

- Today each device picks its own Inbox, and the carriers go in whichever one created them. A
  different Inbox per device is the biggest "different view" there is.
- Recommended: `inbox_project_id` is a synced entry. A device without an entry asks during
  setup, as today, and writes the answer.
- Vikunja's `default_project_id` (from `GET user`) can be the preselected answer on a first
  setup. It is read only; neither app writes Vikunja's user settings (section 10).
- The Inbox is cleared with the account as today; an account switch reads the new account's
  entry.

## 8. Code shape

Mobile (`commonMain`, so Android and iOS share it):

| Piece | What it is |
|---|---|
| `HiddenTasks` (util) | Marker grammar, `isHidden(description)`, owner check, shared task shape for create and write |
| `CarrierSpec` / `CarrierFinder` | As today, plus `ownerId` and the duplicate list |
| `SyncedDocumentCodec<Doc>` | parse, encode, normalize, merge, pruneTombstones, same |
| `SyncedDocumentSync<Doc>` | The loop from `CustomListRepositoryImpl.sync`, made generic: read own carriers, merge with the local copy, write the canonical one when it differs, read back, delete covered duplicates, report status (idle, pending, offline, update required, error) |
| `SyncedDocumentStore` | The local copy and unsynced flag in DataStore, one file per kind |
| `SyncedPreferences` | Typed keys (`TaskOrderKey`, `InboxKey`, ...) with defaults, `Flow` readers, setters |
| `CustomListRepositoryImpl` | Keeps its API; its sync becomes `SyncedDocumentSync<CustomListSyncDocumentV1>` |

Wiring: `SyncEngine` runs both document syncs; `LocalDataWiper` clears both local copies;
`SessionCleanup` counts both as unsynced work; changes call `updateWidgets()` where a widget
reads the value (task order). Run `KoinGraphTest` and `IosKoinGraphTest`.

Desktop: the same pieces in `src/main` (`hidden-tasks.ts`, a generic document service next to
`custom-list-service.ts`, `synced-preferences.ts`) and the regex in `metadata-tasks.ts`.

## 9. The task order (first user)

- Keys: `task_order` (global) and `task_order.project.<id>`. Values: `due_date_first`
  (default), `due_date_priority`, `manual`, `priority`, `title`, `newest`, `oldest`. An unknown
  value acts as `due_date_first`.
- A project uses its own entry, else its nearest ancestor's, else the global one (PR #34's
  `TaskOrderSettings.orderFor`, cycles ended).
- Sort rules reuse the custom-list sort both apps already share (`CustomListFilterBuilder.sortTasks`,
  desktop `custom-list-sort.ts`): titles compared lowercased, dates parsed, missing dates last.
  Ties fall back to the position, then the task id, so both apps put equal rows in the same
  place.
- Drag: every row in `manual`; undated rows in `due_date_first`; none in the others. Move up and
  Move down follow the same rule.
- Widgets: a project or Inbox widget lists in its project's order, built in `TaskWidgetStates`
  so iOS follows too. Today, Upcoming, Anytime and custom lists are unchanged.
- Vectors: a `taskOrder` block in `cross-app-semantics-v1.json` (each order, ties, inheritance,
  drag rules).
- PR #34 keeps its screens and logic. It moves its storage to `SyncedPreferences`, its widget
  change to `TaskWidgetStates`, the `SettingsViewModel` binding to the shared
  `ViewModelModule.kt`, and its sort to the shared rules. The desktop adds the same settings,
  menu entries, sort and drag rules in the same release.

## 10. Alternatives considered

| Alternative | Why not |
|---|---|
| One carrier per setting (`vicu-task-order:v1`) | Each kind adds a hidden task and a search to every sync, and needs its own code and hiding rule. One preferences document covers every small setting |
| Vikunja user settings (`frontend_settings`) | `PUT user/settings/general` replaces all general settings (name, default project, time zone, week start, language), so every write must send them all back; Vikunja's web frontend writes the same field; an API token may not reach the route |
| A project view's sort (`filter.sort_by`) | Only admins can change it, every member of a shared project shares it, it changes Vikunja's web UI too, and it cannot say "due date, then manual" |
| The project description, like the review footer | Visible text in Vikunja, shared with members, per project only (no place for the global value) |
| A dedicated "Vicu" project for every hidden task | Removes the Inbox dependence and keeps hidden tasks out of shared projects, but it is a visible project in Vikunja and every other client, both apps would have to hide it from every project list and picker, and existing carriers would have to move. The owner rule (5.3) fixes the safety problem for much less. Worth revisiting if more kinds appear |
| Moving custom lists into the preferences document | Saves one carrier, but migrates a working format for no user-visible gain |

## 11. Order of work

**Release A, both apps (groundwork, nothing new written):**

1. Contract section "Hidden tasks" (section 5) with vectors: marker detection (known, unknown,
   malformed kinds), ownership, tombstone pruning.
2. Hide every `vicu-` marker; mobile Room migration 5 recomputes `isMetadata`.
3. The owner filter for custom lists, routine carriers and archive parts.
4. Mobile: tombstone pruning and duplicate deletion, matching the desktop.
5. Desktop: the marker check in the Inbox, project and Review lists and sections, archive parts
   out of the done count, reopened carriers kept, "update required" for routines.
6. Unknown routine fields kept on both apps.
7. The iOS fixes from `docs/settings.md` section 9 can ride along (install id out of the
   backup, widget configurations cleared on sign-out, App Group excluded from the backup).

**Release B, both apps (some weeks after A, so most devices already hide the new carrier):**

1. Contract section "Synced preferences" (section 6) with the key registry and vectors (merge,
   null, unknown keys and values, pruning).
2. The engine (section 8), custom lists moved onto it, `SyncedPreferences`.
3. The task order (section 9), with PR #34 rebased onto it, and the Inbox (7.2).

**Release C and later:** the other settings from 7.1, one group at a time, each with the
one-time move in 6.5. Separately, the desktop can give routines the offline behaviour mobile
has (a cached copy and queued writes); today Routines shows an error on the desktop without a
connection.

A device on a version older than A shows the preferences carrier as one done task until it
updates; with B a few weeks behind A, that is a small group.

## 12. Testing

- Contract vectors run in both apps: marker detection, ownership, the preferences merge,
  tombstone pruning, the task order. `SharedContractIdentityTest` keeps the files identical.
- Mobile engine tests against `FakeTaskServer`: two devices editing at once, an offline edit,
  a duplicate carrier, a carrier of another user in a shared project, a newer version, a
  malformed carrier, a sign-out with unsynced preferences.
- Room migration 5 test, like `TaskMetadataMigrationTest`.
- `KoinGraphTest`, `IosKoinGraphTest`, and the desktop's own suites.
- By hand: two accounts on the seeded server sharing an Inbox (`collab.mjs` has the setup), a
  phone and the desktop changing the same setting.

## 13. Decisions for the owner

1. Which settings sync (7.1). The recommendation is the bold rows first.
2. Whether the Inbox syncs (7.2). Recommended yes.
3. The generic preferences carrier instead of a task-order carrier (section 3, row 1).
4. Splitting the work into release A (fixes) and release B (preferences), so the new carrier
   is hidden by most devices before it is written.
