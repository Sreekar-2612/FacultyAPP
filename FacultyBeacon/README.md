# BLE Faculty Beacon (Android)

Android application that turns a faculty member's phone into a BLE beacon, replacing the
ESP32 described in `Faculty Mobile BLE Beacon.pdf`.

The phone does exactly what the ESP32 did:

```
Faculty opens app
   └─> START BEACON
        └─> Check Bluetooth is enabled
        └─> Check the phone supports BLE advertising
        └─> Generate a random 128-bit UUID  (new UUID every session, SecureRandom-backed)
        └─> Report UUID / MAC / device name / device secret to Supabase
        └─> Request the highest practical BLE advertising power (+9 dBm, ESP_PWR_LVL_N9 equivalent)
        └─> Broadcast a manufacturer-specific, non-connectable advertisement continuously
```

The phone never scans and never connects - it is a pure broadcaster, like the ESP32.

## Project layout

```
app/src/main/java/com/facultybeacon/
├── MainActivity.kt              Start/Stop beacon screen (per the spec's UI sketch)
├── TestingActivity.kt           Testing/debug screen with capabilities, TX power and logs
├── beacon/
│   ├── BeaconService.kt         Foreground service owning the beacon lifecycle
│   ├── BleBeaconAdvertiser.kt   BLE advertising (extended + legacy paths)
│   ├── BeaconSessionManager.kt  UUID + persistent pseudo-MAC / device secret
│   ├── BeaconState.kt           Shared observable state + in-app log
│   └── BlePayload.kt            Manufacturer-data payload helpers (unit-tested)
└── network/
    ├── SupabaseConfig.kt        <-- EDIT ME
    └── SupabaseReporter.kt      POST to Supabase PostgREST (same style as the ESP32)
```

## Build & run

1. Open the `FacultyBeacon` folder in **Android Studio** (Ladybug or newer).
2. Let Gradle sync (JDK 17 is bundled with Android Studio).
3. Run on a physical phone (BLE advertising is not available in the emulator).

Minimum SDK is 26 (Android 8.0): the extended-advertising API used for exact dBm TX
power control requires it.

## Configuration: Supabase

Open `app/src/main/java/com/facultybeacon/network/SupabaseConfig.kt` and set:

| Constant | Value |
|---|---|
| `SUPABASE_URL` | `https://<your-project-ref>.supabase.co` |
| `SUPABASE_ANON_KEY` | Anon key from Supabase → Project Settings → API |
| `TABLE_NAME` | The table the ESP32 writes to (default `beacons`) |
| `COL_*` | Column names - match the ESP32 table's columns |

Behavior flags:

- `ENABLED` - set `false` to run the beacon without a backend (lab testing).
- `WAIT_FOR_SUPABASE_BEFORE_ADVERTISING` - `true` mirrors the ESP32 exactly: advertising
  only starts after Supabase accepts the row. Set `false` to advertise even if Supabase
  is unreachable (the failure is still logged).
- `MAX_REPORT_ATTEMPTS` - retries before giving up (3 by default).

### About the Bluetooth MAC

Android has hidden the real hardware MAC since Android 6 (`BluetoothAdapter.getAddress()`
returns the fixed placeholder `02:00:00:00:00:00`). To keep the Supabase schema intact,
the app substitutes a **persistent random pseudo-MAC** generated on first run and stored
in SharedPreferences - the same value is reported on every session, so rows stay linked
to the same phone. The device secret is likewise a persistent random value generated on
first run. The device name is the Bluetooth adapter name (falling back to the device model).

## Permissions (handled at runtime by the app)

- **Android 12+ (API 31+)**: `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT`
  (`BLUETOOTH_CONNECT` is also required by Android 14 for the `connectedDevice`
  foreground service that keeps the beacon alive).
- **Android 6-11 (API 23-30)**: `ACCESS_FINE_LOCATION` (required for BLE advertising).
- **Android 13+**: `POST_NOTIFICATIONS` (optional; only controls the status notification).
- **Android 14+**: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

If Bluetooth is off, the app asks the user to enable it. If the user turns Bluetooth off
while advertising, the beacon stops cleanly.

## BLE advertisement format

