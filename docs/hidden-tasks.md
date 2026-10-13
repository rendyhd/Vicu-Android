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
| In the mobile task cache (Room) | Often, but never read from there: the incremental refresh stores every task that changed, and a Logbook page stores the done tasks it lists. Hidden there | Yes: the full reconcile and the incremental refresh keep it up to date | Like the custom-list carrier, and the full reconcile removes it again |
| Written offline | No: the local copy (mobile `CustomListStore`, desktop `config.json`) is marked unsynced and written at the next sync | Mobile: yes, through the task queue. Desktop: no, the write is refused | No |
| Merge | Per list, the newest revision wins | Contract 6.3 | Contract 6.4 |
| Mobile code | `CustomListEnvelope`, `CustomListRepositoryImpl`, `CarrierFinder` | `RoutineEnvelope`, `RoutineRepositoryImpl`, `TaskRefresher` | `RoutineArchiveStore`, `RoutineArchive` |
| Desktop code | `custom-list-protocol.ts`, `custom-list-service.ts`, `carrier-discovery.ts` | `shared/routines.ts`, `renderer/lib/routine-store.ts`, `hooks/use-routines.ts`, `carrier-discovery.ts` | `renderer/lib/routine-store.ts` |

`<payload>` is the JSON, UTF-8, encoded as base64url without padding.

## What they have in common

- **They are done tasks.** They are created done, in one request, so the open-task queries
  never return them. Every carrier write sends `done: true` again, together with the null due
  date, no repeat and no reminders, so an edit someone makes to those fields in Vikunja's web UI
  is undone by the next write (the desktop sends only the ones that differ).
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
- **Routine carriers.** Mobile keeps them in the task cache: the full reconcile
  (`TaskRefresher`) searches `vicu-routine` and keeps the tasks whose description has a carrier
  marker, and the incremental refresh brings in the ones that changed. A cached carrier that the
  search did not return is fetched by id (at most 20 per reconcile) and removed only when the
  server says it is gone. The desktop finds them like the custom-list carrier
  (`carrier-discovery.ts`, search `vicu-routine:v`) and keeps no copy: Routines loads them each
  time (React Query, 30 s) and shows an error offline.
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
  `<!--\s*vicu-(?:routine|custom-lists):[\s\S]*?-->`) is applied by `useTasks` (Today,
  Upcoming, Anytime, Tag, custom lists, the badge, the sidebar open counts), Search, Quick Find,
  the Logbook, the undo window and Quick View. The Inbox, project and Review lists and project
  sections do not check the marker: they read the list view with `done = false`, so a hidden
  task is kept out only because it is done. The sidebar's done count subtracts the known custom
  list and routine carriers, but not archive parts.

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
  queued edits). The desktop does the same merge on a fresh copy but sends only the fields that
  differ from it, never queues (a write is refused offline), and does not read the carrier
  back.
- **Archive parts** (`RoutineArchiveStore`). A write that pushes history past the 400 days
  first appends it to the highest part (or a new one) straight through the API, reads the part
  again right before writing so another device's append is kept, and checks what the server
  stored. Only then does the main carrier drop that history. Without a connection the history
  stays in the main carrier, and a change that needs the archive is refused with a message until
  the server can be reached. Deleting a routine deletes its parts first. The desktop works the
  same way, except that updating an existing part sends only the description.

## Duplicates and edge cases

