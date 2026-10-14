# Implementation Plan — Kotlin/Compose Multiplatform Migration for iOS Support

> **Status: historical.** iOS support is not being pursued and the targets do not compile. See [ios-status.md](ios-status.md) for the current state.

**Goal:** Ship Vicu on iPhone by sharing the existing Kotlin + Compose codebase via Compose Multiplatform (CMP), with zero regression on the Android app.

**Strategy:** Strangler-style migration. The Android app must build, pass all unit tests, and be manually smoke-testable after every phase. Phases 0–4 run entirely on Windows; only Phase 5 (the iOS host app) requires macOS. No big-bang restructure.

**Verified against codebase 2026-06-12** (195 main Kotlin files, 17 ViewModels, 28 screen files, 40 Retrofit endpoints, 20 unit-test files).

---

## Verification Results (claims in the original plan checked against the code)

| Claim | Verdict |
|---|---|
| AppAuth dependency is unused | **Correct** — no `net.openid` import anywhere in `app/src`. But removal needs more than the toml entry: `app/build.gradle.kts:134` (`implementation(libs.appauth)`), the `appAuthRedirectScheme` manifest placeholders at `app/build.gradle.kts:33` and `:67` (vestigial — not referenced in the manifest), and `app/proguard-rules.pro:101-104`. |
| OIDC uses a custom WebView (`OidcLoginActivity`) intercepting the redirect | **Correct.** Same approach ports directly to a `WKWebView` sheet on iOS. |
| `java.time.*` used in 13 files | **Correct** — 13 main-source files plus 4 test files (49 import lines total). The tests (`TaskParserTest`, `DateUtilsTest`, `DateUtilsMidnightTest`, `ReviewMetadataTest`) are the regression net for the datetime migration and must move to `commonTest`. |
| `SecureTokenStorage` = Tink AEAD + DataStore | **Correct.** Note it also stores non-token config (server URL, inbox project id, auth method, provider key, v2 flag) — the iOS Keychain impl must cover all keys. |
| Room / DataStore "to be added as KMP" | **Already there** — Room 2.7.1 and DataStore 1.1.4 are the KMP-capable versions. The work is reconfiguration (builder + drivers + file paths), not version bumps. |
| Context decoupling = toast + clipboard + 2 banners | **Understated.** 8 `LocalContext.current` sites in `ui/` (incl. `Theme.kt` dynamic color, `TaskEntrySheet`, `TaskDetailScreen`, `SettingsScreen` x2) plus ~18 data-layer files importing `android.*` (12 DataStore prefs stores taking `Context`, `android.util.Log` in interceptors/repos). Full audit list in Component 4. |
| Sync | **Missing from the plan entirely.** The whole offline sync engine (pending-action replay, temp-ID remapping, duplicate detection, server refresh, deletion reconciliation, ~300 lines) lives inside `SyncWorker.doWork()` — an Android `CoroutineWorker`. Without extracting it, iOS gets no sync. See Component 3. |
| Notifications on iOS | **Missing from the plan.** Per-task reminders, snooze, and daily summary are AlarmManager/WorkManager-based. iOS needs a `UNUserNotificationCenter` counterpart or the feature silently disappears. See Component 7. |
| Rename `:app` → `:androidApp` | **Don't.** `.github/workflows/release.yml:29,48` reference `app/` paths (keystore decode, APK upload), and `keystore.properties` conventions follow. Keep the module named `:app`; just add `:shared`. Zero CI churn. |

**Good news found during verification:** the UI layer references Android resources exactly once (`R.string` in `SettingsViewModel`) — strings are hardcoded in composables, so there is no resource-migration phase. The `sh.calvin.reorderable` drag-and-drop library and Coil 3 are already multiplatform.

---

## Decisions (recommended defaults — flag if wrong)

