# Vicu on iOS

Vicu runs on iPhone and iPad (iOS 17 and later) from the same code as Android. The screens,
view models, sync engine and data layer are the Compose Multiplatform code in `:shared`
(`commonMain`). iOS adds its platform pieces in `shared/src/iosMain` and a small Swift app in
`iosApp/`, which also holds the widget and share extensions. Android behaves exactly as before.

This document covers how the iOS app is built, tested and released, how it is put together, and
where it behaves differently from Android.

## Building and running

Requirements: a Mac with Xcode 26 or newer, JDK 17, and XcodeGen (`brew install xcodegen`).

```bash
iosApp/scripts/generate.sh                        # writes Version.xcconfig, generates Vicu.xcodeproj
open iosApp/Vicu.xcodeproj                        # run the Vicu scheme on a simulator or device
./gradlew :shared:iosSimulatorArm64Test           # shared tests on the iOS simulator (Kotlin/Native)
xcodebuild -project iosApp/Vicu.xcodeproj -scheme Vicu \
  -destination 'platform=iOS Simulator,name=iPhone 17' build   # the app and its extensions
```

The **iOS gate** is the last two commands; `.github/workflows/ios.yml` runs them on `main`. The
Android gate (`./gradlew test lint assembleDebug`) also runs
`:shared:compileCommonMainKotlinMetadata`, which compiles `commonMain` without the Android
classpath and fails on Android or JVM-only API used in common code, so a Linux CI run catches
most iOS breakage without a Mac.

- The Xcode project is generated from `iosApp/project.yml` and is not checked in. Run
  `generate.sh` again after changing `project.yml` or `version.properties`.
