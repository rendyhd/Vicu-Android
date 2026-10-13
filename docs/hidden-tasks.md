# Hidden tasks

Vikunja has no place for an app's own data, so Vicu keeps the data that has to reach every
device in ordinary Vikunja tasks and hides them from the user. There are three kinds today.
This document describes each kind, how the apps find, hide and write them, and what happens
when something goes wrong. It covers Android, iOS (the same shared code) and the desktop app.

The formats are part of the contract: `docs/cross-app-semantics-v1.md` section 3 (custom
lists) and section 6 (routines). `docs/settings.md` lists what else syncs and what stays on
the device.

## At a glance

| | Custom-list carrier | Routine carrier | Routine archive part |
|---|---|---|---|
| Holds | Every custom list (name, icon, filter, sort) and their order | One routine: its definition and the last 400 days of history | Routine history older than the main carrier keeps |
| How many | One per account (more only after a race; see "Duplicates") | One per routine | As many as the history needs, per routine |
| Title | `Vicu custom lists (sync metadata — do not delete)` | The routine's name, kept in step with it | `Vicu routine archive` |
| Project | The Inbox project of the device that created it | The Inbox project of the device that created it | The project of the routine's main carrier |
| Marker | `<!-- vicu-custom-lists:v1:<payload> -->` | `<!-- vicu-routine:v1:<payload> -->` | `<!-- vicu-routine:archive:v1:<payload> -->` |
| Payload | `{version, lists: {id: {value, revision}}, order: {ids, revision}}` | `{version, definition, occurrences, prunedBefore}` | `{version, routineId, part, occurrences}` |
| Rest of the description | Replaced on every write | Kept: only the marker is replaced | Nothing but the marker |
| Size | Decoded JSON at most 512 KiB | At most 384 KiB on write, 512 KiB accepted on read | At most 384 KiB per part |
| In the task cache (Room) | Often, but never read from there: the incremental refresh stores every task that changed, and a Logbook page stores the done tasks it lists. Hidden there | Yes: the full reconcile and the incremental refresh keep it up to date | Like the custom-list carrier, and the full reconcile removes it again |
| Written offline | No: the local copy is marked unsynced and written at the next sync | Yes: through the task queue | No |
| Merge | Per list, the newest revision wins | Contract 6.3 | Contract 6.4 |
| Mobile code | `CustomListEnvelope`, `CustomListRepositoryImpl`, `CarrierFinder` | `RoutineEnvelope`, `RoutineRepositoryImpl`, `TaskRefresher` | `RoutineArchiveStore`, `RoutineArchive` |

`<payload>` is the JSON, UTF-8, encoded as base64url without padding.

## What they have in common

- **They are done tasks.** They are created done, in one request, so the open-task queries
  never return them. Every write sends `done: true` again, together with the null due date,
  no repeat and no reminders. An edit someone makes to those fields in Vikunja's web UI is
  undone by the next write.
- **The marker is an HTML comment** in the description. For a hidden task Vicu made, it is the
  whole description; a routine carrier keeps any text someone adds next to it.
- **They are never positioned.** A new task normally goes to the end of its project's list
  view; a hidden task skips that (`TaskRepositoryImpl.create`, `SyncEngine`).
- **They need an Inbox.** Carriers are created in the Inbox project, which each device chooses
  for itself. Before an Inbox is chosen, custom lists do not sync and routines cannot be
  created.
- **They are ordinary tasks to Vikunja.** The web UI shows them with done tasks and in its
  search, the server counts them in a project's done tasks, and a member of a shared project
  sees the ones in it.

## Finding them

Vikunja 2.4's `q` search matches text inside a description's HTML comment, but not a literal
`<!--`, so every search uses the marker name alone, with the filter `done = true`.

- **Custom-list carrier** (`CarrierFinder`, desktop `carrier-discovery.ts`). The ids seen before
  are remembered per server (`CarrierIdStore`, desktop `carrier-ids.json`) and fetched directly.
  A search for `vicu-custom-lists` runs at most once a minute and whenever none is known. All
  done tasks are listed in full only when a remembered id is gone (404 or 403), the first time,
  and once a day, for a server whose search does not read descriptions.
- **Routine carriers** live in the task cache. The full reconcile (`TaskRefresher`) searches
  `vicu-routine` and keeps the tasks whose description has a carrier marker, and the incremental
  refresh brings in the ones that changed. A cached carrier that the search did not return is
  fetched by id (at most 20 per reconcile): it is removed only when the server says it is gone.
- **Archive parts** are read only when needed (History, the CSV export, a write). The store
  searches `vicu-routine:archive`, and lists all done tasks when a routine known to have
  archived history shows no part. Part ids are remembered for the life of the process.

## Hiding them

Both apps decide by the marker, never by the title, and both know two marker names:
`vicu-custom-lists:` and `vicu-routine:` (archive parts match the second).