1. **macOS**: not required until Phase 5. All shared-module work (Phases 0–4) is developed and verified on Windows against the Android target. Phase 5 needs either a local Mac (for simulator testing and Xcode) or a GitHub Actions `macos-*` runner (can build the iOS framework and run `iosSimulatorArm64Test` in CI; eventually a Mac is unavoidable for interactive debugging and App Store submission).
2. **iOS home-screen widgets: deferred** to a follow-up phase after the app ships. (Corrected approach for when it happens: do NOT link the KMP framework into the widget extension and call DAOs directly — widget extensions have a ~30 MB memory cap and loading shared+Room+Ktor there is fragile. Instead, the main app writes a JSON snapshot to the App Group container — same pattern as the existing `TaskWidgetStateDefinition` — and the widget reads it in pure Swift.)
3. **Desktop/JVM target: out of scope.** Nothing in the module layout precludes adding `jvm()` later; structuring for it now is speculative work.
4. **DI**: Hilt cannot live in `commonMain`; the swap to Koin is unavoidable, but it is isolated into its own phase (Component 5) with a full manual smoke test, because it touches all 17 ViewModels, 4 `@HiltWorker` classes, and `VicuApplication`.

---

## Library landscape (as of June 2026)

* **CMP 1.11.x** — iOS target stable since 1.8. Material3 is **decoupled** from the CMP plugin version: JetBrains `compose.material3` stable 1.9.0 tracks Jetpack Material3 1.4.0, which is close to what the app uses today (Jetpack BOM 2026.01.01). Pin the M3 version explicitly and audit the M3 APIs in use before the UI move.
  * **Roadmap interaction:** the deferred task-detail bottom-sheet restoration (`docs/superpowers/plans/2026-06-11-restore-task-detail-sheet.md`) is keyed on "Material3 1.5.0 stable in the Android BOM". After this migration the gate becomes "JetBrains compose.material3 based on Jetpack 1.5.0", which lags the Android BOM. Re-check that plan's trigger condition once Phase 4 lands.
