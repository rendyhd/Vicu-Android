# Vicu Android — Beta 3 Feedback Review & Recommendations

**Date:** 2026-06-04
**Scope:** Full review of the app (bugs, consistency, UI) plus a code-grounded response to every tester feedback item and your replies to them.
**Method:** 12 parallel code investigations across the whole codebase, each validating your responses against the actual source, root-causing the hard bugs, and hunting for issues beyond the feedback list. High-severity and disputed findings were independently re-verified by a second pass. Every claim below cites `file:line`.
**Status:** Report only — nothing has been implemented.

---

## 1. Executive summary

Your instinct to hold the line on "minimal, personal task management, no bloat" is the right lens, and most of the feedback can be addressed without compromising it. The biggest takeaways:

1. **Three of your responses were factually off** (the code disagrees). Worth correcting before you act on them:
   - "Add‑task NLP may be missing priority" → **NLP priority is fully implemented**; only the tappable chip is missing.
   - "Show project category in Today/Upcoming *like Anytime does*" → **Anytime doesn't show a per‑task project chip**; it groups by project. Aligning needs new row UI, not reuse.
   - The tester's "editing the Inbox does nothing" / "multiline already in edit" → both are **wrong at the code level** (your skepticism was right).

2. **Two of your responses were confirmed correct** — good to have certainty:
   - **Project delete already confirms.** Verified at the only deletion entry point (Settings), gated by `confirmBeforeDelete` (default ON). The tester likely had confirmations turned off or saw an old build. *(But: the Inbox project itself can be deleted, which silently orphans your inbox — see §5.)*
   - **Edit offers both image and attachment.** Confirmed. The real gap is that **add‑task only has "image," no "attachment."**

3. **The "AI voice" copy is much easier to fix than you thought** — every one of those strings is a plain one‑line Kotlin literal, not generated text. No copywriter needed; concrete rewrites are in §6.

4. **The hard UI bugs all have concrete root causes**, and they cluster into a small number of systemic issues — IME/inset handling, the swipe gesture layer, and the launch theme. Fixing those few roots resolves ~8 separate complaints.

5. **The sweep found real bugs you didn't know about** — most importantly **recurrence is silently dropped when you create a task** (data loss, desktop‑parity gap), an **offline label queue that clobbers itself**, and **exact‑alarm permission handling that will both risk Play review and silently drop reminders**. These are §7 and, in my opinion, more important than most of the cosmetic feedback.

**Suggested order of attack:** the §9 quick wins (a half‑day of trivial edits clears ~15 items), then the §7 correctness bugs, then the cross‑cutting passes in §8 (IME, swipe, color).

---

## 2. How to read the verdicts

- **Verdict** = whether *your response* matches the code: ✅ confirmed · ⚠️ partial · ❌ contradicted.
- **Severity** = real user impact (low/med/high), after the adversarial re‑check (some were downgraded).
- **Effort** = trivial (one line) · small (one component) · medium (a few files) · large (a feature).

---

## 3. Verdict index — your responses, graded