- **Mobile.** `TaskEntity.isMetadata` is set from the description on every write
  (`CustomListEnvelope.isAnyMetadataTask`) and is an indexed column (schema version 3, A-UI-19).
  Every list query has `isMetadata = 0`: the Inbox, Today, Upcoming, Anytime, a project, the
  Logbook, Search, the daily summary counts and the widget queries. `TaskRepositoryImpl` filters
  the mapped lists again and returns nothing for a hidden task's id. The drawer's progress rings
  take the server's done count and subtract the hidden done tasks the app knows of
  (`ProjectProgressSourceImpl`). When an offline create is retried and the server may already
  have it, a hidden task is taken for the earlier attempt only when it holds the same routine.
- **Desktop.** `hasVicuMetadataMarker` (`src/renderer/lib/metadata-tasks.ts`, regex
  `<!--\s*vicu-(?:routine|custom-lists):[\s\S]*?-->`) is applied by the task, search, quick
  find, Logbook and undo code.

## Writing them

- **Custom lists** (`CustomListRepositoryImpl.sync`). The device keeps the whole document
  locally with an unsynced flag (`CustomListStore`), so editing works offline. An edit marks it
  unsynced and asks for a sync; every sync (`SyncEngine`) runs it too. The sync reads every
  carrier, merges them into the local document, and writes the result to the carrier with the
  lowest id when it differs. The write replaces the title, the description, done, the due date,
  repeat and reminders. Then it reads the carrier back and merges once more, so a write that
  raced another device ends as "pending", not lost. A carrier with a newer version stops the
  sync with "update required" and nothing is written. A malformed carrier is ignored and
  reported, and the valid ones are still used.
- **Routine carriers** (`RoutineRepositoryImpl`). A routine is created as a task through the
  task repository, so an offline create is queued like any task (with a temporary id). An edit
  or a logged occurrence goes through the task queue as well. When it is sent, the app first
  fetches the server's copy, merges the two payloads (contract 6.3), keeps the description text
  outside the marker, and sends the title, the description and the done, due date, repeat and
  reminder fields (`TaskRepositoryImpl.sendUpdate`, and the same step in `SyncEngine` for
  queued edits).
- **Archive parts** (`RoutineArchiveStore`). A write that pushes history past the 400 days
  first appends it to the highest part (or a new one) straight through the API, reads the part
  again right before writing so another device's append is kept, and checks what the server
  stored. Only then does the main carrier drop that history. Without a connection the history
  stays in the main carrier, and a change that needs the archive is refused with a message until
  the server can be reached. Deleting a routine deletes its parts first.

## Duplicates and edge cases

| What happens | Custom-list carrier | Routine carrier |
|---|---|---|
| Two devices create one at the same time | Both are read and merged; the one with the lowest id is written from then on. The other stays on the server, done and hidden | Cannot happen: each routine has its own id |
| Deleted in Vikunja's web UI | The next device that still has the lists creates a new carrier from them. Deleting the carrier does not delete the lists | The routine disappears from the phone after its next full reconcile. Its archive parts stay on the server, unreachable |
| Marked not done, or given a due date, in the web UI | Still hidden (the marker decides). The next write puts the fields back | The same |
| Moved to another project | Still found (by id, or by the search over every project) | Still found. New archive parts go to its new project |
| Description edited in the web UI | A broken marker makes it malformed: it is ignored and every sync reports it. When no readable carrier is left, the next sync that has lists creates a new one | A broken marker shows "routine record needs repair" in the Routines screen. Text added outside the marker is kept |
| Its project archived | Still read by its remembered id. Vikunja refuses changes in an archived project, so writes fail until the project is restored | Still read and shown. Writes fail the same way |
| A newer app version wrote it | Custom lists stop syncing with "update required" | Unknown fields are dropped by mobile's next write (see Known gaps) |

## Known gaps

1. **Carriers are not tied to their owner.** Nothing checks who created a carrier
   (`TaskDto.createdBy` is not read). Vikunja's task search covers every project the user can
   see, shared ones included. So when someone shares a project that holds their carriers, most
   often their Inbox, the other person's Vicu picks them up:
   - the other person's app merges the owner's custom lists into its own, so they see and can
     change the owner's lists, and their own lists can be written into the owner's carrier
     (with read-only access the write fails and custom lists report an error);
   - the owner's routines appear in the other person's Routines screen, with their reminders,
     and logging one writes into the owner's carrier.

   The same happens the other way round when someone picks a project shared with them as their
   Inbox. The fix is a contract rule that a carrier belongs to the user who created it and is
   ignored by everyone else, applied in `CarrierFinder`, the routine reconcile and
   `RoutineArchiveStore`, and in the desktop's discovery.
2. **Only two marker names are hidden.** A new kind of hidden task (for example the synced
   preferences discussed for the task order) shows up as an ordinary done task in every version
   released before it. Hiding any `<!-- vicu-<name>: ... -->` marker, one release ahead, avoids
   that. On mobile that means changing `isAnyMetadataTask` and recomputing `isMetadata` for the
   rows already stored (a migration like version 3's).
3. **Routine payloads lose unknown fields on mobile.** The payload is decoded into typed classes
   and written back from them, so a field added by a newer desktop is dropped. The contract asks
   for unknown fields to be kept only for custom lists (section 3).
4. **A duplicate custom-list carrier is never removed.** It stays hidden and is merged on every
   sync. That costs one extra request and nothing else.
5. **A deleted routine carrier leaves its archive parts behind.** They stay hidden and nothing
   reads them again.
