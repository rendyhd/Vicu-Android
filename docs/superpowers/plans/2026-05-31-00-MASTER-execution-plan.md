# Master Plan — Implement All Five Desktop-Parity Features

## Context

Desktop Vicu (v1.5.0-beta.1) has pulled ahead of the Android app (v1.0.25). A feature survey produced **five detailed, TDD-structured implementation plans** under `docs/superpowers/plans/` (dated 2026-05-31). This master plan sequences those five into a single, conflict-free execution order and defines how they land.

The five sub-plans are the source of truth for exact code, file paths, and per-task TDD steps. This document is the **orchestration layer**: ordering, shared-component coordination, and end-to-end verification. Do not duplicate the sub-plans' code here — open each plan file and execute its tasks in order.

**Decisions (confirmed with user):**
- **Execution:** Sequential, single session, with review checkpoints between plans.
- **Integration:** Commit **straight onto `main`** as each plan completes (same flow as the recent desktop-parity + loading-fix merges — no PR gate). Currently on `main`, in sync with `origin/main` at `08175dc`.

**The only cross-plan coupling:** Plans 4 (Notifications) and 5 (Review) both edit `SettingsViewModel.kt` and `SettingsScreen.kt`, and both reference a `SettingsValueRow` composable that does not yet exist. Everything else is independent. The fix: extract `SettingsValueRow` once, upfront (Step 0).

---

## Sub-plan reference

| # | Plan file (`docs/superpowers/plans/`) | Scope | Files |
|---|---|---|---|
| 1 | `2026-05-31-task-notes-indicator-fix.md` | Notes icon ignores empty-HTML descriptions | 1 modify + 1 test |
| 2 | `2026-05-31-api-token-dedup-pagination.md` | Paginate token-cleanup sweep | 2 modify |
| 3 | `2026-05-31-task-relations-ui.md` | Full relations UI in task detail | 3 create + 6 modify |
| 4 | `2026-05-31-notification-granularity.md` | Afternoon summary, per-type toggles, default reminder offset | 2 create + 7 modify |
| 5 | `2026-05-31-project-review-feature.md` | Spaced-repetition Review feature | 5 create + 7 modify |

---

## Execution order

Run top to bottom. Each plan's internal tasks are already TDD-ordered (write failing test → implement → verify → commit). Commit directly to `main`. After each plan, run the gate (below) before starting the next.

### Step 0 — Pre-req: commit plans + extract shared helper

1. **Commit the five plan docs** (currently untracked under `docs/`) so the roadmap is durable:
   - `git add docs/superpowers/plans/2026-05-31-*.md` → commit `"Add desktop-parity implementation plans"`.
   - Note: `logcat.txt`, `logcat2.txt` are throwaway logs — leave untracked or add to `.gitignore` (do **not** commit them).
2. **Extract `SettingsValueRow`** into `app/.../ui/screens/settings/SettingsScreen.kt`, placed beside the existing private composables (after `TimePickerRow`, ~line 1553). This is the helper both Plans 4 and 5 call. Use the exact composable defined in the notification plan (Task 6) / review plan (Task 6):
   ```kotlin
   @Composable
   private fun SettingsValueRow(label: String, value: String, onClick: () -> Unit) {
       Row(
           modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
               .padding(horizontal = 16.dp, vertical = 14.dp),
           horizontalArrangement = Arrangement.SpaceBetween,
           verticalAlignment = Alignment.CenterVertically,
       ) {
           Text(label, style = MaterialTheme.typography.bodyLarge)
           Text(value, style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onSurfaceVariant)
       }
   }
   ```
   - `./gradlew compileDebugKotlin` → commit `"Add SettingsValueRow helper for settings value/label rows"`.
   - In Plans 4 and 5, **skip** their inline `SettingsValueRow` fallback note — it now already exists.

### Step 1 — Plan 1: Task notes indicator fix (warm-up)
Execute `2026-05-31-task-notes-indicator-fix.md`. Isolated to `TaskLinkParser.kt` + new unit test. Smallest change; good to confirm the test/build loop works.

