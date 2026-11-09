# AGENTS.md

LSPosed module (legacy Xposed API) for One UI 9 / Android 17: replaces the Now Bar capsule with a dynamic island around the front camera. Pure Java, no external dependencies.

## Build

- Needs JDK 17–21 (CI uses 21) and Android SDK platform 36 + build-tools 36.0.0.
- `stubs/build.gradle` resolves the SDK from `local.properties` `sdk.dir` (falls back to `$ANDROID_HOME`); the build fails without one of these.
- Commands (use `gradlew.bat` on Windows, `./gradlew` here):
  - `./gradlew :app:assembleDebug` — APK at `app/build/outputs/apk/debug/app-debug.apk`.
  - `./gradlew :app:assembleRelease` — what CI builds.
- `android.useAndroidX=false`: no AndroidX, no dependency libraries. Code runs inside the SystemUI process; don't add dependencies casually.
- Lint is non-blocking (`abortOnError false`); CI has no lint/typecheck gate — only `assembleRelease` + `apksigner verify`.

## Testing (both need an Android 17 device over adb; no local unit tests)

- Shade probe (`tests/device/run.ps1`): runs production styling methods in an `app_process` on device without installing the APK or restarting SystemUI. Windows-only (invokes `adb.exe`/`d8.bat`/`javac.exe`). Requires `JAVA_HOME` (JDK 17+); defaults `-SdkRoot` to the Windows SDK path. Details: `tests/device/README.md`.
- `RegressionRunner.java` (androidTest): 169 instrumented checks. Install `app-debug.apk` + `app-debug-androidTest.apk`, then `adb shell am instrument -w dev.flexy.dynamicisland.test/dev.flexy.dynamicisland.tests.RegressionRunner`; expect `PASS: 169 regression checks`. Needs JDK 21. If you add/remove checks, update the expected count in the runner and `tests/device/README.md`.
- Install-to-verify loop after building: `adb shell su -c "pkill -f com.android.systemui"` (restarts SystemUI, no reboot).

## Architecture

- Two Gradle modules (root project name `DynamicIsland`): `:app` and `:stubs` (compile-time Xposed API stubs, `compileOnly`).
- Package/namespace is `dev.flexy.dynamicisland`; repo is OneUisland. Instrumentation package is `dev.flexy.dynamicisland.test`.
- Entrypoint: `app/src/main/assets/xposed_init` → `dev.flexy.dynamicisland.xposed.Entry` (`IXposedHookLoadPackage`). It installs hooks per host package: `SystemUiHooks` (`com.android.systemui`, the core), `VoiceRecorderHook`, `ClaudeHook`, `GptHook`. Install failures are caught and logged via `XLog` — they must not crash the host app.
- `app/.../xposed/` — hooks into host apps (System UI, recorder, Claude/GPT apps); `app/.../island/` — the island UI (`IslandView` = drawing/animations/gestures, `IslandController` = data/signals, `*Source` classes = data feeds).
- Samsung internals are reached by reflection (`Refl.java`, string-literal field/method names). Class/field renames in One UI bake into the final APK and only surface on-device.

## Versioning

- Version lives in `app/build.gradle` (`versionCode`/`versionName`). CI hardcodes the version string twice in `.github/workflows/build.yml` (artifact `cp` + upload name) holding an older version — bump it there too, or artifacts will keep the stale name.