- The Kotlin framework is built by a build phase (`iosApp/scripts/build-shared-framework.sh`,
  Gradle's `embedAndSignAppleFrameworkForXcode`), so Xcode builds it on every run. A Release
  build (an archive) links it with optimizations, which takes about 10 minutes and needs more
  memory than the Android build. `gradle.properties` runs the Kotlin/Native compiler in its own
  process with a 10 GB heap (`kotlin.native.disableCompilerDaemon`, `kotlin.native.jvmArgs`; its
  live heap peaks near 7.6 GB, in the devirtualization analysis, and a 6 GB heap runs out) and
  keeps the Gradle daemon at 2 GB. Build it on a Mac with 16 GB of memory. GitHub's standard
  macOS runner has 7 GB, too little for the TestFlight job in `ios.yml`: it needs a larger
  runner (or a link that needs less memory). Until then, upload from a Mac with
  `iosApp/scripts/testflight.sh` (see [Release](#release)).
- Local speech (`native/speech`, whisper.cpp with Metal and Accelerate) is a static library per
  SDK, `shared/build/speech-ios/<iphoneos|iphonesimulator>/lib/libvicu_speech_all.a`, built by
  `iosApp/scripts/build-speech.sh` (CMake from PATH, Homebrew or the Android SDK; whisper.cpp
  downloaded by CMake and checked by SHA-256, or `VICU_WHISPER_SOURCE_DIR`) before each iOS
  target's cinterop (`shared/src/nativeInterop/cinterop/vicuSpeech.def`). The first build of an
  SDK takes about 4 minutes; it is skipped while `native/speech` and the script are unchanged. The
  Metal shaders are embedded as source and compiled on the device, so no Metal toolchain is
  needed. The framework is static, so the app target itself links Metal, MetalKit, Accelerate
  and libc++ (`OTHER_LDFLAGS` in `project.yml`); the Silero model is in the app bundle.
- Signing: put `DEVELOPMENT_TEAM = <your team id>` in `iosApp/Configuration/Local.xcconfig`
  (git-ignored) to run on a device.
- Debug and release install side by side: `com.rendyhd.vicu.debug` ("Vicu Debug", URL scheme
  `vicu-debug`, App Group `group.com.rendyhd.vicu.debug`) and `com.rendyhd.vicu` ("Vicu",
  `vicu`, `group.com.rendyhd.vicu`). The extensions are `<bundle id>.widgets` and
  `<bundle id>.share`.
- To judge speed on a phone, build the **Profile** configuration: the debug app's identity
  ("Vicu Debug", its ids and App Group, so it needs no new identifiers and installs over a
  Debug build) with optimized Swift and Kotlin (`Configuration/Profile.xcconfig`). A Debug
  build's Kotlin code is not optimized and scrolls noticeably slower on a device. The optimized
  link takes about 10 minutes.
  ```bash
  xcodebuild -project iosApp/Vicu.xcodeproj -scheme Vicu -configuration Profile \
    -destination 'platform=iOS,id=<device udid>' -allowProvisioningUpdates build
  xcrun devicectl device install app --device <device> <derived data>/Build/Products/Profile-iphoneos/Vicu.app
  ```
- The version of both apps is `version.properties` at the root. `CFBundleVersion` is the
  Android `versionCode` × 100 plus `BUILD_ATTEMPT` (default 0), so the same version can be
  uploaded to TestFlight again.

### Simulator testing

`scripts/ui-verify-ios/` is a small Node harness (no dependencies) for the simulator:

```bash
node scripts/ui-verify-ios/install.mjs [--device "iPhone 17"] [--no-build] [--launch]
node scripts/ui-verify-ios/shots.mjs --scenario baseline --theme both --text both
```

`install.mjs` builds the debug app, signs it with your Apple Development certificate when there
is one, and installs it. `shots.mjs` opens screens by deep link and captures them in light and
dark and at the default and an accessibility text size into `scripts/ui-verify-ios/out/`. It
only drives simulators: a device is picked by name or UDID from `simctl list devices available`
and every call names that UDID. Taps and typing are done by hand or with the iOS Simulator tools.

- **Data**: the desktop harness' seeded Docker Vikunja (`node scripts/ui-verify/seed.mjs` in the
  vicu repo). The simulator shares the Mac's network, so the server is `http://localhost:3456`
  (the Android emulator uses `10.0.2.2`). Do not run the iOS, Android and desktop harnesses at
  the same time.
- **Widgets in the simulator** need a team-signed build: an ad-hoc signed app has no team, iOS
  then cannot resolve the Tasks widget's list, and the widget always shows Today.
  `iosApp/scripts/sign-for-simulator.sh <Vicu.app>` signs it (`install.mjs` does this for you).
  Builds for a device are always team-signed.
- **Background tasks do not run in the simulator** (BGTaskScheduler refuses to schedule them).
  Check them on a device: Settings > General > Background activity lists the last runs.
- The simulator's keyboard autocorrects typed text; turn it off in the simulator's settings
  (or `defaults write com.apple.Preferences KeyboardAutocorrection -bool NO`) before typing
  into the app from a script.

## How it is put together

### Start and lifecycle

- `iosApp/Vicu/VicuApp.swift` is the SwiftUI app; `AppDelegate.swift` calls
  `IosApp.start(widgetBridge:)` in `didFinishLaunching`, before the first scene, because iOS also
  starts the app without a scene for a notification action or a background task.
- `IosApp` (`iosMain/.../ios/`) starts Koin (`sharedModules + iosModule`), installs the
  notification delegate and registers the background tasks. `IosAppLifecycle` does what the
  Android activity and application do at start, on resume, when the app goes to the background
  and when the session changes (through the shared `AuthTransitions` rule).
- `MainViewController` hosts `VicuApp` (the shared Compose root). The UI's saved state
  (`rememberSaveable`, the back stack, an open editor and its draft) is encoded by `UiStateCodec`
  and kept in SwiftUI scene storage, so it comes back when iOS ends the app in the background and
  is dropped when the user closes the app in the app switcher. A restore that crashed is not
  tried again.
- Links: `vicu://task/<id>`, `vicu://view/<TYPE>[/<id>]`, `vicu://add[?project=<id>]`,
  `vicu://dictate` (`AppLink`), handled through `onOpenURL` and `AppInputs`. Widgets,
  notifications, the control, shortcuts and the quick actions all open the app this way.

### Platform pieces (iosMain)

| Seam | iOS implementation |
|---|---|
| `TokenStorage` | `IosKeychainTokenStorage` (tokens and install id in the Keychain, after first unlock, this device only; the other session values in `session/session.json` in Application Support, excluded from backup) |
| HTTP | Ktor Darwin, with NSURLSession's own cookie handling off (the app sends the refresh cookie itself) |
| `NetworkMonitor` | `IosNetworkMonitor` (NWPathMonitor) |
| `PlatformFiles` | `IosPlatformFiles` (staged copies, streamed uploads, Quick Look, share sheet) |
| Pickers | PHPicker (photos, no permission needed), UIDocumentPicker (files, completion sound) |
| `PlatformRepositoryHooks` | `IosRepositoryHooks`: sync requests, notifications planned again, widgets written |
| `PlatformSettingsHooks` | `IosSettingsHooks` (test notification, background activity log, quick-add hint) |
| `PlatformAuthHooks` | `IosAuthHooks` (background refresh keeps the session alive) |
| Notifications | `IosNotificationScheduler` and `IosNotificationDelegate` |
| Background | `IosBackgroundTasks` (BGTaskScheduler) and `SyncRequester` (in place of WorkManager) |
| Widgets | `IosWidgetSnapshots` writes what the Swift widgets show |
| Share | `IosShareInbox` adds what the share extension saved |
| Images | Coil with the Ktor fetcher and the common `VikunjaCoilInterceptor` |
| Completion sound | `IosCompletionSoundPlayer` (AVAudioPlayer, ambient session) |

The database, preferences and session values live in Application Support (excluded from
backup), not in the App Group: the extensions never read them, and an app that holds an SQLite
lock in a shared container when it is suspended is killed by iOS. Nothing of the account is
backed up, so a device restored from a backup starts signed out with its own install id (older
versions kept the session in the user defaults; the first start of a newer one moves it, or
drops it when the Keychain has no tokens, which means the defaults came from another device).
The App Group holds only what the app and its extensions exchange, in two folders excluded from
backup:

```
widgets/catalog.json        the views a widget can show, whether anyone is signed in, colours
widgets/views/<key>.json    one view (smart list, project, custom list) for today and tomorrow
widgets/routines.json       the routine widget's days
widgets/actions/*.json      taps on widget buttons the app has not stored yet
widgets/account             the signed-in account's tag (app only; see Widgets)
share-inbox/<id>/           a share: item.json and files/ (<id>.part while the sheet is open)
```

Sign-out and a switch to another account (`LocalDataWiper.wipeEverything`) empty
`share-inbox/`, drop widget taps not taken in yet and retire the account's tag.

### Notifications

iOS runs no app code when a notification fires and keeps at most 64 pending, so notifications
are planned ahead instead of being scheduled one by one:

- `LocalNotificationPlanner` (shared, tested) builds every notification the app wants: task
  reminders and snoozes at their instant, routine reminders and daily summaries on the local
  wall clock. `NotificationBudget` keeps 60: snoozes and the next summary of each slot always,
  then the soonest reminders and routine reminders.
- `IosNotificationScheduler` applies the plan to UNUserNotificationCenter, changing only what
  changed, and removes delivered reminders of tasks that are done. It plans again (debounced)
  on every change, on start and resume, on a new day, after syncs and in the background refresh.
- Daily summaries carry their numbers for three days ahead (none on a day with nothing to
  report); after that a general "Open Vicu to see your tasks for today." keeps the summary
  coming when the app has not run.
- Actions: Mark Complete and Snooze 15 min on task reminders, Complete and Skip on routine
  reminders. They run in the app (`NotificationActions`, shared with Android) inside a
  background task, then sync.

### Location reminders

`iosApp/Vicu/LocationMonitor.swift` watches the regions the shared `LocationReminderCoordinator`
plans, with a `CLMonitor` named `VicuLocationReminders` (at most 20 regions; a monitor name is letters and digits
only, iOS 26.5 ends the app for anything else). It is created in
the app delegate before `IosApp.start(widgetBridge:locationBridge:)`, so a relaunch in the
background for a region event delivers the event to `IosApp.onRegionEvent`, which posts the
notification (`location:<taskId>`, kind `LOCATION`) inside a background task. It also asks for the
permission (While Using, then Always) and reads the current position; place search is
`MKLocalSearch` in Kotlin (`IosPlaceSearch`). No `location` background mode is needed. See
`docs/location-reminders.md`.

### Holding + to say a task

`iosApp/Vicu/SpeechRecognizerBridge.swift` is the `AppleSpeechBridge` the app delegate hands to
`IosApp.start(widgetBridge:locationBridge:speechBridge:)`: `SFSpeechRecognizer` fed by
`AVAudioEngine`, with partial results and the voice level. The shared `HoldToTalk` drives it
through `IosSpeechInput`. Speech recognition and the microphone are asked for on the first hold
(`NSSpeechRecognitionUsageDescription`, `NSMicrophoneUsageDescription` in `project.yml`). The
simulator has a recognizer, but its microphone is the Mac's. See `docs/voice-input.md`.

### Local speech and the AI keys

For the AI assistant (`docs/ai-assistant-v1.md`, sections 3 and 7.2), `iosModule` binds:
`IosSpeechEngineNative` (the C API of `native/speech/vicu_speech.h` through cinterop),
`IosAudioRecorder` (`AVAudioEngine`'s input tap converted to 16 kHz mono float by
`AVAudioConverter`; the same record / measurement session as the recognizer bridge; a call or a
route change that stops the engine ends the recording), `IosSpeechOutput` (`AVSpeechSynthesizer`,
its delegate held by the class, switching the session to playback while it speaks),
`IosSpeechStorage` (downloaded models in Application Support `speech-models/`, out of backups;
the Silero model read from the app bundle; unmetered means a path that is neither expensive nor
constrained) and `IosAiKeyStore` (Keychain service `com.rendyhd.vicu.ai`; the first use in an
install deletes keys an earlier install left). `IosSpeechEngineNativeTest` runs the bundled
voice detector on the simulator from the repository's copy.

### Background work

- **Refresh** (`com.rendyhd.vicu.refresh`, BGAppRefreshTask, asked for every 30 minutes; iOS
  decides when): refreshes the session (`TokenRefreshRun`), takes in shares and widget taps,
  syncs, plans notifications and writes widgets (`PeriodicSyncRunner`).
- **Queue sync** (`com.rendyhd.vicu.sync`, BGProcessingTask with a network): sends changes still
  queued when the app went to the background without a connection.
- When the app goes to the background it sends what is queued inside a background task.
- While the app runs, `SyncRequester` runs a sync on request, folds requests made during a sync
  into one follow-up, and retries with a growing delay.

### Widgets, control and quick add (iosApp/VicuWidgets)

The widget extension is Swift only; it links no Kotlin. The app writes JSON snapshots into the
App Group (`WidgetSnapshots` in shared, `IosWidgetSnapshots` in iosMain): every view for today
and tomorrow, with the due labels already phrased by `DateDisplay` and the colours of the Vicu
scheme, so a widget stays right after midnight and Swift reimplements no app rules. Writes are
atomic and debounced, and WidgetKit is asked to reload only when a file changed (reloads from the
background are budgeted).

- **Tasks** widget (small, medium, large): any smart list, project or custom list, chosen in
  Edit Widget. The circle completes a task; a row opens the task and the rest of the widget
  opens the list (a small widget always opens the list); + opens the task entry (medium and
  large). WidgetKit keeps the choice across a sign-out, so the id of a project or custom list
  carries the account's tag (`project-12@<tag>`, `WidgetSnapshots.entityId`): a widget chosen
  under another account says to choose another list instead of showing the new account's
  project 12. Smart lists show whichever account is signed in. A widget chosen before the ids
  carried a tag belongs to the account that was signed in when the app first wrote the catalog
  after the update (`untaggedAccount`).
- **Routines** widget (small, medium, large): today's routines; the circle logs one.
- **Add task** control (iOS 18): Control Center, the Lock Screen and the Action Button.
- **Shortcuts** (`VicuShortcuts`):
  - "Add a task in Vicu" (`AddTaskIntent` in `iosApp/Vicu/`): Siri asks "What's the task?" and
    adds the answer without opening the app, read like quick add ("Call mom tomorrow 3pm
    #Family"), also on a locked phone. It runs `IosApp.addTaskFromText` (`TextTaskCreator`): the
    task is stored on the device and queued, and Siri says what was added. In the Shortcuts app
    its Task parameter can come from "Dictate Text", for an Action Button that adds in one step.
    See `docs/voice-input.md`.
  - "Open Vicu quick add" (`OpenQuickAddIntent` in `iosApp/Shared/`): opens the task entry; the
    Add task control runs it too.
- **Home-screen quick actions**: Add task (quick add) and Dictate (`SceneDelegate` in
  `AppDelegate.swift`); the same two as Android's launcher shortcuts. Dictate opens smart
  dictation, listening, when it is set up, otherwise the voice card without a hold (Done and
  Cancel), as "Say a task" does; see `docs/voice-input.md`.

### Share extension (iosApp/VicuShare)

A native sheet with the title, notes and attachments filled in from what was shared (text, a web
page, images, videos, files). Add saves it into the App Group; the app adds it to the Inbox
(`IosShareInbox`) when it starts, comes to the front, gets a connection back, or in the
background refresh, then syncs and says so in the snackbar.

## Differences from Android

- **Reminders are planned ahead.** At most 60 are pending; the soonest come first. A reminder of
  a task completed on another device can still show until the app has synced (Android checks
  the task when the alarm fires).
- **Daily summary** numbers are worked out up to three days ahead; later days get a general
  text until the app runs again.
- **No exact-alarm banner and no badge.** The snooze button reads "Snooze 15 min".
- **Background sync** runs when iOS allows it, not every 30 minutes. Settings > General >
  Background activity shows the last runs.
- **Pasting an image into a comment.** A text field on iOS cannot take an image, so the composer's
  Paste image chip, shown while the pasteboard holds one, and Cmd+V do it; iOS asks whether to allow
  the paste when it is read. Android also takes it from a long press's Paste and the keyboard's
  clipboard.
- **Widget buttons** run in the widget extension: the row goes at once, and the change is stored
  and sent when the app next runs (opened, or the background refresh). Android sends it from the
  widget straight away. A small Tasks widget has one tap target (opens the list), no + and no
  dates.
- **Quick add**: the Control Center control, Siri and the home-screen quick actions take the
  place of Android's quick-add tile; Settings > Notifications explains how to add the control.
- **Share**: a native sheet adds the share to the Inbox; Android opens the full task entry.
  Quick-add parsing does not apply to a shared title. A share with files waits for a connection.
- **Pasting an image into a description** is Android only; use Add image on iOS.
- **Device colours** (Material You) are Android only; iOS always uses the Vicu scheme.
- **OIDC sign-in** uses an in-app web view that catches Vikunja's own callback. It cannot use
  passkeys, and Google and some other providers refuse embedded web views; "Sign in through your
  browser" signs in with `ASWebAuthenticationSession` instead, once `com.rendyhd.vicu:/oidc-callback`
  is a redirect URL of the Vikunja client at the provider. A passkey prompt in the web view leads
  there by itself.
- **Saved UI state** comes back after iOS ended the app in the background and is dropped when
  the user closes the app in the app switcher.

## Release

1. Apple Developer setup (once; needs the account owner):
   - App IDs `com.rendyhd.vicu`, `com.rendyhd.vicu.widgets` and `com.rendyhd.vicu.share` with the
     App Groups capability, and the App Group `group.com.rendyhd.vicu` assigned to all three.
     The debug ids (`.debug`) are only needed to run the debug build on a device.
   - An App Store Connect app record for `com.rendyhd.vicu`.
   - For uploads from GitHub: an App Store Connect API key (App Manager), stored as the
     repository secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` (the .p8 contents), plus
     `APPLE_TEAM_ID`. Uploads from a Mac can use the Apple account signed in to Xcode instead.
2. Upload a build to TestFlight, from a Mac or from GitHub. Both archive the Release
   configuration with automatic signing (`-allowProvisioningUpdates`) and export it with
   `iosApp/ExportOptions.plist`, which uploads it.
   - **From a Mac** with 16 GB of memory: `iosApp/scripts/testflight.sh`. It signs with the
     Apple account signed in to Xcode (Settings > Accounts) and the team in `Local.xcconfig`, or
     with an API key when `ASC_KEY_PATH`, `ASC_KEY_ID` and `ASC_ISSUER_ID` are set. It takes
     about 15 minutes; `BUILD_ATTEMPT=1` uploads the same version again, `--no-upload` only
     archives (into `iosApp/build/testflight/`). The same can be done by hand in Xcode: Any iOS
     Device, Product > Archive, then Distribute App > TestFlight & App Store.
   - **From GitHub**: the `testflight` job of `ios.yml`, run by hand (workflow_dispatch, with
     `upload` checked; `build_attempt` re-uploads a version). It needs a runner with more
     memory than GitHub's standard macOS runner (see [Building and running](#building-and-running)).
3. Submit the TestFlight build for review in App Store Connect. Review notes should give a demo
   Vikunja server and account that stays up, say that Vicu creates no accounts and connects to
   the user's own server, and explain why App Transport Security allows http (self-hosted
   servers on a home network).

Privacy: the app and both extensions have privacy manifests (`PrivacyInfo.xcprivacy`); Vicu
collects no data (App Store privacy label: Data Not Collected). The app declares the
required-reason APIs its binary calls: user defaults (CA92.1), file timestamps of its own files
(C617.1), and disk space (E174.1, `statfs` in the bundled SQLite). The extensions call none.
`ITSAppUsesNonExemptEncryption` is false (only HTTPS and the system Keychain).

### Review notes for App Store Connect

A starting point for the App Review Information field; fill in the demo server and account.

> Vicu is a client for Vikunja, an open-source task server that people host themselves. The
> app creates no accounts and has no server of its own: it signs in to the user's own Vikunja
> server with the user's existing account (guidelines 4.8 and 5.1.1(v) do not apply to account
> creation here).
>
> To review: on the first screen enter the server address below, choose "Sign in with
> password" and use the demo account. Pick "Inbox" as the Inbox project.
>
> Server: https://<demo server>  Username: <demo user>  Password: <demo password>
>
> App Transport Security allows plain http because many self-hosted servers run on a home
> network without a certificate; the setup screen warns when an http address points outside
> the local network. The app asks for local network access for the same reason.

The demo server must stay up and reachable over HTTPS for as long as the review (and later
reviews) take, with a few projects and tasks in the demo account.

### Before the first release, on a device

The simulator cannot check these; do them with a TestFlight build on an iPhone:

- Reminders, snooze and routine reminders with the app closed from the app switcher, and the
  lock-screen actions.
- Background refresh: after a few hours, Settings > General > Background activity lists runs.
- The widgets and their buttons, the Add task control, Siri ("Add a task in Vicu", also on a
  locked phone and in CarPlay) and the home-screen quick actions (Add task, Dictate).
- The share extension from Safari, Photos and Files.
- Location reminders with the app closed: arrive and leave, set while already at the place
  (fires on the next arrival), after a restart, and the Always prompt.
- The Local Network prompt with a server on the home network.
- VoiceOver on the lists, the editor and quick add (rows read their title, date and state, and
  the row actions are offered).
- OIDC with the user's own provider (the in-app web view must be accepted by the provider), and
  the browser sign-in with a passkey on a device (the simulator has no passkeys of the user's).