| # | Item | Your response | Verdict on your response | Sev | Effort |
|---|------|---------------|--------------------------|-----|--------|
| Idea | View created date (info button) | Will port from desktop | ✅ data already exists, nothing surfaces it | low | trivial |
| Idea | "Add more tasks" keep‑open mode | Maybe a settings toggle; few mass‑add on mobile | ✅ the two feedback items ARE duplicates; plumbing already exists | low | small |
| Idea | Trashbin vs confirm‑delete | Vikunja has no trash; confirm is the pragmatic choice | ✅ correct, keep as‑is | med | large (if ever) |
| Idea | Settings remembers state / back to prior tab | Can add | ✅ back already works; only state‑retention is missing | low | small |
| Idea | Material‑expressive bottom bar | Like it, will research | ✅ stock NavigationBar today; BOM supports expressive | low | medium |
| Idea | FAB position for lefties | Will add a setting | ✅ never customized; `FabPosition.Start` is trivial | low | small |
| Q | "Smart lists" label in settings | Accurate, will clarify wording | ✅ correct & contextual | low | trivial |
| Q | Remove "AI voice" copy | Good call but hard to fix | ⚠️ **easy** — plain string literals | low | small |
| Q | Project colors don't fit Material | Will research | ✅ confirmed; "None" option is the cheap win | med | medium |
| Q | Dark‑mode splash flash | Will fix | ✅ root cause found (light‑only launch theme) | med | small |
| Q | Smaller font / multiline title | Multiline in view, maybe in creation | ❌ **both titles are single‑line**; "view supports it" = the description, not the title | low | small |
| Q | Open‑task keyboard stutter | Aware, unsolved | ✅ root‑caused (concurrent sheet+IME animation) | med | small |
| Q | Explain "add relation" | Ported Vikunja feature, rarely used | ✅ accurate; undocumented in‑app | low | small |
| Q | Review task separation confusing | New/experimental | ✅ confirmed (no dividers/cards) | med | small |
| Q | Show real reminder info not "one reminder" | Will add | ✅ data + formatter already exist | med | small |
| Q | What is "FAB" in gestures | Can remove the term | ✅ jargon, single string | low | trivial |
| Q | **Add project‑delete confirmation** | **Disputes — settings already confirms** | ✅ **YOU ARE CORRECT** | low | none |
| Q | Save‑task stutter | Same glitch | ✅ root‑caused (dismiss gated on full refresh) | med | medium |
| Q | FAB shrink/expand feels weird | Overdoing it, lean minimal | ✅ confirmed; plus‑only is cleaner | low | trivial |
| Q | Undo bar doesn't match theme | Will look into | ✅ default inverse‑surface M3 snackbar | low | small |
| Q | Multi‑select to move | Unsure of mobile UI | ✅ none exists; long‑press CAB is the pattern | med | large |
| VB | Drag‑handle tooltip on long‑press | Can't find it | ✅ it's the framework default handle, not your code | low | trivial |
| VB | Screen shakes swiping up from bottom of edit | Replicated | ✅ root‑caused (`fillMaxHeight(0.85f)` + nested‑scroll) | med | small |
| VB | Search bar off‑screen when keyboard hidden | Will fix | ✅ root‑caused (missing status‑bar inset) | high | trivial |
| VB | Too easy to accidental edge‑swipe | Tried to fine‑tune | ✅ confirmed; no edge dead‑zone, no velocity cap | high | medium |
| VB | Subtext too close to toggle | Subtexts need overhaul | ✅ confirmed (no spacing in `SwitchRow`) | low | small |
| VB | Delay after swipe blocks next tap | Replicated | ✅ root‑caused (settle animation owns pointer) | low | medium |
| VB | Undo bar spams per completed task | Thinking of removing the bar | ✅ confirmed (queued snackbars) | med | small |
| Con | Project category in Today/Upcoming | "Like Anytime, will align" | ❌ **Anytime doesn't show a chip** — needs new UI | med | medium |
| Con | "remind" vs "reminder" labels | Will align | ✅ confirmed | low | trivial |
| Con | Don't allow editing Inbox in project settings | Need to account for it | ❌ tester is wrong: **editing works**; keep it. Guard *delete* instead | low | trivial |
| Con | Image (create) vs attachment (edit) | Edit offers both | ✅ correct; add‑task lacks attachment | med | medium |
| Con | Priority in add‑task (parity) | Will add; check NLP | ⚠️ add the chip; **NLP already parses priority** | med | small |
| Con | Make add‑label consistent | Will edit | ✅ the bare "+" is in the *detail* sheet | low | small |
| Swipe | Circle overlaps checkmark | Annoying, may remove swipe | ✅ confirmed (two checks collide) | low | small |
| Swipe | Haptic only on full swipe | Looking into it | ❌ **already fixed** (commit 2f216ba); residual is a preview‑vs‑commit nuance | low | trivial |
| Swipe | Swipe to undo (opposite dir) | Will look into | ✅ feasible; both dirs disabled when done | low | medium |
| Swipe | Green/orange colors out of place | Will fold into coloring | ✅ confirmed (hardcoded hex) | high→med | small |
| Spin | Spinner inconsistent on tab switch | Brief, mostly Inbox→Today | ✅ root‑caused exactly (see §8.1) | med | small |
| Undo | Undo for complete but not delete | Delete not saved; "panic undo" idea | ✅ feasible — **but not via the sync queue** (§8.4) | med | medium |

*(VB = Visual Bug, Con = Consistency, Q = Suggestion/question.)*

---

## 4. Feedback findings, by theme (detail)

### 4.1 Task creation ⇄ edit parity

This is the densest cluster, and it's mostly real. The add‑task sheet has drifted behind the detail sheet as features were added.