* **Navigation**: `org.jetbrains.androidx.navigation:navigation-compose` 2.9.x (multiplatform port of Nav 2.9, matching the app's current 2.9.6). `rememberNavController`/`AppNavHost` work in `commonMain` as the plan assumed.
* **ViewModel/Lifecycle**: `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel(-compose)` — `ViewModel` + `viewModelScope` work in `commonMain`.
* **kotlinx-datetime 0.7.x** — note `Instant` moved to `kotlin.time.Instant`. There is **no `TemporalAdjusters`**; the few adjuster usages in `ExtractDates.kt` are hand-rolled (next-DayOfWeek arithmetic is ~3 lines each). Fixed-pattern formatting/parsing is covered by kotlinx-datetime's own `Format` API — the `expect`/`actual` `DateTimeFormatter`/`NSDateFormatter` bridge from the original plan is only needed for **locale-aware** display formats, not everywhere.
* **Ktor 3.x** — use the **OkHttp engine on Android** (`ktor-client-okhttp`) so Android keeps its current HTTP stack, TLS behavior, and logging interceptor semantics; Darwin engine on iOS. This materially reduces Android regression risk versus switching Android to CIO.
* **Coil 3** — swap `coil-network-okhttp` for `coil-network-ktor3` in common (or keep okhttp on androidMain).
* **Room 2.7.x KMP** — use the official pattern: `@ConstructedBy(VicuDatabaseConstructor::class)` + `expect fun databaseBuilder(): RoomDatabase.Builder<VikunjaDatabase>`; `BundledSQLiteDriver` on iOS, default framework driver on Android.

---

## Target structure

```
vicu-android/
├── settings.gradle.kts          (adds include(":shared") — :app keeps its name)
├── gradle/libs.versions.toml    (adds ktor, koin, kotlinx-datetime, CMP plugin; removes appauth, retrofit, hilt at the end)
├── shared/                      (NEW: KMP module)
│   ├── build.gradle.kts         (androidTarget, iosArm64, iosSimulatorArm64; XCFramework export)
│   └── src/
│       ├── commonMain/          (domain, parser, data, sync engine, UI screens, Koin modules)
│       ├── commonTest/          (parser + date + sync unit tests, moved from app)
│       ├── androidMain/         (actuals: DB builder, DataStore paths, Tink TokenStorage, logger, sound, file access, network monitor, dynamic color)
│       └── iosMain/             (actuals: DB builder, Keychain TokenStorage, NWPathMonitor, notification scheduler)
├── app/                         (UNCHANGED NAME: Android launcher, widgets, workers, notifications, manifest, OidcLoginActivity)
└── iosApp/                      (NEW in Phase 5: Xcode project, Swift entry, WKWebView OIDC sheet)
```

`iosX64` (Intel simulator) is omitted unless needed; CI and modern Macs are arm64.

---

## Components / Phases

Each phase ends with: `./gradlew :app:assembleDebug :app:testDebugUnitTest` green, plus the listed manual smoke test. Each phase is a separate PR-sized unit and gets its own detailed execution plan (per `docs/superpowers/plans/` conventions) when picked up.

### Phase 0 — In-place prep (no KMP yet; three independent, individually shippable steps)

**0a. Remove AppAuth.**
Remove `implementation(libs.appauth)` (`app/build.gradle.kts:134`), both `manifestPlaceholders["appAuthRedirectScheme"]` lines (`:33`, `:67` — verified unreferenced in `AndroidManifest.xml`), `appauth` entries in `gradle/libs.versions.toml` (`:18`, `:81`), and the proguard block (`app/proguard-rules.pro:101-104`). Smoke: release-variant OIDC login still works (debug never supported OIDC).

**0b. Migrate `java.time` → `kotlinx-datetime` in place, inside `:app`.**
This de-risks the hardest pure-logic migration while the full Android test suite and app are still wrapped around it — far safer than doing it mid-module-move. Order: move/expand tests first (`DateUtilsTest`, `DateUtilsMidnightTest`, `TaskParserTest`, `ReviewMetadataTest`), then migrate the 13 main files (`DateUtils`, `ExtractDates`, `CustomListFilterBuilder`, `ReminderFormat`, `ReviewMetadata`, `DefaultReminder`, `AlarmScheduler`, `DailySummaryScheduler`, `AuthManager`, `TaskEntryViewModel`, `ReminderPickerDialog`, `DatePickerDialog`, `ParseChipRow`). Watch: timezone-sensitive end-of-day logic (`DateUtilsMidnightTest` exists for a reason), Vikunja null date `0001-01-01T00:00:00Z` round-tripping, hand-rolled replacements for `TemporalAdjusters`.

**0c. Decouple Android UI touchpoints in place** (mechanical, low risk):
* `TaskLinkIcons.kt:90` — replace `Toast` with snackbar/callback; replace `Intent.ACTION_VIEW` with `LocalUriHandler` (already multiplatform in compose-ui).
* `SettingsScreen.kt:2347` — replace `ClipboardManager` system service with Compose `LocalClipboard`.
* Audit remaining `LocalContext` sites (`Theme.kt:40` dynamic color, `TaskEntrySheet.kt:414`, `TaskDetailScreen.kt:93`, `SettingsScreen.kt:164`) and route them through small interfaces injected from the platform layer where they can't use a multiplatform Compose API.

### Phase 1 — Create `:shared`, move pure Kotlin

* Add `:shared` with KMP + CMP plugins, targets `androidTarget`, `iosArm64`, `iosSimulatorArm64`; `:app` depends on `:shared`. **Do not rename `:app`** (CI: `.github/workflows/release.yml:29,48`).
* Move to `commonMain`: `domain/model/*`, `domain/repository/*`, `util/parser/*`, `util/DateUtils.kt`, `TaskSort.kt`, `Constants.kt`, `NetworkResult.kt`, mappers' pure parts. Move their tests to `commonTest`.
* Add an `expect`/`actual` logger (or Kermit) now — `android.util.Log` appears in 6+ data-layer files and blocks every later move.

### Phase 2 — Data layer to `:shared`

* **Room KMP**: `@ConstructedBy` + `expect databaseBuilder()`. **Android actual must keep the exact database name `"vicu_database"`** (verified in `DatabaseModule.kt:33`) and default location — existing users' data must survive the upgrade. Keep `schemaDirectory` + exported schemas. iOS actual: place the file in the App Group container (`group.com.rendyhd.vicu`) from day one so deferred widgets never require a DB file move.
* **DataStore KMP**: the 12 prefs stores (`auth_prefs`, NLP, custom lists, behavior, theme, review, logbook, label order, notification, snooze, bottom bar, widget) move behind an `expect` path provider. **Android actual must reproduce the exact current paths** (`files/datastore/<name>.preferences_pb`) or users lose settings and get logged out.
* **TokenStorage**: extract `interface TokenStorage` (all keys, including URL/inbox/auth-method); `androidMain` keeps the current Tink+DataStore implementation behaviorally identical (same keyset prefs `vicu_keyset_prefs`, master key alias, DataStore file). `iosMain`: Keychain implementation.
* **Retrofit → Ktor** (the highest-risk step — budget accordingly): port the 40 endpoints of `VikunjaApiService` to a Ktor client; DTOs and kotlinx-serialization config are reused unchanged. Port `AuthInterceptor` (proactive refresh), `TokenAuthenticator` (reactive 401, Mutex), `BaseUrlInterceptor`/`BaseUrlHolder`, and `RefreshCookieExtractor` (Vikunja 2.0 session cookie — verify Ktor's cookie handling exposes `Set-Cookie` the way OkHttp does). Android engine: **OkHttp**, so the wire behavior barely changes. Write unit tests against Ktor `MockEngine` for: 401-refresh-retry, concurrent-refresh single-flight, 412-TOTP, v2 refresh-cookie capture, multipart attachment upload.
* **Extract the sync engine** (missing from the original plan): pull the body of `SyncWorker` (pending-action replay incl. temp-ID remap and `findRecentDuplicate`, `refreshAllFromServer` incl. deletion reconciliation and pending-skip) into a `commonMain` `SyncEngine` class. Platform side effects (`AlarmScheduler.scheduleForTask/cancelForTask/rescheduleAll`, `WidgetUpdateScheduler`) become a small `PlatformSyncHooks` interface — Android implements with the existing classes, iOS later implements with notification rescheduling. `SyncWorker` becomes a thin wrapper: same WorkManager wiring, retry semantics, and `Result` mapping. The existing `remapLabelTaskPayload` test moves to `commonTest`.
* `AuthManager`, `OidcHandler`, `PasswordLoginHandler` move to common (they're token/HTTP logic); the Activity-launching seam stays platform-side (Android keeps `OidcLoginActivity`; iOS supplies the WKWebView sheet via the same `(authUrl, redirectPrefix, expectedState) → (code, state)` contract).
* Stay-in-`:app`: `NetworkMonitor` gets an `expect`/`actual` (ConnectivityManager / NWPathMonitor); `FileUtils`/`AttachmentRepositoryImpl` content-resolver parts go behind a `PlatformFiles` interface (pick file → name+bytes, save/share file).

### Phase 3 — DI swap: Hilt → Koin (one isolated phase)

* Koin modules in `commonMain` (`databaseModule`, `networkModule`, `repositoryModule`, `viewModelModule`) + `initKoin()` exactly as in the original plan.
* Android: `koin-android` + `koin-androidx-workmanager` (replaces `hilt-work` for the 4 workers: Sync, DailySummary, TokenRefresh, TaskWidget) + `koin-compose-viewmodel` (replaces `hilt-navigation-compose` for the 17 ViewModels). Remove Hilt plugins/deps last, in this same phase.
* Manual smoke test after this phase (DI failures are runtime, not compile-time): login each auth method, offline create/sync, per-task reminder fires, daily summary fires, widget config + refresh, share-into-app target.

### Phase 4 — UI to `commonMain`

* Switch UI artifacts from the Jetpack Compose BOM to CMP 1.11.x; pin `org.jetbrains.compose.material3` explicitly (decoupled versioning) and verify the M3 APIs in use compile (date pickers, modal sheets, pull-to-refresh). `material-icons-extended`, `sh.calvin.reorderable`, and Coil 3 all have multiplatform artifacts.
* Move `ui/screens`, `ui/components`, `ui/theme`, `ui/navigation` to `commonMain`. ViewModels become plain constructors resolved via `koinViewModel()`.
* `expect`/`actual` composables/utilities for the audited platform touchpoints: dynamic color in `Theme.kt` (Android 12+ only; iOS actual returns the static scheme), `ExactAlarmBanner`/`NotificationsDisabledBanner` (iOS actuals render the iOS notification-permission prompt state, not nothing — see Phase 5), attachment picking (`TaskEntrySheet`/`DescriptionField`/`TaskEntryViewModel` use `android.net.Uri` today → switch to the Phase 2 `PlatformFiles` abstraction), attachment open/share (`TaskDetailScreen` Intent), `CompletionSoundPlayer` (MediaPlayer/Ringtone → AVAudioPlayer actual, or no-op initially), the single `R.string` in `SettingsViewModel`.
* Shared entry point `MainAppContent()` as in the original plan; `:app`'s `MainActivity` shrinks to `setContent { MainAppContent() }` plus intent/share handling.

### Phase 5 — iOS host app (requires macOS or macOS CI)

* `iosApp/` Xcode project; `ComposeUIViewController` wrapper exactly as the original plan's snippet.
* **WKWebView OIDC sheet** implementing the Phase 2 auth contract (mirror of `OidcLoginActivity`: load `authUrl`, intercept `redirectPrefix`, return code/state/error). Note: `ASWebAuthenticationSession` cannot intercept an https redirect to the Vikunja frontend URL, so the WebView approach is correct here, same as desktop.
* `KeychainTokenStorage` actual.
* **iOS notifications** (new — absent from the original plan): `UNUserNotificationCenter` actual behind the same scheduling interface `AlarmScheduler` implements on Android; schedule from `task.reminders` on sync (via the `PlatformSyncHooks` from Phase 2); permission prompt + the banner actuals from Phase 4. Daily summary as a repeating local notification (computed content refreshed on each app run/sync — accept the staleness limitation and document it).
* **Sync triggers**: sync on app foreground + after local mutations (the engine is shared); register a `BGAppRefreshTask` for opportunistic background sync. iOS has no WorkManager-grade guarantee; foreground sync is the primary path.
* App icon, launch screen, bundle config, TestFlight setup.

### Phase 6 — Deferred follow-ups

iOS WidgetKit widget (JSON snapshot via App Group, per Decision 2), iOS share extension (parity with Android share-target), App Store listing/distribution, `iosX64` if Intel-simulator CI ever matters.

---

## Verification Plan

**Per phase (Windows, automated):**
```
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :shared:testDebugUnitTest        # phases 1+ (android unit tests of common code)
```
All 20 existing test files must stay green; tests moved to `commonTest` run under the android target on Windows.

**iOS automated (macOS CI from Phase 1 onward, cheap insurance):** a GitHub Actions `macos-latest` job running `./gradlew :shared:compileKotlinIosArm64` (phases 1–4) and `:shared:iosSimulatorArm64Test` — catches accidental JVM-isms in `commonMain` immediately rather than at Phase 5. (Corrected from `iosX64Test`: CI runners and modern Macs are arm64.)

**Manual smoke (Android, after phases 2, 3, 4):** all three login methods, offline task create → airplane mode → sync replay (incl. label-on-offline-task remap), per-task reminder fires, snooze, daily summary, widget refresh + checkbox, attachment upload/download, drag-reorder in a project, recurrence display, share-into-app.

**Manual smoke (iOS, Phase 5):** all screens render, OIDC + password + token login, offline queue + foreground sync, reminders fire with app backgrounded, attachment pick/upload via document picker, date parsing chips.

---

## Risk register (ordered)

1. **Ktor port of auth stack** — refresh races, v2 cookie capture, 412-TOTP flow. Mitigation: OkHttp engine on Android, MockEngine contract tests, port interceptors one-to-one.
2. **DI swap** — runtime-only failures across 17 ViewModels/4 workers. Mitigation: isolated phase + full smoke checklist.
3. **Jetpack → JetBrains Compose artifact skew** — M3 API gaps or behavior drift on Android. Mitigation: pinned decoupled M3 version, API audit before Phase 4, visual pass on all 28 screens.
4. **Data continuity on upgrade** — DB name (`vicu_database`), 12 DataStore file paths, Tink keyset prefs must remain byte-identical on Android or users lose data/sessions. Mitigation: explicit paths in actuals + upgrade-in-place test from the current release APK.
5. **kotlinx-datetime behavior drift** in the NLP parser/end-of-day logic. Mitigation: done in-place in Phase 0b under the existing test suite, expanding tests before migrating.
6. **iOS background limitations** — no WorkManager equivalent; reminders must come from locally scheduled notifications, sync is foreground-first. Accepted platform constraint, documented in Phase 5.
