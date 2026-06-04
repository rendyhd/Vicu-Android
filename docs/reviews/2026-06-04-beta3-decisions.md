# Vicu Android — Beta 3 Feedback: Decisions & Roadmap

**Date:** 2026-06-04
**Source:** Decisions made walking through `2026-06-04-beta3-feedback-review.md`.
**Status:** Decisions only — nothing implemented yet. This is the agreed work list.

**Outcome in one line:** essentially *everything* in the report is approved. The only
"no"s are the forks below (undo bar removed, delete stays immediate). A few items were
modified from the report's suggestion — those are called out with **[MODIFIED]**.

---

## Resolved forks (the genuine decisions)

- **Completion undo bar → REMOVED entirely** (§4.4). Rely on existing tap-the-task-again
  undo (strikethrough row + `undoComplete`). Still factor a single `VicuSnackbarHost`
  (the host is duplicated across 11 screens) for the remaining error snackbars.
- **Task delete → stays IMMEDIATE** (§8.4). No delayed / "panic" delete. The existing
  `confirmBeforeDelete` dialog is the safeguard.
- **Task titles → WRAP** (§4.1). `maxLines = 3`, drop `singleLine`, in **both** add +
  detail sheets. **Build step:** device-test the NLP visual-transform + autocomplete
  popup, which assume a single-line field. Keep the font size (no shrinking).

---

## Tier 0 — Quick wins (all approved)

Copy rewrites in `SettingsScreen.kt` (§6):
1. `:1083` → "Older completed tasks are hidden from Logbook. Nothing is deleted."
2. `:1061` → "Tasks with a due date no longer appear in Inbox." (drops the Things 3 name-drop)
3. `:1170` → "Add ! to set the due date to today."
4. `:1196` → "Plays the system notification sound. Tap below for a custom sound."
5. **[MODIFIED]** `:1744` "FAB (+)" — **delete the whole FAB-explanation line from the
   gestures page** (don't rename it; it's redundant there).

Other quick wins:
6. `TaskEntrySheet.kt:268` "Remind" → "Reminder"; filled state reads "N reminders".
7. `SwitchRow` (`SettingsScreen.kt:1888–1918`) — add `Arrangement.spacedBy(2.dp)`.
8. `SearchScreen.kt:54` — add `.windowInsetsPadding(WindowInsets.statusBars)` +
   `.imePadding()` (highest-value one-liner; also closes §8.3 search-bar item).
9. Custom drag-handle on bottom sheets to suppress the framework long-press tooltip.
10. Splash: `DayNight` theme parent + `values-night/themes.xml` (kills dark-mode flash).
11. `TaskDetailScreen.kt:251` — replace bare `AssistChip { Text("+") }` with `Icon(Add) + "Add label"`.
12. Add-task **Priority chip** — one `AssistChip` in the `FlowRow`, reuse existing
    `PriorityPickerDialog` (NLP already parses priority; only the tap target is missing).
13. **[MODIFIED]** Review screen (`ReviewScreen.kt`) — put a **dot/bullet before each task**
    (not a checkmark; the "✓" at `:183` couldn't be found in the running build — verify
    what actually renders) **and add dividers** so tasks stop blending.
14. **Plus-only FAB** — plain `FloatingActionButton` + `Add` icon; drop the shrink/expand
    label and the `listState` coupling from all call sites. Unify FAB contentDescription
    strings while here.

---

## Tier 1 — Correctness bugs (all approved; do before more features)

From §7:
1. **Recurrence dropped on create** — add `repeat_after`/`repeat_mode` to `CreateTaskDto`
   (`TaskDto.kt:38–46`) + map in `Task.toCreateDto()` (`TaskMapper.kt:199–206`). Fixes
   both online + offline-queued create.
2. **Offline label queue clobbers itself** — `add_label`/`remove_label` must `insert()`
   unconditionally instead of `replaceForEntity` per-label (`LabelRepositoryImpl.kt:147/163`,
   `PendingActionDao.kt:42`).