- **Priority in add‑task (⚠️ your NLP suspicion is wrong).** `ExtractPriority.kt:18‑90` fully parses `!urgent/!high/...`, Todoist `p1‑p4`, and Vikunja `!1‑!4`; it's wired into `TaskParser.kt:42‑46` and applied in `TaskEntryViewModel.save()` (`:299‑302`, manual‑wins). What's missing is just a **Priority chip** in the `FlowRow` (`TaskEntrySheet.kt:213‑277`). The `PriorityPickerDialog` and `setPriority` already exist. **This is the single highest‑parity, lowest‑effort win in the whole list** — add one `AssistChip`, reuse the existing dialog, no ViewModel change.
- **Attachment in add‑task (✅).** Edit has two pickers — `GetContent('*/*')` → "Add attachment" (`TaskDetailScreen.kt:540‑545`) and `PickVisualMedia` → "Add image" (`DescriptionField.kt:183‑187`). Add‑task has only the image one (`TaskEntrySheet.kt:190‑208`). The staging path (`state.pendingAttachmentUris`, uploaded post‑create at `TaskEntryViewModel.kt:398‑400`) already exists, so this is "add the launcher + button," not new infrastructure.
- **"Remind" vs "Reminder" (✅, trivial).** `TaskEntrySheet.kt:268` says `"Remind"`; everywhere else says "reminder(s)". One‑line change. While there, make the filled state read "N reminders" to match.
- **"Multiline already in edit" (❌).** Both titles are `singleLine = true` (`TaskEntrySheet.kt:144`, `TaskDetailScreen.kt:192`). The multiline thing you're remembering is the **description** field (`DescriptionField.kt:127`). So "creation is behind edit" isn't accurate — they're identical. If you *do* want wrapping titles, change both together (`maxLines = 3`, drop `singleLine`), and note the caveat: the NLP visual‑transformation + autocomplete popup assume a single‑line field and need device testing. Your "shrinking font is a slippery slope" call is sound — let it wrap, keep the font.
- **Add‑label control (✅, but it's in the *detail* sheet).** In add‑task all four are consistent chips. The offender is `TaskDetailScreen.kt:251` — a bare `AssistChip { Text("+") }` next to full icon+text rows for everything else. Replace with `Icon(Add) + "Add label"` or convert to the same row pattern.

**Extra issues found here (not in feedback):**
- **`onCreateLabel` in add‑task is a no‑op stub** (`TaskEntrySheet.kt:343‑346`). The inline "create label" UI is present but inert — a typed‑new label at creation time is silently dropped (only NLP‑parsed `@labels` auto‑create). The detail sheet wires this correctly. *(medium/bug)*
- **Save button enabled on raw title, not parsed title** (`TaskEntrySheet.kt:301`). Type only NLP tokens (e.g. `@work !1`) → button looks enabled but `save()` returns silently with no feedback. Gate on the effective parsed title, or surface an error. *(low/bug)*
- **Auto‑save fires twice on dismiss** in the detail sheet (`onDismissRequest` *and* `DisposableEffect.onDispose`, `TaskDetailScreen.kt:142‑150`) — usually a harmless no‑op, but with the Go zero‑value full‑object PUT it widens the clobber window. *(low/bug)*

### 4.2 Task detail polish

- **Created‑date info button (✅).** `Task.created` exists and round‑trips (`Task.kt:25`, `TaskMapper.kt:46/120/227`); `DateUtils.formatFullDate` is ready. Nothing surfaces it. Add a small `Info` icon → dialog. Guard with `isNullDate`.
- **Reminder shows a count, not the time (✅).** `TaskDetailScreen.kt:295‑296` renders `"N reminders"`. The exact formatter you want already exists, trapped as private code in `ReminderPickerDialog.kt:60‑66` + the relative‑option labels (`:49‑55`). **Extract it to a shared `ReminderFormat` util** and reuse in both the picker and the detail row. (Same fix covers the notifications‑side request in §7.)
- **"Add relation" is undocumented (✅).** Your description is accurate — it's bare `FilterChips` with no explanation (`RelationTaskPickerDialog.kt`). Given low usage, the cheap move is a one‑line `supportingText` under the kind chips, and/or hide the always‑visible "Add relation" button (`TaskDetailScreen.kt:479`) behind an overflow so it stops competing with Subtasks. Don't invest more.

**Extra issues:**
- **Relative reminder on a task with no due date silently does nothing** — the picker lets you add "15 min before" with no due date to anchor to (`ReminderPickerDialog.kt:49‑55`), and the detail row just shows a count, so the user gets zero signal it'll never fire. Pass `dueDate` into the picker and disable relative options when absent. *(medium/bug — and ties into §7's "At due time" alarm bug.)*
- **Recurrence is read‑only with no clear/edit** (`TaskDetailScreen.kt:346‑362`) — a repeat set on desktop can't be turned off on mobile. This matches your documented scope, but a single "Clear recurrence" action would close the worst gap cheaply.
- **Picker titles are a grab‑bag** ("Move to project" / "Set due date" / "Reminders" / "Priority" / "Add relation") — mix of verbs and nouns. Pick one convention (bare nouns are most Things‑3).

### 4.3 The swipe layer (this needs one coherent pass — see §8.2)

