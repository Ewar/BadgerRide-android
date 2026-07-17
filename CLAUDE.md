# ErgPoc — project notes for Claude

Single-activity Android proof-of-concept that drives an **FTMS bike trainer in ERG mode**
and reads a **Polar H10** heart-rate strap over BLE. See `README.md` for the user-facing
description and the BLE protocol flow.

**It is deliberately a PoC**: everything lives in one file (`MainActivity.kt`), with no
architecture, no reconnect logic, no DI, and no tests. Do not "improve" this into layers,
ViewModels, or Compose unless asked — the flat structure is intentional.

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

## Building

Java is **not on PATH** on this machine. Use Android Studio's bundled JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --console=plain --no-daemon
```

### Windows file-lock gotcha (happens often)

Android Studio's Gradle daemon holds locks on `app\build`, producing
`Unable to delete directory ...` / `New files were found ...` failures — including from the
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

`BluetoothGattConnectionSettings` (+ `.Builder`, with `setTransport` / `setAutoConnectEnabled` /
`setAutomaticMtuEnabled` / `setOpportunisticEnabled`) is **API 37+ only — absent from
`android-36.1/android.jar`**. With `minSdk 36` it needs a `Build.VERSION.SDK_INT >= 37` gate plus
the deprecated fallback, so migrating does **not** silence the deprecation warning. It only becomes
a clean win at `minSdk 37`. Left un-migrated on purpose.

### Nullability

`bluetoothLeScanner` is `BluetoothLeScanner?` under API 37 annotations (null when Bluetooth is off).
`scanner()` returns nullable and `startScan()` reports "Bluetooth is off" rather than crashing.
Don't "fix" this with `!!`.

## Known, accepted warnings

- 2× `connectGatt(...) is deprecated` — see above. These are the **only** compiler warnings; a
  build emitting more means something regressed.
- 12 lint infos (`SetTextI18n`, `MissingApplicationIcon`, `LockedOrientationActivity`,
  `DiscouragedApi`). Expected for a PoC with a code-built UI and no resources. Not worth fixing.

## No pre-36 branches — keep it that way

The legacy `SDK_INT >= 31` / `>= 33` branches, the `@Deprecated("pre-33") onCharacteristicChanged`
overloads, and the `maxSdkVersion="30"` manifest permissions have all been **removed** — only
Android 16/17 are supported. Don't reintroduce version gates below 36.

This matters beyond tidiness: `writeDescriptor(d)` returns `boolean` while the API-33
`writeDescriptor(d, value)` returns `int` (`BluetoothStatusCodes`). The op queue's
`start: () -> Boolean` lambda binds to either overload while meaning something different —
"submitted" vs. a status code you must compare against `BluetoothStatusCodes.SUCCESS`.

Permissions now go through `registerForActivityResult(RequestMultiplePermissions())`, not
`ActivityCompat.requestPermissions` + `onRequestPermissionsResult` — the latter is deprecated on
`ComponentActivity` and would add a warning.

## The GATT op queue (read before touching it)

Android allows one in-flight op **per `BluetoothGatt`**, not per app. Only the trainer has a queue;
the H10 issues exactly one descriptor write in its lifetime and deliberately bypasses it. Don't
"unify" them — a shared queue lets the H10's callback complete a trainer op.

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

## Behavior changes to keep in mind (targetSdk 37)

`android:screenOrientation="portrait"` in the manifest is **ignored on Android 16+ for large
screens** — lint flags this as `DiscouragedApi`. Harmless on a phone.