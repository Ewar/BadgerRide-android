# Erg PoC — FTMS ERG mode + Polar H10

Minimal single-activity Android app that:

1. Scans for a BLE device advertising the **Fitness Machine Service (0x1826)**
   — the Hammer Varon XTR II advertises this — and one advertising the
   **Heart Rate Service (0x180D)** — the Polar H10.
2. Connects to both, subscribes to Indoor Bike Data (power/cadence) and
   Heart Rate Measurement notifications.
3. Enters ERG mode via the FTMS Control Point (0x2AD9):
   `Request Control (0x00)` → `Start/Resume (0x07)` → `Set Target Power (0x05, sint16 LE watts)`.
   The trainer firmware then adjusts resistance itself to hold the wattage;
   the app only re-sends 0x05 when you change the target with the ±5/±25 buttons.

## Build
Open the folder in Android Studio (Koala or newer), let it sync, run on a
physical phone (BLE does not work in the emulator). Grant the Bluetooth
permissions when prompted.

## Usage
Wake the trainer, wet/wear the H10 strap, tap **Scan + connect**.
Make sure no other app (Kinomap/Zwift/Polar Flow) holds the connection —
BLE peripherals accept only one central at a time.

## Notes / caveats (it's a PoC)
- No reconnect logic, no bonding handling, no per-device picker (it grabs the
  first FTMS + first HR device it sees).
- Each control point command waits for the trainer's 0x80 response before the
  next is sent, so the three steps appear in order in the log. A rejection is
  reported in the status line, not just logged — "ERG unavailable: trainer
  refused control" almost always means another app holds the connection. A
  rejected target power usually means the wattage is outside the supported
  range (check the 0x2AD8 Supported Power Range characteristic if needed).
- Some trainers silently drop the target after a pause; if that happens,
  re-send 0x07 then 0x05.