| What happens | Custom-list carrier | Routine carrier |
|---|---|---|
| Two devices create one at the same time | Both apps read and merge every carrier and write the one with the lowest id. The desktop then deletes an extra once the kept carrier holds everything it holds; mobile leaves it, hidden | Cannot happen: each routine has its own id |
| Deleted in Vikunja's web UI | The next device that still has the lists creates a new carrier from its local copy. Deleting the carrier does not delete the lists | The routine disappears: on the desktop at its next load, on mobile after the next full reconcile. Its archive parts stay on the server, unreachable |
| Marked not done in the web UI | Mobile keeps using it (found by id) and its next write marks it done again. The desktop drops it and creates a new carrier; the reopened one stays open and shows in the desktop's Inbox and project lists | Mobile keeps it, hidden, and the next write marks it done. The desktop drops it: the routine disappears from Routines and its reminders stop |
| Given a due date, repeat or reminders in the web UI | Still hidden. The next write puts the fields back | The same |
| Moved to another project | Still found (by id, or by the search over every project). Writes never send the project | Still found. New archive parts go to its new project; existing ones stay where they are and are still found |
| Description edited in the web UI | A broken marker makes it malformed: it is ignored, never deleted, and every sync reports it. When no readable carrier is left, the next sync that has lists creates a new one. Text added next to the marker is lost on the next write | Mobile shows "routine record needs repair" in the Routines screen; the desktop leaves the routine out without a message. Text next to the marker is kept |
| Its project archived | Neither app handles it. Vikunja refuses changes in an archived project, so writes fail until the project is restored | The same |
| A newer version wrote it | Both apps stop syncing custom lists with "update required"; local edits are still kept locally | Mobile reports it as needing repair, the desktop leaves it out. Neither writes it |

## Where the apps differ

| | Mobile | Desktop |
|---|---|---|
| Routine carriers offline | Created and changed through the task queue; cached in Room, so Routines works offline | Writes refused offline; nothing cached, so Routines shows an error |
| Routine carrier writes | Sends every forced field; uses the server's answer | Sends only the fields that differ from a fresh copy; no read-back |
| Reopened carrier | Still used | Dropped (see above) |
| Duplicate custom-list carriers | Kept, hidden | Deleted once covered |
| Custom-list tombstones | Kept forever | Dropped after 90 days |
| Lists that skip hidden tasks by marker | Every list query (SQL `isMetadata = 0`) | All but the Inbox, project and Review lists and sections, which rely on `done = false` |
| Done counts | Subtract the hidden done tasks it knows, archive parts included | Subtract carriers, not archive parts |
| Unknown routine fields | Dropped | Kept inside the definition and occurrences, dropped at the top level |

## Known gaps

`docs/synced-data-architecture.md` proposes how to fix these.

1. **Carriers are not tied to their owner, in either app.** Nothing checks who created a
   carrier (`created_by`). Vikunja's task search covers every project the user can see, shared
   ones included. So when someone shares a project that holds their carriers, most often their
   Inbox, the other person's Vicu takes them as its own:
   - their app merges the owner's custom lists into its own, so they see and can change the
     owner's lists, and their own lists can be written into the owner's carrier (with read-only
     access the write fails and custom lists report an error). Their desktop can even delete the
     owner's carrier as a covered duplicate; the owner's apps then create a new one from their
     local copy;
   - the owner's routines appear in the other person's Routines screen, with their reminders,
     and logging one writes into the owner's carrier.

   The same happens the other way round when someone picks a project shared with them as their
   Inbox.
2. **The two apps disagree about old tombstones.** The desktop drops deleted-list tombstones
   older than 90 days; mobile keeps them and merges them back. Once a list was deleted more than
   90 days ago, each phone sync writes the tombstone back into the carrier and each desktop sync
   removes it again. Nothing is lost, but the carrier is rewritten over and over, and each
   rewrite makes every device download it again.
3. **Only two marker names are hidden.** A new kind of hidden task (for example the synced
   preferences discussed for the task order) shows up as an ordinary done task in every version
   released before it.
4. **The desktop's Inbox, project and Review lists do not check the marker.** A carrier that
   someone reopened in the web UI appears there as a task. Its sidebar done count also includes
   archive parts.
5. **A reopened carrier splits the apps.** Mobile keeps using it, the desktop creates a new one
   (custom lists) or loses the routine.
6. **Routine payloads lose unknown fields**: all of them on mobile, the top-level ones on the
   desktop. The contract asks for unknown fields to be kept only for custom lists (section 3).
7. **Mobile never removes a duplicate custom-list carrier.** It stays hidden and is merged on
   every sync, one extra request each time.
8. **A deleted routine carrier leaves its archive parts behind**, hidden, and nothing reads them
   again.
9. **Routines work offline on mobile but not on the desktop.**
