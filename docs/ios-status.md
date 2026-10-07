# iOS status

**Vicu does not run on iOS, and no iOS work is planned.** Vicu Android is the only mobile app.

## What is in the repository

- The `:shared` module declares the `iosArm64` and `iosSimulatorArm64` targets, and has an `iosMain` source set with actuals for the platform pieces (Keychain token storage, DataStore and Room builders, files, network monitor, OIDC launcher, pickers, logging).
- `iosApp/` holds an Xcode project with a small SwiftUI host (`ContentView.swift`, `iOSApp.swift`).
- `docs/ios_implementation_plan.md` is the original migration plan. It is a historical document written before the shared module was built and does not describe the current code.

## Why it does not compile

The shared module was structured for Compose Multiplatform, but the Android code was not fully moved out of `commonMain`. About a dozen files in `shared/src/commonMain` import Android APIs directly (`android.util.Log`, `android.content.Intent`, `android.provider.Settings`, `android.os.Build`, `android.net.Uri`, `android.app.AlarmManager`), in the screens and view models, the theme and the settings banners, and a few utilities also use `java.*` types. The Kotlin/Native compiler rejects these, so the iOS targets cannot build even on a Mac. The `iosMain` actuals have not been compiled or run against the current code.

The Android build is not affected: the iOS targets are only declared, and on Windows and Linux Gradle skips them.

## What a revival would need

1. Move the Android imports out of `commonMain` behind `expect`/`actual` declarations or the existing platform interfaces (`PlatformRepositoryHooks`, `PlatformSettingsHooks`, `PlatformFiles`).
2. Replace `java.*` usage in common code with kotlinx-datetime and Kotlin standard library equivalents.
3. Bring the `iosMain` actuals up to date with the common interfaces, which have grown since the actuals were written (streamed attachments, the routine archive, background sync, alarms).
4. Build and run on a Mac (CI would need a macOS runner), then decide how reminders, the daily summary and widgets map to iOS, since they are WorkManager, AlarmManager and Glance based on Android.

Until someone takes this on, treat the iOS targets and `iosApp/` as unmaintained.