3+4. **Exact-alarm pair (bundled, do together now):** remove `USE_EXACT_ALARM`
   (`AndroidManifest.xml:7`, Play-rejection risk) **and** add a Settings banner that
   deep-links to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` + an inexact `setAndAllowWhileIdle`
   fallback (`AlarmScheduler.kt:99–102`). Implements CLAUDE.md gotcha #5.
5. **"At due time" reminder never fires** — guard `resolveReminderTime` so period 0 with a
   non-blank `relativeTo` means "at the base date" (`AlarmScheduler.kt:112–130`).
6. **Monthly recurrence never shows as recurring** — treat `repeatMode == 1` as recurring in
   `formatRecurrence` + the gates at `DateUtils.kt:136`, `TaskItem.kt:133`, `TaskDetailScreen.kt:346`.
7 (report #14). **Inbox-project delete guard** — disable delete when `project.id == inboxProjectId`.

Promoted to must-fix (originally Medium):
- **#9 Cyclic-parent StackOverflow crash** — `ProjectEditDialog` must exclude descendants
  (not just self, `:57`) + add a visited guard in `buildProjectTree`.
- **#13 Alarm request-code overflow** — replace `taskId.toInt() * 100 + index` with a
  collision-free scheme (large server IDs currently let one task's cancel hit another's alarms).

---

## §7 — Remaining bugs (all approved)

Medium:
- **#7** `refreshAll()` — call `deleteNotIn` so tasks deleted/completed elsewhere don't
  linger until WorkManager runs (`TaskRepositoryImpl.kt:484–516`).
- **#8** `update()` — roll back the optimistic Room write on non-retriable failure (match
  `toggleSubtaskDone`).
- **#11** Offline label add/remove — optimistic Room patch so chips aren't stale (pairs with #2).
- **#12** Daily summary — differentiate morning/afternoon copy + notification IDs; stop
  overdue leaking into the "today" list.
- **#10** Pending-action queue — add `ORDER BY` to `getRetryable()` for causal ordering.

Low tail (all approved):
- DST drift in the daily-summary 24h period.
- Snoozed reminders lost on reboot.
- Widget periodic policy `KEEP` → `UPDATE` (so cadence changes apply).
- Custom-list "today/this week" filters — add the missing lower bound (stop overdue leaking in).
- Recurring-complete leaves a stale Room row until refresh.
- Undo-after-refresh race.
- `DescriptionField` re-serializes on every keystroke — debounce.

---

## Tier 2 — Cross-cutting passes (all approved in full)

- **A. Spinner decouple (§8.1)** — `refresh(showSpinner = false)` from `init{}`; keep `true`
  for real pulls + error-retry. **Also** gate the init refresh on staleness (skip the full
  multi-page sync + alarm reschedule + widget update on every VM creation). **Do NOT** remove
  the Inbox nav carve-out (added in `f4cf6fb` to keep Inbox reachable).
- **B. Swipe-layer rework (§8.2)** — one pass over `SwipeableTaskItem.kt`/`TaskItem.kt`:
  threshold `0.35`→`~0.5` + velocity cap + edge dead-zone (`WindowInsets.systemGestures`);
  M3 color roles (complete → `tertiaryContainer`, schedule → `secondaryContainer`, **not**
  `errorContainer`; check → `onPrimary`); `snapTo(Settled)` instead of settle animation;
  progress-gated background check; opposite-direction swipe-to-undo; TalkBack/non-swipe path
  to schedule.
- **C. IME/inset standardization (§8.3)** — defer focus until sheet `Expanded` +
  `windowSoftInputMode="adjustResize"` (open-task stutter); `fillMaxHeight(0.85f)` →
  `heightIn(max=…)` in `TaskDetailSheet` (swipe-up shake); save-task: set `savedTaskId` after
  `create()`, fire `refreshAll()` fire-and-forget, animate `sheetState.hide()`, hide keyboard
  before dismiss. **Keep** the inner `.imePadding()` (it is correct, not a double-inset).
- **D. Color cohesion (§4.7)** — add a "None" swatch in `LabelEditDialog.kt` (hollow circle →
  empty hex) and default new projects to it; extract a single crash-safe `parseHexColor`
  helper (fixes the unguarded `parseColor` crash in `ProjectEditDialog`).

---

## Tier 3 — Features (all on the roadmap; each gets its own brainstorm first)

Small:
- **Created-date info button** — `Info` icon → dialog (`Task.created` + `DateUtils.formatFullDate`,
  guard `isNullDate`).
- **Real reminder display** — extract `ReminderFormat` util from `ReminderPickerDialog.kt:60–66`,
  show actual time/period in picker + detail row. Also satisfies the notifications-side request.
- **Left/right FAB setting** — `fabAlignStart` pref → `FabPosition.Start`, via CompositionLocal.
- **Keep-open quick-add mode** — settings toggle to keep the entry sheet open after save.

Bigger:
- **[MODIFIED] Project in Today/Upcoming → GROUPING, not a chip.** Reuse the Anytime
  `CollapsibleSection`-per-project pattern rather than adding a per-row chip. Brainstorm:
  how project grouping interacts with the due-date sort in those views.
- **[MODIFIED] Multi-select CAB** — long-press → selection mode → contextual top app bar.
  Actions: **Move, Complete, Schedule, Apply label** (**NO bulk delete**). The **Schedule**
  action mirrors the configurable swipe-schedule behavior (set-today vs set-urgent per the
  same setting that drives the swipe side — changing that setting changes both). Bulk
  move/complete must send the **complete Task object** per task (Go zero-value). Disable swipe
  + hide FAB while active; `BackHandler` exits.
- **Expressive bottom bar (subtle)** — `MaterialExpressiveTheme` + a light selected-icon
  scale only. No heavy motion.
- **Material You toggle** — "Use device colors" on/off (today hardcoded on, `Theme.kt:29`).
  Also fix the incomplete static scheme + swapped light/dark comments in `Color.kt`.

Organizational:
- **Settings IA regroup** — break the ~700-line flat General scroll into Library / Behavior.
  **Sequence this LAST** among settings work to avoid redoing the other settings edits.

---

## Leftover extras from §4 (all approved)

Add/detail sheet (§4.1):
- `onCreateLabel` no-op stub in add-task — wire it up (`TaskEntrySheet.kt:343–346`).
- Save button — gate on the effective parsed title / surface an error (`:301`).
- Detail sheet auto-saves twice on dismiss — fire once (`:142–150`).

Reminder / recurrence / picker (§4.2):
- Relative reminder with no due date — pass `dueDate` into picker, disable relative options
  when absent (ties to §7 #5).
- "Clear recurrence" action in the detail sheet.
- Standardize picker titles (bare nouns).

Review screen (§4.8):
- `markReviewed` optimistic Room write + surface errors (important — silent data loss today,
  `ReviewViewModel.kt:162–173`).
- Fix the `combine()` self-feedback loop (re-runs `buildState` on every tap).
- Fix the empty progress-bar track when caught up.

Consistency / accessibility (§5, §4.9):
- Inbox-project picker: `CheckCircle` → chevron.
- Bottom-bar long-name truncation on narrow phones.
- Unify FAB contentDescription (folds into plus-only FAB).
- Accessibility nits: nav-bar double-announce, sub-48dp touch targets ("+" chip, 14dp status
  icons), `SwitchRow` double-handled semantics, `+N` overflow description.

---

## Closed (won't-fix / already done — receipts in the report)

- Project-delete confirmation — already implemented (`confirmBeforeDelete`, default ON).
- Edit Inbox in project settings — editing works; kept. (Guarding *delete* is the real fix → Tier 1 #7.)
- Swipe haptic "only on full swipe" — already fixed in `2f216ba`.
- Local trash — incompatible with the no-trash backend; confirm-with-opt-out stays.

---

## Cross-item dependency to settle in a brainstorm

The **swipe-schedule action** and the **multi-select Schedule action** both depend on a single
"what does schedule do" setting (set-today vs set-urgent), referenced in §8.2 as the desktop
"what counts as urgent" setting being ported. Define that setting once; both surfaces read it.

---

## Suggested execution order

1. Tier 0 quick wins (clears the most items fastest).
2. Tier 1 + remaining §7 bugs + the §4 "important" bug (`markReviewed`) — correctness first.
3. Tier 2 passes (spinner → IME/insets → swipe → color), factoring `VicuSnackbarHost`.
4. Tier 3 features, each brainstormed first; Settings IA regroup last.