Reproduces the ESP32's manufacturer-specific data as closely as Android allows:

```
2 bytes manufacturer ID  0xFF 0xFF  (0xFFFF)
16 bytes random UUID     MSB-first, big-endian
```

- Non-connectable (`ADV_NONCONN_IND` family) - no connections are ever established.
- Continuous broadcast, ~100 ms interval (`ADVERTISE_MODE_LOW_LATENCY` legacy /
  `INTERVAL_LOW` extended).
- Device name is NOT included in the advertisement.

## TX power: what the app requests vs. what Android allows

This was the central question of the project, so the app implements **both** Android
paths and surfaces them on the Testing screen:

| Path | Requirement | TX power control |
|---|---|---|
| Extended advertising (`BluetoothLeAdvertisingSet`, API 26+, BLE 5.0 controller) | Preferred; used automatically when supported | **Exact dBm** via `setTxPowerLevel(...)`. The app requests **+9 dBm** to match `ESP_PWR_LVL_N9`. The callback reports the *actual* power the controller granted. |
| Legacy advertiser (`BluetoothLeAdvertiser`, API 21+) | Fallback on older hardware | Only the fixed presets `ULTRA_LOW` / `LOW` / `MEDIUM` / `HIGH`. The app requests `HIGH`. The callback reports which preset is in effect. |

Answers to the investigation questions in the spec (verified against Android docs):

1. **Can the app request high advertising power?** Yes, but not precisely on the legacy
   path. `ADVERTISE_TX_POWER_HIGH` is a preset whose dBm value is device-dependent.
   The extended-advertising API accepts an exact dBm request, but the controller clamps
   it to its own maximum - the phone cannot exceed its hardware/radio limit.
2. **What TX power is actually available?** Device-dependent. Typical phones cap
   advertising well below the ESP32's +9 dBm; many sit around 0-6 dBm. The Testing screen
   shows the requested value AND the actual value granted by the controller.
3. **Does the OS change/restrict advertising behavior?** Yes. OEM battery managers
   (MIUI, EMUI, ColorOS, etc.), app standby and Doze can pause or kill advertising when
   the app is backgrounded, and Android 12+ background restrictions apply unless the app
   is exempted.
4. **Does advertising continue in the background?** Reliably only while the process stays
   alive. This app runs a foreground service (`connectedDevice`) with a partial wake lock
   and offers a one-tap battery-optimization exemption on the Testing screen. On heavily
   customized OEM ROMs the user may additionally need to lock the app in the recent-apps
   menu / allow "autostart".
5. **Does advertising continue with the screen off?** Generally yes while the process
   lives; the wake lock reduces the risk of the SoC sleeping the radio. Verify per device.
6. **Does battery optimization affect advertising?** Yes - this is the most common cause
   of beacons silently dying. Use the "Exempt from battery optimization" button on the
   Testing screen.
7. **Does range vary between phone models?** Significantly. Phone antennas are
   directional and weaker than the ESP32's; a 120-student classroom is usually covered,
   but range should be validated empirically on each faculty phone model before rollout.

## Testing / debug screen

Reachable via **TESTING / DEBUG** on the main screen. It displays:

- BLE supported: YES/NO
- BLE advertising supported: YES/NO
- Extended advertising (BLE 5.0): YES/NO
- Bluetooth enabled: YES/NO
- Advertising status: ACTIVE/INACTIVE
- Beacon UUID, advertising mode, requested TX power, actual TX power (as reported by the OS)
- Supabase status, reported Bluetooth MAC and device name
- Live log: `Beacon started`, `UUID generated`, `Advertisement configured`,
  `Advertisement started`, `Advertisement stopped`, `Advertising error`, Supabase
  results, and fallback decisions.

## Notes / limitations

- The 100 ms interval + continuous broadcast is aggressive for a phone battery; that is
  intentional (reliability first, per the spec).
- This app does **not** include the student scanner, attendance logic, RSSI processing,
  or a student app - those are explicitly out of scope for this stage.
- The Supabase request is a plain PostgREST POST like the ESP32's, using the anon key.
  If the existing table enforces RLS, make sure the anon role has `INSERT` on it
  (same policy the ESP32 relies on).