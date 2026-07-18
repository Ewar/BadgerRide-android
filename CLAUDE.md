# BadgerRide — project notes for Claude

Android app that drives an **FTMS bike trainer in ERG mode** and reads a **Polar H10**
over BLE, with a Ride screen (landscape) and a Settings screen (portrait). See
`README.md` for the user-facing description and the BLE protocol flow.

Grown out of the single-file Erg PoC (the repo's first commit). The look comes from
the claude.ai/design project **BadgerRide** (`BadgerRide.dc.html`, "Classical" design
system): Cormorant Garamond + Lora (variable TTFs in `res/font/`), gold accent
`#B68235` on paper `#F3F2F2`, and the five-zone palette in `Zones.kt` (the design's
oklch values pre-converted to sRGB).

## Layout of the code

| File | Role |
|---|---|
| `ble/GattOpQueue.kt` | serialized GATT ops for one connection — invariants below |
| `ble/TrainerLink.kt` | FTMS trainer: discovery, CP procedure chain, ERG drive loop, parsers |
| `ble/HrLink.kt` | H10; deliberately queue-less (one descriptor write, ever) |
| `ble/BleCentral.kt` | scanning, link ownership, bounded reconnect, live values + ranges |
| `RideEngine.kt` | app-scoped session state (samples/distance/kJ/Keytel kcal), targets, 1 Hz tick, finish/auto-finish |
| `health/HealthSync.kt` | Health Connect export of finished rides (session + series records) |
| `RideService.kt` | foreground service alive only while a ride runs (anti-freeze; no logic of its own) |
| `Prefs.kt`, `Zones.kt` | persisted settings; zone palette + boundaries |
| `ui/RideActivity.kt`, `ui/SettingsActivity.kt` | the two designed screens |
| `ui/PowerHrChart.kt`, `ui/ZoneHistogram.kt` | custom canvas views |

The connection lives in `RideEngine` (Application-scoped), not in an activity — a ride
survives switching screens. UI updates are coalesced through `RideEngine.notifyUi()`.

## Toolchain (this combination is load-bearing — read before touching versions)

| Piece | Version | Notes |
|---|---|---|
| Gradle | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.3.0 | root `build.gradle.kts` |
| compileSdk / targetSdk | 37 / 37 | Android 17 |
| minSdk | 36 | Android 16 — the only devices this is run on |
| Java | 17 | `compileOptions` |

### Constraints that will bite you

- **AGP 8.x cannot run on Gradle 9.6+.** AGP 8.13.2 used `org.gradle.api.problems.internal.InternalProblems`,
  which Gradle removed in 9.6.0. The build fails at plugin application with a message pointing
  at `app/build.gradle.kts` line 1 — misleading, since the file is fine. If you ever downgrade
  AGP, you must also pin Gradle to 9.5.1 (note: `9.5` is not a real artifact; 9.5.0/9.5.1 are).
- **AGP 9 has built-in Kotlin. Do NOT add `org.jetbrains.kotlin.android`.** It registers its own
  `kotlin` extension and fails with `Cannot add extension with name 'kotlin'`. The plugin was
  removed from both build files on purpose.
- **Do not add a `kotlinOptions {}` or `kotlin { compilerOptions { jvmTarget } }` block.** With
  built-in Kotlin, `jvmTarget` defaults to `compileOptions.targetCompatibility` (17). Setting it
  is redundant, and `kotlinOptions` is deprecated.
- **`kapt` is incompatible with built-in Kotlin.** Use KSP if annotation processing is ever needed.
- **"Activity class {com.example.ergpoc/…} does not exist" on Run from Studio.** The repo is
  clean — the stale id lives in Android Studio's serialized sync model:
  `%LOCALAPPDATA%\Google\AndroidStudio<ver>\projects\ergpoc.<hash>\project-model-cache\cache.data`
  (plus `external_build_system\`). When a Gradle sync fails (e.g. during the AGP/Gradle
  mismatch above), Studio keeps the last good model — which may predate the
  `com.example.ergpoc` → `com.badgerride` rename — and on restart logs
  "Up-to-date models found in the cache. Not invoking Gradle sync", so it never heals on its
  own. Deploy then uses the stale applicationId while the activity class comes from the fresh
  manifest. Fix: delete those two cache dirs and re-sync in Studio; don't hunt through the
  repo, nothing in it contains the old id.

## Building

Java is **not on PATH** on this machine. Use Android Studio's bundled JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --console=plain --no-daemon
```

### Windows file-lock gotcha (happens often)

Android Studio's Gradle daemon holds locks on `app\build`, producing
`Unable to delete directory ...` / `AccessDeniedException ...` failures — including from the
`clean` task itself. Fix:

```powershell
.\gradlew.bat --stop
Remove-Item -Recurse -Force "app\build" -ErrorAction SilentlyContinue
```

Then rebuild. Prefer this over `gradlew clean`. Only idle Gradle daemons get stopped; Studio
spawns a fresh one on next sync.

### Verification

BLE **does not work in the emulator** — this needs a physical phone, a woken trainer, and a worn
H10. Automated verification realistically stops at `assembleDebug` + `:app:lintDebug`.
Also: BLE peripherals accept one central at a time, so Zwift/Kinomap/Polar Flow must not hold
the connection.

## SDK / API notes

- The platform dir is `platforms/android-37.0` (AGP 9 uses `major.minor` naming), not `android-37`.
- Authoritative source for "when was this API added": `platforms/android-37.0/data/api-versions.xml`.
  Grep it rather than guessing or trusting web results — API 37 post-dates most training data.

### `connectGatt` is deprecated as of API 37

```
connectGatt(Context, boolean, callback)             since=18    deprecated=37.0
connectGatt(Context, boolean, callback, int)        since=23    deprecated=37.0   <-- currently used
connectGatt(ConnectionSettings, Executor, callback) since=37.0                    <-- replacement
```

`BluetoothGattConnectionSettings` (+ `.Builder`) is **API 37+ only — absent from
`android-36.1/android.jar`**. With `minSdk 36` it needs a `Build.VERSION.SDK_INT >= 37` gate plus
the deprecated fallback, so migrating does **not** silence the deprecation warning. It only becomes
a clean win at `minSdk 37`. Left un-migrated on purpose (one call each in `TrainerLink`/`HrLink`).

### Nullability

`bluetoothLeScanner` is `BluetoothLeScanner?` under API 37 annotations (null when Bluetooth is
off). `BleCentral.scanner()` returns nullable and `startScan()` returns false rather than
crashing. Don't "fix" this with `!!`.

## Known, accepted warnings

- 2× `connectGatt(...) is deprecated` — see above. These are the **only** compiler warnings; a
  build emitting more means something regressed.
- 18 lint infos/warnings: `SetTextI18n` (dynamic values set in code), `SmallSp` (the design's
  8.5sp/7.5sp letterspaced caps labels — intentional), `NestedWeights` (the ride metric grid),
  `DiscouragedApi`/`LockedOrientationActivity` (`screenOrientation` — ignored on Android 16+
  large screens, harmless on a phone). Not worth fixing; anything *new* is a regression.

## No pre-36 branches — keep it that way

Only Android 16/17 are supported; there are no `SDK_INT` gates below 36, no deprecated
pre-33 `onCharacteristicChanged` overloads, no `maxSdkVersion="30"` manifest permissions.
Don't reintroduce them.

This matters beyond tidiness: `writeDescriptor(d)` returns `boolean` while the API-33
`writeDescriptor(d, value)` returns `int` (`BluetoothStatusCodes`). The op queue's
`start: () -> Boolean` lambda binds to either overload while meaning something different —
"submitted" vs. a status code you must compare against `BluetoothStatusCodes.SUCCESS`.

Permissions go through `registerForActivityResult(RequestMultiplePermissions())`, not
`ActivityCompat.requestPermissions` + `onRequestPermissionsResult` — the latter is deprecated on
`ComponentActivity` and would add a warning.

## The GATT op queue (read before touching it)

Android allows one in-flight op **per `BluetoothGatt`**, not per app. Only the trainer has a
queue (`GattOpQueue`, used by `TrainerLink`); the H10 issues exactly one descriptor write in its
lifetime and deliberately bypasses it. Don't "unify" them — a shared queue lets the H10's
callback complete a trainer op.

Invariants that are load-bearing:

- **Completions are identity-checked.** `opDone(key, cpOpcode)` verifies the callback belongs to
  the in-flight op. Because ops can time out, a late callback would otherwise complete whatever op
  is running *next*, leaving the queue permanently one op out of phase with no way to resync.
- **FTMS control point writes complete on the 0x80 indication, not the ATT write ack.** Each CP
  write is a procedure; the spec forbids starting the next before the previous responds. The ERG
  entry sequence is therefore *chained off the responses* (`0x00` → `0x07` → `0x05`), not enqueued
  back-to-back. ATT status 128 in `onCharacteristicWrite` means "procedure already in progress" —
  i.e. something started pipelining again.
- **A failed `0x07` (Start/Resume) is non-fatal on purpose.** Many trainers reject it when already
  started; `0x05` must still go out or ERG silently never engages. Only `0x00` failing aborts.
- **`start()` returning false means no callback is coming.** The queue must skip the op, not wait.
- The 15 s watchdog is a last-resort escape hatch (Android's own ATT timeout is 30 s). Lowering it
  makes late callbacks routine, which is exactly what the identity check exists to survive.

Mode/targets live in `RideEngine` (persisted, clamped against the trainer-reported ranges in
`BleCentral`); `TrainerLink` reads them through `BleCentral.Targets` so they survive reconnects.

## Ride lifecycle & Health Connect

A ride starts on the first moving sample (`RideEngine.rideStartMs`) and ends via
`finishRide()` — the Finish Ride tag on the Ride screen, or the 5-minute idle watchdog in
`tick()` (end time = last pedal stroke + 1 s, not the timeout moment). Finishing snapshots a
`FinishedRide`, resets the session counters, and sends **FTMS Reset (0x01)**; the trainer
revokes control on reset, so `onCpResponse` re-enters the chain via Request Control.

`RideService` (foreground, type `connectedDevice`) runs for exactly the ride's duration so
Android 16 doesn't freeze the process — the engine's ticker does all the work, the service
only pins the procstate and shows the progress notification. It is started from `tick()`
(retried each tick if the OS refuses a background start; the first attempt happens with the
Ride screen foregrounded, so in practice it succeeds immediately) and stopped by
`finishRide()`. `POST_NOTIFICATIONS` is *requested* but deliberately not *required* —
`RideActivity` gates scanning only on the two Bluetooth permissions; a denied notification
permission just hides the notification, the service still runs. Don't unify `wantedPerms`
into `requiredPerms`.

`HealthSync` notes (connect-client **1.1.0**):

- Record constructors require `metadata` (a 1.1.0 breaking change); built via
  `Metadata.autoRecorded(Device(...))`.
- **There is no cadence permission.** `CyclingPedalingCadenceRecord` maps to
  `android.permission.health.WRITE_EXERCISE` (verified against the 1.1.0 bytecode). The
  manifest's `android.permission.health.*` list must stay in sync with
  `HealthSync.permissions`.
- HR samples must be 1–300 bpm — zero-HR seconds are filtered out, not clamped.
- Series records are chunked (1 000 samples ≈ 17 min) to stay under the binder
  transaction limit on long rides.
- The permission-rationale intent (`VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS`) is
  handled by an exported activity-alias onto `SettingsActivity`, guarded by
  `START_VIEW_PERMISSION_USAGE` — don't export `SettingsActivity` itself and don't put
  that guard permission on the real activity (the app can't hold it, it would break
  internal navigation).
- Rides with < 60 s of moving time are reset without export; an unexported ride
  (permission missing) is kept pending in memory and flushed when the grant arrives.
- `kotlinx-coroutines-android` exists solely because the HC client API is suspend-based;
  the rest of the app stays handler-based on purpose.

## Behavior changes to keep in mind (targetSdk 37)

`android:screenOrientation` in the manifest is **ignored on Android 16+ for large screens** —
lint flags this as `DiscouragedApi`. Harmless on a phone. The Ride screen is landscape, Settings
portrait, per the design.