### Step 2 — Plan 2: API token dedup pagination
Execute `2026-05-31-api-token-dedup-pagination.md`. Touches `VikunjaApiService.kt` (`listApiTokens` gains defaulted `page`/`per_page`) + `AuthManager.kt`. Independent of everything else.

### Step 3 — Plan 3: Task relations UI
Execute `2026-05-31-task-relations-ui.md`. Self-contained feature (new `RelationKind`, `RelationTaskPickerDialog`, repository `createRelation`, relations section in `TaskDetailScreen`). No settings interaction. Its `VikunjaApiService` usage (`createRelation`/`deleteRelation`) is unaffected by Plan 2's signature change.

### Step 4 — Plan 4: Notification granularity
Execute `2026-05-31-notification-granularity.md`. First plan to touch Settings:
- Adds setters to `SettingsViewModel` (enriches the existing `notificationPrefsStore` flow already in the combine — **no combine-arity change needed**).
- Adds rows to the **NotificationsTab** of `SettingsScreen` (a different tab than Plan 5's GeneralTab).
- Uses the `SettingsValueRow` from Step 0.

### Step 5 — Plan 5: Project review feature
Execute `2026-05-31-project-review-feature.md`. Last, because it extends the same Settings files Plan 4 touched:
- Injects `ReviewPrefsStore` into `SettingsViewModel` and folds it into the combine — add it to the `userEtc` inner combine (currently 4 flows → 5) per the plan's Task 6 note.
- Adds a Review section to the **GeneralTab** of `SettingsScreen` (separate from Plan 4's NotificationsTab) using `SettingsValueRow`.
- Also touches navigation (`Routes`, `AppNavHost`, `DrawerViewModel`, `DrawerContent`, `VicuApp`) — all Plan-5-only, no overlap.

---

## Shared-file coordination (reference)

| File | Touched by | Coordination |
|---|---|---|
| `SettingsScreen.kt` (private composables) | Step 0, 4, 5 | `SettingsValueRow` created once in Step 0; Plans 4/5 only call it. |
| `SettingsScreen.kt` (tab bodies) | 4 (NotificationsTab), 5 (GeneralTab) | Different tabs → no overlap. |
| `SettingsViewModel.kt` | 4, 5 | Plan 4 = setters only (no new flow). Plan 5 = add `ReviewPrefsStore` to `userEtc` inner combine + `reviewPrefs` field. Sequencing 4-before-5 avoids touching the same combine line twice. |
| `DrawerViewModel.kt` combine | 5 | If the drawer combine is at arity limit, nest a `combine(projects, reviewPrefs.getPrefs())` sub-combine (per Plan 5 Task 5). Plan-5-only. |
| `VikunjaApiService.kt` | 2, 3 | Plan 2 adds defaulted params to `listApiTokens`; Plan 3 doesn't call it. No conflict. |

---

## Verification

Verification runs in **four layers**, applied as a gate after every Step and again as a full pass at the end. Claims of "done" require observed evidence at each layer — green output, not assumption (per `superpowers:verification-before-completion`). Drive L3/L4 with the `verify` skill (launch the app, observe real behavior).

| Layer | What | How |
|---|---|---|
| **L1 Build** | compiles + assembles | `./gradlew compileDebugKotlin` / `assembleDebug` |
| **L2 Unit** | pure-logic tests | `./gradlew testDebugUnitTest` |
| **L3 Functional** | real app behavior: happy + edge + negative + regression | `./gradlew installDebug` then drive the UI |
| **L4 Backend** | writes land correctly on Vikunja | Vikunja MCP (`vikunja_tasks`, `vikunja_projects`, `vicu_review_*`) or web UI |

**Standing setup for all gradle commands:** `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
**Backend setup for L4:** authenticate the Vikunja MCP to the **same server + account** the test device uses (`vikunja_auth` if prompted). Use a throwaway test project + a few test tasks so verification never mutates real data. Record task/project IDs as you create them for the GET cross-checks.

### Step 0 gate — pre-req
- **L1:** `./gradlew compileDebugKotlin` → SUCCESS after adding `SettingsValueRow`.
- **L3 regression:** open Settings → General; every existing row still renders (helper is unused until Plans 4/5, so this only proves no compile/format break).

### Plan 1 gate — Notes indicator
- **L2:** `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.TaskLinkParserNotesContentTest"` → PASS. Cases asserted: null/blank, `<p></p>`, `<p><br></p>`, `<p>&nbsp;</p>` → **false**; `<p>Buy milk</p>`, plain text → **true**.
- **L2 regression:** `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.TaskLinkParserTest"` → existing link-parsing tests still PASS (the strip changes must not break note/page-link extraction).
- **L3 happy:** a task with body "hello" shows the notes icon on its collapsed row.
- **L3 edge:** a task whose body is only formatting (`<p></p>`, an empty bullet, or only a pasted image/note link) shows **no** icon. Open it, clear the body to one empty paragraph, back out → icon disappears.
- **L3 negative:** a task with no description → no icon (unchanged).

### Plan 2 gate — Token dedup pagination
- **L1:** `./gradlew compileDebugKotlin` → SUCCESS.
- **L3 setup:** in the Vikunja web UI (Settings → API Tokens) create 2–3 extra tokens titled exactly `Vicu — <device-model>` (match `AuthManager.defaultTokenTitle()` for the test device).
- **L3 happy:** sign out, then sign back in via the path that mints the backup token. Expect Settings → Data & Sync → Auth debug log to show `TOKEN_CLEANUP deleted sibling id=…` for each extra; the web UI then lists exactly **one** `Vicu — <device>` token.
- **L3 edge (the actual fix):** create >50 mixed-title tokens so matching siblings span page 2+. Re-auth → confirm siblings beyond page 1 are also deleted (single-page code would have missed them).
- **L3 negative:** a differently-titled token (e.g. `Vicu Desktop`, or another device's) is **not** deleted.
- **L4:** confirm the token-list count in the web UI before/after.

### Plan 3 gate — Relations UI
- **L2:** `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.RelationKindTest"` → PASS (esp. `precedes` = one `e`; labels; `SELECTABLE` excludes subtask/parenttask).
- **L1:** `./gradlew compileDebugKotlin` → SUCCESS.
- **L3 happy:** open task A → Relations → Add relation → "Blocking" → search & select task B → a "Blocking" group shows B after the sheet refreshes.
- **L3 reciprocal:** open task B → it shows "Blocked by" → A (Vikunja auto-creates the reciprocal; the dual re-fetch must reflect it without a manual refresh).
- **L3 remove:** remove B from A via ×  → group disappears on A; reopen B → reciprocal also gone.
- **L3 each kind:** repeat for related, duplicateof, duplicates, precedes, follows → each lands under the correct header label.
- **L3 picker edges:** the picker excludes task A itself; a **done** task is findable (done-inclusive search) so `duplicateof` → a completed task works; searching refreshes results from the server (`s=` query).
- **L3 regression:** the existing **Subtasks** section still creates a brand-new child via the inline "Add subtask" input and toggles its done state — relations work must not break it.
- **L4:** after add, `vikunja_tasks` GET A → assert `related_tasks.blocking` contains B's id; GET B → assert `related_tasks.blocked` contains A's id. After removal, both keys are absent/empty.

### Plan 4 gate — Notification granularity
- **L2:** `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.DefaultReminderTest"` → PASS (offset 0 → null; null/sentinel due → null; -1 → `relative_period 0` at due; 3600 → `-3600`, instant one hour earlier).
- **L1:** `./gradlew compileDebugKotlin` → SUCCESS.
- **L3 afternoon:** Settings → Notifications → enable Afternoon Summary → time row appears; set 16:00. Confirm a distinct `daily_summary_afternoon` periodic job exists (behaviorally, or `adb shell dumpsys jobscheduler` filtered to the app package) and that disabling it does **not** cancel the morning job.
- **L3 per-type:** toggle off "Include Upcoming" → trigger the summary (test button, or set summary time ~1 min ahead) → digest omits the upcoming count. Toggle off all three → no summary fires (total 0 short-circuits).
- **L3 default offset:** set Default Reminder = "1 hour before" (relative-to row appears) → create a task with a due date and **no** manual reminder → open it → it carries one reminder 1h before due. Create a task with **no** due date → no reminder. Create a task **with** a manual reminder → the default is **not** also added (no duplicate).
- **L3 reminder fires:** set a due date so the synthesized trigger is ~1–2 min out → the system alarm fires a notification (proves `AlarmScheduler` consumes the synthesized reminder).
- **L3 regression:** the original morning summary still schedules + fires; pre-existing per-task reminders still fire; existing notification settings (sound, task-reminders toggle, summary time) unchanged.
- **L3 boot:** reboot with afternoon enabled → `BootReceiver` re-schedules **both** slots (next cycle still fires).
- **L4:** `vikunja_tasks` GET the dated task → assert its `reminders` array contains the synthesized reminder with the expected `relative_period` and `relative_to`.

### Plan 5 gate — Review feature
- **L2:** `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.ReviewMetadataTest"` → PASS (parse/serialize round-trip; weekly→days; excluded; upsert does not accumulate footers; status never/within/past/excluded incl. override-beats-global).
- **L1:** `./gradlew compileDebugKotlin` then `./gradlew assembleDebug` → SUCCESS.
- **L3 feature flag:** Settings → General → Review → enable → drawer "Review" appears; disable → it disappears (desktop parity).
- **L3 badge + due:** with a never-reviewed (non-excluded, non-inbox) project present, the drawer shows a purple overdue count and the Due tab lists it.
- **L3 mark reviewed:** "Mark reviewed" → row shows "✓ Reviewed", undo snackbar shows, badge decrements, progress bar advances.
- **L3 undo:** tap Undo in the snackbar → project returns to Due; badge increments back (undo re-sends the prior project).
- **L3 cadence:** set a project to "every 7 days" via the row menu; mark reviewed → it leaves Due; advance the device date +8 days → it returns to Due (or verify via the footer math).
- **L3 exclude:** exclude a project → leaves both Due and All-tracked; un-exclude → returns as "never".
- **L3 exclude-inbox:** with "Exclude Inbox" on, the inbox never appears; toggle off → it appears.
- **L3 default cadence:** change Default review cadence (e.g. 30→7) → previously-not-due projects may become overdue; Due set + badge recompute live.
- **L3 regression (critical — Go zero-value):** after mark-reviewed, open the project in the Projects editor → the human description body above the `---` footer is intact, and title/color/archived/parent are unchanged. Mark-review a project twice → exactly one footer (no accumulation).
- **L4:** after mark-reviewed, `vikunja_projects` GET the project → `description` ends with `---\n**Vicu review**: <today>[ · every N days]` and the body above is preserved. Cross-check with `vicu_review_get_status` (same metadata) → state `reviewed`, lastReviewedAt = today; then `vicu_review_list_due` → the project is no longer due. Exclude in-app → `vicu_review_get_status` → state `excluded`.

### Cross-cutting / integration verification (after Step 5)
- **Settings integrity (highest risk — the combine restructure):** open every Settings tab (General, Notifications, Gestures) → all pre-existing rows still render and function. Concretely exercise: change theme, change inbox project, edit a custom list, create/delete a label, toggle a bottom-bar slot, toggle NLP — each must still work, proving Plan 5's addition of `ReviewPrefs` to the combine dropped no existing state.
- **Navigation:** drawer highlights the active destination including Review; back-stack behaves (Review uses saveState/launchSingleTop/restoreState like other smart lists); bottom bar unchanged.
- **Offline:** in airplane mode the app opens, lists render from Room, and a relation/review write surfaces an error rather than hanging (project update is direct, not queued).
- **Style:** no emojis in new user-facing strings beyond the specified "✓" marks; commit messages/PRs plain text (user preference).

### Full regression + ship gate
1. `./gradlew testDebugUnitTest` → **all** suites green: existing (`TaskSortTest`, `RefreshBackoffTest`, `TaskLinkParserTest`) + 4 new (`TaskLinkParserNotesContentTest`, `RelationKindTest`, `DefaultReminderTest`, `ReviewMetadataTest`).
2. `./gradlew assembleDebug` → BUILD SUCCESSFUL.
3. `./gradlew ktlintCheck` → clean (run `ktlintFormat` and commit churn if it flags anything).
4. `./gradlew installDebug` on a **clean install** → run the full L3/L4 matrix above once more end-to-end.
5. `git push origin main` → confirm `origin/main` advanced. CI builds a signed release only on a *published GitHub release*, so pushing commits triggers no accidental release.

### Definition of done
- [ ] Step 0 + all 5 plans' tasks committed to `main`.
- [ ] All four new unit-test classes pass; no existing test regressed.
- [ ] `assembleDebug` + `ktlintCheck` clean.
- [ ] L3 happy/edge/negative/regression verified for each of the 5 features on a clean install.
- [ ] L4 backend cross-checks pass for Relations (reciprocal), Notifications (reminder persisted), Review (footer + `vicu_review_*`).
- [ ] Settings cross-cutting regression verified (no dropped state).
- [ ] `origin/main` updated.

---

## Execution mechanics

- **Skill:** drive each Step with `superpowers:executing-plans` (inline, batch with checkpoints); use the `verify` skill for the L3/L4 layers. Each sub-plan carries its own per-task checkboxes; debug failures with `superpowers:systematic-debugging` rather than patching past a red gate.
- **Estimated effort:** ~15h serial (Plan 1 ~1h, Plan 2 ~1h, Plan 3 ~3–4h, Plan 4 ~3–4h, Plan 5 ~4–5h) + Step 0 (~30m) + verification passes.
- **Checkpoints:** pause for review after each Step's commit(s). A failing gate stops the line until fixed — do not start the next plan on a red build.
- **Adaptation points** flagged inside the sub-plans (`Project.isArchived` nullability, `parseHexColor` import path, the exact `TaskEntryViewModel` due-date apply site, drawer/settings `combine` arity, `SettingsValueRow` already created in Step 0) must be resolved against live code during execution — each is called out in its plan with the exact code to use.
- **Rollback:** because work commits straight to `main`, each plan is a small contiguous run of commits. If a feature must be backed out post-hoc, `git revert` that plan's commit range — they're authored per-plan and don't interleave.

---

## Clean-context execution handoff (recommended)

Execute in a **fresh session**, not this one. This conversation is heavy with spent research (feature inventories, five planning briefs, cross-plan analysis) — none of which the implementation needs. The sub-plans were written for "an engineer with zero context": exact file paths, complete code, per-task TDD steps. A clean context maximizes the working window for the ~15h of implementation + debugging and avoids mid-run compaction.

**One-time handoff prep (do these before starting fresh, or as the new session's first action — they are Step 0 work):**
1. Copy this master plan into the repo so it travels with the code and a fresh session can find it:
   `docs/superpowers/plans/2026-05-31-00-MASTER-execution-plan.md` (a copy of this file).
2. Commit the plan docs (this is already Step 0 item 1): `git add docs/superpowers/plans/2026-05-31-*.md` → commit.
3. The memory file `desktop-parity-gaps.md` (indexed in `MEMORY.md`) already points to these plans, so a fresh session in this repo will surface them automatically.

**Fresh-session kickoff prompt (paste into the new session):**
> Read the master plan at `C:\Users\rendy\.claude\plans\make-a-plan-to-splendid-pizza.md` and the five sub-plans in `docs/superpowers/plans/2026-05-31-*.md`. Execute them in order (Step 0 → Plan 1…5), committing straight to `main`, running each plan's verification gate before moving on. Step 0's first action: copy the master plan into the repo as `docs/superpowers/plans/2026-05-31-00-MASTER-execution-plan.md` and commit it with the others. Use the `superpowers:executing-plans` skill and the `verify` skill for functional/backend checks. Stop at each Step's checkpoint for my review.

(The master plan lives in the durable plan-mode directory, readable across sessions by that absolute path — so the fresh session can find it before the repo copy exists.)

**What carries over without this conversation:** all implementation detail (in the sub-plans), the execution decisions (this master plan: sequential, straight-to-main, the `SettingsValueRow` pre-req, ordering, the four-layer verification), and the plan locations (memory). Nothing essential is lost by starting clean.

**When NOT to start fresh:** if you want to knock out only the two tiny plans (notes indicator, token dedup) right now, doing them here is fine — they're ~1h each and don't need a clean window.
