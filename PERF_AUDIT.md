# Performance audit — `gaming` (2026-11)

Scope: hot paths of the island loop (`IslandController.refreshNow` / `IslandView.doFrame`),
the SystemUI main-thread hooks (`Shade*`, `Refl`), and the log channels the user saw spamming.

Audited every call site by hand; the frame loop itself is already well gated:

- `IslandView.doFrame` (`IslandView.java:1146`) re-schedules only while a spring is
  active and stops (`lastFrameNanos = 0`) when the island is fully hidden. Verified there
  is no `postOnAnimation`/`Choreographer` loop that keeps running while idle. The only
  standing timers are the 1 Hz `secondTick` (gated by `needsTicks()`, only while a chronometer,
  progress or playing media is shown) and the 15 fps rainbow idle redraw
  (`IslandView.java:2386`, a documented optional setting, skipped when disabled).
- Touch region updates are diffed (`IslandWindow.invalidateTouchRegion` compares regions
  before invalidating), so `onIslandFrame()` per frame is cheap.
- `StatusBars shaders and shadow bitmaps` are cached (`topShadow` LRU, gradient fields).

## Findings and applied changes

| File | Line(s) | Problem | Impact | Change applied |
|---|---|---|---|---|
| `XLog.java` | 21–22 | `XLog.d` always writes to logcat (even in release builds). Every chatty debug line the user reported went through here. | High | Added a `DEBUG` flag (default `false`); `XLog.d` returns immediately unless it is on. Chatty informational lines were moved to a new `XLog.v`, also default-off. |
| `AudioLevels.java` | 39–46, 56–84 | `visualizer started` appears several times per minute: `needsAudio()` flickers near the visibility threshold and each flicker creates/releases a `Visualizer` (binder round-trip + 20 kHz FFT capture). | High | Stop is now debounced: the Visualizer stays alive for 1 s after `setActive(false)` and the pending stop is cancelled if audio becomes needed again. Start still happens at most once per activation. |
| `ShadeMediaPlayer.java` | 89 | `shade media renderer attached` logged on every media-row rebind, not once per row. Synchronized logcat + `XposedBridge.log` I/O on the SystemUI main thread, several times per second while the media shade is open. | High | Moved behind `XLog.v` (debug-only). |
| `ShadeMediaPlayer.java` | 105–117 (`logShadeTree`) | Diagnostic parent-chain dump for the landscape shade logged once per orientation change — user-reported spam. | Medium | Moved behind `XLog.v`. |
| `IslandController.java` | 619 | `landscape notch ...` logged via `XLog.i`, which writes logcat **and** the Xposed log file on every cutout change. | Medium | Moved behind `XLog.v`. |
| `AlertSource.java` | 731 | `headset L/R ...` logged on every Bluetooth battery-metadata read (Samsung sends these chatty while connected). | Medium | One-time string build + disk/read URI work aside, the log line itself moved behind `XLog.v`. |
| `ExitAnimationBridge.java` | 60 | `exit targets -> ...` logs each time the pushed rects change by even 1 px. The rects come from the resting-pill bounds, which jitter while the pill settles, so the log (and an actual binder transaction + 3 allocations) fires several times per second, exactly as the user observed. | Medium | Log moved behind `XLog.v`. |
| `AppInfo.java` | 17–29 | `icon()` has no negative cache: every call for a package that has no icon (or is missing from PackageManager) repeats a PackageManager IPC instead of reusing the failure. Called from `MessageSource`, `DownloadSource`, `AssistantApp`-adjacent paths. | Medium | Negative result cached in a marker set, so a missing package is only queried once. |
| `IslandController.java` | 625–660 (`hiddenCameraHole`) | Re-parses the cutout spec string (`PathParser` + reflection + `computeBounds`) on every `updateGeometry()` while the top cutout rect is missing. | Medium | Result cached per (screen width, cutout spec string); spec string itself cached per process. |
| `MessageSource.java` / `DownloadSource.java` | 42–89 / 42–89 | Runs per notification snapshot, but verified: icons/labels are only loaded when a new event builds an item (`AppInfo` caches); the per-scan cost is one small `HashSet` + map operations. Not a real hot spot. | Low | No change (allocation is notification-cadence, not per frame). |
| `IslandController.java` | 365–483 (`refreshNow`) | Builds `ArrayList`/`HashSet`/`LinkedHashSet` and sorts on every refresh, even when nothing changed. Refreshs are notification/callback cadence, not per frame. | Low | No change: restructuring the whole feed pipeline risks visible regressions; re-verify after any change of refresh frequency. |
| `AssistantApp.java` | 213–240 | Rebuilds the `IslandItem` and its `Drawable[]` on every refresh while an assistant state exists (`kindIcon` is cached, so no PackageManager work after the first read). | Low | No change. |
| `IslandController.java` | 558–566 | Landscape notch retry: up to 10 × 200 ms `postDelayed` geometry re-checks when the cutout is not yet known for a rotation. Bounded and only during rotation. | Low | No change. |
| `IslandView.java` | 4934–4948 (`onTouchEvent`) | Two `Matrix` allocations per touch event while the side-rotated landscape mode is active. Touch events are low-frequency; the allocations are small. | Low | No change (touch-region code was intentionally left untouched in this task). |
| `IslandView.java` | 2386 | 15 fps redraw loop while the optional rainbow idle pill is visible. By design, only for that optional setting. | Low | No change (documented setting, off by default). |
| `IslandWindow.java` | 103–108 (`setWindowHeight`) | `wm.updateViewLayout()` on every height change request. Verified the caller (`IslandController.applyWindowHeight`) only fires on real width changes. | Low | No change. |

## Applied changes methodology

Restrictions respected: none of the applied changes alter visible behavior.

- The log gating only changes what gets written to logcat/the Xposed log — setting
  `XLog.DEBUG = true` restores the old verbosity for debugging.
- The Visualizer debounce keeps the waveform exactly as responsive; it only avoids
  start/stop-thrash.
- The icon negative cache and cutout-spec cache return the same values the code would
  have recomputed.

## Changes that could alter visible behavior (ask before applying)

1. **Quantize the exit-target rects** (`IslandController.pushExitTargets`): rounding the
   pushed rects to, say, 4 px would stop the 1-px jitter that causes repeated binder
   transactions, but the One UI Home exit animation target could differ by a few pixels.
2. **Rate-limit `refreshNow`** (e.g., minimum 150 ms between runs): during a download the
   full collection runs once per notification update (~1 Hz), which is acceptable; a
   limiter would also delay legitimate updates from other sources.

## Verified without device

- No runaway frame loops were found in the code; this needs one adb logcat session on the
  S23 Ultra (`logcat -s DynamicIsland`) to confirm the spam is gone in practice.
- The Visualizer behavior (new start/stop cadence) needs a device test with music and
  lock-screen cycles.