- **Accidental edge‑swipe (✅, high).** `SwipeableTaskItem.kt:50‑51` uses a `0.35` positional threshold with **no velocity cap**, so a quick flick commits below 35%. There's **no edge dead‑zone** anywhere (verified: zero `systemGesture`/`velocityThreshold` matches in the repo). The left edge is *triple‑booked*: your swipe, the OS back gesture, **and** the drawer open gesture (`ModalNavigationDrawer gesturesEnabled=true`, `VicuApp.kt:313`). Fix: raise the threshold to ~0.5, pass a high `velocityThreshold`, and add a left/right dead‑zone sized to `WindowInsets.systemGestures`.
- **Green/orange clash (✅).** `SwipeableTaskItem.kt:32‑35` hardcodes iOS hex (`0xFF34C759` / `0xFFFF9800`) + a `Color.White` icon, none from `colorScheme`. Map to M3 roles: complete → `tertiaryContainer`/`onTertiaryContainer`, schedule → `secondaryContainer`/`onSecondaryContainer`. **Do not use `errorContainer`** — neither swipe is destructive, and with no server trash a "delete‑looking" swipe would be dangerous. (Two of the four color constants are also dead duplicates.)
- **Haptic "only on full swipe" (❌ — already fixed).** Commit `2f216ba` moved haptic to a `snapshotFlow { targetValue }`; both haptic and action now key off the same `0.35` threshold. The residual nuance: the buzz fires mid‑drag when you cross 35% (a "will‑commit" preview), but the action commits on *release* — so crossing then dragging back gives a buzz with no action. Acceptable as a preview cue; optionally add a distinct, lighter commit haptic. Tell the tester this one's resolved.
- **Circle overlaps checkmark (✅).** During a complete‑swipe the background's check icon and the row's `AnimatedCheckbox` both sit at the left and collide. Best fix: gate the background check to appear only past ~35% and align it where the circle lands, so it reads as "the circle becoming a check."
- **Post‑swipe tap dead‑zone (✅).** `confirmValueChange` returns `false`, so the box *animates back* to Settled while its draggable still owns the next pointer‑down — that's the "looks settled but taps don't register" window. Fix: `snapTo(Settled)` instead of the settle animation so the pointer frees instantly.
- **Swipe‑to‑undo (✅, feasible).** Today both directions are disabled once `task.done` (`SwipeableTaskItem.kt:99‑100`). Enable *only* the opposite direction for rows in the undo window and call the existing `undoComplete`. Reuses the settle pattern, so no new stability risk.

**Extra:** the threshold‑cross haptic can double‑buzz on a direction reversal within one gesture; checkmark color is hardcoded `Color.White` instead of `onPrimary`; and there's **no TalkBack/non‑swipe path to "schedule"** from a row (accessibility gap).

### 4.4 The undo bar (a decision, not just a fix — see §8.4)

Everything you observed is confirmed: it's the **default inverse‑surface M3 snackbar** (so "white" in dark mode, by spec — `CompletionUndoSnackbar.kt:27`), it **queues one bar per completion** (`showSnackbar` suspends, so N completions = N stacked bars, each generic "Task completed"), and it **clashes with the FAB** (bottom‑end FAB over a full‑width bottom bar). Critically, your claim that **tap‑the‑task‑again undo already works without the bar is verified** (`toggleDone` never flips Room on completion; the strikethrough row + `undoComplete` carry it).

So the bar is largely redundant. My recommendation matches your lean: **remove it, or replace it with one coalesced, themed bar** ("N tasks completed — Undo" debounced ~400 ms) rather than a per‑task queue. Don't build a bespoke floating undo pill — that's the bloat you're avoiding. If kept, theme it to `surfaceContainerHigh`/`onSurface`/`primary` so it stops looking foreign. *(Note: the same untyled `SnackbarHost` is duplicated across **11 screens** — whatever you decide, factor a single `VicuSnackbarHost` so it's a one‑place change.)*

### 4.5 Settings (copy, spacing, IA — see §6)

- **"Smart lists" (✅).** Accurate and contextual (the subtitle switches to "Project"/"Custom List" when reassigned). Keep the term; just make "pre‑created" clearer in the section description (`SettingsScreen.kt:913`).
- **"AI voice" (⚠️ — easy).** These are plain literals, each a one‑line edit. Worst offenders and rewrites in §6. Also: `:1061` literally name‑drops **"like Things 3"** in shipping copy — drop it.
- **"FAB" jargon (✅).** `SettingsScreen.kt:1744` → "Plus button" / "Add button (+)".
- **Subtext spacing (✅).** `SwitchRow` (`:1888‑1918`) stacks title over description in a `Column` with **no spacing** and only 12 dp row padding. Add `Arrangement.spacedBy(2.dp)` (and ideally extract a shared `SettingTwoLine`). Same crowding repeats in ~5 other rows.
- **Project‑delete confirmation (✅ — you're right).** Verified twice: the *only* `deleteProject` callers are `SettingsScreen.kt:229/536`, gated by `confirmBeforeDelete` (default ON at `BehaviorPrefsStore.kt:24,46`). No delete affordance exists in the drawer or project screen. The original report is stale. **No change needed** — though, since there's no trash, you *could* make project delete always‑confirm regardless of the global pref (1 line, judgment call).
- **Edit Inbox "does nothing" (❌ — tester wrong).** Editing genuinely renames/recolors/reparents the project (`updateProject`, `SettingsViewModel.kt:313`). Keep edit. The real risk is the next item.
- **Trash vs confirm (✅).** Your reasoning holds — a local trash against a no‑trash backend is a genuine sync hazard. Keep the opt‑out confirm.

### 4.6 Navigation, loading, spinner — see §8.1.

### 4.7 Theme / colors / splash

- **Dark‑mode splash flash (✅, root cause found).** `res/values/themes.xml:4` is `parent="android:Theme.Material.Light.NoActionBar"` — the **light** platform theme, applied to every Activity, with **no `values-night/`** and no SplashScreen API. So the pre‑Compose window is always white. Minimal fix: switch to a `DayNight` parent + add `values-night/themes.xml`. Better: add `androidx.core:core-splashscreen` with a `?attr/colorSurface` background. (Dynamic wallpaper color can't apply to the pre‑Compose frame, so use a neutral surface, not the accent.)
- **Project colors (✅).** `PRESET_COLORS` is 10 saturated Asana‑style hex (`LabelEditDialog.kt:32‑36`), unrelated to M3 — so they clash under Material You. The rendering layer **already supports "no color"** (empty hex → falls back to `colorScheme.primary`/`onSurfaceVariant`), but the **editor forces** `PRESET_COLORS.first()` on every new project. Cheapest high‑value move: **add a "None" swatch** (hollow circle → empty hex) and default new projects to it. Adding *more* arbitrary saturated colors would hurt cohesion; if anything, retune the palette to calmer tones.
- **Undo bar color (⚠️).** Technically it *is* pulling from the scheme (`inverseSurface`), so "doesn't match dynamic M3" is imprecise — but the inverse bar reads as foreign. See §4.4 / §8.4.

**Extra:** there's **no way to turn Material You off** (dynamic color hardcoded on, `Theme.kt:29`), so API 26–30 devices silently get the static blue scheme with no user control — consider a "Use device colors" toggle. The static scheme is also incomplete (only primary/secondary/tertiary set) and `Color.kt` has **swapped light/dark comments**. Hex‑parsing is duplicated in 4+ files with one **unguarded `parseColor` that can crash** `ProjectEditDialog` — extract a single `parseHexColor` helper (your MEMORY already flags this pattern).

### 4.8 Review screen

- **Tasks blend together (✅).** `ReviewRow` is a bare `Column>Row` with padding only — **no card, no divider, no tint** (`ReviewScreen.kt:158‑161`), while the rest of the app always draws dividers (`TaskItem.kt:162`) and uses `SectionHeader`. Adopt the existing grammar: card‑per‑project (or dividers), reuse `SectionHeader` for sub‑projects, and **reuse the real `TaskItem`** for review task rows (currently a lone `Text` with no checkbox/badge). That single reuse fixes most of the "separation" complaint and gives in‑place completion.

**Extra (important):** **`markReviewed` has no optimistic Room write** — `ProjectRepositoryImpl.update` only persists *after* a successful API call, so on network failure the reviewed state is silently lost and the project re‑appears as due (`ReviewViewModel.kt:162‑173`). Make it optimistic + surface errors. Also: a self‑feedback loop in the `combine()` (re‑runs `buildState` on every tap), a literal "✓" glyph that **violates the no‑emoji rule** (`:183`), and a progress bar that shows an empty track when caught up.

### 4.9 FAB / bottom bar / list consistency

- **FAB position for lefties (✅).** Never customized; `FabPosition.Start` is built‑in. Add a `fabAlignStart` pref + one Scaffold param. Expose once via a CompositionLocal so you don't thread it through 8 screens.
- **FAB shrink/expand (✅).** Binary `firstVisibleItemIndex == 0` makes the label snap off abruptly. Your lean is right — **go plus‑only** (`FloatingActionButton` + `Add`), which also drops the `listState` coupling from all 8 call sites. (Also: contentDescription "Add task" ≠ label "New Task" ≠ `AddTaskButton`'s "Add Task" — pick one.)
- **Expressive bottom bar (✅).** Stock `NavigationBar` today. The BOM (`2026.01.01` → M3 1.5.x) has the expressive motion APIs; wrap in `MaterialExpressiveTheme` (lowest effort) + a light selected‑icon scale. Don't over‑animate.
- **Project chip in Today/Upcoming (❌ premise).** Anytime conveys project **by grouping** (`CollapsibleSection` per project), not a per‑row chip — `TaskItem` has no project field at all. So aligning means **new UI**: add optional `projectName`/`projectColor` params to `TaskItem` (+ thread through `SwipeableTaskItem`), and `combine` the task flow with `projectRepository.getAll()` in Today/Upcoming VMs (Anytime already shows the pattern). Keep it subtle (small dot or muted chip), suppress for inbox tasks.
- **Multi‑select (✅, large).** None exists. The right mobile pattern is exactly your instinct: `combinedClickable { onLongClick = enterSelection }` → selection mode → **contextual top app bar** ("{n} selected", Move/Complete/Delete), row checkboxes, disable swipe + hide FAB while active, `BackHandler` exits. **Critical:** bulk move/complete must send the **complete Task object** per task (Go zero‑value), and bulk delete is permanent — gate behind `confirmBeforeDelete` with a count.

---

## 5. Consistency & smaller UI issues (consolidated)

- Inbox project is a **pinned, non‑configurable** 4th bottom‑bar item — reasonable, but undocumented in the editor.
- Long project/custom‑list names will **truncate** in a 4‑item bottom bar on narrow phones.
- The Inbox‑project picker uses a **`CheckCircle`** trailing icon (reads as "done"), where a chevron would say "opens a picker."
- Settings General is **one ~700‑line flat scroll** with inconsistent section‑header styles — consider grouping (Library / Behavior).
- Numerous **accessibility** nits: redundant nav‑bar contentDescriptions (announced twice), sub‑48dp touch targets on the "+" chip and 14dp status icons, `SwitchRow` double‑handles toggle semantics, `+N` label overflow has no description.

---

## 6. The "AI voice" copy — concrete rewrites

All are plain literals in `SettingsScreen.kt`; each is a one‑line edit.

| Line | Current | Suggested |
|------|---------|-----------|
| 1083 | "Tasks finished long ago disappear from the Logbook view (nothing is deleted)" | "Older completed tasks are hidden from Logbook. Nothing is deleted." |
| 1061 | "Tasks with a due date leave the Inbox (like Things 3)" | "Tasks with a due date no longer appear in Inbox." |
| 1170 | "Prefix or suffix ! to set due date to today" | "Add ! to set the due date to today." |
| 1196 | "Uses the system notification sound by default. Tap below to choose a custom audio file." | "Plays the system notification sound. Tap below for a custom sound." |
| 1744 | "FAB (+)" | "Add button (+)" |

**Rule of thumb:** present tense, say what the toggle does, drop parentheticals, never name another app. That's the whole "fix" — no copywriter needed.

---

## 7. Bugs found beyond the feedback (prioritized)

These came out of the data/sync and notifications sweeps. The top ones are genuine correctness/data‑loss issues and, in my view, outrank most of the cosmetic feedback.

### High

1. **Recurrence is silently dropped when creating a task.** `CreateTaskDto` has no `repeat_after`/`repeat_mode` fields (`TaskDto.kt:38‑46`) and `Task.toCreateDto()` never maps them (`TaskMapper.kt:199‑206`). You parse recurrence and set it on the Task, but it's dropped on **both** the online create *and* the offline‑queued create (SyncWorker reuses the same `toCreateDto`). A task typed "every week" is created non‑recurring. Desktop sends these on create — confirmed parity gap. **Fix:** add the two `@SerialName` fields + map them. One change fixes both paths. *(Verified, breadth understated in first pass.)*
2. **Offline label add/remove queue clobbers itself.** `queueLabelAction` keys on `labelId` and calls `replaceForEntity`, which deletes *all* pending "label" rows with that id (`LabelRepositoryImpl.kt:147/163`, `PendingActionDao.kt:42`). Offline: add label L to task A, then to task B → the second enqueue deletes the first, so **task A never gets the label.** Add/remove of the same label across different tasks also cancel. **Fix:** `add_label`/`remove_label` must `insert()` unconditionally, not dedup per‑label.
3. **`USE_EXACT_ALARM` declared** (`AndroidManifest.xml:7`) — a "strong," auto‑granted permission Google Play restricts to alarm/calendar apps. Play‑review/rejection risk for a task manager. **Fix:** remove it, rely on `SCHEDULE_EXACT_ALARM` + a runtime prompt — *but only together with #4.*
4. **No UI ever requests the exact‑alarm permission.** `AlarmScheduler.kt:99‑102` silently returns when `canScheduleExactAlarms()` is false on Android 12+; there's no `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` anywhere. Today `USE_EXACT_ALARM` masks it; remove that (#3) without adding a prompt and **every reminder silently dies** on 12+. CLAUDE.md gotcha #5 ("check + prompt on 14+") is unimplemented. **Fix:** a Settings banner that deep‑links to the system toggle + an inexact `setAndAllowWhileIdle` fallback.

### Medium

5. **"At due time" reminder never schedules a local alarm.** `relativePeriod = 0` + blank absolute time falls through `resolveReminderTime` to `return null` (`AlarmScheduler.kt:112‑130`). The reminder saves and shows in the UI but never fires. (The *default*‑reminder path is fine; only the manual picker option is broken.) **Fix:** one‑line guard change to treat period 0 with a non‑blank `relativeTo` as "at the base date."
6. **Monthly recurrence never displays as recurring.** Monthly maps to `repeatAfter=0, repeatMode=1`, but every display gate checks `repeatAfter > 0` (`DateUtils.kt:136`, `TaskItem.kt:133`, `TaskDetailScreen.kt:346`). `repeatMode` is ignored, so monthly tasks show no repeat indicator. **Fix:** treat `repeatMode==1` as recurring in `formatRecurrence` + the two gates.
7. **`refreshAll()` never deletes server‑removed tasks.** It upserts but never calls `deleteNotIn` (only `SyncWorker` does), so a task deleted/completed on another device **lingers** in lists until WorkManager runs (`TaskRepositoryImpl.kt:484‑516`).
8. **`update()` doesn't roll back on hard failure.** Optimistic write stays in Room on a non‑retriable error (e.g. a 400 from a Go zero‑value issue), so the rejected change persists locally until a refetch. `toggleSubtaskDone` rolls back; `update` doesn't — inconsistent.
9. **`ProjectEditDialog` allows a cyclic parent** — only excludes the project itself, not descendants (`:57`); `buildProjectTree` has no visited guard, so A→B→A can **StackOverflow** the settings list.
10. **Pending‑action queue has no `ORDER BY`** in `getRetryable()` — cross‑entity causal chains can apply out of order.
11. **Offline label add/remove never patches Room** — chips show stale state until the next online refresh.
12. **Daily summary morning/afternoon are byte‑identical** (`slot` input ignored), reuse one notification ID, and the "today" list silently includes overdue tasks.
13. **Alarm request codes** use `taskId.toInt() * 100 + index` — truncation/overflow for large server IDs can make one task's cancel hit another's alarms.
14. **Inbox project can be deleted from Settings**, orphaning the inbox designation (`inboxProjectId` → nonexistent project). Guard the delete button when `project.id == inboxProjectId`. *(This is the real bug behind the "edit inbox" feedback.)*

### Low (selected)

- DST drift in the daily‑summary 24h period; snoozed reminders lost on reboot; widget periodic policy `KEEP` (won't pick up cadence changes); custom‑list "today/this week" filters omit the lower bound (overdue leaks in); recurring‑complete leaves a stale Room row until refresh; undo‑after‑refresh race; `DescriptionField` re‑serializes on every keystroke (typing churn inside the sheet).

---

## 8. Cross‑cutting themes (where one fix clears many complaints)

### 8.1 The spinner — one mechanism, not random

The "spinny" is the **pull‑to‑refresh indicator**, not a separate loader, and it has an exact cause: every list VM calls `refresh()` from `init{}`, and `refresh()` flips `isRefreshing = true` — so **a freshly created ViewModel always flashes the spinner.** Whether a VM is recreated depends on the nav save/restore policy: **Inbox is the start destination and is force‑excluded from save/restore** (`VicuApp.kt:367‑375`). So tapping Inbox discards the tab you left, and the next Today visit recreates Today → spinner. Upcoming↔Anytime bounce *without* passing Inbox, so they survive — which is exactly why "Today always spins" and the others "usually don't." (Your "mostly Inbox→Today, very brief" read is spot‑on.)

**Primary fix (small, kills it on all tabs):** decouple the init refresh from the spinner — `refresh(showSpinner = false)` from `init{}`, keep `true` for real pulls and the error‑retry path. Room already shows cached rows instantly, so the background refresh needs no spinner.

**Do NOT** simply remove the Inbox carve‑out (the tempting "fix"): the verifier found via `git` that it was added in `f4cf6fb` to fix **Inbox becoming unreachable** after visiting a parameterized tab. Removing it regresses that. The primary fix makes it moot anyway. *Related:* `init{refresh()}` also fires a full multi‑page sync + alarm reschedule + widget update on every VM creation — wasteful given how often VMs are recreated; gate it on staleness.

### 8.2 The swipe layer deserves one deliberate pass

Six separate complaints (edge‑trigger, colors, haptic, circle overlap, tap dead‑zone, undo) all live in `SwipeableTaskItem.kt`/`TaskItem.kt`. Rather than six spot‑edits, do one focused rework: thresholds + velocity cap + edge dead‑zone (the safety fix), M3 color roles, `snapTo` instead of settle, progress‑gated background check, and opposite‑direction undo. You mentioned sometimes wanting to *remove* swipe — but a single coherent pass would turn the most error‑prone surface into a polished one, and it pairs naturally with the desktop "what counts as urgent" setting you're adding.

### 8.3 IME / window insets — the root of 4 "feels janky" reports

Four issues share a window‑insets root, and the **first‑pass diagnosis was partly corrected by the verifier** — use the corrected version:

- **Open‑task stutter (med):** `LaunchedEffect(Unit){ focusRequester.requestFocus() }` fires *while the sheet is still sliding in*, so the sheet‑enter and IME‑show animations fight over window height. **Fix:** defer focus until `sheetState.currentValue == Expanded`; add `windowSoftInputMode="adjustResize"`. ⚠️ **Do not remove the inner `.imePadding()`** — the first pass called it a "double inset," but M3 `ModalBottomSheet`'s default `windowInsets` is `systemBars`, *not* IME, so the `imePadding` is correct and required; removing it hides content behind the keyboard.
- **Swipe‑up‑from‑bottom shake (med):** the detail sheet's `LazyColumn` combines **`fillMaxHeight(0.85f)`** with nested‑scroll/drag‑to‑dismiss; the fractional fixed height fights the drag settling → oscillation. **Fix:** replace `fillMaxHeight(0.85f)` with a content‑sized `heightIn(max = …)`. (File is `TaskDetailScreen.kt`, fn `TaskDetailSheet` — there is no `TaskDetailSheet.kt`.)
- **Search bar off‑screen (high, trivial):** `SearchScreen.kt:54` is a bare `Column` with **no status‑bar inset** and no Scaffold; the outer Scaffold zeroes `contentWindowInsets`. With the IME up the resized window hides it; when the IME hides, the bar re‑anchors under the clock. **Fix:** add `.windowInsetsPadding(WindowInsets.statusBars)` (exactly what `SetupScreen.kt:83` already does) + `.imePadding()` on the results list. One line.
- **Save‑task stutter (med):** dismissal is gated behind a full `refreshAll()` and the sheet is yanked by a boolean toggle (no `hide()` animation) on the same frame the list re‑emits. **Fix:** set `savedTaskId` right after `create()`, fire `refreshAll()` fire‑and‑forget, animate `sheetState.hide()`, hide the keyboard before dismiss.

Underlying all of these: the **legacy light‑only Activity theme** (§4.7) and `contentWindowInsets = WindowInsets(0)` with per‑screen inset handling that some screens (Search) simply forgot. Standardizing inset handling is the durable fix.

### 8.4 The undo bar — and your "panic delete" idea

Your delayed‑delete idea is good and I'd pursue it — **but not through the PendingAction/SyncWorker queue**, which fires as soon as there's connectivity (no real grace window). Instead: optimistically remove the row, hold `api.deleteTask` behind an in‑memory `delay()` (I'd use **~5 s, not 20 s** — a 20 s window mostly means "lost if the app is killed"), show one themed "Deleted — Undo" bar; on undo, cancel the coroutine and re‑upsert the cached Task (you already hold it in state). No separate persistent store, so your sync‑headache concern doesn't apply. Pair this with collapsing the completion bar to a single coalesced one (§4.4), and the "spam," "unbalanced," "doesn't match theme," and "no undo for delete" complaints all resolve together.

---

## 9. Recommended roadmap

### Tier 0 — Quick wins (mostly one‑line; ~half a day clears ~15 items)
- "Remind" → "Reminder" (`TaskEntrySheet.kt:268`); "FAB (+)" → "Add button (+)"; the 5 copy rewrites (§6).
- `SwitchRow` spacing (`Arrangement.spacedBy(2.dp)`).
- Search bar status‑bar inset (§8.3) — *highest‑value one‑liner here.*
- Custom drag‑handle to kill the long‑press tooltip.
- Review screen: replace the "✓" glyph (no‑emoji rule); add dividers/cards.
- Splash: `DayNight` theme parent + `values-night/`.
- Add‑task **Priority chip** (reuses existing dialog).
- Replace the detail "+" label chip with "Add label."
- Plus‑only FAB.

### Tier 1 — Correctness bugs (do before more features)
- §7 #1 recurrence‑on‑create, #2 label queue, #3+#4 exact‑alarm pair, #5 "at due time," #6 monthly display, #14 inbox‑delete guard. These are silent failures users won't report but will feel.

### Tier 2 — Cross‑cutting passes
- IME/inset standardization (§8.3) — clears 4 jank reports.
- The swipe layer pass (§8.2) — clears 6 reports.
- Color cohesion (swipe roles + undo bar + project "None" swatch + `parseHexColor` helper).
- Spinner decouple (§8.1) + factor a shared `VicuSnackbarHost`/list scaffold.
- The undo‑bar decision + panic‑delete (§8.4).

### Tier 3 — Features (each is a proper design task — worth a brainstorm first)
- Project chip in Today/Upcoming · multi‑select CAB · keep‑open quick‑add mode · created‑date info button · real reminder display · left/right FAB setting · expressive bottom‑bar motion · Material You toggle · settings IA regroup.

### Resolved / won't‑fix (with the receipts)
- **Project‑delete confirmation** — already implemented; you were right.
- **Edit Inbox in project settings** — editing works; keep it (guard *delete* instead).
- **Swipe haptic "only on full swipe"** — fixed in `2f216ba`.
- **Local trash** — incompatible with a no‑trash backend; confirm‑with‑opt‑out stays. (Panic‑delete is the softer option if wanted.)

---

## 10. Notes on method & confidence

Every finding is grounded in a specific `file:line`, and the high‑severity / disputed ones were re‑checked by an independent pass that overturned a few first‑pass claims (the "double imePadding" and the swipe‑shake "inset feedback loop" were both wrong — corrected above; the spinner's nav history was corrected via `git`). Where the first pass over‑reached, I've used the verified version. Severities reflect the post‑verification view. Nothing here was implemented; the recommendations are intentionally concrete so any of them can be turned into a plan on request.
